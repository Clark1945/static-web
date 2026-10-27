package com.example.dbshowcase.postgres.practice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.postgres.QueryResult;
import com.example.dbshowcase.postgres.QueryService;
import com.example.dbshowcase.postgres.SandboxService;

/**
 * 批改練習題：同時執行使用者的 SQL 與標準答案，比對兩邊的結果。
 * 欄位名稱不比對（別名自由取），只比對欄位數量、筆數與每一格的值。
 */
@Service
public class Grader {

    /** 批改時最多讀幾筆；題目的答案都遠小於這個數字。 */
    private static final int GRADE_LIMIT = 20_000;

    public record Mismatch(int row, List<Object> yours, List<Object> expected) {
    }

    public record Grade(
            boolean correct,
            String message,
            QueryResult result,          // 使用者的查詢結果（最多顯示 500 筆）
            int expectedRowCount,
            Mismatch mismatch,
            String explanation) {        // 答對才回傳解說
    }

    private final QueryService queryService;
    private final SandboxService sandbox;

    public Grader(QueryService queryService, SandboxService sandbox) {
        this.queryService = queryService;
        this.sandbox = sandbox;
    }

    public Grade grade(Exercise exercise, String userSql) {
        QueryResult yours;
        QueryResult expected;
        if (exercise.isWrite()) {
            // 寫入題：在沙盒裡執行（一律 ROLLBACK），比對最後一個回傳的資料列
            yours = sandbox.run(userSql, GRADE_LIMIT).lastRows();
            expected = sandbox.run(exercise.answer(), GRADE_LIMIT).lastRows();
            if (yours == null) {
                return new Grade(false, "沒有回傳任何資料列。這題要用 RETURNING（或最後再 SELECT 一次）把結果回傳出來。",
                        null, expected.rowCount(), null, null);
            }
        } else {
            yours = queryService.run(userSql, GRADE_LIMIT);
            expected = queryService.run(exercise.answer(), GRADE_LIMIT);
        }
        QueryResult shown = forDisplay(yours);
        int expectedRows = expected.rowCount();

        if (yours.truncated()) {
            return wrong("結果超過 " + GRADE_LIMIT + " 筆，正確答案只有 " + expectedRows + " 筆，檢查一下條件。",
                    shown, expectedRows, null);
        }
        if (yours.columns().size() != expected.columns().size()) {
            return wrong("欄位數量不同：你的結果有 " + yours.columns().size() + " 欄，題目要求 "
                    + expected.columns().size() + " 欄。", shown, expectedRows, null);
        }
        if (yours.rowCount() != expectedRows) {
            return wrong("筆數不同：你回傳 " + yours.rowCount() + " 筆，正確答案是 " + expectedRows + " 筆。",
                    shown, expectedRows, null);
        }

        List<Row> mine = rows(yours);
        List<Row> answer = rows(expected);
        if (!exercise.ordered()) {
            mine.sort(Comparator.comparing(Row::key));
            answer.sort(Comparator.comparing(Row::key));
        }

        int diff = firstDifference(mine, answer);
        if (diff < 0) {
            return new Grade(true, "完全正確！", shown, expectedRows, null, exercise.explanation());
        }

        Mismatch mismatch = new Mismatch(diff + 1, mine.get(diff).values(), answer.get(diff).values());
        if (exercise.ordered() && sameContent(mine, answer)) {
            return wrong("資料都對，但排列順序不對。看一下題目要求的排序。", shown, expectedRows, mismatch);
        }
        String where = exercise.ordered() ? "第 " + (diff + 1) + " 列的內容不同。" : "內容有差異（兩邊都先依內容排序後比對）。";
        return wrong(where, shown, expectedRows, mismatch);
    }

    private static Grade wrong(String message, QueryResult shown, int expectedRows, Mismatch mismatch) {
        return new Grade(false, message, shown, expectedRows, mismatch, null);
    }

    /** 批改用完整結果，回給前端時只留前 500 筆。 */
    private static QueryResult forDisplay(QueryResult r) {
        int max = 500;
        if (r.rows().size() <= max) {
            return r;
        }
        return new QueryResult(r.columns(), r.rows().subList(0, max), max, true, r.elapsedMs());
    }

    private static int firstDifference(List<Row> a, List<Row> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).key().equals(b.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameContent(List<Row> a, List<Row> b) {
        List<String> ka = new ArrayList<>(a.stream().map(Row::key).toList());
        List<String> kb = new ArrayList<>(b.stream().map(Row::key).toList());
        ka.sort(null);
        kb.sort(null);
        return ka.equals(kb);
    }

    // ---------- 正規化：讓 9.8 與 9.80、2022 與 2022.0 視為相同 ----------

    private record Row(List<Object> values, String key) {
    }

    private static List<Row> rows(QueryResult r) {
        List<Row> list = new ArrayList<>(r.rows().size());
        for (List<Object> values : r.rows()) {
            StringBuilder key = new StringBuilder();
            for (Object v : values) {
                key.append(normalize(v)).append('\u0001');
            }
            list.add(new Row(values, key.toString()));
        }
        return list;
    }

    private static String normalize(Object v) {
        if (v == null) {
            return "\u0000NULL";
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return v.toString();
    }
}
