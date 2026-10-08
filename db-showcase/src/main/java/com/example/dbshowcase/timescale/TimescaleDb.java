package com.example.dbshowcase.timescale;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.dbshowcase.postgres.QueryService;
import com.example.dbshowcase.postgres.SandboxService;
import com.example.dbshowcase.postgres.practice.Grader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * TimescaleDB（timescale-lab 容器）。TimescaleDB 就是 PostgreSQL 的擴充，所以直接沿用 PostgreSQL 頁面的
 * QueryService（唯讀交易 + SET LOCAL ROLE learner）、SandboxService（一律 ROLLBACK）與 Grader。
 * 連線池不註冊成 DataSource / JdbcTemplate bean：註冊了會讓 Spring Boot 不再自動建立 PostgreSQL 的那一組。
 *
 * 啟動時在背景：建立結構（schema.sql）→ 資料是空的就載入（訂單從 PostgreSQL 複製，點擊流與感測器在 TimescaleDB 裡產生）
 * → 建立連續聚合。
 */
@Component
public class TimescaleDb {

    private static final Logger log = LoggerFactory.getLogger(TimescaleDb.class);

    /** 連續聚合：每日瀏覽只物化到 9/24（最後一週留給「還沒刷新」的陷阱題），感測器每小時全部物化。 */
    public static final String PAGE_VIEWS_REFRESHED_UNTIL = "2026-09-24 00:00+08";

    public record Status(boolean running, String step, String error, Double seconds) {
    }

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate postgres;
    private final QueryService queries;
    private final SandboxService sandbox;
    private final Grader grader;
    private volatile boolean running;
    private volatile String step;
    private volatile String error;
    private volatile Double seconds;

    public TimescaleDb(@Value("${showcase.timescale.url}") String url,
                       @Value("${showcase.timescale.user}") String user,
                       @Value("${showcase.timescale.password}") String password,
                       @Value("${showcase.postgres.max-rows}") int maxRows,
                       @Value("${showcase.postgres.statement-timeout}") Duration timeout,
                       JdbcTemplate postgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(6);
        config.setPoolName("timescale");
        config.setInitializationFailTimeout(-1);     // 容器還沒起來也不要讓展示台啟動失敗
        config.setConnectionTimeout(5000);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        this.postgres = postgres;
        this.queries = new QueryService(jdbc, maxRows, timeout);
        this.sandbox = new SandboxService(jdbc, maxRows, timeout);
        this.grader = new Grader(queries, sandbox);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public QueryService queries() {
        return queries;
    }

    public SandboxService sandbox() {
        return sandbox;
    }

    public Grader grader() {
        return grader;
    }

    public Status status() {
        return new Status(running, step, error, seconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        Thread.ofVirtual().name("timescale-loader").start(() -> {
            try {
                running = true;
                step = "建立結構";
                for (String sql : statements("timescale/schema.sql")) {
                    jdbc.execute(sql);
                }
                Long orders = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
                if (orders == null || orders == 0) {
                    load();
                }
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("TimescaleDB 練習資料準備失敗（timescale-lab 容器有啟動嗎？）：{}", e.getMessage());
            } finally {
                running = false;
                step = null;
            }
        });
    }

    /** 清空後重新載入（頁面上的「重新載入資料」）。在背景執行。 */
    public synchronized Status reload() {
        if (!running) {
            running = true;
            Thread.ofVirtual().name("timescale-loader").start(() -> {
                try {
                    load();
                } catch (Exception e) {
                    error = e.getMessage();
                    log.warn("TimescaleDB 練習資料載入失敗：{}", e.getMessage());
                } finally {
                    running = false;
                    step = null;
                }
            });
        }
        return status();
    }

    /** 一律先清空（連續聚合也刪掉重建），所以中途失敗後重跑也不會重複。 */
    private void load() {
        error = null;
        long start = System.nanoTime();

        step = "清空資料";
        jdbc.execute("DROP MATERIALIZED VIEW IF EXISTS page_views_daily CASCADE");
        jdbc.execute("DROP MATERIALIZED VIEW IF EXISTS sensor_hourly CASCADE");
        jdbc.execute("TRUNCATE orders, page_views, sensor_readings");

        step = "複製訂單";
        List<Object[]> rows = new ArrayList<>();
        postgres.query("""
                SELECT o.order_date, o.id, o.customer_id, o.status, o.shipping_city,
                       sum(oi.quantity * oi.unit_price * (1 - oi.discount)) AS total, count(*) AS items
                FROM orders o JOIN order_items oi ON oi.order_id = o.id
                GROUP BY o.id ORDER BY o.order_date
                """, rs -> {
            rows.add(new Object[] {rs.getTimestamp(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                    rs.getBigDecimal(6).setScale(0, RoundingMode.HALF_UP).intValue(), rs.getInt(7)});
        });
        jdbc.batchUpdate("INSERT INTO orders (order_time, order_id, customer_id, status, city, total, item_count) VALUES (?, ?, ?, ?, ?, ?, ?)",
                rows, 5000, (ps, r) -> {
                    ps.setTimestamp(1, (Timestamp) r[0]);
                    ps.setInt(2, (Integer) r[1]);
                    ps.setInt(3, (Integer) r[2]);
                    ps.setString(4, (String) r[3]);
                    ps.setString(5, (String) r[4]);
                    ps.setInt(6, (Integer) r[5]);
                    ps.setInt(7, (Integer) r[6]);
                });
        jdbc.execute("ANALYZE orders");

        step = "產生點擊流與感測器資料";
        for (String sql : statements("timescale/generate.sql")) {
            jdbc.execute(sql);
        }

        step = "建立連續聚合";
        jdbc.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS page_views_daily WITH (timescaledb.continuous) AS
                SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day,
                       product_id,
                       count(*) AS views,
                       count(customer_id) AS member_views
                FROM page_views
                GROUP BY day, product_id
                WITH NO DATA""");
        jdbc.execute("CALL refresh_continuous_aggregate('page_views_daily', NULL, '" + PAGE_VIEWS_REFRESHED_UNTIL + "')");
        jdbc.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS sensor_hourly WITH (timescaledb.continuous) AS
                SELECT time_bucket('1 hour', time) AS hour,
                       sensor_id,
                       avg(temperature) AS avg_temp,
                       min(temperature) AS min_temp,
                       max(temperature) AS max_temp,
                       count(*) AS readings
                FROM sensor_readings
                GROUP BY hour, sensor_id
                WITH NO DATA""");
        jdbc.execute("CALL refresh_continuous_aggregate('sensor_hourly', NULL, NULL)");
        jdbc.execute("GRANT SELECT ON page_views_daily, sensor_hourly TO learner");

        seconds = (System.nanoTime() - start) / 1e9;
        log.info("TimescaleDB 練習資料載入完成，{} 秒", String.format("%.1f", seconds));
    }

    /** 讀取 classpath 上的 SQL 檔，用「;;」切成多句。 */
    static List<String> statements(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String part : text.split(";;")) {
                String s = part.replaceAll("(?m)^\\s*--.*$", "").strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PreDestroy
    public void close() {
        dataSource.close();
    }
}
