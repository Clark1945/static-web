package com.example.dbshowcase.cassandra.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.example.dbshowcase.cassandra.CassandraDataLoader;
import com.example.dbshowcase.cassandra.CassandraSessions;
import com.example.dbshowcase.cassandra.CqlShell;
import com.example.dbshowcase.common.YamlContent;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cassandra 實驗室：
 * 1. 查詢與表設計：同一個問題用不同的表 / ALLOW FILTERING / SAI 索引查，看查詢追蹤裡讀了多少列
 * 2. 墓碑：把佇列當成 Cassandra 表用（逐筆刪除 vs 範圍刪除），看讀取要跳過多少墓碑
 * 3. 一致性與輕量交易：一致性等級的計算、庫存超賣（先讀再寫 vs IF 條件寫入）
 */
@Service
public class CassandraLabService {

    private static final int MAX_INDEXES = 6;
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^CREATE\\s+(?:CUSTOM\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)?\\s*ON\\s+(?:shop\\.)?(\\w+)\\s*\\(.*\\)\\s*(?:USING\\s+'\\w+'.*)?$");
    private static final Pattern DROP_INDEX = Pattern.compile("(?is)^DROP\\s+INDEX\\s+(?:IF\\s+EXISTS\\s+)?(?:shop\\.)?(\\w+)$");

    public record Step(String id, String title, String goal, List<Query> queries, List<String> indexes,
                       String question, String takeaway) {
        public record Query(String label, String cql) {
        }
    }

    public record IndexInfo(String name, String table, String target, String kind, boolean queryable) {
    }

    private final CassandraSessions sessions;
    private final CqlShell shell;
    private final List<Step> steps;

    public CassandraLabService(CassandraSessions sessions, CqlShell shell, ObjectMapper mapper) {
        this.sessions = sessions;
        this.shell = shell;
        this.steps = YamlContent.load("cassandra/query-lab.yml", Step.class, mapper);
    }

    // ================================================================ 1. 查詢與表設計

    public List<Step> steps() {
        return steps;
    }

    /**
     * 用 reader 在 shop 執行一句查詢，並開啟查詢追蹤。
     * 一次把結果全部讀完（最多 5000 列，一個分頁），追蹤才會反映整句查詢的成本；回傳給畫面時只留前 20 列。
     */
    public CqlShell.Result trace(String cql) {
        if (CqlShell.splitStatements(cql).size() != 1) {
            throw new IllegalArgumentException("一次只分析一句查詢。");
        }
        CqlShell.Result r = shell.run(sessions.reader(), CassandraSessions.SHOP, cql, 5000, DefaultConsistencyLevel.LOCAL_ONE, true).last();
        if (r.rows() == null || r.rows().size() <= 20) {
            return r;
        }
        return new CqlShell.Result(r.statement(), r.kind(), r.columns(), r.rows().subList(0, 20), r.total(), true,
                r.message(), r.warnings(), r.consistency(), r.trace(), r.millis());
    }

    public List<IndexInfo> indexes() {
        CqlSession admin = sessions.admin();
        List<IndexInfo> out = new ArrayList<>();
        for (Row r : admin.execute("SELECT index_name, table_name, kind, options FROM system_schema.indexes WHERE keyspace_name = 'shop'")) {
            String name = r.getString("index_name");
            Row sai = admin.execute(SimpleStatement.newInstance(
                    "SELECT is_queryable FROM system_views.sai_column_indexes WHERE keyspace_name = 'shop' AND index_name = ?", name)).one();
            String className = r.getMap("options", String.class, String.class).getOrDefault("class_name", "");
            out.add(new IndexInfo(name, r.getString("table_name"), r.getMap("options", String.class, String.class).get("target"),
                    className.contains("StorageAttachedIndex") ? "SAI" : r.getString("kind"),
                    sai == null || sai.getBoolean("is_queryable")));
        }
        return out;
    }

