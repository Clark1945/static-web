package com.example.dbshowcase.mongo.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.dbshowcase.common.YamlContent;
import com.example.dbshowcase.mongo.MongoConfig;
import com.example.dbshowcase.mongo.MongoShell;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * MongoDB 實驗室：
 * 1. 索引與 explain：對 shop.orders 建 / 刪索引，把 explain 整理成「計畫、看了幾份、回傳幾份」
 * 2. 內嵌 vs 參照：把訂單拆成 order_headers + order_lines，比較內嵌讀取與 $lookup
 * 3. 聚合管線逐步看：每個 stage 之後剩幾份文件、長什麼樣子
 */
@Service
public class MongoLabService {

    private static final int MAX_INDEXES = 8;
    private static final int NO_INDEX_ORDER_LIMIT = 30;

    public record Step(String id, String title, String goal, List<Query> queries, List<String> indexes,
                       String question, String takeaway) {
        public record Query(String label, String command) {
        }
    }

    public record IndexInfo(String name, Object key, String size) {
    }

    public record ExplainSummary(String command, List<String> stages, String indexName, Boolean multiKey,
                                 long nReturned, long keysExamined, long docsExamined, long millis,
                                 boolean hasSort, Object plan) {
    }

    private final MongoClient admin;
    private final MongoClient reader;
    private final MongoShell shell;
    private final List<Step> steps;

    public MongoLabService(@Qualifier("mongoAdmin") MongoClient admin, @Qualifier("mongoReader") MongoClient reader,
                           MongoShell shell, ObjectMapper mapper) {
        this.admin = admin;
        this.reader = reader;
        this.shell = shell;
        this.steps = YamlContent.load("mongo/index-lab.yml", Step.class, mapper);
    }

    // ================================================================ 索引與 explain

    public List<Step> steps() {
        return steps;
    }

    public List<IndexInfo> indexes() {
        MongoCollection<Document> orders = admin.getDatabase(MongoConfig.SHOP).getCollection("orders");
        Document sizes;
        try {
            Document stats = orders.aggregate(List.of(new Document("$collStats", new Document("storageStats", new Document())))).first();
            sizes = stats == null ? new Document() : stats.get("storageStats", new Document()).get("indexSizes", new Document());
        } catch (com.mongodb.MongoCommandException e) {
            // 正在「重置資料」時 orders 會暫時不存在
            throw new IllegalArgumentException("shop.orders 目前不存在（是不是正在重置資料？），請稍後再試。");
        }
        List<IndexInfo> out = new ArrayList<>();
        orders.listIndexes().forEach(i -> out.add(new IndexInfo(i.getString("name"), MongoShell.plain(i.get("key")),
                human(sizes.get(i.getString("name")) instanceof Number n ? n.longValue() : 0))));
        return out;
    }

    /** 只接受對 orders 的 createIndex / dropIndex。 */
    public List<IndexInfo> ddl(String command) {
        List<MongoShell.Statement> st = shell.parse(command);
        if (st.size() != 1 || !st.get(0).collection().equals("orders")
                || !List.of("createIndex", "dropIndex").contains(st.get(0).calls().get(0).name())) {
            throw new IllegalArgumentException("這裡只接受 db.orders.createIndex(…) 或 db.orders.dropIndex(…)。");
        }
        if (st.get(0).calls().get(0).name().equals("createIndex") && indexes().size() > MAX_INDEXES) {
            throw new IllegalArgumentException("最多 " + MAX_INDEXES + " 個索引，請先刪掉一些（或按「重置」）。");
        }
        MongoShell.Result r = shell.run(admin.getDatabase(MongoConfig.SHOP), st, 10).last();
        if (r.kind().equals("error")) {
            throw new IllegalArgumentException(String.valueOf(r.value()));
        }
        return indexes();
    }

    public List<IndexInfo> resetIndexes() {
        admin.getDatabase(MongoConfig.SHOP).getCollection("orders").dropIndexes();
        return indexes();
    }

