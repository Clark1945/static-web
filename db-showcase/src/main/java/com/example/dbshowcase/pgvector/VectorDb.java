package com.example.dbshowcase.pgvector;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.dbshowcase.postgres.QueryService;
import com.example.dbshowcase.postgres.SandboxService;
import com.example.dbshowcase.postgres.practice.Grader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * pgvector：pg-lab 容器（映像檔已內建 pgvector）裡另外一個 vectors 資料庫。
 * pgvector 是 PostgreSQL 的擴充，所以跟 TimescaleDB 一樣直接沿用 QueryService（唯讀交易 + SET LOCAL ROLE learner）、
 * SandboxService（一律 ROLLBACK）與 Grader。連線池不註冊成 bean，以免 Spring Boot 不再自動建立 PostgreSQL 的那一組。
 *
 * 啟動時在背景：沒有 vectors 資料庫就建立 → 建立結構（schema.sql）→ 資料是空的就載入
 * （商品與購買紀錄從 PostgreSQL 複製，詞庫、向量、10 萬筆索引實驗資料在資料庫裡產生）→ 建立 HNSW 索引。
 */
@Component
public class VectorDb {

    private static final Logger log = LoggerFactory.getLogger(VectorDb.class);

    public record Status(boolean running, String step, String error, Double seconds, Double indexSeconds) {
    }

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate postgres;
    private final String database;
    private final QueryService queries;
    private final SandboxService sandbox;
    private final Grader grader;
    private volatile boolean running;
    private volatile String step;
    private volatile String error;
    private volatile Double seconds;
    private volatile Double indexSeconds;
    private volatile int loads;

