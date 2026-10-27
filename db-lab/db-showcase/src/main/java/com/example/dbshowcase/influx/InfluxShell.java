package com.example.dbshowcase.influx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 執行使用者輸入的 InfluxQL、Flux 或 line protocol，結果統一成「區塊」：表格、錯誤或訊息。
 * 權限由 token 把關（reader 只能讀 metrics；learner 另外可以讀寫 scratch）。
 * Flux 可以 import "sql"、"http" 這類套件連到外部系統，token 管不到，所以 import 只允許白名單內的套件。
 */
@Component
public class InfluxShell {

    public enum Lang { INFLUXQL, FLUX, WRITE }

    private static final Set<String> FLUX_IMPORTS = Set.of("timezone", "date", "strings", "math", "regexp", "array", "join",
            "interpolate", "influxdata/influxdb", "influxdata/influxdb/schema", "influxdata/influxdb/v1", "experimental",
            "experimental/aggregate", "experimental/array", "universe", "types", "dict");
    private static final Pattern IMPORT = Pattern.compile("import\\s+\"([^\"]+)\"");
    /** 顯示時每個區塊最多幾列（批改用完整結果）。 */
    private static final int DISPLAY_ROWS = 100;

    /**
     * 一個結果區塊。kind：table / error / info。title：InfluxQL 是 measurement 與 tags，Flux 是表的分組欄位值。
     */
    public record Block(String statement, String kind, String title, List<String> columns, List<List<Object>> rows,
                        int total, String message, double millis) {
    }

    public record RunResult(Lang lang, List<Block> blocks, double totalMillis) {
    }

    private final InfluxClient client;

    public InfluxShell(InfluxClient client) {
        this.client = client;
    }

