package com.example.dbshowcase.timescale;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.dbshowcase.common.YamlContent;
import com.example.dbshowcase.postgres.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TimescaleDB 實驗室：
 * 1. chunk exclusion：EXPLAIN ANALYZE 看查詢讀了幾個 chunk
 * 2. 壓縮（columnstore）：把 9 月的瀏覽紀錄複製到 lab_page_views，用不同的 segmentby 壓縮，比較大小與查詢速度
 * 3. 連續聚合：原始資料即時算 vs 讀聚合；即時聚合（materialized_only = false）與 refresh
 * 壓縮、連續聚合都在實驗室自己的表 / 視圖上做，不影響練習題用的資料。
 */
@Service
public class TimescaleLabService {

    private static final Pattern CHUNK = Pattern.compile("_hyper_\\d+_\\d+_chunk");
    private static final Pattern EXEC_TIME = Pattern.compile("Execution Time: ([\\d.]+) ms");
    private static final Set<String> SEGMENT_BY = Set.of("none", "device", "product_id");

    public record Step(String id, String title, String goal, List<Query> queries, String question, String takeaway) {
        public record Query(String label, String sql) {
        }
    }

    public record ExplainResult(String sql, int chunks, int totalChunks, Double executionMs, List<String> plan, Object value) {
    }

    private final TimescaleDb db;
    private final List<Step> steps;

    public TimescaleLabService(TimescaleDb db, ObjectMapper mapper) {
        this.db = db;
        this.steps = YamlContent.load("timescale/chunk-lab.yml", Step.class, mapper);
    }

    private JdbcTemplate jdbc() {
        return db.jdbc();
    }

    // ================================================================ 1. chunk exclusion

    public List<Step> steps() {
        return steps;
    }

    /** 以 learner 角色在唯讀交易裡執行 EXPLAIN (ANALYZE)，數出計畫裡出現幾個不同的 chunk。 */
    public ExplainResult explain(String sql) {
        String q = sql.strip().replaceAll(";+$", "").replaceFirst("(?i)^EXPLAIN\\s*(\\([^)]*\\))?\\s*", "");
        QueryResult value = db.queries().run(q, 5);
        QueryResult plan = db.queries().run("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) " + q, 2000);
        List<String> lines = new ArrayList<>();
        Set<String> chunks = new LinkedHashSet<>();
        Double ms = null;
        for (List<Object> row : plan.rows()) {
            String line = String.valueOf(row.get(0));
            lines.add(line);
            Matcher m = CHUNK.matcher(line);
            while (m.find()) {
                chunks.add(m.group());
            }
            Matcher t = EXEC_TIME.matcher(line);
            if (t.find()) {
                ms = Double.parseDouble(t.group(1));
            }
        }
        Integer total = jdbc().queryForObject("SELECT count(*) FROM show_chunks('page_views')", Integer.class);
        Object first = value.rows().isEmpty() ? null : value.rows().get(0).get(0);
        return new ExplainResult(q, chunks.size(), total == null ? 0 : total, ms, lines, first);
    }

    // ================================================================ 2. 壓縮

    public record CompressionStatus(boolean prepared, long rows, int chunks, int compressedChunks, String segmentBy,
                                    String orderBy, Long beforeBytes, Long afterBytes, Long currentBytes, String message) {
    }

    public synchronized CompressionStatus compressionStatus() {
        Boolean exists = jdbc().queryForObject("SELECT to_regclass('public.lab_page_views') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            return new CompressionStatus(false, 0, 0, 0, null, null, null, null, null, null);
        }
        long rows = jdbc().queryForObject("SELECT count(*) FROM lab_page_views", Long.class);
        Map<String, Object> c = jdbc().queryForMap("""
                SELECT count(*) AS chunks, count(*) FILTER (WHERE is_compressed) AS compressed
                FROM timescaledb_information.chunks WHERE hypertable_name = 'lab_page_views'""");
        List<Map<String, Object>> settings = jdbc().queryForList("""
                SELECT segmentby, orderby FROM timescaledb_information.hypertable_compression_settings
                WHERE hypertable = 'lab_page_views'::regclass""");
        Map<String, Object> stats = jdbc().queryForMap("""
                SELECT sum(before_compression_total_bytes) AS before, sum(after_compression_total_bytes) AS after
                FROM chunk_compression_stats('lab_page_views') WHERE compression_status = 'Compressed'""");
        Long current = jdbc().queryForObject("SELECT hypertable_size('lab_page_views')", Long.class);
        String seg = settings.isEmpty() ? null : (String) settings.get(0).get("segmentby");
        String ord = settings.isEmpty() ? null : (String) settings.get(0).get("orderby");
        return new CompressionStatus(true, rows, ((Number) c.get("chunks")).intValue(), ((Number) c.get("compressed")).intValue(),
                seg, ord, stats.get("before") == null ? null : ((Number) stats.get("before")).longValue(),
                stats.get("after") == null ? null : ((Number) stats.get("after")).longValue(), current, null);
    }

