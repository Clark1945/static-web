package com.example.dbshowcase.neo4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.summary.Plan;
import org.neo4j.driver.summary.ProfiledPlan;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Component;

/**
 * 執行使用者輸入的 Cypher。社群版沒有角色權限，所以安全靠這裡：
 * 1. 所有句子放在同一個交易裡依序執行，最後一律 ROLLBACK（寫入只在這個交易裡看得到）
 * 2. 交易有時間上限；伺服器另外限制單一交易的記憶體
 * 3. 擋掉會碰到外部資源或管理功能的語法：LOAD CSV、CALL dbms.*、使用者 / 資料庫管理、USE
 */
@Component
public class CypherShell {

    private static final int MAX_STATEMENTS = 20;
    private static final int MAX_GRAPH_NODES = 300;
    private record Blocked(Pattern pattern, String name) {
    }

    private static final List<Blocked> BLOCKED = List.of(
            new Blocked(Pattern.compile("(?i)\\bLOAD\\s+CSV\\b"), "LOAD CSV"),
            new Blocked(Pattern.compile("(?i)\\bCALL\\s+dbms\\."), "CALL dbms.*"),
            new Blocked(Pattern.compile("(?i)\\b(CREATE|DROP|ALTER|RENAME|START|STOP|GRANT|DENY|REVOKE)\\s+(OR\\s+REPLACE\\s+)?(USER|ROLE|DATABASE|COMPOSITE|ALIAS|SERVER)\\b"),
                    "使用者、角色、資料庫管理"),
            new Blocked(Pattern.compile("(?i)^\\s*USE\\s"), "USE"),
            new Blocked(Pattern.compile("(?i)\\bIN\\s+TRANSACTIONS\\b"), "CALL { … } IN TRANSACTIONS"));

    /** 查詢計畫的一個運算子（PROFILE 才有 rows、dbHits）。 */
    public record PlanNode(String operator, String details, Long rows, Long dbHits, Double estimatedRows,
                           List<PlanNode> children) {
    }

    /** 結果裡出現的節點與關係，給前端畫圖。 */
    public record Graph(List<Map<String, Object>> nodes, List<Map<String, Object>> relationships, boolean truncated) {
    }

    /**
     * 一句的結果。kind = "rows"（有欄位）、"ok"（只有寫入、沒有 RETURN）、"error"。
     * counters 是寫入統計（例如 nodesCreated: 1），notifications 是伺服器的提示（例如笛卡兒積警告）。
     */
    public record Outcome(String statement, String kind, List<String> columns, List<List<Object>> rows, Integer total,
                          boolean truncated, String message, Map<String, Integer> counters, List<String> notifications,
                          PlanNode plan, Long totalDbHits, Graph graph, double millis) {
    }

    public record RunResult(List<Outcome> results, List<Outcome> checks, double totalMillis, boolean committed) {

        public Outcome last() {
            return results.get(results.size() - 1);
        }
    }

    private final Driver driver;

    public CypherShell(Driver driver) {
        this.driver = driver;
    }

    // ================================================================ 切句

