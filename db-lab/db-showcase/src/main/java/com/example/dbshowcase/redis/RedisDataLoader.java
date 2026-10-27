package com.example.dbshowcase.redis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

/**
 * 把 PostgreSQL 的 shop 資料轉成 Redis 的各種資料結構，寫進 db 0。
 * 展示台啟動時、以及按下「重置資料」時執行；用 Pipeline 批次送出，幾萬個 key 幾秒內完成。
 */
@Service
public class RedisDataLoader {

    private static final Logger log = LoggerFactory.getLogger(RedisDataLoader.class);
    private static final LocalDate ACTIVE_FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate ACTIVE_TO = LocalDate.of(2026, 9, 30);

    /** 門市位置：經度、緯度。 */
    private static final Map<String, double[]> STORES = Map.ofEntries(
            Map.entry("台北市", new double[] {121.5654, 25.0330}),
            Map.entry("新北市", new double[] {121.4628, 25.0120}),
            Map.entry("基隆市", new double[] {121.7419, 25.1276}),
            Map.entry("桃園市", new double[] {121.3010, 24.9936}),
            Map.entry("新竹市", new double[] {120.9686, 24.8138}),
            Map.entry("新竹縣", new double[] {121.0177, 24.8387}),
            Map.entry("台中市", new double[] {120.6736, 24.1477}),
            Map.entry("彰化縣", new double[] {120.5161, 24.0518}),
            Map.entry("嘉義市", new double[] {120.4491, 23.4801}),
            Map.entry("台南市", new double[] {120.2270, 22.9999}),
            Map.entry("高雄市", new double[] {120.3014, 22.6273}),
            Map.entry("屏東縣", new double[] {120.4879, 22.6727}),
            Map.entry("宜蘭縣", new double[] {121.7531, 24.7570}),
            Map.entry("花蓮縣", new double[] {121.6014, 23.9872}),
            Map.entry("台東縣", new double[] {121.1438, 22.7583}));

    /** 資料結構總覽的一列。 */
    public record KeyGroup(String pattern, String type, long count, String example, String purpose, String tryIt) {
    }

    public record LoadResult(List<KeyGroup> groups, long keys, double seconds) {
    }

    private final JdbcTemplate jdbc;
    private final JedisPool admin;
    private volatile LoadResult last;

    public RedisDataLoader(JdbcTemplate jdbc, @Qualifier("adminPool") JedisPool admin) {
        this.jdbc = jdbc;
        this.admin = admin;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        try {
            load();
        } catch (Exception e) {
            log.warn("Redis 練習資料載入失敗（redis-lab 容器有啟動嗎？）：{}", e.getMessage());
        }
    }

    public LoadResult lastLoad() {
        return last;
    }

