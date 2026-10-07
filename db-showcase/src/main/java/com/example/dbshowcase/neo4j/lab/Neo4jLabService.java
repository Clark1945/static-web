package com.example.dbshowcase.neo4j.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.dbshowcase.common.YamlContent;
import com.example.dbshowcase.neo4j.CypherShell;
import com.example.dbshowcase.neo4j.Neo4jConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Neo4j 實驗室：
 * 1. PROFILE 與索引：看執行計畫（運算子、每一步的列數與 db hits），比較有沒有索引
 * 2. 圖 vs SQL：同一個問題分別用 Cypher 和 PostgreSQL 的 SQL 回答，比較寫法與耗時
 */
@Service
public class Neo4jLabService {

    private static final int MAX_INDEXES = 6;
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^CREATE\\s+(RANGE\\s+|TEXT\\s+|POINT\\s+)?INDEX\\s+\\w*\\s*(IF\\s+NOT\\s+EXISTS\\s+)?FOR\\s*\\(\\s*\\w+\\s*:\\s*\\w+\\s*\\)\\s*ON\\s*\\(.*\\)$");
    private static final Pattern DROP_INDEX = Pattern.compile("(?is)^DROP\\s+INDEX\\s+\\w+(\\s+IF\\s+EXISTS)?$");

    public record Step(String id, String title, String goal, List<Query> queries, List<String> indexes,
                       String question, String takeaway) {
        public record Query(String label, String cypher) {
        }
    }

    public record IndexInfo(String name, String type, String entity, List<String> labels, List<String> properties,
                            String state, boolean constraint) {
    }

    private final Driver driver;
    private final CypherShell shell;
    private final JdbcTemplate jdbc;
    /** 實驗室比較用的 SQL：最多跑 30 秒。 */
    private final JdbcTemplate sqlWithTimeout;
    private final List<Step> steps;

    public Neo4jLabService(Driver driver, CypherShell shell, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.driver = driver;
        this.shell = shell;
        this.jdbc = jdbc;
        this.sqlWithTimeout = new JdbcTemplate(jdbc.getDataSource());
        this.sqlWithTimeout.setQueryTimeout(30);
        this.steps = YamlContent.load("neo4j/profile-lab.yml", Step.class, mapper);
    }

    private Session session() {
        return driver.session(SessionConfig.forDatabase(Neo4jConfig.DATABASE));
    }

    // ================================================================ 1. PROFILE 與索引

    public List<Step> steps() {
        return steps;
    }

    public List<IndexInfo> indexes() {
        try (Session s = session()) {
            return s.run("SHOW INDEXES YIELD name, type, entityType, labelsOrTypes, properties, state, owningConstraint "
                            + "WHERE type <> 'LOOKUP' RETURN * ORDER BY owningConstraint IS NULL, name")
                    .list(r -> new IndexInfo(r.get("name").asString(), r.get("type").asString(), r.get("entityType").asString(),
                            r.get("labelsOrTypes").asList(v -> v.asString()), r.get("properties").asList(v -> v.asString()),
                            r.get("state").asString(), !r.get("owningConstraint").isNull()));
        }
    }

    /** 只接受 CREATE [RANGE|TEXT|POINT] INDEX … FOR (n:Label) ON (…) 與 DROP INDEX；約束不能刪。建好之後等索引上線。 */
    public List<IndexInfo> ddl(String cypher) {
        List<String> st = CypherShell.splitStatements(cypher);
        if (st.size() != 1) {
            throw new IllegalArgumentException("一次只能執行一句 CREATE INDEX 或 DROP INDEX。");
        }
        String s = st.get(0);
        if (CREATE_INDEX.matcher(s).matches()) {
            if (indexes().stream().filter(i -> !i.constraint()).count() >= MAX_INDEXES) {
                throw new IllegalArgumentException("最多 " + MAX_INDEXES + " 個自己建的索引，請先刪掉一些（或按「刪除自己建的索引」）。");
            }
        } else if (DROP_INDEX.matcher(s).matches()) {
            String name = s.replaceAll("(?is)^DROP\\s+INDEX\\s+(\\w+).*$", "$1");
            if (indexes().stream().anyMatch(i -> i.name().equals(name) && i.constraint())) {
                throw new IllegalArgumentException(name + " 是唯一約束的索引，載入資料要用到，這裡不能刪。");
            }
        } else {
            throw new IllegalArgumentException("這裡只接受 CREATE INDEX 名稱 FOR (n:標籤) ON (n.屬性) 或 DROP INDEX 名稱。");
        }
        try (Session session = session()) {
            session.run(s).consume();
            session.run("CALL db.awaitIndexes(120)").consume();
        }
        return indexes();
    }

