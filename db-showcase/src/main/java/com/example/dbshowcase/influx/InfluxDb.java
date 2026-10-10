package com.example.dbshowcase.influx;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * InfluxDB 的初始化與練習資料（influx-lab 容器；組織 shop、bucket metrics 由容器第一次啟動時建立）。
 *
 * 每次啟動：等 InfluxDB 準備好 → 建立 scratch bucket → 建立 InfluxQL 用的 DBRP 對應（bucket ↔ database / retention policy）
 * → 重新建立 reader（只能讀 metrics）與 learner（讀 metrics、讀寫 scratch）兩個 token → metrics 是空的就載入資料。
 *
 * 資料都在 2026 年 9 月（台灣時間），用固定亂數種子產生：
 * cpu、mem（5 台主機，每分鐘）、http（API 請求計數器，每分鐘）、sensors（倉庫溫溼度，每 5 分鐘）、orders（從 PostgreSQL 複製，全部 5 年）。
 */
@Component
public class InfluxDb {

    private static final Logger log = LoggerFactory.getLogger(InfluxDb.class);
    public static final String BUCKET = "metrics";
    public static final String SCRATCH = "scratch";
    public static final List<String> MEASUREMENTS = List.of("cpu", "http", "mem", "orders", "sensors");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final long START = LocalDate.of(2026, 9, 1).atStartOfDay(TAIPEI).toEpochSecond();
    private static final int MINUTES = 30 * 24 * 60;

    public record Status(boolean running, String step, String error, Double seconds, long points) {
    }

    private final InfluxClient client;
    private final JdbcTemplate postgres;
    private volatile boolean running;
    private volatile String step;
    private volatile String error;
    private volatile Double seconds;
    private volatile long points;
    private volatile String orgId;

    public InfluxDb(InfluxClient client, JdbcTemplate postgres) {
        this.client = client;
        this.postgres = postgres;
    }

    public Status status() {
        return new Status(running, step, error, seconds, points);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        Thread.ofVirtual().name("influx-loader").start(() -> {
            running = true;
            try {
                step = "等待 InfluxDB 啟動";
                waitForHealth();
                step = "建立 bucket、DBRP 與 token";
                setup();
                if (!loaded()) {
                    load();
                }
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("InfluxDB 練習資料準備失敗（influx-lab 容器有啟動嗎？）：{}", e.getMessage());
            } finally {
                running = false;
                step = null;
            }
        });
    }

    /** 刪掉 metrics bucket 重建後重新載入（bucket 的 id 會變，所以 DBRP 與 token 也要重建）。 */
    public synchronized Status reload() {
        if (!running) {
            running = true;
            Thread.ofVirtual().name("influx-loader").start(() -> {
                try {
                    step = "重建 bucket";
                    JsonNode b = bucket(BUCKET);
                    if (b != null) {
                        client.admin("DELETE", "/api/v2/buckets/" + b.path("id").asText(), null);
                    }
                    setup();
                    load();
                } catch (Exception e) {
                    error = e.getMessage();
                    log.warn("InfluxDB 練習資料載入失敗：{}", e.getMessage());
                } finally {
                    running = false;
                    step = null;
                }
            });
        }
        return status();
    }

