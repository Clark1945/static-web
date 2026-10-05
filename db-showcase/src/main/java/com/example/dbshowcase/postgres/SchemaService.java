package com.example.dbshowcase.postgres;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 從 PostgreSQL 的系統目錄讀出資料表結構與筆數。 */
@Service
public class SchemaService {

    public record ColumnInfo(String name, String type, boolean nullable, boolean primaryKey, String references) {
    }

    public record TableInfo(String name, String comment, long rowCount, String size, List<ColumnInfo> columns) {
    }

    private final JdbcTemplate jdbc;

    public SchemaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TableInfo> tables() {
        // 主鍵、外鍵：key = "表.欄位"
        Map<String, Boolean> pk = new HashMap<>();
        Map<String, String> fk = new HashMap<>();
        jdbc.query("""
                SELECT con.contype,
                       rel.relname  AS table_name,
                       att.attname  AS column_name,
                       ref.relname  AS ref_table
                FROM pg_constraint con
                JOIN pg_class rel     ON rel.oid = con.conrelid
                JOIN pg_namespace ns  ON ns.oid = rel.relnamespace
                JOIN LATERAL unnest(con.conkey) AS k(attnum) ON true
                JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = k.attnum
                LEFT JOIN pg_class ref ON ref.oid = con.confrelid
                WHERE ns.nspname = 'public' AND con.contype IN ('p', 'f')
                """, rs -> {
            String key = rs.getString("table_name") + "." + rs.getString("column_name");
            if ("p".equals(rs.getString("contype"))) {
                pk.put(key, true);
            } else {
                fk.put(key, rs.getString("ref_table"));
            }
        });

        Map<String, List<ColumnInfo>> columns = new LinkedHashMap<>();
        jdbc.query("""
                SELECT table_name, column_name, is_nullable,
                       CASE WHEN data_type = 'ARRAY' THEN substr(udt_name, 2) || '[]' ELSE data_type END AS data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name, ordinal_position
                """, rs -> {
            String table = rs.getString("table_name");
            String col = rs.getString("column_name");
            String key = table + "." + col;
            columns.computeIfAbsent(table, t -> new ArrayList<>()).add(new ColumnInfo(
                    col,
                    rs.getString("data_type"),
                    "YES".equals(rs.getString("is_nullable")),
                    pk.containsKey(key),
                    fk.get(key)));
        });

        List<TableInfo> result = new ArrayList<>();
        jdbc.query("""
                SELECT c.relname AS name,
                       obj_description(c.oid, 'pg_class') AS comment,
                       pg_size_pretty(pg_total_relation_size(c.oid)) AS size
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                ORDER BY c.relname
                """, rs -> {
            String name = rs.getString("name");
            // 表名來自系統目錄，不是使用者輸入，可以安全地組進 SQL
            Long count = jdbc.queryForObject("SELECT count(*) FROM " + name, Long.class);
            result.add(new TableInfo(name, rs.getString("comment"), count == null ? 0 : count,
                    rs.getString("size"), columns.getOrDefault(name, List.of())));
        });
        return result;
    }
}