    /** 只接受對 shop 資料表的 CREATE INDEX / DROP INDEX。建立後等索引建好（SAI 是在背景建的）。 */
    public List<IndexInfo> ddl(String cql) {
        List<String> st = CqlShell.splitStatements(cql);
        if (st.size() != 1) {
            throw new IllegalArgumentException("一次只能執行一句 CREATE INDEX 或 DROP INDEX。");
        }
        String s = st.get(0);
        Matcher create = CREATE_INDEX.matcher(s);
        if (create.matches()) {
            if (!CassandraDataLoader.TABLES.contains(create.group(2).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("只能對 shop 的資料表建索引：" + String.join("、", CassandraDataLoader.TABLES));
            }
            if (indexes().size() >= MAX_INDEXES) {
                throw new IllegalArgumentException("最多 " + MAX_INDEXES + " 個索引，請先刪掉一些（或按「刪除全部索引」）。");
            }
        } else if (!DROP_INDEX.matcher(s).matches()) {
            throw new IllegalArgumentException("這裡只接受 CREATE INDEX … ON 表 (欄位) USING 'sai' 或 DROP INDEX 索引名稱。");
        }
        CqlShell.Result r = shell.run(sessions.admin(), CassandraSessions.SHOP, s, 10).last();
        if (r.kind().equals("error")) {
            throw new IllegalArgumentException(r.message());
        }
        waitForIndexes();
        return indexes();
    }

    public List<IndexInfo> dropIndexes() {
        for (IndexInfo i : indexes()) {
            sessions.admin().execute("DROP INDEX IF EXISTS shop." + i.name());
        }
        return indexes();
    }

    private void waitForIndexes() {
        for (int i = 0; i < 120; i++) {
            if (indexes().stream().allMatch(IndexInfo::queryable)) {
                return;
            }
            sleep(500);
        }
    }

    // ================================================================ 2. 墓碑

    public record TombstoneStatus(boolean ready, int messages, int deleted, double insertMillis, double deleteMillis,
                                  long overwhelmed) {
    }

    public record TombstoneRead(String partition, String label, CqlShell.Result result) {
    }

    private volatile TombstoneStatus tombstones = new TombstoneStatus(false, 0, 0, 0, 0, 0);

    public TombstoneStatus tombstoneStatus() {
        return tombstones;
    }

    /**
     * 三個分區，各放 n 則訊息（佇列）：
     * fresh：沒刪過；row：消費時一筆一筆刪（留下最後 10 則）；range：一句範圍刪除（留下最後 10 則）。
     */
    public synchronized TombstoneStatus prepareTombstones(int n) {
        if (n < 100 || n > 50_000) {
            throw new IllegalArgumentException("訊息數量請在 100 到 50,000 之間。");
        }
        CqlSession s = sessions.admin();
        s.execute("DROP TABLE IF EXISTS lab.queue");
        s.execute("CREATE TABLE lab.queue (queue text, msg_id int, payload text, PRIMARY KEY ((queue), msg_id))");
        PreparedStatement insert = s.prepare("INSERT INTO lab.queue (queue, msg_id, payload) VALUES (?, ?, ?)");
        long t0 = System.nanoTime();
        Async w = new Async(s);
        for (String q : List.of("fresh", "row", "range")) {
            for (int i = 1; i <= n; i++) {
                w.write(insert.bind(q, i, "訂單通知 #" + i));
            }
        }
        w.await();
        double insertMs = (System.nanoTime() - t0) / 1e6;
        long t1 = System.nanoTime();
        PreparedStatement delete = s.prepare("DELETE FROM lab.queue WHERE queue = 'row' AND msg_id = ?");
        for (int i = 1; i <= n - 10; i++) {
            w.write(delete.bind(i));
        }
        w.await();
        s.execute(SimpleStatement.newInstance("DELETE FROM lab.queue WHERE queue = 'range' AND msg_id <= ?", n - 10));
        tombstones = new TombstoneStatus(true, n, n - 10, insertMs, (System.nanoTime() - t1) / 1e6, 0);
        return tombstones;
    }

    /** 再對 row 分區刪掉 100,001 則（不存在的也照樣寫墓碑），超過 tombstone_failure_threshold（100,000）。 */
    public synchronized TombstoneStatus overwhelm() {
        if (!tombstones.ready()) {
            throw new IllegalArgumentException("請先建立佇列。");
        }
        CqlSession s = sessions.admin();
        PreparedStatement delete = s.prepare("DELETE FROM lab.queue WHERE queue = 'row' AND msg_id = ?");
        Async w = new Async(s);
        int from = -1;
        for (int i = 0; i < 100_001; i++) {
            w.write(delete.bind(from - i));     // 負的編號：排在剩下的 10 則前面，讀取時一定會經過
        }
        w.await();
        TombstoneStatus t = tombstones;
        tombstones = new TombstoneStatus(true, t.messages(), t.deleted(), t.insertMillis(), t.deleteMillis(), 100_001);
        return tombstones;
    }

    public List<TombstoneRead> readQueues() {
        if (!tombstones.ready()) {
            throw new IllegalArgumentException("請先建立佇列。");
        }
        List<TombstoneRead> out = new ArrayList<>();
        out.add(read("fresh", "沒有刪過"));
        out.add(read("row", "逐筆刪除"));
        out.add(read("range", "範圍刪除"));
        return out;
    }

    private TombstoneRead read(String partition, String label) {
        String cql = "SELECT msg_id, payload FROM lab.queue WHERE queue = '" + partition + "' LIMIT 10";
        return new TombstoneRead(partition, label,
                shell.run(sessions.admin(), CassandraSessions.LAB, cql, 10, DefaultConsistencyLevel.LOCAL_ONE, true).last());
    }

    // ================================================================ 3. 一致性等級

    public record ConsistencyResult(String keyspace, int replicationFactor, String level, String operation,
                                    boolean ok, String message, double millis) {
    }

    /** 同一句讀 / 寫，分別用 ONE、QUORUM、ALL 在複本數 1 與 3 的 keyspace 執行。這個叢集只有 1 個節點。 */
    public List<ConsistencyResult> consistency() {
        CqlSession s = sessions.admin();
        s.execute("CREATE TABLE IF NOT EXISTS lab.kv (k text PRIMARY KEY, v text)");
        List<ConsistencyResult> out = new ArrayList<>();
        for (String[] ks : new String[][] {{CassandraSessions.LAB, "1"}, {CassandraSessions.LAB_RF3, "3"}}) {
            for (DefaultConsistencyLevel cl : List.of(DefaultConsistencyLevel.ONE, DefaultConsistencyLevel.QUORUM, DefaultConsistencyLevel.ALL)) {
                out.add(consistencyRun(s, ks[0], Integer.parseInt(ks[1]), cl, "寫入",
                        "INSERT INTO " + ks[0] + ".kv (k, v) VALUES ('hello', '" + cl + "')"));
                out.add(consistencyRun(s, ks[0], Integer.parseInt(ks[1]), cl, "讀取",
                        "SELECT v FROM " + ks[0] + ".kv WHERE k = 'hello'"));
            }
        }
        return out;
    }

    private static ConsistencyResult consistencyRun(CqlSession s, String ks, int rf, DefaultConsistencyLevel cl, String op, String cql) {
        long t = System.nanoTime();
        try {
            ResultSet rs = s.execute(SimpleStatement.builder(cql).setConsistencyLevel(cl).build());
            Row row = rs.one();
            String msg = row == null ? "成功" : "成功，讀到 v = '" + row.getString(0) + "'";
            return new ConsistencyResult(ks, rf, cl.name(), op, true, msg, (System.nanoTime() - t) / 1e6);
        } catch (DriverException e) {
            return new ConsistencyResult(ks, rf, cl.name(), op, false, CqlShell.describe(e),
                    (System.nanoTime() - t) / 1e6);
        }
    }

    // ================================================================ 3. 庫存超賣

    public record OversellResult(String mode, int buyers, int stock, int sold, int finalStock, int oversold,
                                 int conflicts, int errors, double millis, String sample) {
    }

    /**
     * buyers 個人同時搶 stock 件商品。
     * naive：讀出庫存 → 大於 0 就寫回「庫存 - 1」（兩步之間別人也在做同樣的事）。
     * lwt  ：UPDATE … SET stock = 新值 IF stock = 讀到的值，失敗（被別人搶先）就重讀再試。
     */
    public synchronized OversellResult oversell(String mode, int buyers, int stock) {
        if (!mode.equals("naive") && !mode.equals("lwt")) {
            throw new IllegalArgumentException("mode 只能是 naive 或 lwt。");
        }
        if (buyers < 2 || buyers > 200 || stock < 1 || stock > 100) {
            throw new IllegalArgumentException("搶購人數 2～200、庫存 1～100。");
        }
        CqlSession s = sessions.admin();
        s.execute("CREATE TABLE IF NOT EXISTS lab.stock (sku text PRIMARY KEY, stock int)");
        s.execute(SimpleStatement.newInstance("INSERT INTO lab.stock (sku, stock) VALUES ('iphone', ?)", stock));
        PreparedStatement read = s.prepare("SELECT stock FROM lab.stock WHERE sku = 'iphone'");
        PreparedStatement write = s.prepare("UPDATE lab.stock SET stock = ? WHERE sku = 'iphone'");
        PreparedStatement cas = s.prepare("UPDATE lab.stock SET stock = ? WHERE sku = 'iphone' IF stock = ?");

        AtomicInteger sold = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<String> sample = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyers);
        for (int b = 0; b < buyers; b++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (mode.equals("naive")) {
                        int current = s.execute(read.bind()).one().getInt(0);
                        if (current > 0) {
                            s.execute(write.bind(current - 1));
                            sold.incrementAndGet();
                        }
                    } else {
                        for (int attempt = 0; attempt < 100; attempt++) {
                            int current = s.execute(read.bind().setConsistencyLevel(DefaultConsistencyLevel.SERIAL)).one().getInt(0);
                            if (current <= 0) {
                                break;
                            }
                            Row r = s.execute(cas.bind(current - 1, current)).one();
                            if (r.getBoolean("[applied]")) {
                                sold.incrementAndGet();
                                break;
                            }
                            conflicts.incrementAndGet();
                            sample.compareAndSet(null, "[applied] = false，目前庫存已經是 " + r.getInt("stock") + "，重讀再試");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (DriverException e) {
                    errors.incrementAndGet();
                    sample.compareAndSet(null, CqlShell.describe(e));
                } finally {
                    done.countDown();
                }
            });
        }
        long t = System.nanoTime();
        start.countDown();
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        double ms = (System.nanoTime() - t) / 1e6;
        int finalStock = s.execute(read.bind().setConsistencyLevel(DefaultConsistencyLevel.SERIAL)).one().getInt(0);
        return new OversellResult(mode, buyers, stock, sold.get(), finalStock, Math.max(0, sold.get() - stock),
                conflicts.get(), errors.get(), ms, sample.get());
    }

