package com.example.dbshowcase.postgres.practice;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.dbshowcase.postgres.QueryResult;
import com.example.dbshowcase.postgres.QueryService;

/** 練習題與陷阱題的 API。 */
@RestController
@RequestMapping("/api/postgres")
public class PracticeController {

    public record SqlRequest(String sql) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String sql, String explanation) {
    }

    public record TrapResult(boolean correct, int answer, String explanation, List<QueryResult> results) {
    }

    private final PracticeCatalog catalog;
    private final Grader grader;
    private final QueryService queryService;

    public PracticeController(PracticeCatalog catalog, Grader grader, QueryService queryService) {
        this.catalog = catalog;
        this.grader = grader;
        this.queryService = queryService;
    }

    // ---------- 練習題 ----------

    @GetMapping("/exercises")
    public List<Exercise.View> exercises() {
        return catalog.exercises().stream().map(Exercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public Grader.Grade check(@PathVariable String id, @RequestBody SqlRequest request) {
        return grader.grade(catalog.exercise(id), request.sql());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        Exercise e = catalog.exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    // ---------- 陷阱題 ----------

    @GetMapping("/traps")
    public List<Trap.View> traps() {
        return catalog.traps().stream().map(Trap::view).toList();
    }

    /** 送出選擇後才執行兩段 SQL，並回傳正確答案與解說。 */
    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        Trap trap = catalog.trap(id);
        List<QueryResult> results = trap.sqls().stream().map(s -> queryService.run(s.sql())).toList();
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }
}
