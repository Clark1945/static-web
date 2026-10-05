package com.example.dbshowcase.postgres.lab;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.dbshowcase.common.YamlContent;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 索引實驗室：在 perf schema 的大表上建立 / 刪除索引。
 *
 * 安全設計有兩層：
 * 1. 只接受 CREATE INDEX / DROP INDEX 一句。
 * 2. 執行前 SET LOCAL ROLE perf_lab。這個角色只擁有 perf schema，
 *    就算送出 DROP INDEX public.xxx，PostgreSQL 本身也會拒絕（must be owner）。
 */
@Service
public class IndexLabService {

    private static final int MAX_INDEXES = 8;
    private static final Pattern ALLOWED = Pattern.compile("^(create\\s+(unique\\s+)?index|drop\\s+index)\\b");

    public record IndexInfo(String table, String name, String definition, String size, boolean primary) {
    }

    public record DdlResult(double elapsedMs, List<IndexInfo> indexes) {
    }

    public record TableInfo(String table, String comment, long rowCount, String size) {
    }

    public record LabInfo(List<TableInfo> tables, List<IndexInfo> indexes) {
    }

    private final JdbcTemplate jdbc;
    private final List<LabStep> steps;

    public IndexLabService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.steps = YamlContent.load("postgres/index-lab.yml", LabStep.class, mapper);
    }

    public List<LabStep> steps() {
        return steps;
    }

    /** perf schema 裡的實驗用大表（筆數用 pg_class.reltuples 的統計估計值，不用每次 count(*)）。 */
    public LabInfo info() {
        List<TableInfo> tables = jdbc.query("""
                SELECT 'perf.' || c.relname AS name,
                       obj_description(c.oid, 'pg_class') AS comment,
                       c.reltuples::bigint AS row_count,
                       pg_size_pretty(pg_table_size(c.oid)) AS size
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'perf' AND c.relkind = 'r'
                ORDER BY c.relname
                """,
                (rs, i) -> new TableInfo(rs.getString("name"), rs.getString("comment"),
                        rs.getLong("row_count"), rs.getString("size")));
        return new LabInfo(tables, indexes());
    }

    public List<IndexInfo> indexes() {
        return jdbc.query("""
                SELECT 'perf.' || t.relname AS table_name,
                       i.relname AS name,
                       pg_get_indexdef(i.oid) AS definition,
                       pg_size_pretty(pg_relation_size(i.oid)) AS size,
                       x.indisprimary AS is_primary
                FROM pg_index x
                JOIN pg_class i ON i.oid = x.indexrelid
                JOIN pg_class t ON t.oid = x.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'perf'
                ORDER BY t.relname, x.indisprimary DESC, i.relname
                """,
                (rs, n) -> new IndexInfo(rs.getString("table_name"), rs.getString("name"),
                        rs.getString("definition"), rs.getString("size"), rs.getBoolean("is_primary")));
    }

    public DdlResult execute(String rawDdl) {
        String ddl = normalize(rawDdl);
        boolean create = ddl.toLowerCase(Locale.ROOT).startsWith("create");
        if (create && indexes().stream().filter(i -> !i.primary()).count() >= MAX_INDEXES) {
            throw new IllegalArgumentException("最多只能有 " + MAX_INDEXES + " 個索引，請先刪掉一些（或按「重置」）。");
        }
        double elapsed = runAsLabRole(List.of(ddl));
        return new DdlResult(elapsed, indexes());
    }

    /** 刪掉主鍵以外的所有索引，回到「只有主鍵」的初始狀態。 */
    public DdlResult reset() {
        List<String> drops = indexes().stream()
                .filter(i -> !i.primary())
                .map(i -> "DROP INDEX perf.\"" + i.name().replace("\"", "\"\"") + "\"")
                .toList();
        double elapsed = drops.isEmpty() ? 0 : runAsLabRole(drops);
        return new DdlResult(elapsed, indexes());
    }

    private double runAsLabRole(List<String> statements) {
        return jdbc.execute((ConnectionCallback<Double>) con -> {
            boolean autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute("SET LOCAL ROLE perf_lab");
                st.execute("SET LOCAL statement_timeout = 120000");
                long start = System.nanoTime();
                for (String s : statements) {
                    st.execute(s);
                }
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                con.commit();
                return elapsed;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(autoCommit);
            }
        });
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("請輸入 CREATE INDEX 或 DROP INDEX。");
        }
        String sql = raw.strip().replaceAll(";+\\s*$", "");
        if (sql.contains(";")) {
            throw new IllegalArgumentException("一次只能執行一句。");
        }
        if (!ALLOWED.matcher(sql.toLowerCase(Locale.ROOT)).find()) {
            throw new IllegalArgumentException("索引實驗室只接受 CREATE INDEX 或 DROP INDEX。");
        }
        return sql;
    }
}