    /** 一般寫入 vs 輕量交易寫入，各追蹤一次，比較伺服器內部做了幾步。 */
    public List<CqlShell.Result> lwtCost() {
        CqlSession s = sessions.admin();
        s.execute("CREATE TABLE IF NOT EXISTS lab.kv (k text PRIMARY KEY, v text)");
        s.execute("DELETE FROM lab.kv WHERE k = 'lwt-demo'");
        return List.of(
                shell.run(s, CassandraSessions.LAB, "INSERT INTO kv (k, v) VALUES ('plain-demo', '一般寫入')", 5,
                        DefaultConsistencyLevel.LOCAL_ONE, true).last(),
                shell.run(s, CassandraSessions.LAB, "INSERT INTO kv (k, v) VALUES ('lwt-demo', '輕量交易') IF NOT EXISTS", 5,
                        DefaultConsistencyLevel.LOCAL_ONE, true).last());
    }

    // ================================================================ 工具

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 非同步寫入，同時最多 256 個請求。 */
    private static final class Async {
        private final CqlSession session;
        private final Semaphore permits = new Semaphore(256);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Async(CqlSession session) {
            this.session = session;
        }

        void write(com.datastax.oss.driver.api.core.cql.Statement<?> st) {
            permits.acquireUninterruptibly();
            CompletionStage<?> f = session.executeAsync(st);
            f.whenComplete((r, e) -> {
                if (e != null) {
                    failure.compareAndSet(null, e);
                }
                permits.release();
            });
        }

        void await() {
            permits.acquireUninterruptibly(256);
            permits.release(256);
            if (failure.get() != null) {
                throw new IllegalStateException("寫入失敗：" + failure.get().getMessage(), failure.get());
            }
        }
    }
}
