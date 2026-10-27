package com.example.dbshowcase.neo4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 把 PostgreSQL 的 shop 資料轉成圖：
 *   (:Customer)-[:PLACED]->(:Order)-[:CONTAINS {qty, unitPrice, discount}]->(:Product)-[:IN_CATEGORY]->(:Category)
 *   (:Category)-[:SUBCATEGORY_OF]->(:Category)、(:Product)-[:MADE_BY]->(:Brand)
 *   (:Customer)-[:LIVES_IN]->(:City)、(:Order)-[:SHIPPED_TO]->(:City)
 *   (:Customer)-[:FOLLOWS]->(:Customer)：PostgreSQL 沒有社群資料，用固定的亂數種子產生（大多追蹤同城市的人，少數熱門會員被很多人追蹤）
 * 載入在背景執行，頁面可以看到進度。
 */
@Service
public class Neo4jDataLoader {

    private static final Logger log = LoggerFactory.getLogger(Neo4jDataLoader.class);
    private static final int BATCH = 5000;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /** 唯一性約束（同時建立索引）：載入時用 id 找節點，沒有索引會非常慢。 */
    public static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (c:Customer) REQUIRE c.id IS UNIQUE",
            "CREATE CONSTRAINT order_id IF NOT EXISTS FOR (o:Order) REQUIRE o.id IS UNIQUE",
            "CREATE CONSTRAINT product_id IF NOT EXISTS FOR (p:Product) REQUIRE p.id IS UNIQUE",
            "CREATE CONSTRAINT category_id IF NOT EXISTS FOR (c:Category) REQUIRE c.id IS UNIQUE",
            "CREATE CONSTRAINT city_name IF NOT EXISTS FOR (c:City) REQUIRE c.name IS UNIQUE",
            "CREATE CONSTRAINT brand_name IF NOT EXISTS FOR (b:Brand) REQUIRE b.name IS UNIQUE");

    public record LoadResult(Map<String, Long> nodes, Map<String, Long> relationships, double seconds) {
    }

    public record Status(boolean running, String step, long done, long expected, String error, LoadResult last) {
    }

    private final JdbcTemplate jdbc;
    private final Driver driver;
    private final AtomicLong done = new AtomicLong();
    private volatile long expected;
    private volatile String step;
    private volatile boolean running;
    private volatile String error;
    private volatile LoadResult last;

    public Neo4jDataLoader(JdbcTemplate jdbc, Driver driver) {
        this.jdbc = jdbc;
        this.driver = driver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadIfEmpty() {
        Thread.ofVirtual().name("neo4j-loader").start(() -> {
            try (Session s = session()) {
                if (s.run("MATCH (o:Order) RETURN count(o) AS n").single().get("n").asLong() == 0) {
                    load();
                }
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("Neo4j 練習資料載入失敗（neo4j-lab 容器有啟動嗎？）：{}", e.getMessage());
            }
        });
    }

    public Status status() {
        return new Status(running, step, done.get(), expected, error, last);
    }

    public Status startReload() {
        synchronized (this) {
            if (!running) {
                running = true;
                Thread.ofVirtual().name("neo4j-loader").start(() -> {
                    try {
                        load();
                    } catch (Exception e) {
                        error = e.getMessage();
                        running = false;
                        log.warn("Neo4j 練習資料載入失敗：{}", e.getMessage());
                    }
                });
            }
        }
        return status();
    }

    private Session session() {
        return driver.session(SessionConfig.forDatabase(Neo4jConfig.DATABASE));
    }

    public synchronized LoadResult load() {
        running = true;
        error = null;
        done.set(0);
        long start = System.nanoTime();
        try (Session s = session()) {
            step = "清空資料";
            s.run("MATCH (n) CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS").consume();
            for (String c : CONSTRAINTS) {
                s.run(c).consume();
            }
            Long orders = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
            Long itemCount = jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class);
            expected = 20_000 + 1_500 + orders + itemCount;

            // ---------- 分類樹 ----------
            step = "分類";
            List<Map<String, Object>> cats = new ArrayList<>();
            jdbc.query("SELECT id, name, parent_id FROM categories ORDER BY id", rs -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                int p = rs.getInt("parent_id");
                m.put("parent", rs.wasNull() ? null : p);
                cats.add(m);
            });
            write(s, "UNWIND $rows AS r CREATE (:Category {id: r.id, name: r.name})", cats);
            write(s, """
                    UNWIND $rows AS r WITH r WHERE r.parent IS NOT NULL
                    MATCH (c:Category {id: r.id}), (p:Category {id: r.parent})
                    CREATE (c)-[:SUBCATEGORY_OF]->(p)""", cats);

            // ---------- 商品與品牌（品牌是商品名稱的第一個字） ----------
            step = "商品";
            List<Map<String, Object>> products = new ArrayList<>();
            jdbc.query("SELECT id, name, category_id, price, cost, stock, is_active, created_at, tags FROM products ORDER BY id", rs -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                m.put("brand", rs.getString("name").split(" ")[0]);
                m.put("category", rs.getInt("category_id"));
                m.put("price", rs.getBigDecimal("price").intValue());
                m.put("cost", rs.getBigDecimal("cost").intValue());
                m.put("stock", rs.getInt("stock"));
                m.put("isActive", rs.getBoolean("is_active"));
                m.put("createdAt", rs.getDate("created_at").toLocalDate());
                m.put("tags", List.of((String[]) rs.getArray("tags").getArray()));
                products.add(m);
            });
            write(s, """
                    UNWIND $rows AS r
                    MERGE (b:Brand {name: r.brand})
                    WITH r, b
                    MATCH (c:Category {id: r.category})
                    CREATE (p:Product {id: r.id, name: r.name, price: r.price, cost: r.cost, stock: r.stock,
                                       isActive: r.isActive, createdAt: r.createdAt, tags: r.tags})
                    CREATE (p)-[:IN_CATEGORY]->(c), (p)-[:MADE_BY]->(b)""", products);
            done.addAndGet(products.size());

            // ---------- 會員與城市 ----------
            step = "會員";
            List<Map<String, Object>> customers = new ArrayList<>();
            jdbc.query("SELECT * FROM customers ORDER BY id", rs -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                m.put("email", rs.getString("email"));
                m.put("gender", rs.getString("gender"));
                m.put("birthDate", rs.getDate("birth_date") == null ? null : rs.getDate("birth_date").toLocalDate());
                m.put("city", rs.getString("city"));
                m.put("signupDate", rs.getDate("signup_date").toLocalDate());
                m.put("vipLevel", rs.getString("vip_level"));
                customers.add(m);
            });
            // 屬性是 null 時 Neo4j 不會存這個屬性（跟「沒有這個屬性」一樣）
            write(s, """
                    UNWIND $rows AS r
                    CREATE (c:Customer {id: r.id, name: r.name, email: r.email, gender: r.gender,
                                        birthDate: r.birthDate, signupDate: r.signupDate, vipLevel: r.vipLevel})
                    WITH c, r WHERE r.city IS NOT NULL
                    MERGE (city:City {name: r.city})
                    CREATE (c)-[:LIVES_IN]->(city)""", customers);
            done.addAndGet(customers.size());

            // ---------- 追蹤關係（固定亂數種子，每次產生一樣的結果） ----------
            step = "追蹤關係";
            List<Map<String, Object>> follows = follows(customers);
            write(s, """
                    UNWIND $rows AS r
                    MATCH (a:Customer {id: r.from}), (b:Customer {id: r.to})
                    CREATE (a)-[:FOLLOWS {since: r.since}]->(b)""", follows);
            syncFollowsToPostgres(follows);

            // ---------- 訂單、明細 ----------
            step = "訂單";
            List<Map<String, Object>> batch = new ArrayList<>();
            Map<String, Object>[] current = new Map[1];
            BigDecimal[] sum = {BigDecimal.ZERO};
            Runnable finish = () -> {
                if (current[0] != null) {
                    current[0].put("total", sum[0].setScale(0, RoundingMode.HALF_UP).intValue());
                    batch.add(current[0]);
                    if (batch.size() >= 2000) {
                        writeOrders(s, batch);
                        batch.clear();
                    }
                }
            };
            jdbc.query("""
                    SELECT o.id, o.customer_id, o.order_date, o.status, o.shipping_city, o.shipping_fee,
                           oi.product_id, oi.quantity, oi.unit_price, oi.discount
                    FROM orders o JOIN order_items oi ON oi.order_id = o.id
                    ORDER BY o.id, oi.product_id
                    """, rs -> {
                int id = rs.getInt("id");
                if (current[0] == null || (int) current[0].get("id") != id) {
                    finish.run();
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", id);
                    m.put("customer", rs.getInt("customer_id"));
                    m.put("orderDate", OffsetDateTime.ofInstant(rs.getTimestamp("order_date").toInstant(), TAIPEI));
                    m.put("status", rs.getString("status"));
                    m.put("city", rs.getString("shipping_city"));
                    m.put("shippingFee", rs.getInt("shipping_fee"));
                    m.put("items", new ArrayList<Map<String, Object>>());
                    current[0] = m;
                    sum[0] = BigDecimal.ZERO;
                }
                BigDecimal price = rs.getBigDecimal("unit_price");
                BigDecimal discount = rs.getBigDecimal("discount");
                int qty = rs.getInt("quantity");
                sum[0] = sum[0].add(price.multiply(BigDecimal.valueOf(qty)).multiply(BigDecimal.ONE.subtract(discount)));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) current[0].get("items");
                items.add(Map.of("product", rs.getInt("product_id"), "qty", qty, "unitPrice", price.intValue(),
                        "discount", discount.doubleValue()));
            });
            finish.run();
            if (!batch.isEmpty()) {
                writeOrders(s, batch);
            }

            step = "統計";
            Map<String, Long> nodes = new LinkedHashMap<>();
            s.run("MATCH (n) RETURN labels(n)[0] AS label, count(*) AS n ORDER BY n DESC").list()
                    .forEach(r -> nodes.put(r.get("label").asString(), r.get("n").asLong()));
            Map<String, Long> rels = new LinkedHashMap<>();
            s.run("MATCH ()-[r]->() RETURN type(r) AS type, count(*) AS n ORDER BY n DESC").list()
                    .forEach(r -> rels.put(r.get("type").asString(), r.get("n").asLong()));
            double seconds = (System.nanoTime() - start) / 1e9;
            last = new LoadResult(nodes, rels, seconds);
            log.info("Neo4j 練習資料載入完成：{} {}，{} 秒", nodes, rels, String.format("%.1f", seconds));
            return last;
        } finally {
            running = false;
            step = null;
        }
    }

    /**
     * 同一份追蹤關係也寫進 PostgreSQL 的 graphlab.follows（不放在 public，不影響 PostgreSQL 的頁面），
     * 實驗室用來比較「遞迴 CTE」與「圖的走訪」。
     */
    private void syncFollowsToPostgres(List<Map<String, Object>> follows) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS graphlab");
        jdbc.execute("CREATE TABLE IF NOT EXISTS graphlab.follows (src int NOT NULL, dst int NOT NULL, PRIMARY KEY (src, dst))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS follows_dst ON graphlab.follows (dst)");
        jdbc.execute("TRUNCATE graphlab.follows");
        jdbc.batchUpdate("INSERT INTO graphlab.follows (src, dst) VALUES (?, ?)", follows, 5000,
                (ps, f) -> {
                    ps.setInt(1, (Integer) f.get("from"));
                    ps.setInt(2, (Integer) f.get("to"));
                });
        jdbc.execute("ANALYZE graphlab.follows");
    }

    private void writeOrders(Session s, List<Map<String, Object>> orders) {
        s.executeWriteWithoutResult(tx -> tx.run("""
                UNWIND $rows AS r
                MATCH (c:Customer {id: r.customer})
                CREATE (o:Order {id: r.id, orderDate: r.orderDate, status: r.status, shippingFee: r.shippingFee, total: r.total})
                CREATE (c)-[:PLACED]->(o)
                WITH o, r
                CALL (o, r) {
                  WITH o, r WHERE r.city IS NOT NULL
                  MERGE (city:City {name: r.city})
                  CREATE (o)-[:SHIPPED_TO]->(city)
                }
                WITH o, r
                UNWIND r.items AS i
                MATCH (p:Product {id: i.product})
                CREATE (o)-[:CONTAINS {qty: i.qty, unitPrice: i.unitPrice, discount: i.discount}]->(p)
                """, Map.of("rows", orders)).consume());
        long itemCount = orders.stream().mapToLong(o -> ((List<?>) o.get("items")).size()).sum();
        done.addAndGet(orders.size() + itemCount);
    }

    private void write(Session s, String cypher, List<Map<String, Object>> rows) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            List<Map<String, Object>> part = rows.subList(i, Math.min(rows.size(), i + BATCH));
            s.executeWriteWithoutResult(tx -> tx.run(cypher, Map.of("rows", part)).consume());
        }
    }

    /**
     * 每位會員追蹤 0～8 人：約七成是同城市的人，三成是「熱門會員」（編號越小越熱門）。
     * 固定種子 42，所以每次載入都一樣，題目的答案才不會變。
     */
    private static List<Map<String, Object>> follows(List<Map<String, Object>> customers) {
        Random r = new Random(42);
        Map<String, List<Integer>> byCity = new LinkedHashMap<>();
        for (Map<String, Object> c : customers) {
            byCity.computeIfAbsent(String.valueOf(c.get("city")), k -> new ArrayList<>()).add((Integer) c.get("id"));
        }
        int total = customers.size();
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate base = LocalDate.of(2022, 1, 1);
        for (Map<String, Object> c : customers) {
            int id = (Integer) c.get("id");
            int k = r.nextInt(9);
            List<Integer> sameCity = byCity.get(String.valueOf(c.get("city")));
            Set<Integer> targets = new LinkedHashSet<>();
            for (int i = 0; i < k * 3 && targets.size() < k; i++) {
                int to = r.nextDouble() < 0.7
                        ? sameCity.get(r.nextInt(sameCity.size()))
                        : 1 + (int) Math.floor(Math.pow(r.nextDouble(), 3) * total);   // 偏向小編號：熱門會員
                if (to != id) {
                    targets.add(to);
                }
            }
            for (int to : targets) {
                out.add(Map.of("from", id, "to", to, "since", base.plusDays(r.nextInt(1700))));
            }
        }
        return out;
    }
}
