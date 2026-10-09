package com.example.dbshowcase.pgvector;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.dbshowcase.postgres.QueryResult;

/**
 * pgvector 實驗室：
 * 1. 語意搜尋 vs 關鍵字搜尋 vs 混合搜尋（RRF），用 products
 * 2. 索引：精確搜尋、HNSW（ef_search）、IVFFlat（lists、probes）的速度與召回率，用 passages 的 10 萬筆
 * 3. 過濾 + 向量索引：結果不足 LIMIT 筆、iterative scan
 * 4. 量化：halfvec、binary_quantize 的索引大小與召回率
 * 召回率 = 跟精確搜尋的前 10 名相比，找回了幾筆。100 個測試向量（questions）各查一次取平均。
 * 量測都在交易裡用 SET LOCAL 調參數，最後 ROLLBACK；IVFFlat 建在複製出來的 lab_passages，量化索引名稱以 lab_ 開頭。
 */
@Service
public class PgvectorLabService {

    private static final int K = 10;
    private static final Set<String> FILTERS = Set.of("tenant_id = 7", "tenant_id <= 5", "tenant_id <= 25");
    private static final Set<String> ITERATIVE = Set.of("off", "strict_order", "relaxed_order");
    private static final Set<Integer> LISTS = Set.of(10, 100, 1000);

    private final VectorDb db;
    private volatile List<String> questionVectors;
    private volatile int cachedLoad = -1;
    private final Map<String, List<Set<Integer>>> truth = new ConcurrentHashMap<>();

    public PgvectorLabService(VectorDb db) {
        this.db = db;
    }

    private JdbcTemplate jdbc() {
        return db.jdbc();
    }

    // ================================================================ 1. 語意搜尋

    public record Section(String title, String sql, QueryResult result) {
    }

    public record SearchResult(String text, List<String> tokens, List<Section> sections) {
    }

    public SearchResult search(String text) {
        String q = text == null ? "" : text.strip();
        if (q.isEmpty() || q.length() > 100) {
            throw new IllegalArgumentException("請輸入 1～100 個字的搜尋文字。");
        }
        List<String> tokens = jdbc().query("SELECT unnest(tokens(?))", (rs, i) -> rs.getString(1), q);
        String lit = literal(q);
        List<String> terms = Arrays.stream(q.split("\\s+")).filter(s -> !s.isEmpty()).distinct().limit(5).toList();
        String matched = String.join(" + ", terms.stream().map(t -> "(description ILIKE " + literal("%" + likeEscape(t) + "%") + ")::int").toList());
        String any = String.join(" OR ", terms.stream().map(t -> "description ILIKE " + literal("%" + likeEscape(t) + "%")).toList());

        String semantic = """
                SELECT id, name, description,
                       round((embedding <=> embed(%s))::numeric, 3) AS distance
                FROM products
                ORDER BY embedding <=> embed(%s)
                LIMIT 10""".formatted(lit, lit);
        String keyword = """
                SELECT id, name, description,
                       %s AS matched_words
                FROM products
                WHERE %s
                ORDER BY matched_words DESC, id
                LIMIT 10""".formatted(matched, any);
        String hybrid = """
                WITH semantic AS (
                  SELECT id, row_number() OVER (ORDER BY embedding <=> embed(%s)) AS r
                  FROM products WHERE embed(%s) IS NOT NULL    -- 模型一個詞都不認得時，只用關鍵字
                  ORDER BY embedding <=> embed(%s) LIMIT 50
                ), keyword AS (
                  SELECT id, row_number() OVER (ORDER BY %s DESC, id) AS r
                  FROM products WHERE %s
                  ORDER BY r LIMIT 50
                )
                SELECT p.id, p.name, p.description, s.r AS semantic_rank, k.r AS keyword_rank,
                       round(coalesce(1.0 / (60 + s.r), 0) + coalesce(1.0 / (60 + k.r), 0), 4) AS rrf_score
                FROM semantic s
                FULL JOIN keyword k ON k.id = s.id
                JOIN products p ON p.id = coalesce(s.id, k.id)
                ORDER BY rrf_score DESC, p.id
                LIMIT 10""".formatted(lit, lit, lit, matched, any);
        List<Section> sections = List.of(
                new Section("語意搜尋：embedding <=> embed(文字)", semantic, db.queries().run(semantic)),
                new Section("關鍵字搜尋：描述裡有幾個詞（ILIKE）", keyword, db.queries().run(keyword)),
                new Section("混合搜尋：兩邊的名次用 RRF 合併", hybrid, db.queries().run(hybrid)));
        return new SearchResult(q, tokens, sections);
    }

    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ================================================================ 共用：量測

