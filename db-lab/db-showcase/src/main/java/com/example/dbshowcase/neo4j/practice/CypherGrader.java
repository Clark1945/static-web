package com.example.dbshowcase.neo4j.practice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.neo4j.CypherShell;
import com.example.dbshowcase.neo4j.CypherShell.Outcome;
import com.example.dbshowcase.neo4j.CypherShell.RunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批改 Neo4j 練習題。
 * 你的 Cypher 與標準答案各自在一個交易裡執行，最後都 ROLLBACK，所以寫入題不會改到資料。
 * 查詢題比對最後一句的結果；寫入題在同一個交易裡接著執行 check 查詢，比對資料狀態。
 * 比對的是每一列的值（依欄位位置），欄位名稱不算（可以用別名）；整數與小數視為相同（1 等於 1.0）。
 */
@Service
public class CypherGrader {

    private static final int GRADE_LIMIT = 5000;

    /** yours / expected 是一列（欄位 → 值）或幾列的清單，給前端顯示。 */
    public record Mismatch(String what, Object yours, Object expected) {
    }

    public record Grade(boolean correct, String message, RunResult result, List<Outcome> checks,
                        Mismatch mismatch, String explanation) {
    }

    private record Attempt(RunResult run, List<Outcome> checks) {
    }

    private final CypherShell shell;
    private final ObjectMapper json;

    public CypherGrader(CypherShell shell, ObjectMapper json) {
        this.shell = shell;
        this.json = json;
    }

    public Grade grade(CypherExercise ex, String script) {
        Attempt yours = attempt(ex, script);
        Attempt expected = attempt(ex, ex.answer());
        Outcome last = yours.run().last();
        if (last.kind().equals("error")) {
            return wrong(yours, "執行出錯了：" + last.message(), null);
        }

        if (ex.check() != null && !ex.check().isBlank()) {
            for (int i = 0; i < expected.checks().size(); i++) {
                Outcome y = i < yours.checks().size() ? yours.checks().get(i) : null;
                Outcome e = expected.checks().get(i);
                if (y == null || !same(y.rows(), e.rows(), true)) {
                    return wrong(yours, "執行完之後的資料不對：檢查用的「" + e.statement() + "」結果不同。",
                            new Mismatch("檢查結果", y == null ? null : rows(y, y.rows()), rows(e, e.rows())));
                }
            }
            return right(yours, ex);
        }

        Outcome exp = expected.run().last();
        if (!exp.kind().equals("rows")) {
            return right(yours, ex);
        }
        if (!last.kind().equals("rows")) {
            return wrong(yours, "最後一句要是查詢（SELECT），才能比對結果。", null);
        }
        if (last.columns().size() != exp.columns().size()) {
            return wrong(yours, "欄位數不同：你回傳 " + last.columns().size() + " 欄（" + String.join(", ", last.columns())
                    + "），正確答案是 " + exp.columns().size() + " 欄（" + String.join(", ", exp.columns()) + "）。",
                    new Mismatch("第 1 列", row(last, 0, last.rows()), row(exp, 0, exp.rows())));
        }
        if (!last.total().equals(exp.total())) {
            return wrong(yours, "筆數不同：你回傳 " + last.total() + " 列，正確答案是 " + exp.total() + " 列。",
                    new Mismatch("前幾列", rows(last, head(last.rows())), rows(exp, head(exp.rows()))));
        }
        if (!same(last.rows(), exp.rows(), ex.isOrdered())) {
            int i = firstDifference(last.rows(), exp.rows(), ex.isOrdered());
            if (ex.isOrdered() && same(last.rows(), exp.rows(), false)) {
                return wrong(yours, "資料都對，但順序不對。看一下題目要求的排序（ORDER BY）。",
                        new Mismatch("第 " + (i + 1) + " 列", row(last, i, last.rows()), row(exp, i, exp.rows())));
            }
            return wrong(yours, "第 " + (i + 1) + " 列的內容不同" + (ex.isOrdered() ? "。" : "（兩邊都先排序後比對）。"),
                    new Mismatch("第 " + (i + 1) + " 列", row(last, i, raw(last.rows(), ex.isOrdered())),
                            row(exp, i, raw(exp.rows(), ex.isOrdered()))));
        }
        return right(yours, ex);
    }

    private Attempt attempt(CypherExercise ex, String script) {
        RunResult run = shell.run(script, ex.check(), GRADE_LIMIT, false);
        return new Attempt(run, run.checks());
    }

    private static Grade right(Attempt a, CypherExercise ex) {
        return new Grade(true, "完全正確！", a.run(), a.checks(), null, ex.explanation());
    }

    private static Grade wrong(Attempt a, String message, Mismatch mismatch) {
        return new Grade(false, message, a.run(), a.checks(), mismatch, null);
    }

    // ---------------------------------------------------------------- 比對

    private boolean same(Object a, Object b, boolean ordered) {
        return key(sorted(a, ordered)).equals(key(sorted(b, ordered)));
    }

    private Object sorted(Object v, boolean ordered) {
        Object n = normalize(v);
        if (!ordered && n instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            copy.sort(Comparator.comparing(this::key));
            return copy;
        }
        return n;
    }

    /** 跟 sorted() 同樣的順序，但保留原始值（顯示用）。 */
    private List<List<Object>> raw(List<List<Object>> rows, boolean ordered) {
        if (!ordered) {
            List<List<Object>> copy = new ArrayList<>(rows);
            copy.sort(Comparator.comparing(o -> key(normalize(o))));
            return copy;
        }
        return rows;
    }

    private int firstDifference(Object a, Object b, boolean ordered) {
        List<?> x = (List<?>) sorted(a, ordered), y = (List<?>) sorted(b, ordered);
        for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
            if (!key(x.get(i)).equals(key(y.get(i)))) {
                return i;
            }
        }
        return Math.min(x.size(), y.size());
    }

    /** map 依 key 排序、數字統一成字串，讓 1 與 1.0、int 與 bigint 視為相同。 */
    private Object normalize(Object v) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(k), normalize(val)));
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                out.add(normalize(o));
            }
            return out;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return v;
    }

    private String key(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            return String.valueOf(v);
        }
    }

    // ---------------------------------------------------------------- 顯示

    private static List<List<Object>> head(List<List<Object>> rows) {
        return rows == null ? List.of() : rows.subList(0, Math.min(3, rows.size()));
    }

    /** 一列轉成「欄位 → 值」。 */
    private static Map<String, Object> row(Outcome r, int i, List<List<Object>> rows) {
        if (rows == null || i >= rows.size()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int c = 0; c < r.columns().size(); c++) {
            out.put(r.columns().get(c), rows.get(i).get(c));
        }
        return out;
    }

    private static List<Map<String, Object>> rows(Outcome r, List<List<Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (r.kind().equals("rows") && rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                out.add(row(r, i, rows));
            }
        }
        return out;
    }
}