    public List<IndexInfo> dropIndexes() {
        try (Session s = session()) {
            for (IndexInfo i : indexes()) {
                if (!i.constraint()) {
                    s.run("DROP INDEX " + i.name() + " IF EXISTS").consume();
                }
            }
        }
        return indexes();
    }

    /** 在查詢前面加上 PROFILE，於交易裡執行後 ROLLBACK。回傳結果裡有 plan（每個運算子的列數、db hits）。 */
    public CypherShell.Outcome profile(String cypher) {
        List<String> st = CypherShell.splitStatements(cypher);
        if (st.size() != 1) {
            throw new IllegalArgumentException("一次只分析一句查詢。");
        }
        String q = st.get(0).replaceFirst("(?i)^\\s*(PROFILE|EXPLAIN)\\s+", "");
        return shell.run("PROFILE " + q, 20).last();
    }

    // ================================================================ 2. 圖 vs SQL

    public record Side(String language, String code, List<String> columns, List<List<Object>> rows, double millis, String error) {
    }

    public record Comparison(String title, Side cypher, Side sql) {
    }

    /**
     * 買了某件商品的人也買了什麼：Cypher 兩段路徑 vs SQL 五張表 JOIN。
     * Cypher 分成兩個 MATCH：同一個 MATCH 裡同一條關係不會走兩次，寫成一條路徑會漏掉「同一張訂單一起買」的情況。
     */
    public Comparison recommend(int productId) {
        String cypher = """
                MATCH (:Product {id: %d})<-[:CONTAINS]-(:Order)<-[:PLACED]-(c:Customer)
                MATCH (c)-[:PLACED]->(:Order)-[:CONTAINS]->(other:Product)
                WHERE other.id <> %d
                RETURN other.name AS product, count(DISTINCT c) AS buyers
                ORDER BY buyers DESC, product
                LIMIT 5""".formatted(productId, productId);
        String sql = """
                SELECT p.name AS product, count(DISTINCT o1.customer_id) AS buyers
                FROM order_items i1
                JOIN orders o1 ON o1.id = i1.order_id
                JOIN orders o2 ON o2.customer_id = o1.customer_id
                JOIN order_items i2 ON i2.order_id = o2.id
                JOIN products p ON p.id = i2.product_id
                WHERE i1.product_id = %d AND i2.product_id <> %d
                GROUP BY p.name
                ORDER BY buyers DESC, product
                LIMIT 5""".formatted(productId, productId);
        return new Comparison("買了商品 " + productId + " 的人也買了", runCypher(cypher), runSql(sql));
    }

    /** 從一位會員出發，沿著追蹤關係 depth 步之內能到達幾位會員：Cypher 可變長度關係 vs SQL 遞迴 CTE。 */
    public List<Comparison> reach(int customerId, int maxDepth) {
        if (maxDepth < 1 || maxDepth > 6) {
            throw new IllegalArgumentException("步數請在 1 到 6 之間。");
        }
        ensureFollowsTable();
        List<Comparison> out = new ArrayList<>();
        for (int d = 1; d <= maxDepth; d++) {
            String cypher = """
                    MATCH (me:Customer {id: %d})-[:FOLLOWS*1..%d]->(x:Customer)
                    WHERE x <> me
                    RETURN count(DISTINCT x) AS reachable""".formatted(customerId, d);
            String sql = """
                    WITH RECURSIVE r(id, depth) AS (
                      SELECT dst, 1 FROM graphlab.follows WHERE src = %d
                      UNION
                      SELECT f.dst, r.depth + 1
                      FROM r JOIN graphlab.follows f ON f.src = r.id
                      WHERE r.depth < %d
                    )
                    SELECT count(DISTINCT id) AS reachable FROM r WHERE id <> %d""".formatted(customerId, d, customerId);
            out.add(new Comparison(d + " 步之內", runCypher(cypher), runSql(sql)));
        }
        return out;
    }

    public record ShortestPath(Comparison comparison, CypherShell.Graph graph) {
    }

