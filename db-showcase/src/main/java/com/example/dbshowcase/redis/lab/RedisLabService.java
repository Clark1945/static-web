package com.example.dbshowcase.redis.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.Tuple;

/**
 * 實戰場景實驗室：每個場景都是「真的對 Redis 下指令」，回傳過程與耗時給前端畫出來。
 * 實驗用的 key 都以 lab: 或 cache: 開頭，不影響練習資料（排行榜模擬除外，按「重置資料」即可還原）。
 */
@Service
public class RedisLabService {

    /** 安全釋放鎖：確認 value 還是自己的 token 才刪除（GET 與 DEL 必須在同一個原子操作裡）。 */
    static final String RELEASE_LOCK = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0""";

    /** 固定視窗限流：第一次 INCR 時才設定過期時間，INCR 與 EXPIRE 一起原子執行。 */
    static final String RATE_LIMIT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('TTL', KEYS[1])}""";

    /** 庫存足夠才扣：讀取、判斷、扣減在同一個原子操作裡。 */
    static final String BUY = """
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock > 0 then
                redis.call('DECR', KEYS[1])
                return 1
            end
            return 0""";

    private final JedisPool pool;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RedisLabService(@Qualifier("adminPool") JedisPool pool, JdbcTemplate jdbc, ObjectMapper json) {
        this.pool = pool;
        this.jdbc = jdbc;
        this.json = json;
    }

    // ================================================================ Cache-Aside

    public record CacheResult(String source, double totalMs, Double dbMs, long ttl, String key, Object data) {
    }