    public record Bench(String label, int queries, double recall, double avgMs, double maxMs, double avgRows, int minRows,
                        String sql, List<String> plan) {
    }

    private List<String> questions() {
        if (cachedLoad != db.loads()) {          // 資料重新載入過：測試向量與正確答案都要重算
            questionVectors = null;
            truth.clear();
            cachedLoad = db.loads();
        }
        if (questionVectors == null) {
            questionVectors = jdbc().query("SELECT embedding::text FROM questions ORDER BY id", (rs, i) -> rs.getString(1));
        }
        return questionVectors;
    }

    /** 精確搜尋的前 10 名（關掉索引，全部算一遍），依 where 條件分開快取。 */
    private List<Set<Integer>> truth(String where) {
        questions();
        return truth.computeIfAbsent(where, w -> timed(
                "SELECT id FROM passages " + (w.isEmpty() ? "" : "WHERE " + w + " ") + "ORDER BY embedding <=> ?::vector LIMIT " + K,
                List.of("SET LOCAL enable_indexscan = off"), null, null).ids());
    }

    private record Timed(List<Set<Integer>> ids, double avgMs, double maxMs) {
    }

    /** 在同一個交易裡套用設定，先暖機（讓索引讀進快取），再把 100 個測試向量各查一次並計時。最後 ROLLBACK。 */
    private Timed timed(String sql, List<String> settings, String explainSql, List<String> planOut) {
        List<String> vectors = questions();
        return jdbc().execute((ConnectionCallback<Timed>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (Statement st = con.createStatement(); PreparedStatement ps = con.prepareStatement(sql)) {
                st.execute("SET LOCAL statement_timeout = 60000");
                for (String s : settings) {
                    st.execute(s);
                }
                for (int i = 0; i < Math.min(10, vectors.size()); i++) {
                    run(ps, sql, vectors.get(i));
                }
                List<Set<Integer>> ids = new ArrayList<>();
                double total = 0;
                double max = 0;
                for (String v : vectors) {
                    long start = System.nanoTime();
                    ids.add(run(ps, sql, v));
                    double ms = (System.nanoTime() - start) / 1e6;
                    total += ms;
                    max = Math.max(max, ms);
                }
                if (explainSql != null && planOut != null) {
                    try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, COSTS OFF) " + explainSql)) {
                        while (rs.next()) {
                            planOut.add(rs.getString(1));
                        }
                    }
                }
                return new Timed(ids, total / vectors.size(), max);
            } finally {
                con.rollback();
                con.setAutoCommit(auto);
            }
        });
    }

    private static Set<Integer> run(PreparedStatement ps, String sql, String vector) throws SQLException {
        int params = (int) sql.chars().filter(c -> c == '?').count();
        for (int i = 1; i <= params; i++) {
            ps.setString(i, vector);
        }
        Set<Integer> ids = new HashSet<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    private Bench bench(String label, String sql, List<String> settings, String where, String explainSql) {
        List<String> plan = new ArrayList<>();
        List<Set<Integer>> expected = truth(where);
        Timed t = timed(sql, settings, explainSql, plan);
        double hit = 0;
        double rows = 0;
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < t.ids().size(); i++) {
            Set<Integer> got = t.ids().get(i);
            Set<Integer> want = expected.get(i);
            rows += got.size();
            min = Math.min(min, got.size());
            hit += got.stream().filter(want::contains).count() / (double) Math.max(1, want.size());
        }
        int n = t.ids().size();
        return new Bench(label, n, hit / n, t.avgMs(), t.maxMs(), rows / n, min, explainSql, plan);
    }

    private static String q1() {
        return "(SELECT embedding FROM questions WHERE id = 1)";
    }

    // ================================================================ 2. 索引：精確 / HNSW / IVFFlat

    public record IndexStatus(String hnswSize, Double hnswSeconds, String tableSize, Integer ivfLists, String ivfSize, Double ivfSeconds) {
    }

    public synchronized IndexStatus indexStatus() {
        String hnsw = jdbc().queryForObject("SELECT pg_size_pretty(pg_relation_size(to_regclass('passages_hnsw')))", String.class);
        String table = jdbc().queryForObject("SELECT pg_size_pretty(pg_table_size('passages'))", String.class);
        List<Map<String, Object>> ivf = jdbc().queryForList("""
                SELECT c.reloptions::text AS options, pg_size_pretty(pg_relation_size(c.oid)) AS size
                FROM pg_class c WHERE c.relname = 'lab_passages_ivfflat'""");
        Integer lists = null;
        String ivfSize = null;
        if (!ivf.isEmpty()) {
            String opts = String.valueOf(ivf.get(0).get("options"));
            lists = Integer.valueOf(opts.replaceAll("\\D+", ""));
            ivfSize = (String) ivf.get(0).get("size");
        }
        return new IndexStatus(hnsw, db.status().indexSeconds(), table, lists, ivfSize, lists == null ? null : db.timing("lab_passages_ivfflat"));
    }

    /** 把 passages 複製成 lab_passages，建 IVFFlat 索引（lists 個分群）。 */
    public synchronized IndexStatus buildIvf(int lists) {
        if (!LISTS.contains(lists)) {
            throw new IllegalArgumentException("lists 只能是 " + LISTS);
        }
        jdbc().execute("DROP TABLE IF EXISTS lab_passages");
        jdbc().execute("CREATE TABLE lab_passages AS SELECT * FROM passages");
        long start = System.nanoTime();
        jdbc().execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET maintenance_work_mem = '512MB'");
                st.execute("SET max_parallel_maintenance_workers = 0");
                st.execute("CREATE INDEX lab_passages_ivfflat ON lab_passages USING ivfflat (embedding vector_cosine_ops) WITH (lists = " + lists + ")");
                st.execute("RESET maintenance_work_mem");
                st.execute("RESET max_parallel_maintenance_workers");
            }
            return null;
        });
        db.saveTiming("lab_passages_ivfflat", (System.nanoTime() - start) / 1e9);
        jdbc().execute("ANALYZE lab_passages");
        return indexStatus();
    }

    public synchronized Bench runIndex(String method, int efSearch, int probes) {
        return switch (method) {
            case "exact" -> bench("精確搜尋（不用索引）",
                    "SELECT id FROM passages ORDER BY embedding <=> ?::vector LIMIT " + K,
                    List.of("SET LOCAL enable_indexscan = off"), "",
                    "SELECT id FROM passages ORDER BY embedding <=> " + q1() + " LIMIT " + K);
            case "hnsw" -> bench("HNSW，ef_search = " + efSearch,
                    "SELECT id FROM passages ORDER BY embedding <=> ?::vector LIMIT " + K,
                    List.of("SET LOCAL hnsw.ef_search = " + clamp(efSearch, 1, 1000)), "",
                    "SELECT id FROM passages ORDER BY embedding <=> " + q1() + " LIMIT " + K);
            case "ivfflat" -> {
                if (indexStatus().ivfLists() == null) {
                    throw new IllegalArgumentException("還沒建立 IVFFlat 索引：先選 lists 再按「建立 IVFFlat 索引」。");
                }
                yield bench("IVFFlat，lists = " + indexStatus().ivfLists() + "，probes = " + probes,
                        "SELECT id FROM lab_passages ORDER BY embedding <=> ?::vector LIMIT " + K,
                        List.of("SET LOCAL ivfflat.probes = " + clamp(probes, 1, 1000)), "",
                        "SELECT id FROM lab_passages ORDER BY embedding <=> " + q1() + " LIMIT " + K);
            }
            default -> throw new IllegalArgumentException("method 只能是 exact、hnsw、ivfflat");
        };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ================================================================ 3. 過濾 + 向量索引

    public synchronized Bench runFilter(String filter, String mode, int efSearch) {
        if (!FILTERS.contains(filter)) {
            throw new IllegalArgumentException("過濾條件只能是 " + FILTERS);
        }
        String sql = "SELECT id FROM passages WHERE " + filter + " ORDER BY embedding <=> ?::vector LIMIT " + K;
        String explain = "SELECT id FROM passages WHERE " + filter + " ORDER BY embedding <=> " + q1() + " LIMIT " + K;
        if ("exact".equals(mode)) {
            return bench("精確搜尋（不用向量索引）", sql, List.of("SET LOCAL enable_indexscan = off"), filter, explain);
        }
        if (!ITERATIVE.contains(mode)) {
            throw new IllegalArgumentException("iterative_scan 只能是 " + ITERATIVE);
        }
        int ef = clamp(efSearch, 1, 1000);
        return bench("HNSW，ef_search = " + ef + "，iterative_scan = " + mode, sql,
                List.of("SET LOCAL hnsw.ef_search = " + ef, "SET LOCAL hnsw.iterative_scan = " + mode), filter, explain);
    }

    // ================================================================ 4. 量化

    public record QuantStatus(boolean prepared, Map<String, String> sizes, Map<String, Double> buildSeconds) {
    }

    public synchronized QuantStatus quantStatus() {
        Map<String, String> sizes = new LinkedHashMap<>();
        sizes.put("table", jdbc().queryForObject("SELECT pg_size_pretty(pg_table_size('passages'))", String.class));
        boolean prepared = true;
        for (String name : List.of("passages_hnsw", "lab_passages_half", "lab_passages_bit")) {
            String size = jdbc().queryForObject("SELECT pg_size_pretty(pg_relation_size(to_regclass(?)))", String.class, name);
            sizes.put(name, size);
            if (size == null && name.startsWith("lab_")) {
                prepared = false;
            }
        }
        Map<String, Double> seconds = new LinkedHashMap<>();
        for (String name : List.of("passages_hnsw", "lab_passages_half", "lab_passages_bit")) {
            seconds.put(name, db.timing(name));
        }
        return new QuantStatus(prepared, sizes, seconds);
    }

    /** 在 passages 上建兩個運算式索引：halfvec（每維 2 bytes）與 binary_quantize（每維 1 bit）。 */
    public synchronized QuantStatus prepareQuant() {
        Map<String, String> ddl = new LinkedHashMap<>();
        ddl.put("lab_passages_half", "CREATE INDEX lab_passages_half ON passages USING hnsw ((embedding::halfvec(128)) halfvec_cosine_ops)");
        ddl.put("lab_passages_bit", "CREATE INDEX lab_passages_bit ON passages USING hnsw ((binary_quantize(embedding)::bit(128)) bit_hamming_ops)");
        for (var e : ddl.entrySet()) {
            jdbc().execute("DROP INDEX IF EXISTS " + e.getKey());
            long start = System.nanoTime();
            jdbc().execute((ConnectionCallback<Void>) con -> {
                try (Statement st = con.createStatement()) {
                    st.execute("SET maintenance_work_mem = '512MB'");
                    st.execute("SET max_parallel_maintenance_workers = 0");
                    st.execute(e.getValue());
                    st.execute("RESET maintenance_work_mem");
                    st.execute("RESET max_parallel_maintenance_workers");
                }
                return null;
            });
            db.saveTiming(e.getKey(), (System.nanoTime() - start) / 1e9);
        }
        return quantStatus();
    }

    public synchronized Bench runQuant(String method, int efSearch) {
        int ef = clamp(efSearch, 1, 1000);
        if (!"full".equals(method) && !quantStatus().prepared()) {
            throw new IllegalArgumentException("還沒建立量化索引：先按「建立量化索引」。");
        }
        String setting = "SET LOCAL hnsw.ef_search = " + ef;
        return switch (method) {
            case "full" -> bench("vector（float4）", "SELECT id FROM passages ORDER BY embedding <=> ?::vector LIMIT " + K,
                    List.of(setting), "", "SELECT id FROM passages ORDER BY embedding <=> " + q1() + " LIMIT " + K);
            case "half" -> bench("halfvec（float2）",
                    "SELECT id FROM passages ORDER BY embedding::halfvec(128) <=> ?::halfvec(128) LIMIT " + K,
                    List.of(setting), "",
                    "SELECT id FROM passages ORDER BY embedding::halfvec(128) <=> " + q1() + "::halfvec(128) LIMIT " + K);
            case "bit" -> bench("binary_quantize（1 bit）",
                    "SELECT id FROM passages ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize(?::vector) LIMIT " + K,
                    List.of(setting), "",
                    "SELECT id FROM passages ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize(" + q1() + ") LIMIT " + K);
            case "rerank" -> {
                // 先用 bit 索引撈 100 筆候選，再用原本的向量精確排序；HNSW 最多只回傳 ef_search 筆，所以至少要 100
                int efr = Math.max(ef, 100);
                yield bench("binary_quantize 撈 100 筆 → 原始向量重排（ef_search = " + efr + "）", """
                        SELECT id FROM (
                          SELECT id, embedding FROM passages
                          ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize(?::vector) LIMIT 100
                        ) c ORDER BY embedding <=> ?::vector LIMIT %d""".formatted(K),
                        List.of("SET LOCAL hnsw.ef_search = " + efr), "", """
                        SELECT id FROM (
                          SELECT id, embedding FROM passages
                          ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize(%s) LIMIT 100
                        ) c ORDER BY embedding <=> %s LIMIT %d""".formatted(q1(), q1(), K));
            }
            default -> throw new IllegalArgumentException("method 只能是 full、half、bit、rerank");
        };
    }

}