    /** 把 9 月的瀏覽紀錄複製成一個新的 hypertable（每天一個 chunk），還沒壓縮。 */
    public synchronized CompressionStatus prepareCompression() {
        jdbc().execute("DROP TABLE IF EXISTS lab_page_views");
        jdbc().execute("CREATE TABLE lab_page_views (LIKE page_views INCLUDING DEFAULTS)");
        jdbc().execute("SELECT create_hypertable('lab_page_views', by_range('view_time', INTERVAL '1 day'))");
        jdbc().execute("""
                INSERT INTO lab_page_views
                SELECT * FROM page_views
                WHERE view_time >= '2026-09-01 00:00+08' AND view_time < '2026-10-01 00:00+08'
                ORDER BY view_time""");
        jdbc().execute("CREATE INDEX ON lab_page_views (product_id, view_time DESC)");
        jdbc().execute("ANALYZE lab_page_views");
        jdbc().execute("GRANT SELECT ON lab_page_views TO learner");
        return compressionStatus();
    }

    /** 先解壓（如果壓過），換一組 segmentby 設定，再壓縮全部 chunk。 */
    public synchronized CompressionStatus compress(String segmentBy) {
        if (!SEGMENT_BY.contains(segmentBy)) {
            throw new IllegalArgumentException("segmentby 只能是 none、device、product_id。");
        }
        if (!compressionStatus().prepared()) {
            prepareCompression();
        }
        decompressAll();
        String seg = segmentBy.equals("none") ? "" : segmentBy;
        jdbc().execute("ALTER TABLE lab_page_views SET (timescaledb.compress, timescaledb.compress_segmentby = '" + seg
                + "', timescaledb.compress_orderby = 'view_time DESC')");
        long start = System.nanoTime();
        jdbc().query("SELECT compress_chunk(c, if_not_compressed => true) FROM show_chunks('lab_page_views') c", rs -> {
        });
        double ms = (System.nanoTime() - start) / 1e6;
        jdbc().execute("ANALYZE lab_page_views");
        CompressionStatus s = compressionStatus();
        return new CompressionStatus(s.prepared(), s.rows(), s.chunks(), s.compressedChunks(), s.segmentBy(), s.orderBy(),
                s.beforeBytes(), s.afterBytes(), s.currentBytes(), String.format("壓縮 %d 個 chunk 花了 %.0f ms", s.compressedChunks(), ms));
    }

    public synchronized CompressionStatus decompress() {
        decompressAll();
        return compressionStatus();
    }

    private void decompressAll() {
        Boolean exists = jdbc().queryForObject("SELECT to_regclass('public.lab_page_views') IS NOT NULL", Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            jdbc().query("""
                    SELECT decompress_chunk(format('%I.%I', chunk_schema, chunk_name)::regclass)
                    FROM timescaledb_information.chunks
                    WHERE hypertable_name = 'lab_page_views' AND is_compressed""", rs -> {
            });
        }
    }

    public record Benchmark(String label, String sql, double ms, String scan, Object value) {
    }

    /** 同樣幾句查詢在 lab_page_views 上執行（各跑兩次取第二次），看壓縮前後的差別。 */
    public List<Benchmark> benchmark() {
        if (!compressionStatus().prepared()) {
            throw new IllegalArgumentException("請先按「建立實驗用的表」。");
        }
        List<String[]> qs = List.of(
                new String[] {"整個月各裝置的瀏覽數（讀很多列、只用 1 個欄位）",
                        "SELECT device, count(*) FROM lab_page_views GROUP BY device ORDER BY device"},
                new String[] {"商品 540 每天的瀏覽數（只要一小部分的列）",
                        "SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day, count(*) FROM lab_page_views WHERE product_id = 540 GROUP BY day ORDER BY day"},
                new String[] {"最新的 10 筆（依時間排序）",
                        "SELECT * FROM lab_page_views ORDER BY view_time DESC LIMIT 10"});
        List<Benchmark> out = new ArrayList<>();
        for (String[] q : qs) {
            db.queries().run(q[1], 50);
            QueryResult r = db.queries().run(q[1], 50);
            QueryResult plan = db.queries().run("EXPLAIN (COSTS OFF) " + q[1], 200);
            String scan = plan.rows().stream().map(row -> String.valueOf(row.get(0)))
                    .filter(l -> l.contains("DecompressChunk") || l.contains("ColumnarScan") || l.contains("Seq Scan")
                            || l.contains("Index Scan") || l.contains("Index Only Scan"))
                    .map(l -> l.strip().replaceAll("^->\\s*", "").replaceAll(" on _hyper.*| using _hyper.*|_timescaledb_internal\\.", ""))
                    .findFirst().orElse("");
            out.add(new Benchmark(q[0], q[1], r.elapsedMs(), scan, r.rows().isEmpty() ? null : r.rows().get(0)));
        }
        return out;
    }