    /** 依分號切句；字串、`識別字`、註解裡的分號不算。註解會拿掉。 */
    public static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String s = script == null ? "" : script;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            char next = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && c != '`') {
                        j++;
                    }
                    j++;
                }
                cur.append(s, i, Math.min(n, j + 1));
                i = j;
            } else if (c == '/' && next == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                cur.append('\n');
            } else if (c == '/' && next == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                cur.append(' ');
            } else if (c == ';') {
                add(out, cur);
            } else {
                cur.append(c);
            }
        }
        add(out, cur);
        return out;
    }

    private static void add(List<String> out, StringBuilder cur) {
        String t = cur.toString().strip();
        if (!t.isEmpty()) {
            out.add(t);
        }
        cur.setLength(0);
    }

    // ================================================================ 執行

    /** 使用者的 Cypher：在一個交易裡執行，最後 ROLLBACK。 */
    public RunResult run(String script, int maxRows) {
        return run(script, null, maxRows, false);
    }

    /**
     * script 的每一句依序在同一個交易裡執行，遇到錯誤就停；之後在「同一個交易」裡執行 check（批改寫入題時用來檢查資料），
     * 最後 commit = false 時 ROLLBACK。commit = true 只給後端自己寫好的指令用（載入資料、建索引）。
     */
    public RunResult run(String script, String check, int maxRows, boolean commit) {
        List<String> statements = splitStatements(script);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("請輸入至少一句 Cypher。");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException("一次最多執行 " + MAX_STATEMENTS + " 句。");
        }
        if (!commit) {
            for (String st : statements) {
                for (Blocked b : BLOCKED) {
                    if (b.pattern().matcher(st).find()) {
                        throw new IllegalArgumentException("這裡不能使用 " + b.name()
                                + "：展示台的 Neo4j 是社群版，沒有權限控管，所以擋掉會碰到外部檔案、網路或管理功能的指令。");
                    }
                }
            }
        }
        long start = System.nanoTime();
        List<Outcome> results = new ArrayList<>();
        List<Outcome> checks = new ArrayList<>();
        boolean committed = false;
        try (Session session = driver.session(SessionConfig.forDatabase(Neo4jConfig.DATABASE));
             Transaction tx = session.beginTransaction(TransactionConfig.builder().withTimeout(Neo4jConfig.USER_TX_TIMEOUT).build())) {
            boolean failed = false;
            for (String st : statements) {
                Outcome r = execute(tx, st, maxRows);
                results.add(r);
                if (r.kind().equals("error")) {
                    failed = true;
                    break;
                }
            }
            if (!failed && check != null && !check.isBlank()) {
                for (String st : splitStatements(check)) {
                    Outcome r = execute(tx, st, maxRows);
                    checks.add(r);
                    if (r.kind().equals("error")) {
                        break;
                    }
                }
            }
            if (commit && !failed) {
                tx.commit();
                committed = true;
            } else if (tx.isOpen()) {
                tx.rollback();
            }
        }
        return new RunResult(results, checks, (System.nanoTime() - start) / 1e6, committed);
    }

    private Outcome execute(Transaction tx, String statement, int maxRows) {
        long start = System.nanoTime();
        try {
            Result result = tx.run(statement);
            List<String> columns = result.keys();
            List<List<Object>> rows = new ArrayList<>();
            GraphCollector graph = new GraphCollector();
            boolean truncated = false;
            while (result.hasNext()) {
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                Record rec = result.next();
                List<Object> row = new ArrayList<>(columns.size());
                for (Value v : rec.values()) {
                    row.add(plain(v));
                    graph.collect(v);
                }
                rows.add(row);
            }
            ResultSummary summary = result.consume();
            double millis = (System.nanoTime() - start) / 1e6;
            PlanNode plan = summary.hasProfile() ? plan(summary.profile()) : summary.hasPlan() ? plan(summary.plan()) : null;
            List<String> notes = new ArrayList<>();
            summary.notifications().forEach(n -> notes.add(n.title() + "：" + n.description()));
            boolean hasColumns = !columns.isEmpty();
            return new Outcome(statement, hasColumns ? "rows" : "ok", columns, hasColumns ? rows : null,
                    hasColumns ? rows.size() : null, truncated, null, counters(summary.counters()), notes, plan,
                    summary.hasProfile() ? totalDbHits(summary.profile()) : null, graph.result(), millis);
        } catch (Neo4jException e) {
            return error(statement, e.code() + "：" + e.getMessage(), start);
        } catch (RuntimeException e) {
            return error(statement, e.getClass().getSimpleName() + "：" + e.getMessage(), start);
        }
    }

    private static Outcome error(String statement, String message, long start) {
        return new Outcome(statement, "error", null, null, null, false, message, Map.of(), List.of(), null, null, null,
                (System.nanoTime() - start) / 1e6);
    }

    private static Map<String, Integer> counters(SummaryCounters c) {
        Map<String, Integer> out = new LinkedHashMap<>();
        put(out, "nodesCreated", c.nodesCreated());
        put(out, "nodesDeleted", c.nodesDeleted());
        put(out, "relationshipsCreated", c.relationshipsCreated());
        put(out, "relationshipsDeleted", c.relationshipsDeleted());
        put(out, "propertiesSet", c.propertiesSet());
        put(out, "labelsAdded", c.labelsAdded());
        put(out, "labelsRemoved", c.labelsRemoved());
        put(out, "indexesAdded", c.indexesAdded());
        put(out, "indexesRemoved", c.indexesRemoved());
        put(out, "constraintsAdded", c.constraintsAdded());
        put(out, "constraintsRemoved", c.constraintsRemoved());
        return out;
    }

    private static void put(Map<String, Integer> m, String k, int v) {
        if (v != 0) {
            m.put(k, v);
        }
    }

    // ================================================================ 查詢計畫

    private static PlanNode plan(Plan p) {
        List<PlanNode> children = new ArrayList<>();
        for (Plan c : p.children()) {
            children.add(plan(c));
        }
        Long rows = null, hits = null;
        if (p instanceof ProfiledPlan pp) {
            rows = pp.records();
            hits = pp.dbHits();
        }
        Value est = p.arguments().get("EstimatedRows");
        Value details = p.arguments().get("Details");
        return new PlanNode(p.operatorType().replaceAll("@.*$", ""), details == null ? null : details.asString(),
                rows, hits, est == null ? null : est.asDouble(), children);
    }

    private static long totalDbHits(ProfiledPlan p) {
        long sum = p.dbHits();
        for (ProfiledPlan c : p.children()) {
            sum += totalDbHits(c);
        }
        return sum;
    }

    // ================================================================ 值的轉換

    /**
     * 把 driver 的值轉成 JSON 友善的型別。
     * 節點 → {"~labels": [...], "~props": {...}}；關係 → {"~type": "...", "~props": {...}}；路徑 → {"~path": [節點, 關係, 節點…]}。
     * 不放 elementId，批改時才能比對內容。
     */
    public static Object plain(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        // 清單的型別名稱是「LIST OF ANY?」這種形式，先統一成 LIST
        String type = v.type().name().startsWith("LIST") ? "LIST" : v.type().name();
        switch (type) {
            case "NODE" -> {
                return node(v.asNode());
            }
            case "RELATIONSHIP" -> {
                return rel(v.asRelationship());
            }
            case "PATH" -> {
                List<Object> parts = new ArrayList<>();
                Path path = v.asPath();
                parts.add(node(path.start()));
                for (Path.Segment seg : path) {
                    parts.add(rel(seg.relationship()));
                    parts.add(node(seg.end()));
                }
                return Map.of("~path", parts);
            }
            case "LIST" -> {
                List<Object> out = new ArrayList<>();
                for (Value x : v.values()) {
                    out.add(plain(x));
                }
                return out;
            }
            case "MAP" -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (String k : v.keys()) {
                    out.put(k, plain(v.get(k)));
                }
                return out;
            }
            case "INTEGER" -> {
                return v.asLong();
            }
            case "FLOAT" -> {
                return v.asDouble();
            }
            case "BOOLEAN" -> {
                return v.asBoolean();
            }
            case "STRING" -> {
                return v.asString();
            }
            case "DATE_TIME" -> {
                return v.asZonedDateTime().toOffsetDateTime().toString();
            }
            default -> {
                return v.asObject().toString();     // DATE、LOCAL_DATE_TIME、DURATION、POINT…
            }
        }
    }

    private static Map<String, Object> node(Node n) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        n.labels().forEach(labels::add);
        out.put("~labels", labels);
        out.put("~props", props(n.asMap(Value::asObject).keySet(), n::get));
        return out;
    }

    private static Map<String, Object> rel(Relationship r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("~type", r.type());
        out.put("~props", props(r.asMap(Value::asObject).keySet(), r::get));
        return out;
    }

    private static Map<String, Object> props(java.util.Set<String> keys, java.util.function.Function<String, Value> get) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys.stream().sorted().toList()) {
            out.put(k, plain(get.apply(k)));
        }
        return out;
    }

    /** 收集結果裡的節點與關係（含路徑、清單裡的），最多 MAX_GRAPH_NODES 個節點。 */
    private static final class GraphCollector {
        private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> rels = new LinkedHashMap<>();
        private boolean truncated;

        void collect(Value v) {
            if (v == null || v.isNull()) {
                return;
            }
            String type = v.type().name().startsWith("LIST") ? "LIST" : v.type().name();
            switch (type) {
                case "NODE" -> addNode(v.asNode());
                case "RELATIONSHIP" -> addRel(v.asRelationship());
                case "PATH" -> {
                    for (Node n : v.asPath().nodes()) {
                        addNode(n);
                    }
                    for (Relationship r : v.asPath().relationships()) {
                        addRel(r);
                    }
                }
                case "LIST" -> v.values().forEach(this::collect);
                case "MAP" -> v.values().forEach(this::collect);
                default -> {
                }
            }
        }

        private void addNode(Node n) {
            if (nodes.containsKey(n.elementId())) {
                return;
            }
            if (nodes.size() >= MAX_GRAPH_NODES) {
                truncated = true;
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.elementId());
            List<String> labels = new ArrayList<>();
            n.labels().forEach(labels::add);
            m.put("labels", labels);
            m.put("caption", caption(n));
            m.put("props", props(n.asMap(Value::asObject).keySet(), n::get));
            nodes.put(n.elementId(), m);
        }

        private void addRel(Relationship r) {
            if (rels.containsKey(r.elementId())) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.elementId());
            m.put("type", r.type());
            m.put("start", r.startNodeElementId());
            m.put("end", r.endNodeElementId());
            rels.put(r.elementId(), m);
        }

        private static String caption(Node n) {
            for (String k : List.of("name", "status", "id")) {
                if (n.containsKey(k)) {
                    return String.valueOf(n.get(k).asObject());
                }
            }
            return n.labels().iterator().hasNext() ? n.labels().iterator().next() : "";
        }

        Graph result() {
            if (nodes.isEmpty()) {
                return null;
            }
            // 關係的兩端都要在圖裡才畫
            List<Map<String, Object>> rs = rels.values().stream()
                    .filter(r -> nodes.containsKey((String) r.get("start")) && nodes.containsKey((String) r.get("end"))).toList();
            return new Graph(new ArrayList<>(nodes.values()), rs, truncated);
        }
    }
}
