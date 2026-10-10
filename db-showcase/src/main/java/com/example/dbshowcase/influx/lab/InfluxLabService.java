package com.example.dbshowcase.influx.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.common.YamlContent;
import com.example.dbshowcase.influx.InfluxClient;
import com.example.dbshowcase.influx.InfluxDb;
import com.example.dbshowcase.influx.InfluxShell;
import com.example.dbshowcase.influx.InfluxShell.Lang;
import com.example.dbshowcase.influx.InfluxShell.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * InfluxDB 實驗室：
 * 1. series 數量（cardinality）：同樣的事件，user_id 當 tag 與當 field 各寫一份到 scratch，比較 series 數量與查詢
 * 2. InfluxQL vs Flux：同一個問題兩種寫法（compare-lab.yml，唯讀）
 * 3. 降低精度（downsampling）：用 Flux 的 to() 把每分鐘的 CPU 彙總成每小時寫進 scratch，再比較查詢
 */
@Service
public class InfluxLabService {

    public record Step(String id, String title, String goal, String influxql, String flux, String question, String takeaway) {
    }

    public record Timed(String label, String lang, String code, RunResult result, double millis) {
    }

    public record Cardinality(int points, int users, List<Timed> runs) {
    }

    public record Downsample(String code, RunResult written, List<Timed> runs) {
    }

    private final InfluxShell shell;
    private final InfluxClient client;
    private final InfluxDb db;
    private final List<Step> steps;

    public InfluxLabService(InfluxShell shell, InfluxClient client, InfluxDb db, ObjectMapper json) {
        this.shell = shell;
        this.client = client;
        this.db = db;
        this.steps = YamlContent.load("influx/compare-lab.yml", Step.class, json);
    }

    // ================================================================ 1. cardinality

    public Cardinality cardinality(int users) {
        int u = Math.max(10, Math.min(5000, users));
        int perUser = 20;
        synchronized (db) {
            try {
                db.clearScratch();
                Random r = new Random(42);
                long start = 1790726400L;                         // 2026-09-30 00:00 +08:00
                StringBuilder tag = new StringBuilder(), field = new StringBuilder();
                int n = 0;
                for (int i = 0; i < u * perUser; i++) {
                    int user = 1 + r.nextInt(u);
                    long ts = start + i;                           // 每秒一個事件，時間戳記不重複
                    int ms = 20 + r.nextInt(200);
                    tag.append("events_tag,page=home,user_id=").append(user).append(" latency_ms=").append(ms).append("i ").append(ts).append('\n');
                    field.append("events_field,page=home user_id=").append(user).append("i,latency_ms=").append(ms).append("i ").append(ts).append('\n');
                    n++;
                }
                write(tag.toString());
                write(field.toString());
                List<Timed> runs = new ArrayList<>();
                runs.add(run("series 數量：user_id 當 tag", Lang.INFLUXQL, "SHOW SERIES EXACT CARDINALITY FROM events_tag"));
                runs.add(run("series 數量：user_id 當 field", Lang.INFLUXQL, "SHOW SERIES EXACT CARDINALITY FROM events_field"));
                runs.add(run("找 user_id 42 的事件：tag（用索引）", Lang.INFLUXQL, "SELECT count(latency_ms) FROM events_tag WHERE user_id = '42'"));
                runs.add(run("找 user_id 42 的事件：field（逐筆比對）", Lang.INFLUXQL, "SELECT count(latency_ms) FROM events_field WHERE user_id = 42"));
                runs.add(run("每位使用者的平均延遲：tag 可以 GROUP BY", Lang.INFLUXQL, "SELECT mean(latency_ms) FROM events_tag GROUP BY user_id SLIMIT 3"));
                runs.add(run("每位使用者的平均延遲：field 不能 GROUP BY", Lang.INFLUXQL, "SELECT mean(latency_ms) FROM events_field GROUP BY user_id SLIMIT 3"));
                return new Cardinality(n, u, runs);
            } finally {
                db.clearScratch();
            }
        }
    }

    private void write(String lines) {
        InfluxClient.Response r = client.write(InfluxClient.User.ADMIN, InfluxDb.SCRATCH, "s", lines);
        if (!r.ok()) {
            throw new IllegalStateException("寫入 scratch 失敗：" + r.body());
        }
    }

    /** 跑兩次取第二次的時間（第一次可能還在載入快取）。 */
    private Timed run(String label, Lang lang, String code) {
        shell.run(InfluxClient.User.ADMIN, lang, code, InfluxDb.SCRATCH);
        RunResult r = shell.run(InfluxClient.User.ADMIN, lang, code, InfluxDb.SCRATCH);
        return new Timed(label, lang.name().toLowerCase(), code, shell.forDisplay(r), r.totalMillis());
    }

    // ================================================================ 2. InfluxQL vs Flux

    public List<Step> steps() {
        return steps;
    }

    public RunResult runReadOnly(String lang, String code) {
        Lang l = Lang.valueOf(lang.toUpperCase());
        if (l == Lang.WRITE) {
            throw new IllegalArgumentException("這裡只能查詢。");
        }
        return shell.forDisplay(shell.run(InfluxClient.User.READER, l, code, InfluxDb.BUCKET));
    }

    // ================================================================ 3. downsampling

    private static final String DOWNSAMPLE = """
            from(bucket: "metrics")
              |> range(start: 2026-09-01T00:00:00+08:00, stop: 2026-10-01T00:00:00+08:00)
              |> filter(fn: (r) => r._measurement == "cpu")
              |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
              |> set(key: "_measurement", value: "cpu_1h")
              |> to(bucket: "scratch")
              |> count()
              |> group()
              |> sum()""";

    public Downsample downsample() {
        synchronized (db) {
            try {
                db.clearScratch();
                RunResult written = shell.run(InfluxClient.User.LEARNER, Lang.FLUX, DOWNSAMPLE, InfluxDb.SCRATCH);
                List<Timed> runs = new ArrayList<>();
                runs.add(runOn("原始資料（每分鐘）：9 月每天每台主機的最大 CPU", Lang.INFLUXQL, InfluxDb.BUCKET, """
                        SELECT max(usage_user) FROM cpu
                        WHERE time >= '2026-09-01T00:00:00+08:00' AND time < '2026-10-01T00:00:00+08:00'
                        GROUP BY time(1d), host tz('Asia/Taipei')"""));
                runs.add(runOn("降低精度後（每小時平均）：同樣的查詢", Lang.INFLUXQL, InfluxDb.SCRATCH, """
                        SELECT max(usage_user) FROM cpu_1h
                        WHERE time >= '2026-09-01T00:00:00+08:00' AND time < '2026-10-01T00:00:00+08:00'
                        GROUP BY time(1d), host tz('Asia/Taipei')"""));
                runs.add(runOn("點數：原始資料", Lang.INFLUXQL, InfluxDb.BUCKET, "SELECT count(usage_user) FROM cpu"));
                runs.add(runOn("點數：降低精度後", Lang.INFLUXQL, InfluxDb.SCRATCH, "SELECT count(usage_user) FROM cpu_1h"));
                return new Downsample(DOWNSAMPLE, shell.forDisplay(written), runs);
            } finally {
                db.clearScratch();
            }
        }
    }

    private Timed runOn(String label, Lang lang, String database, String code) {
        shell.run(InfluxClient.User.ADMIN, lang, code, database);
        RunResult r = shell.run(InfluxClient.User.ADMIN, lang, code, database);
        return new Timed(label, lang.name().toLowerCase(), code, shell.forDisplay(r), r.totalMillis());
    }
}
