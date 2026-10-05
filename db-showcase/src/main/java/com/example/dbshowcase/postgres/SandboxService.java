package com.example.dbshowcase.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 寫入沙盒：可以執行 INSERT / UPDATE / DELETE，也可以一次送多句（例如先 UPDATE 再 SELECT 看結果），
 * 全部在同一個交易裡執行，結束時一律 ROLLBACK，資料不會真的被改。
 *
 * 防線：
 * 1. 每一句的開頭都必須是允許的關鍵字。COMMIT、BEGIN、SET ROLE、DROP… 都會被擋下，
 *    所以使用者沒辦法自己提早 COMMIT。
 * 2. SET LOCAL ROLE learner：只能讀寫 public 的 5 張表，不能改結構、不能用超級使用者函數。
 * 注意：序列（serial 的 id）不受交易控制，ROLLBACK 之後 id 還是會往前跳。
 */
@Service
public class SandboxService {

    private static final Set<String> ALLOWED = Set.of(
            "select", "with", "insert", "update", "delete", "merge", "explain", "values", "table");
    private static final int MAX_STATEMENTS = 10;

    /** 一句 SQL 的結果：查詢或 RETURNING 會有 result；其他只有影響筆數。 */
    public record StatementResult(int index, String kind, QueryResult result, Integer updateCount) {
    }

    public record SandboxResult(List<StatementResult> statements, double elapsedMs) {

        /** 最後一個有回傳資料列的結果（批改寫入題時使用）。 */
        public QueryResult lastRows() {
            for (int i = statements.size() - 1; i >= 0; i--) {
                if (statements.get(i).result() != null) {
                    return statements.get(i).result();
                }
            }
            return null;
        }
    }

    private final JdbcTemplate jdbc;
    private final int maxRows;
    private final long timeoutMs;

    public SandboxService(JdbcTemplate jdbc,
                          @Value("${showcase.postgres.max-rows}") int maxRows,
                          @Value("${showcase.postgres.statement-timeout}") Duration timeout) {
        this.jdbc = jdbc;
        this.maxRows = maxRows;
        this.timeoutMs = timeout.toMillis();
    }

    public SandboxResult run(String script) {
        return run(script, maxRows);
    }

    public SandboxResult run(String rawScript, int limit) {
        String script = validate(rawScript);
        return jdbc.execute((ConnectionCallback<SandboxResult>) con -> execute(con, script, limit));
    }

    private SandboxResult execute(Connection con, String script, int limit) throws SQLException {
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try (Statement st = con.createStatement()) {
            st.execute("SET LOCAL ROLE learner");
            st.execute("SET LOCAL statement_timeout = " + timeoutMs);
            st.setMaxRows(limit + 1);

            List<StatementResult> results = new ArrayList<>();
            long start = System.nanoTime();
            boolean isResultSet = st.execute(script);   // PostgreSQL JDBC 支援一次送出多句
            int index = 1;
            while (true) {
                if (isResultSet) {
                    try (ResultSet rs = st.getResultSet()) {
                        results.add(new StatementResult(index++, "rows", JdbcRows.read(rs, limit, System.nanoTime()), null));
                    }
                } else {
                    int count = st.getUpdateCount();
                    if (count == -1) {
                        break;
                    }
                    results.add(new StatementResult(index++, "count", null, count));
                }
                isResultSet = st.getMoreResults();
            }
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            return new SandboxResult(results, elapsedMs);
        } finally {
            con.rollback();                               // 一律還原
            con.setAutoCommit(autoCommit);
        }
    }

    /**
     * 用分號切開，檢查每一段的開頭。字串裡如果有分號，切出來的片段開頭會很奇怪而被擋下；
     * 這是刻意的保守做法：寧可誤擋，也不能讓 COMMIT 混進去。
     */
    private static String validate(String raw) {
        String script = JdbcRows.stripTrailingSemicolons(raw);
        String[] parts = script.split(";");
        int statements = 0;
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            statements++;
            String word = JdbcRows.firstWord(part);
            if (!ALLOWED.contains(word)) {
                throw new IllegalArgumentException(word.isEmpty()
                        ? "有一段 SQL 的開頭無法辨識（字串或註解裡有分號嗎？）。沙盒會用分號切開每一句來檢查。"
                        : "寫入沙盒不接受「" + word.toUpperCase() + "」。可以用 SELECT、WITH、INSERT、UPDATE、DELETE、MERGE、EXPLAIN；"
                          + "交易控制（BEGIN / COMMIT）由沙盒自動處理，最後一律 ROLLBACK。");
            }
        }
        if (statements > MAX_STATEMENTS) {
            throw new IllegalArgumentException("一次最多 " + MAX_STATEMENTS + " 句。");
        }
        return script;
    }
}