    public RunResult run(InfluxClient.User user, Lang lang, String text, String db) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("請輸入內容。");
        }
        return switch (lang) {
            case INFLUXQL -> influxql(user, text, db);
            case FLUX -> flux(user, text);
            case WRITE -> write(user, text);
        };
    }

    // ---------------------------------------------------------------- InfluxQL

    private RunResult influxql(InfluxClient.User user, String text, String db) {
        String q = text.replaceAll("(?m)^\\s*(--|#).*$", "").strip();
        List<String> statements = new ArrayList<>();
        for (String s : q.split(";")) {
            if (!s.isBlank()) {
                statements.add(s.strip());
            }
        }
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("請輸入 InfluxQL，例如：SELECT * FROM cpu LIMIT 5");
        }
        InfluxClient.Response r = client.influxql(user, db, q);
        JsonNode body = client.parse(r.body());
        List<Block> blocks = new ArrayList<>();
        if (!r.ok() && body.has("error")) {
            blocks.add(error(statements.get(0), body.path("error").asText(), r.millis()));
            return new RunResult(Lang.INFLUXQL, blocks, r.millis());
        }
        for (JsonNode res : body.path("results")) {
            int id = res.path("statement_id").asInt();
            String stmt = id < statements.size() ? statements.get(id) : "第 " + (id + 1) + " 句";
            if (res.has("error")) {
                blocks.add(error(stmt, res.path("error").asText(), r.millis()));
                continue;
            }
            if (!res.has("series")) {
                blocks.add(new Block(stmt, "info", null, List.of(), List.of(), 0, "完成，沒有回傳資料", r.millis()));
                continue;
            }
            for (JsonNode s : res.path("series")) {
                List<String> cols = new ArrayList<>();
                s.path("columns").forEach(c -> cols.add(c.asText()));
                List<List<Object>> rows = new ArrayList<>();
                for (JsonNode v : s.path("values")) {
                    List<Object> row = new ArrayList<>();
                    v.forEach(x -> row.add(value(x)));
                    rows.add(row);
                }
                StringBuilder title = new StringBuilder(s.path("name").asText(""));
                Map<String, String> tags = new TreeMap<>();
                s.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
                if (!tags.isEmpty()) {
                    title.append("  ").append(tags.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
                }
                blocks.add(new Block(stmt, "table", title.toString(), cols, rows, rows.size(), null, r.millis()));
            }
        }
        return new RunResult(Lang.INFLUXQL, blocks, r.millis());
    }

    private static Object value(JsonNode x) {
        if (x.isNull()) {
            return null;
        }
        if (x.isIntegralNumber()) {
            return x.asLong();
        }
        if (x.isNumber()) {
            return x.asDouble();
        }
        if (x.isBoolean()) {
            return x.asBoolean();
        }
        return x.asText();
    }

    // ---------------------------------------------------------------- Flux

    private RunResult flux(InfluxClient.User user, String text) {
        Matcher m = IMPORT.matcher(text);
        while (m.find()) {
            if (!FLUX_IMPORTS.contains(m.group(1))) {
                throw new IllegalArgumentException("展示台不允許 import \"" + m.group(1) + "\"（可能連到外部系統）。可以用的套件：" + new java.util.TreeSet<>(FLUX_IMPORTS));
            }
        }
        InfluxClient.Response r = client.flux(user, text);
        List<Block> blocks = new ArrayList<>();
        if (!r.ok()) {
            JsonNode body = client.parse(r.body());
            blocks.add(error("Flux", body.path("message").asText(r.body()), r.millis()));
            return new RunResult(Lang.FLUX, blocks, r.millis());
        }
        for (InfluxClient.FluxTable t : InfluxClient.parseCsv(r.body())) {
            // 表的「分組鍵」：每一列都一樣的 tag 欄位，當作標題
            List<String> key = new ArrayList<>();
            for (int i = 0; i < t.columns().size(); i++) {
                String c = t.columns().get(i);
                if (c.startsWith("_") || t.rows().isEmpty()) {
                    continue;
                }
                Object first = t.rows().get(0).get(i);
                final int col = i;
                if (t.rows().stream().allMatch(row -> java.util.Objects.equals(row.get(col), first))) {
                    key.add(c + "=" + first);
                }
            }
            String title = (t.result().isEmpty() || t.result().equals("_result") ? "" : t.result() + " · ") + "table " + t.table() + (key.isEmpty() ? "" : "  " + key);
            blocks.add(new Block("Flux", "table", title, t.columns(), t.rows(), t.rows().size(), null, r.millis()));
        }
        if (blocks.isEmpty()) {
            blocks.add(new Block("Flux", "info", null, List.of(), List.of(), 0, "沒有回傳任何資料表（範圍內沒有資料）", r.millis()));
        }
        return new RunResult(Lang.FLUX, blocks, r.millis());
    }

    // ---------------------------------------------------------------- line protocol

    /** 寫入 scratch bucket，時間戳記的單位是秒（precision=s）；# 開頭是註解。 */
    private RunResult write(InfluxClient.User user, String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.replace("\r", "").split("\n")) {
            if (!l.isBlank() && !l.strip().startsWith("#")) {
                lines.add(l.strip());
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("請輸入 line protocol，例如：sensors,sensor_id=9 temperature=4.2 1790726400");
        }
        InfluxClient.Response r = client.write(user, "scratch", "s", String.join("\n", lines));
        List<Block> blocks = new ArrayList<>();
        if (r.ok()) {
            blocks.add(new Block("寫入 scratch", "info", null, List.of(), List.of(), lines.size(),
                    "寫入 " + lines.size() + " 行（HTTP " + r.status() + "）", r.millis()));
        } else {
            JsonNode body = client.parse(r.body());
            blocks.add(error("寫入 scratch", body.path("message").asText(r.body()), r.millis()));
        }
        return new RunResult(Lang.WRITE, blocks, r.millis());
    }

    private static Block error(String stmt, String message, double millis) {
        return new Block(stmt, "error", null, List.of(), List.of(), 0, message, millis);
    }

    /** 顯示用：每個區塊最多 100 列，區塊最多 30 個。 */
    public RunResult forDisplay(RunResult run) {
        List<Block> out = new ArrayList<>();
        Iterator<Block> it = run.blocks().iterator();
        while (it.hasNext() && out.size() < 30) {
            Block b = it.next();
            out.add(b.rows().size() <= DISPLAY_ROWS ? b
                    : new Block(b.statement(), b.kind(), b.title(), b.columns(), b.rows().subList(0, DISPLAY_ROWS), b.total(), b.message(), b.millis()));
        }
        if (run.blocks().size() > 30) {
            out.add(new Block("", "info", null, List.of(), List.of(), 0, "共 " + run.blocks().size() + " 個表，只顯示前 30 個", 0));
        }
        return new RunResult(run.lang(), out, run.totalMillis());
    }
}