    private void waitForHealth() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (true) {
            try {
                if (client.send(InfluxClient.User.ADMIN, "GET", "/health", null, null, "application/json").ok()) {
                    return;
                }
            } catch (IllegalStateException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("等了 2 分鐘 InfluxDB 還沒準備好");
            }
            Thread.sleep(2000);
        }
    }

    // ================================================================ bucket、DBRP、token

    /** 找不到時 InfluxDB 回傳 404（不是空陣列）。 */
    private JsonNode bucket(String name) {
        InfluxClient.Response r = client.send(InfluxClient.User.ADMIN, "GET", "/api/v2/buckets?name=" + name + "&org=" + client.org(),
                null, null, "application/json");
        if (r.status() == 404) {
            return null;
        }
        if (!r.ok()) {
            throw new IllegalStateException("查詢 bucket 失敗（" + r.status() + "）：" + r.body());
        }
        JsonNode list = client.parse(r.body()).path("buckets");
        return list.isEmpty() ? null : list.get(0);
    }

    private String ensureBucket(String name) {
        JsonNode b = bucket(name);
        if (b != null) {
            return b.path("id").asText();
        }
        return client.admin("POST", "/api/v2/buckets", Map.of("orgID", orgId, "name", name,
                "retentionRules", List.of())).path("id").asText();
    }

    private void ensureDbrp(String bucketId, String database) {
        JsonNode list = client.admin("GET", "/api/v2/dbrps?orgID=" + orgId + "&db=" + database, null).path("content");
        for (JsonNode d : list) {
            if (d.path("bucketID").asText().equals(bucketId)) {
                return;
            }
            client.admin("DELETE", "/api/v2/dbrps/" + d.path("id").asText() + "?orgID=" + orgId, null);   // 舊的 bucket 已被刪除
        }
        client.admin("POST", "/api/v2/dbrps", Map.of("bucketID", bucketId, "database", database,
                "retention_policy", "autogen", "default", true, "orgID", orgId));
    }

    private synchronized void setup() {
        orgId = client.admin("GET", "/api/v2/orgs?org=" + client.org(), null).path("orgs").get(0).path("id").asText();
        String metrics = ensureBucket(BUCKET);
        String scratch = ensureBucket(SCRATCH);
        ensureDbrp(metrics, BUCKET);
        ensureDbrp(scratch, SCRATCH);
        // token 的值只有建立時拿得到：每次啟動都刪掉舊的、重新建立
        for (JsonNode a : client.admin("GET", "/api/v2/authorizations?orgID=" + orgId, null).path("authorizations")) {
            if (a.path("description").asText().startsWith("showcase-")) {
                client.admin("DELETE", "/api/v2/authorizations/" + a.path("id").asText(), null);
            }
        }
        String reader = token("showcase-reader（只能讀 metrics）", List.of(perm("read", metrics)));
        String learner = token("showcase-learner（讀 metrics、讀寫 scratch）",
                List.of(perm("read", metrics), perm("read", scratch), perm("write", scratch)));
        client.setTokens(reader, learner);
    }

    private Map<String, Object> perm(String action, String bucketId) {
        return Map.of("action", action, "resource", Map.of("type", "buckets", "id", bucketId, "orgID", orgId));
    }

    private String token(String description, List<Map<String, Object>> permissions) {
        return client.admin("POST", "/api/v2/authorizations", Map.of("orgID", orgId, "description", description,
                "permissions", permissions)).path("token").asText();
    }

    /** 清空 scratch bucket（批改寫入題前後）：刪掉所有時間範圍的資料。 */
    public void clearScratch() {
        InfluxClient.Response r = client.send(InfluxClient.User.ADMIN, "POST",
                "/api/v2/delete?org=" + client.org() + "&bucket=" + SCRATCH, "application/json",
                client.write(Map.of("start", "1970-01-01T00:00:00Z", "stop", "2100-01-01T00:00:00Z")), "application/json");
        if (!r.ok()) {
            throw new IllegalStateException("清空 scratch 失敗：" + r.body());
        }
    }

    private boolean loaded() {
        InfluxClient.Response r = client.influxql(InfluxClient.User.ADMIN, BUCKET, "SHOW MEASUREMENTS");
        JsonNode values = client.parse(r.body()).path("results").path(0).path("series").path(0).path("values");
        List<String> found = new ArrayList<>();
        values.forEach(v -> found.add(v.path(0).asText()));
        return found.containsAll(MEASUREMENTS);
    }

    // ================================================================ 產生資料

    private final StringBuilder batch = new StringBuilder();
    private int batchLines;

    private void line(String precision, String l) {
        batch.append(l).append('\n');
        points++;
        if (++batchLines >= 5000) {
            flush(precision);
        }
    }

    private void flush(String precision) {
        if (batchLines == 0) {
            return;
        }
        InfluxClient.Response r = client.write(InfluxClient.User.ADMIN, BUCKET, precision, batch.toString());
        if (!r.ok()) {
            throw new IllegalStateException("寫入失敗：" + r.body());
        }
        batch.setLength(0);
        batchLines = 0;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** 流量的時段比例（晚上 8～10 點最高），跟 Elasticsearch 的存取紀錄同一個曲線。 */
    private static final double[] TRAFFIC = {0.3, 0.2, 0.15, 0.1, 0.1, 0.15, 0.3, 0.6, 0.9, 1, 1, 1.1, 1.2, 1, 1, 1, 1, 1.1, 1.3, 1.6, 1.9, 2.0, 1.6, 0.8};

    private static double traffic(long ts) {
        int hour = java.time.Instant.ofEpochSecond(ts).atZone(TAIPEI).getHour();
        return TRAFFIC[hour] / 2.0;
    }

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, TAIPEI).toEpochSecond();
    }

    private synchronized void load() {
        error = null;
        points = 0;
        long started = System.nanoTime();
        step = "主機 CPU 與記憶體";
        hosts();
        step = "API 請求計數器";
        http();
        step = "倉庫感測器";
        sensors();
        step = "訂單（從 PostgreSQL 複製）";
        orders();
        seconds = (System.nanoTime() - started) / 1e9;
        log.info("InfluxDB 練習資料載入完成：{} 個點，{} 秒", points, String.format("%.1f", seconds));
    }

    private record Host(String name, String role, String region, double base, double load) {
    }

    /**
     * cpu：usage_user、usage_system；mem：used_percent。
     * db-01 在 9/18 14:00～14:40 CPU 滿載；web-02 記憶體慢慢洩漏，9/15 03:00 重啟後歸位；web-03 在 9/20 12:00 下線，之後沒有資料。
     */
    private void hosts() {
        Random r = new Random(2026_09_01);
        List<Host> hosts = List.of(
                new Host("web-01", "web", "tpe", 20, 30), new Host("web-02", "web", "tpe", 20, 30),
                new Host("web-03", "web", "tch", 18, 25), new Host("db-01", "db", "tpe", 25, 25),
                new Host("cache-01", "cache", "tpe", 8, 10));
        long incidentStart = at(18, 14, 0), incidentEnd = at(18, 14, 40), web03Off = at(20, 12, 0), restart = at(15, 3, 0);
        for (int m = 0; m < MINUTES; m++) {
            long ts = START + m * 60L;
            for (Host h : hosts) {
                if (h.name().equals("web-03") && ts >= web03Off) {
                    continue;
                }
                double user = h.base() + h.load() * traffic(ts) + r.nextGaussian() * 2.5;
                if (h.name().equals("db-01") && ts >= incidentStart && ts < incidentEnd) {
                    user = 92 + r.nextDouble() * 7;
                }
                user = Math.max(0.5, Math.min(99.5, user));
                double system = Math.max(0.2, user * 0.3 + r.nextGaussian());
                String tags = ",host=" + h.name() + ",region=" + h.region() + ",role=" + h.role();
                line("s", "cpu" + tags + " usage_user=" + f(user) + ",usage_system=" + f(system) + " " + ts);
                double mem;
                if (h.name().equals("web-02")) {
                    long since = ts < restart ? ts - START : ts - restart;          // 記憶體洩漏：從上次重啟起每分鐘多 0.0012%
                    mem = 40 + since / 60.0 * 0.0012 + r.nextGaussian() * 0.3;
                } else {
                    mem = 50 + (h.role().equals("db") ? 20 : 0) + r.nextGaussian() * 1.5;
                }
                line("s", "mem" + tags + " used_percent=" + f(mem) + " " + ts);
            }
        }
        flush("s");
    }

    /**
     * http：requests、errors 是「累計計數器」（只會增加），每分鐘回報一次目前的累計值。
     * api 服務在 9/15 03:00 重啟，計數器從 0 重新開始（non_negative_derivative 的經典情境）；9/18 事故期間錯誤暴增。
     */
    private void http() {
        Random r = new Random(15);
        long restart = at(15, 3, 0), incidentStart = at(18, 14, 0), incidentEnd = at(18, 14, 40);
        long[] requests = {0, 0}, errors = {0, 0};
        String[] services = {"api", "web"};
        for (int m = 0; m < MINUTES; m++) {
            long ts = START + m * 60L;
            for (int s = 0; s < 2; s++) {
                if (s == 0 && ts == restart) {
                    requests[0] = 0;
                    errors[0] = 0;
                }
                long inc = Math.round((s == 0 ? 400 : 250) * traffic(ts) * (0.9 + r.nextDouble() * 0.2));
                requests[s] += inc;
                double errRate = (s == 0 && ts >= incidentStart && ts < incidentEnd) ? 0.35 : 0.004;
                errors[s] += Math.round(inc * errRate * (0.5 + r.nextDouble()));
                line("s", "http,service=" + services[s] + " requests=" + requests[s] + "i,errors=" + errors[s] + "i " + ts);
            }
        }
        flush("s");
    }

    /**
     * sensors：每 5 分鐘一筆。1～3 號在北倉、4～6 號在南倉；1、2、4 號是冷藏（約 4 度），其他是常溫。
     * 2 號在 9/18 13:00～15:00 冷藏門沒關好，溫度升到 12 度；5 號在 9/10 10:00～14:00 斷線，沒有資料。
     */
    private void sensors() {
        Random r = new Random(7);
        long spikeStart = at(18, 13, 0), spikeEnd = at(18, 15, 0), offStart = at(10, 10, 0), offEnd = at(10, 14, 0);
        for (int m = 0; m < MINUTES; m += 5) {
            long ts = START + m * 60L;
            int hour = java.time.Instant.ofEpochSecond(ts).atZone(TAIPEI).getHour();
            for (int id = 1; id <= 6; id++) {
                if (id == 5 && ts >= offStart && ts < offEnd) {
                    continue;
                }
                boolean cold = id == 1 || id == 2 || id == 4;
                double temp = cold ? 4 + r.nextGaussian() * 0.4
                        : 24 + 3 * Math.sin((hour - 9) / 24.0 * 2 * Math.PI) + r.nextGaussian() * 0.5;
                if (id == 2 && ts >= spikeStart && ts < spikeEnd) {
                    temp = 4 + 8 * Math.min(1, (ts - spikeStart) / 3600.0) + r.nextGaussian() * 0.3;
                }
                double humidity = (cold ? 85 : 60) + r.nextGaussian() * 3;
                String tags = ",kind=" + (cold ? "cold" : "ambient") + ",sensor_id=" + id + ",warehouse=" + (id <= 3 ? "north" : "south");
                line("s", "sensors" + tags + " temperature=" + f(temp) + ",humidity=" + f(humidity) + " " + ts);
            }
        }
        flush("s");
    }

    /** orders：tag 是 city、status、vip_level（值的種類少）；customer_id、order_id 是 field（值的種類很多，不能當 tag）。 */
    private void orders() {
        postgres.query("""
                SELECT o.id, o.customer_id, o.status, o.order_date, coalesce(o.shipping_city, '未知') AS city, c.vip_level,
                       round(sum(oi.quantity * oi.unit_price * (1 - oi.discount)))::bigint AS total, count(*) AS items
                FROM orders o JOIN order_items oi ON oi.order_id = o.id JOIN customers c ON c.id = o.customer_id
                GROUP BY o.id, c.vip_level ORDER BY o.order_date""", rs -> {
            long ms = rs.getObject(4, OffsetDateTime.class).toInstant().toEpochMilli();
            line("ms", "orders,city=" + rs.getString(5) + ",status=" + rs.getString(3) + ",vip_level=" + rs.getString(6)
                    + " order_id=" + rs.getInt(1) + "i,customer_id=" + rs.getInt(2) + "i,total=" + rs.getLong(7) + "i,items=" + rs.getInt(8) + "i " + ms);
        });
        flush("ms");
    }
}
