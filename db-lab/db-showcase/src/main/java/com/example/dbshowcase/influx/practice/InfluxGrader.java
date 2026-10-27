package com.example.dbshowcase.influx.practice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.influx.InfluxClient;
import com.example.dbshowcase.influx.InfluxDb;
import com.example.dbshowcase.influx.InfluxShell;
import com.example.dbshowcase.influx.InfluxShell.Block;
import com.example.dbshowcase.influx.InfluxShell.Lang;
import com.example.dbshowcase.influx.InfluxShell.RunResult;

/**
 * 批改 InfluxDB 練習題。
 * InfluxQL：每個 series 的每一列依「位置」比對（欄位名稱、別名不比對），measurement 與 tags 也要一樣。
 * Flux：只比對標準答案有的欄位（依名稱；_start、_stop 不比對，多出來的欄位不影響），所有表的列攤平後比對。
 * write：清空 scratch → 寫入 → 用 check 查詢比對資料。數字四捨五入到小數 4 位。
 */
@Service
public class InfluxGrader {

    private static final Set<String> FLUX_IGNORE = Set.of("_start", "_stop");

    public record Mismatch(String what, Object yours, Object expected) {
    }

    public record Grade(boolean correct, String message, RunResult result, RunResult check, Mismatch mismatch, String explanation) {
    }

    private record Attempt(RunResult run, RunResult check) {
        RunResult graded() {
            return check != null ? check : run;
        }
    }

    private final InfluxShell shell;
    private final InfluxDb db;

    public InfluxGrader(InfluxShell shell, InfluxDb db) {
        this.shell = shell;
        this.db = db;
    }

    public Grade grade(InfluxExercise ex, String code) {
        Attempt yours = attempt(ex, code);
        Attempt expected = attempt(ex, ex.answer());
        for (Block b : yours.run().blocks()) {
            if (b.kind().equals("error")) {
                return wrong(yours, "執行失敗：" + b.message(), null);
            }
        }
        RunResult y = yours.graded(), e = expected.graded();
        for (Block b : e.blocks()) {
            if (b.kind().equals("error")) {
                throw new IllegalStateException("標準答案執行失敗：" + b.message());
            }
        }
        boolean flux = y.lang() == Lang.FLUX;
        List<String> ys, es;
        if (flux) {
            List<String> cols = fluxColumns(e);
            ys = new ArrayList<>();
            for (Block b : y.blocks()) {
                for (String c : cols) {
                    if (b.kind().equals("table") && !b.columns().contains(c)) {
                        return wrong(yours, "結果少了欄位「" + c + "」。", new Mismatch("欄位", b.columns(), cols));
                    }
                }
            }
            ys = fluxRows(y, cols);
            es = fluxRows(e, cols);
        } else {
            ys = influxqlRows(y);
            es = influxqlRows(e);
        }
        String prefix = ex.isWrite() ? "寫入之後的資料不對（用檢查查詢比對）：" : "";
        if (ys.size() != es.size()) {
            return wrong(yours, prefix + "筆數不同：你的結果有 " + ys.size() + " 列，正確答案是 " + es.size() + " 列。",
                    new Mismatch("前幾列", head(ys), head(es)));
        }
        List<String> ya = new ArrayList<>(ys), ea = new ArrayList<>(es);
        if (!ex.isOrdered()) {
            ya.sort(null);
            ea.sort(null);
        }
        for (int i = 0; i < ya.size(); i++) {
            if (!ya.get(i).equals(ea.get(i))) {
                List<String> ys2 = new ArrayList<>(ys), es2 = new ArrayList<>(es);
                ys2.sort(null);
                es2.sort(null);
                if (ex.isOrdered() && ys2.equals(es2)) {
                    return wrong(yours, prefix + "資料都對，但順序不對。", new Mismatch("第 " + (i + 1) + " 列", ya.get(i), ea.get(i)));
                }
                return wrong(yours, prefix + "第 " + (i + 1) + " 列不同" + (ex.isOrdered() ? "。" : "（兩邊都先排序後比對）。"),
                        new Mismatch("第 " + (i + 1) + " 列", ya.get(i), ea.get(i)));
            }
        }
        return new Grade(true, "完全正確！", yours.run(), yours.check(), null, ex.explanation());
    }

    private Attempt attempt(InfluxExercise ex, String code) {
        if (!ex.isWrite()) {
            Lang lang = Lang.valueOf(ex.lang().toUpperCase());
            return new Attempt(shell.run(InfluxClient.User.READER, lang, code, InfluxDb.BUCKET), null);
        }
        synchronized (db) {
            try {
                db.clearScratch();
                RunResult run = shell.run(InfluxClient.User.LEARNER, Lang.WRITE, code, InfluxDb.SCRATCH);
                Lang checkLang = Lang.valueOf((ex.checkLang() == null ? "influxql" : ex.checkLang()).toUpperCase());
                RunResult check = shell.run(InfluxClient.User.ADMIN, checkLang, ex.check(), InfluxDb.SCRATCH);
                return new Attempt(run, check);
            } finally {
                db.clearScratch();
            }
        }
    }

    private static Grade wrong(Attempt a, String message, Mismatch mismatch) {
        return new Grade(false, message, a.run(), a.check(), mismatch, null);
    }

    // ---------------------------------------------------------------- 攤平成可以比較的字串

    private static List<String> influxqlRows(RunResult r) {
        List<String> out = new ArrayList<>();
        for (Block b : r.blocks()) {
            if (!b.kind().equals("table")) {
                continue;
            }
            for (List<Object> row : b.rows()) {
                StringBuilder s = new StringBuilder(b.title() == null ? "" : b.title()).append(" | ");
                row.forEach(v -> s.append(norm(v)).append(" | "));
                out.add(s.toString());
            }
        }
        return out;
    }

    private static List<String> fluxColumns(RunResult r) {
        List<String> cols = new ArrayList<>();
        for (Block b : r.blocks()) {
            for (String c : b.columns()) {
                if (!FLUX_IGNORE.contains(c) && !cols.contains(c)) {
                    cols.add(c);
                }
            }
        }
        return cols;
    }

    private static List<String> fluxRows(RunResult r, List<String> cols) {
        List<String> out = new ArrayList<>();
        for (Block b : r.blocks()) {
            if (!b.kind().equals("table")) {
                continue;
            }
            for (List<Object> row : b.rows()) {
                StringBuilder s = new StringBuilder();
                for (String c : cols) {
                    int i = b.columns().indexOf(c);
                    s.append(c).append('=').append(i < 0 ? "" : norm(row.get(i))).append(" | ");
                }
                out.add(s.toString());
            }
        }
        return out;
    }

    private static String norm(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return v.toString();
    }

    private static List<String> head(List<String> rows) {
        return rows.subList(0, Math.min(5, rows.size()));
    }
}
