package com.example.dbshowcase.elastic.practice;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.elastic.EsClient;
import com.example.dbshowcase.elastic.EsShell;
import com.example.dbshowcase.elastic.EsShell.Result;
import com.example.dbshowcase.elastic.EsShell.RunResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批改 Elasticsearch 練習題，比對最後一個請求的回應（寫入題比對 check 請求的回應）：
 * - 搜尋：標準答案有 hits 時比對 hits 的 _id（順序依題目）；有 aggregations 時比對聚合結果。
 *   聚合的名稱不比對（自己取名），bucket 的 key、doc_count、數值比對；數字四捨五入到小數 4 位。
 * - _count：比對 count。
 * - 其他（GET _doc、_cat…）：比對整個回應，忽略 took、_shards、_seq_no 這類每次都不一樣的欄位。
 */
@Service
public class EsGrader {

    private static final Set<String> VOLATILE = Set.of("took", "_shards", "_seq_no", "_primary_term", "timed_out", "_version",
            "max_score", "_score", "key_as_string", "value_as_string", "from_as_string", "to_as_string", "meta",
            "doc_count_error_upper_bound", "sum_other_doc_count", "_展示台");
    /** 聚合結果裡「不是子聚合」的欄位名稱；其他的 key 都當成使用者自己取名的聚合。 */
    private static final Set<String> AGG_FIELDS = Set.of("key", "doc_count", "value", "values", "buckets", "hits", "count",
            "min", "max", "avg", "sum", "sum_of_squares", "variance", "std_deviation", "std_deviation_bounds", "from", "to",
            "bg_count", "score", "location", "keys", "total", "_id", "_index", "_source", "_nested", "sort", "fields");

    public record Mismatch(String what, Object yours, Object expected) {
    }

    public record Grade(boolean correct, String message, RunResult result, List<Result> checks,
                        Mismatch mismatch, String explanation) {
    }

    private record Attempt(RunResult run, List<Result> checks) {
    }

    private final EsShell shell;
    private final EsScratch scratch;
    private final EsClient es;
    private final ObjectMapper json;

    public EsGrader(EsShell shell, EsScratch scratch, EsClient es, ObjectMapper json) {
        this.shell = shell;
        this.scratch = scratch;
        this.es = es;
        this.json = json;
    }

    public Grade grade(EsExercise ex, String script) {
        Attempt yours = attempt(ex, script);
        Attempt expected = attempt(ex, ex.answer());
        for (Result r : yours.run().results()) {
            if (!r.ok()) {
                return wrong(yours, "「" + r.statement() + "」執行失敗（HTTP " + r.status() + "）：" + reason(r.response()), null);
            }
        }
        // 查詢題比對最後一個請求；寫入題比對每一個檢查請求
        List<Result> mine = yours.checks().isEmpty() ? List.of(yours.run().last()) : yours.checks();
        List<Result> answer = expected.checks().isEmpty() ? List.of(expected.run().last()) : expected.checks();
        for (int i = 0; i < answer.size(); i++) {
            if (!answer.get(i).ok()) {
                throw new IllegalStateException("標準答案執行失敗：" + answer.get(i).response());
            }
            Diff d = diff(mine.get(i), answer.get(i), ex.isOrdered());
            if (d != null) {
                String prefix = yours.checks().isEmpty() ? "" : "執行完之後的資料不對（檢查請求「" + answer.get(i).statement() + "」）：";
                return wrong(yours, prefix + d.message(), d.mismatch());
            }
        }
        return right(yours, ex);
    }

    private record Diff(String message, Mismatch mismatch) {
    }

    /** 比對兩個回應；一樣時回傳 null。 */
    private Diff diff(Result mineResult, Result answerResult, boolean ordered) {
        JsonNode y = mineResult.response(), e = answerResult.response();
        if (e.has("count") && !e.has("hits")) {
            return same(y.path("count"), e.path("count")) ? null
                    : new Diff("數量不同：你的是 " + y.path("count") + "，正確答案是 " + e.path("count") + "。",
                    new Mismatch("count", y.path("count"), e.path("count")));
        }
        if (e.has("hits")) {
            List<String> eIds = ids(e), yIds = ids(y);
            if (!eIds.isEmpty() || !e.has("aggregations")) {
                if (eIds.size() != yIds.size()) {
                    return new Diff("回傳的文件數不同：你回傳 " + yIds.size() + " 筆，正確答案是 " + eIds.size() + " 筆。",
                            new Mismatch("hits 的 _id", head(yIds), head(eIds)));
                }
                List<String> ys = new ArrayList<>(yIds), es2 = new ArrayList<>(eIds);
                ys.sort(null);
                es2.sort(null);
                if (!ys.equals(es2)) {
                    return new Diff("回傳的文件不同" + (ordered ? "。" : "（順序不拘，兩邊都先依 _id 排序後比對）。"),
                            new Mismatch("hits 的 _id", ordered ? yIds : ys, ordered ? eIds : es2));
                }
                if (ordered && !yIds.equals(eIds)) {
                    return new Diff("文件都對，但順序不對。看一下題目要求的排序。", new Mismatch("hits 的 _id", yIds, eIds));
                }
            }
            if (e.has("aggregations")) {
                Object ya = normalizeAgg(y.path("aggregations")), ea = normalizeAgg(e.path("aggregations"));
                if (!key(ya).equals(key(ea))) {
                    return new Diff("聚合結果不同（聚合的名稱不影響批改）。",
                            new Mismatch("aggregations", prune(y.path("aggregations")), prune(e.path("aggregations"))));
                }
            }
            return null;
        }
        if (!key(normalize(y)).equals(key(normalize(e)))) {
            return new Diff("回應內容不同。", new Mismatch(answerResult.statement(), prune(y), prune(e)));
        }
        return null;
    }