    public synchronized LoadResult load() {
        long start = System.nanoTime();
        List<KeyGroup> groups = new ArrayList<>();
        try (Jedis jedis = admin.getResource()) {
            jedis.select(RedisConfig.DATA_DB);
            jedis.flushDB();
            Batch batch = new Batch(jedis);

            // ---------- Hash：商品、會員 ----------
            long[] products = {0};
            jdbc.query("""
                    SELECT p.id, p.name, p.price, p.stock, c.name AS category, p.category_id, p.tags
                    FROM products p JOIN categories c ON c.id = p.category_id ORDER BY p.id
                    """, rs -> {
                String id = rs.getString("id");
                batch.cmd("HSET", "product:" + id, "name", rs.getString("name"), "price", rs.getBigDecimal("price").toPlainString(),
                        "stock", rs.getString("stock"), "category", rs.getString("category"));
                batch.cmd("SADD", "category:" + rs.getString("category_id") + ":products", id);
                for (String tag : (String[]) rs.getArray("tags").getArray()) {
                    batch.cmd("SADD", "tag:" + tag, id);
                }
                products[0]++;
            });
            long categorySets = jdbc.queryForObject("SELECT count(DISTINCT category_id) FROM products", Long.class);
            long tagSets = jdbc.queryForObject("SELECT count(DISTINCT t) FROM products, unnest(tags) t", Long.class);
            groups.add(new KeyGroup("product:{id}", "hash", products[0], "product:540",
                    "商品資料（名稱、價格、庫存、分類），物件快取最常見的形式", "HGETALL product:540"));

            long[] customers = {0};
            jdbc.query("SELECT id, name, city, vip_level FROM customers ORDER BY id", rs -> {
                List<String> args = new ArrayList<>(List.of("customer:" + rs.getString("id"),
                        "name", rs.getString("name"), "vip", rs.getString("vip_level")));
                if (rs.getString("city") != null) {
                    args.addAll(List.of("city", rs.getString("city")));
                }
                batch.cmd("HSET", args.toArray(String[]::new));
                customers[0]++;
            });
            groups.add(new KeyGroup("customer:{id}", "hash", customers[0], "customer:1",
                    "會員資料；沒填城市的會員就沒有 city 欄位", "HGETALL customer:1"));

            // ---------- Set：分類、標籤 ----------
            groups.add(new KeyGroup("category:{id}:products", "set", categorySets, "category:2:products",
                    "每個分類有哪些商品 id", "SCARD category:2:products"));
            groups.add(new KeyGroup("tag:{標籤}", "set", tagSets, "tag:特價",
                    "有某個標籤的商品 id，可以做交集、聯集、差集", "SINTER tag:熱銷 tag:限量"));

            // ---------- Sorted Set：排行榜 ----------
            jdbc.query("""
                    SELECT oi.product_id, sum(oi.quantity) AS qty
                    FROM order_items oi JOIN orders o ON o.id = oi.order_id
                    WHERE o.status = 'delivered' GROUP BY oi.product_id
                    """, rs -> {
                batch.cmd("ZADD", "leaderboard:products:sales", rs.getString("qty"), rs.getString("product_id"));
            });
            groups.add(new KeyGroup("leaderboard:products:sales", "zset", 1, "leaderboard:products:sales",
                    "商品銷售件數排行（分數 = 已送達的件數）", "ZRANGE leaderboard:products:sales 0 4 REV WITHSCORES"));
            jdbc.query("""
                    SELECT o.customer_id, round(sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS total
                    FROM orders o JOIN order_items oi ON oi.order_id = o.id
                    WHERE o.status = 'delivered' GROUP BY o.customer_id
                    """, rs -> {
                batch.cmd("ZADD", "leaderboard:customers:spend", rs.getString("total"), rs.getString("customer_id"));
            });
            groups.add(new KeyGroup("leaderboard:customers:spend", "zset", 1, "leaderboard:customers:spend",
                    "會員累計消費排行（分數 = 已送達訂單的金額）", "ZREVRANK leaderboard:customers:spend 1"));

            // ---------- List：每位會員最近 10 筆訂單（索引 0 是最新的） ----------
            long[] lists = {0};
            String[] current = {null};
            List<String> buffer = new ArrayList<>();
            Consumer<String> flush = cid -> {
                if (cid != null && !buffer.isEmpty()) {
                    List<String> args = new ArrayList<>();
                    args.add("customer:" + cid + ":recent_orders");
                    args.addAll(buffer);
                    batch.cmd("RPUSH", args.toArray(String[]::new));
                    lists[0]++;
                }
                buffer.clear();
            };
            jdbc.query("""
                    SELECT customer_id, id FROM (
                        SELECT customer_id, id,
                               row_number() OVER (PARTITION BY customer_id ORDER BY order_date DESC) AS rn
                        FROM orders) t
                    WHERE rn <= 10 ORDER BY customer_id, rn
                    """, rs -> {
                String cid = rs.getString("customer_id");
                if (!cid.equals(current[0])) {
                    flush.accept(current[0]);
                    current[0] = cid;
                }
                buffer.add(rs.getString("id"));
            });
            flush.accept(current[0]);
            groups.add(new KeyGroup("customer:{id}:recent_orders", "list", lists[0], "customer:1:recent_orders",
                    "會員最近 10 筆訂單 id，索引 0 是最新的一筆", "LRANGE customer:1:recent_orders 0 2"));

            // ---------- Stream：最新 1000 筆訂單事件（ID 用下單時間的毫秒數，可以依時間範圍查） ----------
            long[] lastMs = {-1}, seq = {0};
            jdbc.query("""
                    SELECT * FROM (
                        SELECT o.id, o.customer_id, o.status, o.shipping_city,
                               (extract(epoch FROM o.order_date) * 1000)::bigint AS ms,
                               round(sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS amount
                        FROM orders o JOIN order_items oi ON oi.order_id = o.id
                        GROUP BY o.id ORDER BY o.order_date DESC, o.id DESC LIMIT 1000) t
                    ORDER BY ms, id
                    """, rs -> {
                long ms = rs.getLong("ms");
                seq[0] = ms == lastMs[0] ? seq[0] + 1 : 0;
                lastMs[0] = ms;
                batch.cmd("XADD", "orders:stream", ms + "-" + seq[0], "order_id", rs.getString("id"),
                        "customer_id", rs.getString("customer_id"), "status", rs.getString("status"),
                        "city", rs.getString("shipping_city"), "amount", rs.getString("amount"));
            });
            groups.add(new KeyGroup("orders:stream", "stream", 1, "orders:stream",
                    "最新 1000 筆訂單事件；ID 是下單時間（毫秒）", "XREVRANGE orders:stream + - COUNT 3"));

            // ---------- Bitmap + HyperLogLog：2026 年 9 月每天下單的會員 ----------
            long[] days = {0};
            jdbc.query("""
                    SELECT o.order_date::date AS day, array_agg(DISTINCT o.customer_id) AS ids
                    FROM orders o
                    WHERE o.order_date >= ? AND o.order_date < ?
                    GROUP BY 1 ORDER BY 1
                    """, rs -> {
                String day = rs.getString("day");
                Integer[] ids = (Integer[]) rs.getArray("ids").getArray();
                List<String> pf = new ArrayList<>(List.of("uv:" + day));
                for (Integer id : ids) {
                    batch.cmd("SETBIT", "active:" + day, id.toString(), "1");
                    pf.add(id.toString());
                }
                batch.cmd("PFADD", pf.toArray(String[]::new));
                days[0]++;
            }, java.sql.Date.valueOf(ACTIVE_FROM), java.sql.Date.valueOf(ACTIVE_TO.plusDays(1)));
            groups.add(new KeyGroup("active:{日期}", "bitmap", days[0], "active:2026-09-01",
                    "那天有下單的會員：第 N 個 bit 是 1 代表會員 N 有下單（精確，每天約 2.5 KB）", "BITCOUNT active:2026-09-01"));
            groups.add(new KeyGroup("uv:{日期}", "hyperloglog", days[0], "uv:2026-09-01",
                    "同樣的資料用 HyperLogLog 估算不重複人數（誤差約 0.81%，固定最多 12 KB）", "PFCOUNT uv:2026-09-01"));

            // ---------- Geo：門市位置 ----------
            List<String> geo = new ArrayList<>(List.of("store:locations"));
            STORES.forEach((city, lonLat) -> {
                geo.add(String.valueOf(lonLat[0]));
                geo.add(String.valueOf(lonLat[1]));
                geo.add(city);
            });
            batch.cmd("GEOADD", geo.toArray(String[]::new));
            groups.add(new KeyGroup("store:locations", "geo", 1, "store:locations",
                    "15 個縣市的門市座標（底層其實是 Sorted Set）", "GEODIST store:locations 台北市 高雄市 km"));

            // ---------- String：計數器、Session ----------
            batch.cmd("SET", "stats:orders:total", jdbc.queryForObject("SELECT count(*) FROM orders", Long.class).toString());
            batch.cmd("SET", "stats:customers:total", String.valueOf(customers[0]));
            groups.add(new KeyGroup("stats:*", "string", 2, "stats:orders:total",
                    "計數器：訂單總數、會員總數，可以用 INCR 原子加一", "GET stats:orders:total"));
            String[] tokens = {"a1b2c3", "d4e5f6", "g7h8i9", "j1k2l3", "m4n5o6"};
            for (int i = 0; i < tokens.length; i++) {
                batch.cmd("SET", "session:" + tokens[i], "{\"customer_id\":" + (i + 1) + ",\"login\":\"2026-10-06T09:00\"}",
                        "EX", "1800");
            }
            batch.cmd("SET", "config:site:maintenance", "off");
            groups.add(new KeyGroup("session:{token}", "string", tokens.length, "session:a1b2c3",
                    "登入 Session（JSON 字串），30 分鐘後自動過期", "TTL session:a1b2c3"));
            groups.add(new KeyGroup("config:site:maintenance", "string", 1, "config:site:maintenance",
                    "沒有設定過期時間的設定值", "TTL config:site:maintenance"));

            batch.finish();
            long keys = jedis.dbSize();
            double seconds = (System.nanoTime() - start) / 1e9;
            last = new LoadResult(groups, keys, seconds);
            log.info("Redis 練習資料載入完成：{} 個 key，{} 秒", keys, String.format("%.2f", seconds));
            return last;
        }
    }

    /** Pipeline 包一層：每 5000 個指令送一次。 */
    private static final class Batch {
        private final Jedis jedis;
        private Pipeline pipeline;
        private int pending;

        Batch(Jedis jedis) {
            this.jedis = jedis;
            this.pipeline = jedis.pipelined();
        }

        void cmd(String name, String... args) {
            pipeline.sendCommand(RedisCommandRunner.command(name), args);
            if (++pending >= 5000) {
                sync();
            }
        }

        void sync() {
            finish();
            pipeline = jedis.pipelined();
        }

        /** 送出剩下的指令並關閉 Pipeline，之後這條連線才能再下一般指令。 */
        void finish() {
            pipeline.sync();
            pipeline.close();
            pending = 0;
        }
    }
}
