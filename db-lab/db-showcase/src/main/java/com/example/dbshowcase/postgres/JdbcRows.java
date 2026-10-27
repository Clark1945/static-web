package com.example.dbshowcase.postgres;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 讀取 ResultSet、檢查使用者 SQL 的共用工具。 */
final class JdbcRows {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern LEADING_COMMENTS = Pattern.compile("(?s)^(\\s*(--[^\\n]*(\\n|$)|/\\*.*?\\*/))*\\s*");

    private JdbcRows() {
    }

    /** 讀出最多 limit 筆（呼叫端要先 setMaxRows(limit + 1)，多讀的一筆用來判斷有沒有截斷）。 */
    static QueryResult read(ResultSet rs, int limit, long startNanos) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int n = meta.getColumnCount();
        List<String> columns = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            columns.add(meta.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                row.add(toJsonValue(rs.getObject(i)));
            }
            rows.add(row);
        }
        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(0, limit);
        }
        return new QueryResult(columns, rows, rows.size(), truncated, elapsedMs);
    }

    /** 去掉開頭的空白與註解後，回傳第一個英文單字（小寫）。 */
    static String firstWord(String sql) {
        String rest = LEADING_COMMENTS.matcher(sql).replaceFirst("");
        int end = 0;
        while (end < rest.length() && Character.isLetter(rest.charAt(end))) {
            end++;
        }
        return rest.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /** 去掉結尾分號；空字串時丟出錯誤訊息。 */
    static String stripTrailingSemicolons(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("請輸入 SQL。");
        }
        return raw.strip().replaceAll(";+\\s*$", "");
    }

    private static Object toJsonValue(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(TS_FORMAT);
        }
        if (v instanceof java.sql.Array arr) {
            try {
                return java.util.Arrays.toString((Object[]) arr.getArray()).replace("[", "{").replace("]", "}");
            } catch (SQLException e) {
                return v.toString();
            }
        }
        return v.toString();
    }
}
