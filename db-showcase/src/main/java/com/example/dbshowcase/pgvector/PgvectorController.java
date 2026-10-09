package com.example.dbshowcase.pgvector;

import java.util.List;

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
 * pgvector 的 API，全部掛在 /api/pgvector 底下。
 * 練習題、陷阱題、/sql、/sandbox 的格式跟 /api/postgres 一樣，前端直接共用 PostgreSQL 的頁面模組。
 */
@RestController
@RequestMapping("/api/pgvector")
public class PgvectorController {

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

    public record Index(String name, String definition, String size) {
    }

    public record Table(String name, String design, long rows, String size, List<Column> columns, List<Index> indexes, String sample) {
    }

    public record Overview(List<Table> tables, String version, int vocabWords, VectorDb.Status load) {
    }

    private static final List<String[]> DESIGN = List.of(
            new String[] {"products", "商品（從 PostgreSQL 複製）。description 依規格產生，embedding = embed(description) 加一點雜訊，64 維",
                    "SELECT id, name, description FROM products ORDER BY embedding <=> embed('通勤 安靜') LIMIT 5"},
            new String[] {"vocab", "詞庫：迷你嵌入模型。每個詞一個 64 維向量，由幾個「主題」加權組成（recipe）；embed(文字) 把文字裡認得的詞相加",
                    "SELECT word, recipe FROM vocab ORDER BY embedding <=> (SELECT embedding FROM vocab WHERE word = '通勤') LIMIT 6"},
            new String[] {"purchases", "購買紀錄（從 PostgreSQL 的訂單明細複製，不含取消、退貨），用來做推薦",
                    "SELECT * FROM purchases WHERE customer_id = 224"},
            new String[] {"passages", "模擬 RAG 的文件片段：10 萬筆 128 維，分屬 50 個租戶。有 HNSW 索引，索引實驗都用這張表",
                    "SELECT id, tenant_id FROM passages ORDER BY embedding <=> (SELECT embedding FROM questions WHERE id = 1) LIMIT 5"},
            new String[] {"questions", "100 個測試用的查詢向量（不在 passages 裡），量測召回率用",
                    "SELECT id, vector_dims(embedding), vector_norm(embedding) FROM questions LIMIT 5"});

    private final VectorDb db;
    private final List<Exercise> exercises;
    private final List<Trap> traps;

    public PgvectorController(VectorDb db, ObjectMapper mapper) {
        this.db = db;
        this.exercises = YamlContent.load("pgvector/exercises.yml", Exercise.class, mapper);
        this.traps = YamlContent.load("pgvector/traps.yml", Trap.class, mapper);
    }

    @GetMapping("/overview")
    public Overview overview() {
        var jdbc = db.jdbc();
        List<Table> tables = DESIGN.stream().map(d -> {
            String name = d[0];
            Long rows = jdbc.queryForObject("SELECT count(*) FROM " + name, Long.class);
            String size = jdbc.queryForObject("SELECT pg_size_pretty(pg_table_size(?::regclass))", String.class, name);
            List<Column> columns = jdbc.query("""
                    SELECT a.attname, format_type(a.atttypid, a.atttypmod) FROM pg_attribute a
                    WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum""",
                    (r, k) -> new Column(r.getString(1), r.getString(2)), name);
            List<Index> indexes = jdbc.query("""
                    SELECT indexname, indexdef, pg_size_pretty(pg_relation_size(format('%I', indexname)::regclass))
                    FROM pg_indexes WHERE schemaname = 'public' AND tablename = ? AND indexname NOT LIKE 'lab\\_%'
                    ORDER BY indexname""", (r, k) -> new Index(r.getString(1), r.getString(2), r.getString(3)), name);
            return new Table(name, d[1], rows == null ? 0 : rows, size, columns, indexes, d[2]);
        }).toList();
        String version = jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
        Integer words = jdbc.queryForObject("SELECT count(*) FROM vocab", Integer.class);
        return new Overview(tables, version, words == null ? 0 : words, db.status());
    }

    @GetMapping("/load")
    public VectorDb.Status load() {
        return db.status();
    }

    @PostMapping("/reset")
    public VectorDb.Status reset() {
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
        // 陷阱題可能故意示範錯誤，把錯誤訊息當成一個結果顯示，而不是讓整題失敗
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
