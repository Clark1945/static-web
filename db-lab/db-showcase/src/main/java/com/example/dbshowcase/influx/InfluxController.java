package com.example.dbshowcase.influx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

/** InfluxDB 總覽、主控台、重新載入：/api/influx/… */
@RestController
@RequestMapping("/api/influx")
public class InfluxController {

    public record RunRequest(String lang, String code) {
    }

    public record Measurement(String name, String design, List<String> tags, List<String> fields, long series, String sample) {
    }

    public record Overview(List<Measurement> measurements, String version, InfluxDb.Status load) {
    }

    private static final Map<String, String[]> DESIGN = Map.of(
            "cpu", new String[] {"5 台主機的 CPU 使用率，每分鐘一筆。db-01 在 9/18 14:00～14:40 滿載；web-03 在 9/20 12:00 下線",
                    "SELECT * FROM cpu WHERE host = 'db-01' AND time >= '2026-09-18T14:00:00+08:00' LIMIT 5"},
            "mem", new String[] {"同樣 5 台主機的記憶體使用率。web-02 有記憶體洩漏，9/15 03:00 重啟後歸位",
                    "SELECT last(used_percent) FROM mem GROUP BY host"},
            "http", new String[] {"API 請求的累計計數器（只會增加），每分鐘回報一次。api 服務在 9/15 03:00 重啟，計數器從 0 開始",
                    "SELECT requests, errors FROM http WHERE service = 'api' LIMIT 5"},
            "sensors", new String[] {"倉庫溫溼度感測器，每 5 分鐘一筆（跟 TimescaleDB 同一個情境）。2 號 9/18 下午升溫，5 號 9/10 斷線 4 小時",
                    "SELECT mean(temperature) FROM sensors WHERE time >= '2026-09-18T00:00:00+08:00' AND time < '2026-09-19T00:00:00+08:00' GROUP BY sensor_id"},
            "orders", new String[] {"訂單（從 PostgreSQL 複製，2022～2026 年）。city、status、vip_level 是 tag；order_id、customer_id 是 field",
                    "SELECT * FROM orders ORDER BY time DESC LIMIT 5"});

    private final InfluxClient client;
    private final InfluxShell shell;
    private final InfluxDb db;

    public InfluxController(InfluxClient client, InfluxShell shell, InfluxDb db) {
        this.client = client;
        this.shell = shell;
        this.db = db;
    }

    @GetMapping("/overview")
    public Overview overview() {
        List<Measurement> list = new ArrayList<>();
        String version = null;
        try {
            version = client.send(InfluxClient.User.ADMIN, "GET", "/health", null, null, "application/json").body()
                    .replaceAll("(?s).*\"version\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            for (String m : InfluxDb.MEASUREMENTS) {
                List<String> tags = column(client.influxql(InfluxClient.User.ADMIN, InfluxDb.BUCKET, "SHOW TAG KEYS FROM " + m), 0, null);
                List<String> fields = column(client.influxql(InfluxClient.User.ADMIN, InfluxDb.BUCKET, "SHOW FIELD KEYS FROM " + m), 0, 1);
                List<String> series = column(client.influxql(InfluxClient.User.ADMIN, InfluxDb.BUCKET, "SHOW SERIES EXACT CARDINALITY FROM " + m), 0, null);
                String[] d = DESIGN.get(m);
                list.add(new Measurement(m, d[0], tags, fields, series.isEmpty() ? 0 : Long.parseLong(series.get(0)), d[1]));
            }
        } catch (IllegalStateException e) {
            // 容器沒啟動或 token 還沒建立：回傳空的總覽，頁面顯示載入狀態
        }
        return new Overview(list, version, db.status());
    }

    /** 取 InfluxQL 結果第一個 series 的某一欄（second 不是 null 時串成「欄 1（欄 2）」）。 */
    private List<String> column(InfluxClient.Response r, int first, Integer second) {
        List<String> out = new ArrayList<>();
        JsonNode values = client.parse(r.body()).path("results").path(0).path("series").path(0).path("values");
        for (JsonNode v : values) {
            out.add(v.path(first).asText() + (second == null ? "" : "（" + v.path(second).asText() + "）"));
        }
        return out;
    }

    @GetMapping("/load")
    public InfluxDb.Status load() {
        return db.status();
    }

    @PostMapping("/reset")
    public InfluxDb.Status reset() {
        return db.reload();
    }

    /** 主控台：用 learner token 執行（metrics 唯讀；scratch 可以寫入、查詢）。InfluxQL 預設查 metrics，寫 FROM scratch..xxx 或在主控台切換資料庫。 */
    @PostMapping("/run")
    public InfluxShell.RunResult run(@RequestBody RunRequest request) {
        return shell.forDisplay(shell.run(InfluxClient.User.LEARNER, lang(request.lang()), request.code(), InfluxDb.BUCKET));
    }

    static InfluxShell.Lang lang(String s) {
        try {
            return InfluxShell.Lang.valueOf(s == null ? "INFLUXQL" : s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("lang 只能是 influxql、flux、write");
        }
    }
}