    // ================================================================ 3. 連續聚合

    public record AggregateStatus(boolean exists, Boolean materializedOnly, String watermark) {
    }

    public AggregateStatus aggregateStatus() {
        List<Map<String, Object>> r = jdbc().queryForList("""
                SELECT c.materialized_only,
                       to_char(_timescaledb_functions.to_timestamp(_timescaledb_functions.cagg_watermark(m.mat_hypertable_id)) AT TIME ZONE 'Asia/Taipei', 'YYYY-MM-DD HH24:MI') AS watermark
                FROM timescaledb_information.continuous_aggregates c
                JOIN _timescaledb_catalog.continuous_agg m ON m.user_view_name = c.view_name
                WHERE c.view_name = 'lab_views_daily'""");
        if (r.isEmpty()) {
            return new AggregateStatus(false, null, null);
        }
        return new AggregateStatus(true, (Boolean) r.get(0).get("materialized_only"), (String) r.get(0).get("watermark"));
    }

    /** 建立實驗用的連續聚合（跟 page_views_daily 一樣，物化到 9/24）。 */
    public synchronized AggregateStatus prepareAggregate() {
        jdbc().execute("DROP MATERIALIZED VIEW IF EXISTS lab_views_daily");
        jdbc().execute("""
                CREATE MATERIALIZED VIEW lab_views_daily WITH (timescaledb.continuous) AS
                SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day, product_id, count(*) AS views
                FROM page_views GROUP BY day, product_id
                WITH NO DATA""");
        jdbc().execute("CALL refresh_continuous_aggregate('lab_views_daily', NULL, '" + TimescaleDb.PAGE_VIEWS_REFRESHED_UNTIL + "')");
        jdbc().execute("GRANT SELECT ON lab_views_daily TO learner");
        return aggregateStatus();
    }

    public synchronized AggregateStatus setRealtime(boolean realtime) {
        requireAggregate();
        jdbc().execute("ALTER MATERIALIZED VIEW lab_views_daily SET (timescaledb.materialized_only = " + !realtime + ")");
        return aggregateStatus();
    }

    public synchronized AggregateStatus refreshAll() {
        requireAggregate();
        jdbc().execute("CALL refresh_continuous_aggregate('lab_views_daily', NULL, NULL)");
        return aggregateStatus();
    }

    /** 同一份報表：原始資料即時算 vs 讀連續聚合；以及 9/24 之後的資料在聚合裡看不看得到。 */
    public List<Benchmark> compareAggregate() {
        requireAggregate();
        List<String[]> qs = List.of(
                new String[] {"原始資料：9/1～9/22 最熱門的 10 件商品",
                        "SELECT product_id, count(*) AS views FROM page_views WHERE view_time >= '2026-09-01 00:00+08' AND view_time < '2026-09-23 00:00+08' GROUP BY product_id ORDER BY views DESC, product_id LIMIT 10"},
                new String[] {"連續聚合：同一份報表",
                        "SELECT product_id, sum(views) AS views FROM lab_views_daily WHERE day >= '2026-09-01 00:00+08' AND day < '2026-09-23 00:00+08' GROUP BY product_id ORDER BY views DESC, product_id LIMIT 10"},
                new String[] {"原始資料：9/24 之後的瀏覽數",
                        "SELECT count(*) FROM page_views WHERE view_time >= '2026-09-24 00:00+08'"},
                new String[] {"連續聚合：9/24 之後的瀏覽數",
                        "SELECT coalesce(sum(views), 0) FROM lab_views_daily WHERE day >= '2026-09-24 00:00+08'"});
        List<Benchmark> out = new ArrayList<>();
        for (String[] q : qs) {
            db.queries().run(q[1], 20);
            QueryResult r = db.queries().run(q[1], 20);
            out.add(new Benchmark(q[0], q[1], r.elapsedMs(), "", r.rows().isEmpty() ? null : r.rows().get(0)));
        }
        return out;
    }

    private void requireAggregate() {
        if (!aggregateStatus().exists()) {
            throw new IllegalArgumentException("請先按「建立實驗用的連續聚合」。");
        }
    }
}