    /**
     * 最短路徑：Cypher 的 shortestPath（雙向廣度優先搜尋，找到就停）vs SQL 遞迴 CTE（一層一層列舉所有不重複的路徑）。
     * SQL 必須事先給最大步數，這裡給 8；超過 8 步就找不到。
     */
    public ShortestPath shortestPath(int from, int to) {
        if (from < 1 || from > 20000 || to < 1 || to > 20000 || from == to) {
            throw new IllegalArgumentException("會員編號請在 1 到 20000 之間，而且起點和終點不同。");
        }
        ensureFollowsTable();
        String cypher = """
                MATCH p = shortestPath((:Customer {id: %d})-[:FOLLOWS*..10]->(:Customer {id: %d}))
                RETURN length(p) AS hops, [n IN nodes(p) | n.id] AS path""".formatted(from, to);
        String sql = """
                WITH RECURSIVE bfs(id, depth, path) AS (
                  SELECT %d, 0, ARRAY[%d]
                  UNION ALL
                  SELECT f.dst, b.depth + 1, b.path || f.dst
                  FROM bfs b JOIN graphlab.follows f ON f.src = b.id
                  WHERE b.depth < 8 AND f.dst <> ALL (b.path)   -- 最多 8 步、不走回頭路
                )
                SELECT depth AS hops, path FROM bfs WHERE id = %d
                ORDER BY depth LIMIT 1""".formatted(from, from, to);
        Comparison c = new Comparison("會員 " + from + " → 會員 " + to + " 的最短追蹤路徑", runCypher(cypher), runSql(sql));
        CypherShell.Outcome g = shell.run("MATCH p = shortestPath((:Customer {id: %d})-[:FOLLOWS*..10]->(:Customer {id: %d})) RETURN p"
                .formatted(from, to), 5).last();
        return new ShortestPath(c, g.graph());
    }

    private Side runCypher(String cypher) {
        // 先跑一次暖身（讓查詢計畫進快取），第二次才計時，兩邊公平
        shell.run(cypher, 50);
        CypherShell.Outcome r = shell.run(cypher, 50).last();
        return new Side("Cypher", cypher, r.columns(), r.rows(), r.millis(), r.kind().equals("error") ? r.message() : null);
    }

    private Side runSql(String sql) {
        try {
            sqlWithTimeout.queryForList(sql);
            long start = System.nanoTime();
            List<Map<String, Object>> rows = sqlWithTimeout.queryForList(sql);
            double ms = (System.nanoTime() - start) / 1e6;
            List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
            List<List<Object>> values = rows.stream().map(m -> (List<Object>) new ArrayList<Object>(m.values().stream()
                    .map(v -> v instanceof java.sql.Array a ? arrayToList(a) : v).toList())).toList();
            return new Side("SQL（PostgreSQL）", sql, columns, values, ms, null);
        } catch (RuntimeException e) {
            return new Side("SQL（PostgreSQL）", sql, List.of(), List.of(), 0, e.getMessage());
        }
    }

    private static Object arrayToList(java.sql.Array a) {
        try {
            return java.util.Arrays.asList((Object[]) a.getArray());
        } catch (java.sql.SQLException e) {
            return a.toString();
        }
    }

    /** graphlab.follows 是空的（例如 PostgreSQL 重建過）時，從 Neo4j 把追蹤關係抄一份過去。 */
    private synchronized void ensureFollowsTable() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS graphlab");
        jdbc.execute("CREATE TABLE IF NOT EXISTS graphlab.follows (src int NOT NULL, dst int NOT NULL, PRIMARY KEY (src, dst))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS follows_dst ON graphlab.follows (dst)");
        Long n = jdbc.queryForObject("SELECT count(*) FROM graphlab.follows", Long.class);
        if (n != null && n > 0) {
            return;
        }
        List<int[]> edges;
        try (Session s = session()) {
            edges = s.run("MATCH (a:Customer)-[:FOLLOWS]->(b:Customer) RETURN a.id AS src, b.id AS dst")
                    .list(r -> new int[] {r.get("src").asInt(), r.get("dst").asInt()});
        }
        jdbc.batchUpdate("INSERT INTO graphlab.follows (src, dst) VALUES (?, ?) ON CONFLICT DO NOTHING", edges, 5000,
                (ps, e) -> {
                    ps.setInt(1, e[0]);
                    ps.setInt(2, e[1]);
                });
        jdbc.execute("ANALYZE graphlab.follows");
    }
}