    private Attempt attempt(EsExercise ex, String script) {
        if (!ex.isWrite()) {
            return new Attempt(shell.run(EsClient.User.READER, script), List.of());
        }
        return scratch.use(ex.fixture(), () -> {
            RunResult run = shell.run(EsClient.User.LEARNER, script);
            es.send(EsClient.User.ADMIN, "POST", "/scratch*/_refresh", null);
            List<Result> checks = ex.check() == null || ex.check().isBlank()
                    ? List.of() : shell.run(EsClient.User.ADMIN, ex.check()).results();
            return new Attempt(run, checks);
        });
    }

    private static Grade right(Attempt a, EsExercise ex) {
        return new Grade(true, "完全正確！", a.run(), a.checks(), null, ex.explanation());
    }

    private static Grade wrong(Attempt a, String message, Mismatch mismatch) {
        return new Grade(false, message, a.run(), a.checks(), mismatch, null);
    }

    static String reason(JsonNode body) {
        JsonNode err = body.path("error");
        if (err.isTextual()) {
            return err.asText();
        }
        JsonNode root = err.path("root_cause").path(0);
        String r = root.path("reason").asText(err.path("reason").asText(""));
        return r.isEmpty() ? String.valueOf(body).substring(0, Math.min(300, String.valueOf(body).length())) : r;
    }

    // ---------------------------------------------------------------- 正規化

    private static List<String> ids(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode h : body.path("hits").path("hits")) {
            out.add(h.path("_id").asText());
        }
        return out;
    }

    private static List<String> head(List<String> ids) {
        return ids.subList(0, Math.min(10, ids.size()));
    }

    /** 聚合結果：使用者取的聚合名稱拿掉（同一層的子聚合改成依內容排序的清單），每次都不一樣的欄位拿掉。 */
    private Object normalizeAgg(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> out = new TreeMap<>();
            List<Object> aggs = new ArrayList<>();
            node.fields().forEachRemaining(f -> {
                if (VOLATILE.contains(f.getKey())) {
                    return;
                }
                if (f.getKey().equals("hits") && f.getValue().has("hits")) {
                    out.put("hits", ids(f.getValue().isObject() ? json.createObjectNode().set("hits", f.getValue()) : f.getValue()));
                } else if (AGG_FIELDS.contains(f.getKey()) || !f.getValue().isObject()) {
                    out.put(f.getKey(), normalizeAgg(f.getValue()));
                } else {
                    aggs.add(normalizeAgg(f.getValue()));
                }
            });
            if (!aggs.isEmpty()) {
                aggs.sort(Comparator.comparing(this::key));
                out.put("(aggs)", aggs);
            }
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(normalizeAgg(n)));
            return out;
        }
        return scalar(node);
    }

    private Object normalize(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> out = new TreeMap<>();
            node.fields().forEachRemaining(f -> {
                if (!VOLATILE.contains(f.getKey())) {
                    out.put(f.getKey(), normalize(f.getValue()));
                }
            });
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(normalize(n)));
            return out;
        }
        return scalar(node);
    }

    private static Object scalar(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue().setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    /** 顯示用：拿掉 took、_shards 這類欄位，方便比較。 */
    private JsonNode prune(JsonNode node) {
        if (node.isObject()) {
            var copy = json.createObjectNode();
            node.fields().forEachRemaining(f -> {
                if (!Set.of("took", "_shards", "timed_out", "_展示台").contains(f.getKey())) {
                    copy.set(f.getKey(), prune(f.getValue()));
                }
            });
            return copy;
        }
        return node;
    }

    private String key(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private boolean same(JsonNode a, JsonNode b) {
        return key(scalar(a)).equals(key(scalar(b)));
    }
}