    /**
     * 先查 Redis；沒有才查 PostgreSQL，再寫回 Redis（60 秒過期）。
     * cacheNull = true 時，查不到的結果也會快取 15 秒（防止快取穿透）。
     */
    public CacheResult categoryReport(int categoryId, boolean cacheNull) {
        String key = "cache:category-report:" + categoryId;
        long start = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            String cached = jedis.get(key);
            if (cached != null) {
                Object data = cached.equals("null") ? null : read(cached);
                return new CacheResult("cache", ms(start), null, jedis.ttl(key), key, data);
            }
            long dbStart = System.nanoTime();
            Map<String, Object> data = queryCategoryReport(categoryId);
            double dbMs = ms(dbStart);
            if (data != null) {
                jedis.set(key, write(data), SetParams.setParams().ex(60));
            } else if (cacheNull) {
                jedis.set(key, "null", SetParams.setParams().ex(15));
            }
            return new CacheResult("db", ms(start), dbMs, jedis.ttl(key), key, data);
        }
    }

    public long invalidate(int categoryId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.del("cache:category-report:" + categoryId);
        }
    }

    /** 刻意找一個要掃大量資料的彙總查詢（沒有索引時大約幾十毫秒），用來對比快取的效果。 */
    private Map<String, Object> queryCategoryReport(int categoryId) {
        List<Map<String, Object>> top = jdbc.queryForList("""
                SELECT p.id, p.name,
                       sum(oi.quantity) AS qty,
                       round(sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS revenue
                FROM order_items oi
                JOIN orders o   ON o.id = oi.order_id
                JOIN products p ON p.id = oi.product_id
                WHERE p.category_id = ? AND o.status = 'delivered'
                GROUP BY p.id, p.name
                ORDER BY revenue DESC
                """, categoryId);
        if (top.isEmpty()) {
            return null;
        }
        String name = jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, categoryId);
        long revenue = top.stream().mapToLong(r -> ((Number) r.get("revenue")).longValue()).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", name);
        data.put("products", top.size());
        data.put("revenue", revenue);
        data.put("top3", top.subList(0, Math.min(3, top.size())));
        return data;
    }

    // ================================================================ 限流

    public record RateResult(int request, boolean allowed, long count, long ttl, double ms) {
    }

    public List<RateResult> rateLimit(int requests, int limit, int windowSeconds) {
        List<RateResult> results = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            for (int i = 1; i <= requests; i++) {
                long start = System.nanoTime();
                @SuppressWarnings("unchecked")
                List<Long> r = (List<Long>) jedis.eval(RATE_LIMIT, List.of("lab:ratelimit:demo-user"),
                        List.of(String.valueOf(windowSeconds)));
                results.add(new RateResult(i, r.get(0) <= limit, r.get(0), r.get(1), ms(start)));
            }
        }
        return results;
    }

    public void resetRateLimit() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del("lab:ratelimit:demo-user");
        }
    }

    // ================================================================ 分散式鎖

    public record LockAttempt(String worker, boolean acquired, double atMs) {
    }

    /** n 個 worker 同時搶同一把鎖（SET NX PX），只會有一個成功。 */
    public List<LockAttempt> contend(int workers) {
        String key = "lab:lock:order:1";
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
        }
        CountDownLatch go = new CountDownLatch(1);
        long base = System.nanoTime();
        List<LockAttempt> out = parallel(workers, i -> {
            go.await();
            try (Jedis jedis = pool.getResource()) {
                String ok = jedis.set(key, "worker-" + i, SetParams.setParams().nx().px(2000));
                return new LockAttempt("worker-" + i, "OK".equals(ok), ms(base));
            }
        }, go);
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
        }
        out.sort((a, b) -> Double.compare(a.atMs(), b.atMs()));
        return out;
    }

    public record LockEvent(double atMs, String actor, String action, String result) {
    }

    /**
     * 重現「鎖過期後刪到別人的鎖」：
     * A 拿鎖（300 ms 過期）→ A 做事做太久，鎖過期 → B 拿到鎖 → A 做完釋放鎖 → C 來搶鎖。
     * safe = false 用 DEL 釋放；safe = true 用 Lua 先比對 token 再刪。
     */
    public List<LockEvent> releaseDemo(boolean safe) throws InterruptedException {
        String key = "lab:lock:report";
        List<LockEvent> log = new ArrayList<>();
        long base = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
            String a = jedis.set(key, "token-A", SetParams.setParams().nx().px(300));
            log.add(new LockEvent(ms(base), "A", "SET " + key + " token-A NX PX 300", String.valueOf(a)));
            log.add(new LockEvent(ms(base), "A", "開始處理工作（要花 500 ms，比鎖的 300 ms 還久）", ""));
            Thread.sleep(360);
            log.add(new LockEvent(ms(base), "系統", "鎖已經過期，被 Redis 自動刪除", "TTL = " + jedis.pttl(key)));
            String b = jedis.set(key, "token-B", SetParams.setParams().nx().px(3000));
            log.add(new LockEvent(ms(base), "B", "SET " + key + " token-B NX PX 3000", String.valueOf(b)));
            Thread.sleep(150);
            if (safe) {
                Object r = jedis.eval(RELEASE_LOCK, List.of(key), List.of("token-A"));
                log.add(new LockEvent(ms(base), "A", "做完了，用 Lua 釋放：value 是 token-A 才刪", "回傳 " + r
                        + (Long.valueOf(0).equals(r) ? "（鎖已經是 B 的，沒有刪）" : "")));
            } else {
                long r = jedis.del(key);
                log.add(new LockEvent(ms(base), "A", "做完了，直接 DEL " + key, "回傳 " + r + "（刪掉的其實是 B 的鎖！）"));
            }
            String c = jedis.set(key, "token-C", SetParams.setParams().nx().px(3000));
            log.add(new LockEvent(ms(base), "C", "SET " + key + " token-C NX PX 3000", String.valueOf(c)));
            String holder = jedis.get(key);
            log.add(new LockEvent(ms(base), "結果", "目前鎖在誰手上：GET " + key, String.valueOf(holder)));
            if (!safe && "OK".equals(c)) {
                log.add(new LockEvent(ms(base), "結果", "B 以為自己還拿著鎖，C 也拿到了鎖", "兩個 worker 同時在做同一件事"));
            } else {
                log.add(new LockEvent(ms(base), "結果", "B 的鎖沒被動到，C 拿不到鎖", "同一時間只有一個 worker"));
            }
            jedis.del(key);
        }
        return log;
    }

    // ================================================================ 庫存扣減（超賣）

    public record StockResult(String mode, int initial, int buyers, int sold, long finalStock, int oversold, double ms) {
    }

    /** buyers 個人同時搶購 initial 件商品。 */
    public StockResult oversell(String mode, int initial, int buyers) {
        String key = "lab:stock:flash-sale";
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, String.valueOf(initial));
        }
        AtomicInteger sold = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        long start = System.nanoTime();
        parallel(buyers, i -> {
            go.await();
            try (Jedis jedis = pool.getResource()) {
                boolean ok = switch (mode) {
                    case "naive" -> {                                  // 先讀、在程式裡判斷、再寫回
                        int stock = Integer.parseInt(jedis.get(key));
                        if (stock > 0) {
                            Thread.sleep(3);                           // 模擬中間的商業邏輯
                            jedis.set(key, String.valueOf(stock - 1));
                            yield true;
                        }
                        yield false;
                    }
                    case "decr" -> {                                   // 原子扣減，扣過頭再加回去
                        if (jedis.decr(key) >= 0) {
                            yield true;
                        }
                        jedis.incr(key);
                        yield false;
                    }
                    default -> Long.valueOf(1).equals(jedis.eval(BUY, List.of(key), List.of()));   // Lua
                };
                if (ok) {
                    sold.incrementAndGet();
                }
                return ok;
            }
        }, go);
        double elapsed = ms(start);
        try (Jedis jedis = pool.getResource()) {
            long left = Long.parseLong(jedis.get(key));
            jedis.del(key);
            return new StockResult(mode, initial, buyers, sold.get(), left, Math.max(0, sold.get() - initial), elapsed);
        }
    }

    // ================================================================ 排行榜

    public record Entry(long rank, String id, String name, long score) {
    }

    public record Board(List<Entry> top, Entry me, List<Entry> around, double ms) {
    }

    public Board leaderboard(String customerId) {
        String key = "leaderboard:customers:spend";
        long start = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            List<Tuple> top = jedis.zrevrangeWithScores(key, 0, 9);
            Long rank = customerId == null || customerId.isBlank() ? null : jedis.zrevrank(key, customerId);
            List<Tuple> around = rank == null ? List.of() : jedis.zrevrangeWithScores(key, Math.max(0, rank - 2), rank + 2);
            Map<String, String> names = names(jedis, top, around);
            List<Entry> topEntries = entries(top, 0, names);
            List<Entry> aroundEntries = rank == null ? List.of() : entries(around, Math.max(0, rank - 2), names);
            Entry me = rank == null ? null
                    : new Entry(rank + 1, customerId, names.get(customerId), Math.round(jedis.zscore(key, customerId)));
            return new Board(topEntries, me, aroundEntries, ms(start));
        }
    }

    /** 模擬 n 筆新訂單：隨機會員、隨機金額，用 ZINCRBY 更新排行榜。 */
    public double simulateOrders(int n) {
        Random random = new Random();
        long start = System.nanoTime();
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (int i = 0; i < n; i++) {
                p.zincrby("leaderboard:customers:spend", 500 + random.nextInt(50_000), String.valueOf(1 + random.nextInt(19_000)));
            }
            p.sync();
        }
        return ms(start);
    }

    private static Map<String, String> names(Jedis jedis, List<Tuple> a, List<Tuple> b) {
        List<String> ids = new ArrayList<>();
        a.forEach(t -> ids.add(t.getElement()));
        b.forEach(t -> ids.add(t.getElement()));
        Pipeline p = jedis.pipelined();
        List<redis.clients.jedis.Response<String>> responses = new ArrayList<>();
        for (String id : ids) {
            responses.add(p.hget("customer:" + id, "name"));
        }
        p.sync();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            out.put(ids.get(i), responses.get(i).get());
        }
        return out;
    }

    private static List<Entry> entries(List<Tuple> tuples, long firstRank, Map<String, String> names) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < tuples.size(); i++) {
            Tuple t = tuples.get(i);
            out.add(new Entry(firstRank + i + 1, t.getElement(), names.get(t.getElement()), Math.round(t.getScore())));
        }
        return out;
    }

    // ================================================================ Pipeline

    public record PipelineResult(int n, double loopMs, double pipelineMs, double msetMs) {
    }

    public PipelineResult pipeline(int n) {
        try (Jedis jedis = pool.getResource()) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                jedis.set("lab:pipe:" + i, "v" + i);                 // 每個指令都等一次來回
            }
            double loop = ms(t0);

            long t1 = System.nanoTime();
            Pipeline p = jedis.pipelined();                          // 全部送出，最後一次收回結果
            for (int i = 0; i < n; i++) {
                p.set("lab:pipe:" + i, "v" + i);
            }
            p.sync();
            double piped = ms(t1);

            String[] kv = new String[n * 2];
            for (int i = 0; i < n; i++) {
                kv[i * 2] = "lab:pipe:" + i;
                kv[i * 2 + 1] = "v" + i;
            }
            long t2 = System.nanoTime();
            jedis.mset(kv);                                          // 一個指令寫入全部
            double mset = ms(t2);

            Pipeline cleanup = jedis.pipelined();
            for (int i = 0; i < n; i++) {
                cleanup.del("lab:pipe:" + i);
            }
            cleanup.sync();
            return new PipelineResult(n, loop, piped, mset);
        }
    }

    // ================================================================ 工具

    @FunctionalInterface
    private interface Task<T> {
        T run(int index) throws Exception;
    }

    /** 開 n 條執行緒，等 go 倒數後一起開始，收集結果。 */
    private static <T> List<T> parallel(int n, Task<T> task, CountDownLatch go) {
        ExecutorService executor = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                int index = i;
                futures.add(executor.submit(() -> task.run(index)));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("實驗執行失敗：" + e.getMessage(), e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static double ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private Object read(String s) {
        try {
            return json.readValue(s, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return s;
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