    public VectorDb(@Value("${showcase.pgvector.url}") String url,
                    @Value("${spring.datasource.username}") String user,
                    @Value("${spring.datasource.password}") String password,
                    @Value("${showcase.postgres.max-rows}") int maxRows,
                    @Value("${showcase.postgres.statement-timeout}") Duration timeout,
                    JdbcTemplate postgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(6);
        config.setPoolName("pgvector");
        config.setInitializationFailTimeout(-1);     // 資料庫還沒建立也不要讓展示台啟動失敗
        config.setConnectionTimeout(5000);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        this.postgres = postgres;
        this.database = url.substring(url.lastIndexOf('/') + 1);
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

    /** 載入過幾次（實驗室用來判斷快取是否過期）。 */
    public int loads() {
        return loads;
    }

    public Status status() {
        return new Status(running, step, error, seconds, indexSeconds != null ? indexSeconds : safeTiming("passages_hnsw"));
    }

    private Double safeTiming(String name) {
        try {
            return timing(name);
        } catch (Exception e) {
            return null;                          // 資料庫還沒準備好
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        Thread.ofVirtual().name("pgvector-loader").start(() -> {
            try {
                running = true;
                step = "建立資料庫";
                Integer exists = postgres.queryForObject("SELECT count(*) FROM pg_database WHERE datname = ?", Integer.class, database);
                if (exists == null || exists == 0) {
                    postgres.execute("CREATE DATABASE " + database);
                }
                step = "建立結構";
                for (String sql : statements("pgvector/schema.sql")) {
                    jdbc.execute(sql);
                }
                Long products = jdbc.queryForObject("SELECT count(*) FROM products WHERE embedding IS NOT NULL", Long.class);
                Boolean indexed = jdbc.queryForObject("SELECT to_regclass('public.passages_hnsw') IS NOT NULL", Boolean.class);
                if (products == null || products == 0 || !Boolean.TRUE.equals(indexed)) {
                    load();
                }
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("pgvector 練習資料準備失敗（pg-lab 容器有啟動嗎？）：{}", e.getMessage());
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
            Thread.ofVirtual().name("pgvector-loader").start(() -> {
                try {
                    load();
                } catch (Exception e) {
                    error = e.getMessage();
                    log.warn("pgvector 練習資料載入失敗：{}", e.getMessage());
                } finally {
                    running = false;
                    step = null;
                }
            });
        }
        return status();
    }

    /** 一律先清空（索引也刪掉重建），所以中途失敗後重跑也不會重複。 */
    private void load() {
        error = null;
        long start = System.nanoTime();

        step = "清空資料";
        loads++;
        jdbc.execute("DROP TABLE IF EXISTS lab_passages");
        jdbc.execute("DROP INDEX IF EXISTS passages_hnsw, lab_passages_half, lab_passages_bit");
        jdbc.execute("TRUNCATE products, purchases, passages, questions");

        step = "複製商品與購買紀錄";
        List<Object[]> products = new ArrayList<>();
        postgres.query("""
                SELECT p.id, p.name, c.name AS category, round(p.price)::int AS price, p.tags, p.specs::text
                FROM products p JOIN categories c ON c.id = p.category_id
                ORDER BY p.id""", rs -> {
            products.add(new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                    rs.getArray(5).getArray(), rs.getString(6)});
        });
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (var ps = con.prepareStatement("INSERT INTO products (id, name, category, price, tags, specs) VALUES (?, ?, ?, ?, ?, ?::jsonb)")) {
                for (Object[] r : products) {
                    ps.setInt(1, (Integer) r[0]);
                    ps.setString(2, (String) r[1]);
                    ps.setString(3, (String) r[2]);
                    ps.setInt(4, (Integer) r[3]);
                    ps.setArray(5, con.createArrayOf("text", (Object[]) r[4]));
                    ps.setString(6, (String) r[5]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        List<Object[]> purchases = new ArrayList<>();
        postgres.query("""
                SELECT o.customer_id, oi.product_id, oi.quantity, o.order_date
                FROM orders o JOIN order_items oi ON oi.order_id = o.id
                WHERE o.status NOT IN ('cancelled', 'returned')
                ORDER BY o.id, oi.product_id""", rs -> {
            purchases.add(new Object[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4)});
        });
        jdbc.batchUpdate("INSERT INTO purchases (customer_id, product_id, quantity, purchased_at) VALUES (?, ?, ?, ?)",
                purchases, 5000, (ps, r) -> {
                    ps.setInt(1, (Integer) r[0]);
                    ps.setInt(2, (Integer) r[1]);
                    ps.setInt(3, (Integer) r[2]);
                    ps.setTimestamp(4, (Timestamp) r[3]);
                });

        step = "產生詞庫與向量";
        for (String sql : statements("pgvector/generate.sql")) {
            jdbc.execute(sql);
        }

        step = "建立 HNSW 索引（10 萬筆，約 40 秒）";
        long indexStart = System.nanoTime();
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (var st = con.createStatement()) {
                st.execute("SET maintenance_work_mem = '512MB'");          // 圖要能整個放進記憶體，建索引才快
                st.execute("SET max_parallel_maintenance_workers = 0");     // 容器的 /dev/shm 只有 64 MB，平行建索引會失敗
                st.execute("CREATE INDEX passages_hnsw ON passages USING hnsw (embedding vector_cosine_ops)");
                st.execute("RESET maintenance_work_mem");
                st.execute("RESET max_parallel_maintenance_workers");
            }
            return null;
        });
        indexSeconds = (System.nanoTime() - indexStart) / 1e9;
        saveTiming("passages_hnsw", indexSeconds);

        seconds = (System.nanoTime() - start) / 1e9;
        loads++;
        log.info("pgvector 練習資料載入完成，{} 秒（HNSW 索引 {} 秒）", String.format("%.1f", seconds), String.format("%.1f", indexSeconds));
    }

    /** 記錄索引建立花了幾秒。 */
    public void saveTiming(String name, double seconds) {
        jdbc.update("""
                INSERT INTO lab_timings (name, seconds) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET seconds = EXCLUDED.seconds""", name, seconds);
    }

    /** 讀出索引建立花了幾秒；沒有紀錄回傳 null。 */
    public Double timing(String name) {
        List<Double> list = jdbc.queryForList("SELECT seconds FROM lab_timings WHERE name = ?", Double.class, name);
        return list.isEmpty() ? null : list.get(0);
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