    /** 對一句 find 執行 explain("executionStats") 並整理重點。用 reader 執行，所以只能查詢。 */
    public ExplainSummary explain(String command) {
        String trimmed = command.strip().replaceAll(";+$", "");
        if (!trimmed.contains(".explain(")) {
            trimmed += ".explain(\"executionStats\")";
        }
        List<MongoShell.Statement> st = shell.parse(trimmed);
        if (st.size() != 1 || !st.get(0).calls().get(0).name().equals("find")) {
            throw new IllegalArgumentException("索引實驗只分析一句 find 查詢。");
        }
        MongoShell.Result r = shell.run(reader.getDatabase(MongoConfig.SHOP), st, 10).last();
        if (r.kind().equals("error")) {
            throw new IllegalArgumentException(String.valueOf(r.value()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> explain = (Map<String, Object>) r.value();
        Map<String, Object> planner = map(explain.get("queryPlanner"));
        Map<String, Object> winning = map(planner.get("winningPlan"));
        Map<String, Object> plan = winning.containsKey("queryPlan") ? map(winning.get("queryPlan")) : winning;
        Map<String, Object> exec = map(explain.get("executionStats"));

        List<String> stages = new ArrayList<>();
        String[] indexName = {null};
        Boolean[] multiKey = {null};
        walk(plan, stages, indexName, multiKey);
        return new ExplainSummary(command.strip(), stages, indexName[0], multiKey[0],
                num(exec.get("nReturned")), num(exec.get("totalKeysExamined")), num(exec.get("totalDocsExamined")),
                num(exec.get("executionTimeMillis")), stages.contains("SORT"), plan);
    }

    private static void walk(Map<String, Object> node, List<String> stages, String[] indexName, Boolean[] multiKey) {
        if (node == null || node.isEmpty()) {
            return;
        }
        String stage = String.valueOf(node.get("stage"));
        stages.add(stage);
        if (node.get("indexName") != null) {
            indexName[0] = String.valueOf(node.get("indexName"));
            multiKey[0] = Boolean.TRUE.equals(node.get("isMultiKey"));
        }
        if (node.get("inputStage") != null) {
            walk(map(node.get("inputStage")), stages, indexName, multiKey);
        } else if (node.get("inputStages") instanceof List<?> list && !list.isEmpty()) {
            walk(map(list.get(0)), stages, indexName, multiKey);
        }
    }

    // ================================================================ 內嵌 vs 參照

    public record EmbedStatus(boolean ready, long headers, long lines, long embeddedAvg, long headerAvg, long lineAvg,
                              boolean linesIndexed) {
    }

    public record Comparison(String label, String command, double millis, int orders, int items, long docsExamined,
                             Object sample) {
    }

    public EmbedStatus embedStatus() {
        MongoDatabase lab = admin.getDatabase(MongoConfig.LAB);
        long headers = lab.getCollection("order_headers").estimatedDocumentCount();
        long lines = lab.getCollection("order_lines").estimatedDocumentCount();
        boolean indexed = false;
        for (Document i : lab.getCollection("order_lines").listIndexes()) {
            indexed |= i.get("key", Document.class).containsKey("orderId");
        }
        return new EmbedStatus(headers > 0 && lines > 0, headers, lines,
                avg(MongoConfig.LAB, "orders_embedded"), avg(MongoConfig.LAB, "order_headers"), avg(MongoConfig.LAB, "order_lines"), indexed);
    }

    /** 從 shop.orders 建立三個 collection：內嵌版（原樣複製）、訂單表頭、訂單明細（一項一份文件）。 */
    public EmbedStatus prepareEmbed() {
        MongoDatabase lab = admin.getDatabase(MongoConfig.LAB);
        lab.drop();
        MongoCollection<Document> orders = admin.getDatabase(MongoConfig.SHOP).getCollection("orders");
        orders.aggregate(List.of(new Document("$out", new Document("db", MongoConfig.LAB).append("coll", "orders_embedded")))).toCollection();
        orders.aggregate(List.of(new Document("$unset", "items"),
                new Document("$out", new Document("db", MongoConfig.LAB).append("coll", "order_headers")))).toCollection();
        orders.aggregate(List.of(
                new Document("$unwind", "$items"),
                new Document("$project", new Document("_id", 0).append("orderId", "$_id")
                        .append("productId", "$items.productId").append("name", "$items.name")
                        .append("qty", "$items.qty").append("unitPrice", "$items.unitPrice").append("discount", "$items.discount")),
                new Document("$out", new Document("db", MongoConfig.LAB).append("coll", "order_lines")))).toCollection();
        lab.getCollection("orders_embedded").createIndex(new Document("customerId", 1));
        lab.getCollection("order_headers").createIndex(new Document("customerId", 1));
        return embedStatus();
    }

    public EmbedStatus indexLines(boolean create) {
        MongoCollection<Document> lines = admin.getDatabase(MongoConfig.LAB).getCollection("order_lines");
        if (create) {
            lines.createIndex(new Document("orderId", 1));
        } else {
            lines.dropIndexes();
        }
        return embedStatus();
    }

    public List<Comparison> compare(int customerId) {
        EmbedStatus status = embedStatus();
        if (!status.ready()) {
            throw new IllegalArgumentException("請先按「建立參照版本」。");
        }
        MongoDatabase lab = admin.getDatabase(MongoConfig.LAB);
        long orderCount = lab.getCollection("order_headers").countDocuments(new Document("customerId", customerId));
        if (!status.linesIndexed() && orderCount > NO_INDEX_ORDER_LIMIT) {
            throw new IllegalArgumentException("會員 " + customerId + " 有 " + orderCount + " 筆訂單。order_lines 沒有索引時，每筆訂單都要掃過全部 "
                    + status.lines() + " 份明細，會跑很久。請先建立 orderId 索引，或換一位訂單少一點的會員（例如 4242）。");
        }
        String embedded = "db.orders_embedded.find({ customerId: " + customerId + " })";
        String lookup = """
                db.order_headers.aggregate([
                  { $match: { customerId: %d } },
                  { $lookup: { from: "order_lines", localField: "_id", foreignField: "orderId", as: "items" } }
                ])""".formatted(customerId);
        return List.of(run("A：內嵌（讀一個集合）", embedded, lab), run("B：參照（$lookup 兩個集合）", lookup, lab));
    }

    private Comparison run(String label, String command, MongoDatabase lab) {
        MongoShell.Result r = shell.run(lab, shell.parse(command), 1000).last();
        if (r.kind().equals("error")) {
            throw new IllegalArgumentException(String.valueOf(r.value()));
        }
        List<?> docs = (List<?>) r.value();
        int items = 0;
        for (Object d : docs) {
            if (map(d).get("items") instanceof List<?> l) {
                items += l.size();
            }
        }
        long examined = docsExamined(lab, command);
        return new Comparison(label, command, r.millis(), docs.size(), items, examined, docs.isEmpty() ? null : docs.get(0));
    }

    /** 用 explain 算出總共讀了幾份文件（$lookup 的部分也算進去）。 */
    private long docsExamined(MongoDatabase lab, String command) {
        MongoShell.Result r = shell.run(lab, shell.parse(command + ".explain()"), 10).last();
        Map<String, Object> e = map(r.value());
        if (e.containsKey("executionStats")) {
            return num(map(e.get("executionStats")).get("totalDocsExamined"));
        }
        long total = 0;
        if (e.get("stages") instanceof List<?> stages) {
            for (Object s : stages) {
                Map<String, Object> stage = map(s);
                if (stage.get("$cursor") != null) {
                    total += num(map(map(stage.get("$cursor")).get("executionStats")).get("totalDocsExamined"));
                }
                if (stage.get("totalDocsExamined") != null) {
                    total += num(stage.get("totalDocsExamined"));
                }
            }
        }
        return total;
    }

    // ================================================================ 聚合管線逐步看

    public record StageResult(int index, String stage, long count, List<Object> sample, double millis) {
    }

    /** 對 pipeline 的每個前綴各跑一次：前 k 個 stage 之後剩幾份文件（$count），以及前 3 份長什麼樣子。 */
    public List<StageResult> pipeline(String command) {
        List<MongoShell.Statement> st = shell.parse(command);
        if (st.size() != 1 || !st.get(0).calls().get(0).name().equals("aggregate")) {
            throw new IllegalArgumentException("請輸入一句 db.集合.aggregate([ … ])。");
        }
        @SuppressWarnings("unchecked")
        List<Document> pipeline = (List<Document>) st.get(0).calls().get(0).args().get(0);
        if (pipeline.size() > 10) {
            throw new IllegalArgumentException("最多 10 個 stage。");
        }
        MongoCollection<Document> coll = reader.getDatabase(MongoConfig.SHOP).getCollection(st.get(0).collection());
        List<StageResult> out = new ArrayList<>();
        out.add(stage(coll, List.of(), 0, "（原始集合）"));
        for (int i = 0; i < pipeline.size(); i++) {
            out.add(stage(coll, pipeline.subList(0, i + 1), i + 1, pipeline.get(i).toJson()));
        }
        return out;
    }

    private static StageResult stage(MongoCollection<Document> coll, List<Document> prefix, int index, String label) {
        long start = System.nanoTime();
        List<Document> countPipe = new ArrayList<>(prefix);
        countPipe.add(new Document("$count", "n"));
        Document c = coll.aggregate(countPipe).allowDiskUse(true).first();
        List<Document> samplePipe = new ArrayList<>(prefix);
        samplePipe.add(new Document("$limit", 3));
        List<Object> sample = new ArrayList<>();
        coll.aggregate(samplePipe).allowDiskUse(true).forEach(d -> sample.add(MongoShell.plain(d)));
        return new StageResult(index, label, c == null ? 0 : num(c.get("n")), sample, (System.nanoTime() - start) / 1e6);
    }

    // ================================================================ 工具

    private long avg(String db, String coll) {
        Document stats = admin.getDatabase(db).getCollection(coll)
                .aggregate(List.of(new Document("$collStats", new Document("storageStats", new Document())))).first();
        return stats == null ? 0 : num(stats.get("storageStats", new Document()).get("avgObjSize"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String human(long bytes) {
        return bytes >= 1 << 20 ? String.format("%.1f MB", bytes / 1048576.0) : String.format("%d KB", bytes / 1024);
    }
}
