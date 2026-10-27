package com.example.dbshowcase.timescale;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.dbshowcase.common.YamlContent;
import com.example.dbshowcase.postgres.QueryResult;
import com.example.dbshowcase.postgres.SandboxService;
import com.example.dbshowcase.postgres.practice.Exercise;
import com.example.dbshowcase.postgres.practice.Grader;
import com.example.dbshowcase.postgres.practice.Trap;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TimescaleDB 的 API，全部掛在 /api/timescale 底下。
 * 練習題、陷阱題、/sql、/sandbox 的格式跟 /api/postgres 一樣，前端直接共用 PostgreSQL 的頁面模組。
 */
@RestController
@RequestMapping("/api/timescale")
public class TimescaleController {

    public record SqlRequest(String sql) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String sql, String explanation) {
    }

    public record TrapResult(boolean correct, int answer, String explanation, List<QueryResult> results) {
    }

    public record Column(String name, String type) {
    }

    public record Hypertable(String name, String design, String timeColumn, String chunkInterval, long chunks,
                             long approxRows, String size, String from, String to, boolean compression,
                             List<Column> columns, String sample) {
    }

    public record ContinuousAggregate(String name, String definition, boolean materializedOnly, String watermark,
                                      String size, String sample) {
    }

    public record Overview(List<Hypertable> hypertables, List<ContinuousAggregate> aggregates, String version,
                           TimescaleDb.Status load) {
    }

    private static final Map<String, String[]> DESIGN = Map.of(
            "page_views", new String[] {"商品瀏覽紀錄（點擊流），寫入量最大的表；每 7 天一個 chunk",
                    "SELECT * FROM page_views ORDER BY view_time DESC LIMIT 5"},
            "sensor_readings", new String[] {"倉庫溫濕度感測器，每分鐘一筆；每天一個 chunk",
                    "SELECT * FROM sensor_readings WHERE sensor_id = 3 ORDER BY time DESC LIMIT 5"},
            "orders", new String[] {"從 PostgreSQL 複製的訂單，以下單時間當時間軸；每 30 天一個 chunk",
                    "SELECT * FROM orders ORDER BY order_time DESC LIMIT 5"});

    private final TimescaleDb db;
    private final List<Exercise> exercises;
    private final List<Trap> traps;

    public TimescaleController(TimescaleDb db, ObjectMapper mapper) {
        this.db = db;
        this.exercises = YamlContent.load("timescale/exercises.yml", Exercise.class, mapper);
        this.traps = YamlContent.load("timescale/traps.yml", Trap.class, mapper);
    }

    @GetMapping("/overview")
    public Overview overview() {
        var jdbc = db.jdbc();
        List<Hypertable> tables = jdbc.query("""
                SELECT h.hypertable_name, d.column_name, d.time_interval::text AS chunk_interval, h.num_chunks,
                       h.compression_enabled,
                       approximate_row_count(format('%I', h.hypertable_name)::regclass) AS approx,
                       pg_size_pretty(hypertable_size(format('%I', h.hypertable_name)::regclass)) AS size
                FROM timescaledb_information.hypertables h
                JOIN timescaledb_information.dimensions d ON d.hypertable_name = h.hypertable_name AND d.dimension_number = 1
                WHERE h.hypertable_schema = 'public' AND h.hypertable_name NOT LIKE 'lab\\_%'   -- 實驗室自己的表不算
                ORDER BY approx DESC""", (rs, i) -> {
            String name = rs.getString(1);
            String time = rs.getString(2);
            List<String> range = jdbc.query("SELECT to_char(min(" + time + "), 'YYYY-MM-DD HH24:MI'), to_char(max(" + time + "), 'YYYY-MM-DD HH24:MI') FROM " + name,
                    (r, k) -> List.of(String.valueOf(r.getString(1)), String.valueOf(r.getString(2)))).get(0);
            List<Column> columns = jdbc.query("""
                    SELECT column_name, data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position""",
                    (r, k) -> new Column(r.getString(1), r.getString(2)), name);
            String[] design = DESIGN.getOrDefault(name, new String[] {"", "SELECT * FROM " + name + " LIMIT 5"});
            return new Hypertable(name, design[0], time, rs.getString(3), rs.getLong(4), rs.getLong(6), rs.getString(7),
                    range.get(0), range.get(1), rs.getBoolean(5), columns, design[1]);
        });
        List<ContinuousAggregate> aggs = jdbc.query("""
                SELECT c.view_name, c.view_definition, c.materialized_only,
                       to_char(_timescaledb_functions.to_timestamp(_timescaledb_functions.cagg_watermark(m.mat_hypertable_id)) AT TIME ZONE 'Asia/Taipei', 'YYYY-MM-DD HH24:MI') AS watermark,
                       pg_size_pretty(hypertable_size(format('%I.%I', c.materialization_hypertable_schema, c.materialization_hypertable_name)::regclass))
                FROM timescaledb_information.continuous_aggregates c
                JOIN _timescaledb_catalog.continuous_agg m ON m.user_view_name = c.view_name
                WHERE c.view_name NOT LIKE 'lab\\_%'
                ORDER BY c.view_name""", (rs, i) -> new ContinuousAggregate(rs.getString(1), rs.getString(2).strip(),
                rs.getBoolean(3), rs.getString(4), rs.getString(5),
                "SELECT * FROM " + rs.getString(1) + " ORDER BY 1 DESC LIMIT 5"));
        String version = jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'", String.class);
        return new Overview(tables, aggs, version, db.status());
    }

    @GetMapping("/load")
    public TimescaleDb.Status load() {
        return db.status();
    }

    @PostMapping("/reset")
    public TimescaleDb.Status reset() {
        return db.reload();
    }

    // ---------- 跟 /api/postgres 一樣的查詢與沙盒 ----------

    @PostMapping("/sql")
    public QueryResult sql(@RequestBody SqlRequest request) {
        return db.queries().run(request.sql());
    }

    @PostMapping("/sandbox")
    public SandboxService.SandboxResult sandbox(@RequestBody SqlRequest request) {
        return db.sandbox().run(request.sql());
    }

    // ---------- 練習題、陷阱題 ----------

    @GetMapping("/exercises")
    public List<Exercise.View> exercises() {
        return exercises.stream().map(Exercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public Grader.Grade check(@PathVariable String id, @RequestBody SqlRequest request) {
        return db.grader().grade(exercise(id), request.sql());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        Exercise e = exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    @GetMapping("/traps")
    public List<Trap.View> traps() {
        return traps.stream().map(Trap::view).toList();
    }

    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        Trap trap = traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
        // 陷阱題可能故意示範錯誤（例如 gapfill 沒給範圍），把錯誤訊息當成一個結果顯示，而不是讓整題失敗
        List<QueryResult> results = trap.sqls().stream().map(s -> {
            try {
                return db.queries().run(s.sql());
            } catch (org.springframework.dao.DataAccessException e) {
                String msg = e.getMostSpecificCause().getMessage();
                return new QueryResult(List.of("錯誤"), List.of(List.<Object>of(msg == null ? e.getMessage() : msg)), 1, false, 0);
            }
        }).toList();
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }

    private Exercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
