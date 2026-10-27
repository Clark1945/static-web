package com.example.dbshowcase.mongo.practice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.mongo.MongoShell.Result;
import com.example.dbshowcase.mongo.MongoShell.RunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批改 MongoDB 練習題。
 * 查詢題：用 reader 在 shop 執行你的指令與標準答案，比對最後一句的結果。
 * 寫入題：在兩份乾淨的 scratch 副本分別執行，再用 check 指令比對資料狀態。
 * 比對時文件欄位的順序不算（{a, b} 等於 {b, a}），數字型別不算（int 1 等於 long 1 等於 1.0）。
 */
@Service
public class MongoGrader {

    private static final int GRADE_LIMIT = 5000;

    public record Mismatch(String what, Object yours, Object expected) {
    }

    public record Grade(boolean correct, String message, RunResult result, List<Result> checks,
                        Mismatch mismatch, String explanation) {
    }

    private record Attempt(RunResult run, List<Result> checks) {
    }

    private final MongoScratch scratch;
    private final ObjectMapper json;

    public MongoGrader(MongoScratch scratch, ObjectMapper json) {
        this.scratch = scratch;
        this.json = json;
    }

    public Grade grade(MongoExercise ex, String script) {
        Attempt yours = attempt(ex, script);
        Attempt expected = attempt(ex, ex.answer());
        Result last = yours.run().last();
        if (last.kind().equals("error")) {
            return wrong(yours, "執行出錯了：" + last.value(), null);
        }

        if (ex.check() != null && !ex.check().isBlank()) {
            for (int i = 0; i < expected.checks().size(); i++) {
                Result y = yours.checks().get(i), e = expected.checks().get(i);
                if (!same(y.value(), e.value(), true)) {
                    return wrong(yours, "執行完之後的資料不對：檢查指令「" + e.statement() + "」的結果不同。",
                            new Mismatch(e.statement(), y.value(), e.value()));
                }
            }
            return right(yours, ex);
        }

        Result exp = expected.run().last();
        if (exp.kind().equals("docs") && last.kind().equals("docs") && !last.total().equals(exp.total())) {
            return wrong(yours, "筆數不同：你回傳 " + last.total() + " 份文件，正確答案是 " + exp.total() + " 份。",
                    new Mismatch("前幾份文件", head(last.value()), head(exp.value())));
        }
        if (!same(last.value(), exp.value(), ex.isOrdered())) {
            if (exp.kind().equals("docs") && last.kind().equals("docs")) {
                int i = firstDifference(last.value(), exp.value(), ex.isOrdered());
                if (ex.isOrdered() && same(last.value(), exp.value(), false)) {
                    return wrong(yours, "文件都對，但順序不對。看一下題目要求的排序。",
                            new Mismatch("第 " + (i + 1) + " 份文件", item(last.value(), i), item(exp.value(), i)));
                }
                return wrong(yours, "第 " + (i + 1) + " 份文件的內容不同" + (ex.isOrdered() ? "。" : "（兩邊都先排序後比對）。"),
                        new Mismatch("第 " + (i + 1) + " 份文件", item(raw(last.value(), ex.isOrdered()), i), item(raw(exp.value(), ex.isOrdered()), i)));
            }
            return wrong(yours, "回傳值不同。", new Mismatch("最後一句的回傳值", last.value(), exp.value()));
        }
        return right(yours, ex);
    }

    private Attempt attempt(MongoExercise ex, String script) {
        if (!ex.isWrite()) {
            return new Attempt(scratch.runAsReader(script, GRADE_LIMIT), List.of());
        }
        return scratch.use(ex.fixture(), () -> {
            RunResult run = scratch.runAsLearner(script, GRADE_LIMIT);
            List<Result> checks = ex.check() == null || ex.check().isBlank()
                    ? List.of() : scratch.runAsAdmin(ex.check(), GRADE_LIMIT).results();
            return new Attempt(run, checks);
        });
    }

    private static Grade right(Attempt a, MongoExercise ex) {
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
    private Object raw(Object v, boolean ordered) {
        if (!ordered && v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            copy.sort(Comparator.comparing(o -> key(normalize(o))));
            return copy;
        }
        return v;
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

    /** 欄位依名稱排序、數字統一成字串，讓 {a:1, b:2} 與 {b:2.0, a:1} 視為相同。 */
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

    private static Object head(Object v) {
        return v instanceof List<?> list ? list.subList(0, Math.min(3, list.size())) : v;
    }

    private static Object item(Object v, int i) {
        return v instanceof List<?> list && i < list.size() ? list.get(i) : null;
    }
}
