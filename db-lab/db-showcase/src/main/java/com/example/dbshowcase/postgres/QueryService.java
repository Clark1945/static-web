package com.example.dbshowcase.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 執行唯讀查詢並計時。
 * 兩道防線：READ ONLY 交易（最後一律 rollback）＋ SET LOCAL ROLE learner（權限最小的角色）。
 */
@Service
public class QueryService {

    private static final Set<String> ALLOWED = Set.of("select", "with", "explain", "values", "table");

    private final JdbcTemplate jdbc;
    private final int maxRows;
    private final long timeoutMs;

    public QueryService(JdbcTemplate jdbc,
                        @Value("${showcase.postgres.max-rows}") int maxRows,
                        @Value("${showcase.postgres.statement-timeout}") Duration timeout) {
        this.jdbc = jdbc;
        this.maxRows = maxRows;
        this.timeoutMs = timeout.toMillis();
    }

    public QueryResult run(String rawSql) {
        return run(rawSql, maxRows);
    }

    /** 指定最多回傳幾筆；批改練習題時需要完整結果，會傳比較大的上限。 */
    public QueryResult run(String rawSql, int limit) {
        String sql = normalize(rawSql);
        return jdbc.execute((ConnectionCallback<QueryResult>) con -> execute(con, sql, limit));
    }

    private QueryResult execute(Connection con, String sql, int limit) throws SQLException {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        con.setReadOnly(true);
        try (Statement st = con.createStatement()) {
            st.execute("SET LOCAL ROLE learner");
            st.execute("SET LOCAL statement_timeout = " + timeoutMs);
            st.setMaxRows(limit + 1);     // 多抓一筆，用來判斷有沒有被截斷
            long start = System.nanoTime();
            try (ResultSet rs = st.executeQuery(sql)) {
                return JdbcRows.read(rs, limit, start);
            }
        } finally {
            con.rollback();
            con.setReadOnly(false);
            con.setAutoCommit(autoCommit);
        }
    }

    /** 只允許單一句唯讀查詢。 */
    private static String normalize(String rawSql) {
        String sql = JdbcRows.stripTrailingSemicolons(rawSql);
        if (sql.contains(";")) {
            throw new IllegalArgumentException("一次只能執行一句 SQL（中間不能有分號）。要執行多句請到「寫入沙盒」。");
        }
        if (!ALLOWED.contains(JdbcRows.firstWord(sql))) {
            throw new IllegalArgumentException("這裡只能執行查詢：請用 SELECT、WITH 或 EXPLAIN 開頭。INSERT / UPDATE / DELETE 請到「寫入沙盒」。");
        }
        return sql;
    }
}
