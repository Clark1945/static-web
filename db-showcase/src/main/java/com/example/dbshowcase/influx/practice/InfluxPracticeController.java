package com.example.dbshowcase.influx.practice;

import java.util.ArrayList;
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
import com.example.dbshowcase.influx.InfluxClient;
import com.example.dbshowcase.influx.InfluxDb;
import com.example.dbshowcase.influx.InfluxShell;
import com.example.dbshowcase.influx.InfluxShell.Block;
import com.example.dbshowcase.influx.InfluxShell.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** InfluxDB 練習題與陷阱題的 API。 */
@RestController
@RequestMapping("/api/influx")
public class InfluxPracticeController {

    public record CodeRequest(String code) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String code, String explanation) {
    }

    /** 陷阱題每段腳本的結果：每個步驟各一個 RunResult。 */
    public record TrapResult(boolean correct, int answer, String explanation, List<List<RunResult>> results) {
    }

    private final List<InfluxExercise> exercises;
    private final List<InfluxTrap> traps;
    private final InfluxGrader grader;
    private final InfluxShell shell;
    private final InfluxDb db;

    public InfluxPracticeController(ObjectMapper mapper, InfluxGrader grader, InfluxShell shell, InfluxDb db) {
        this.exercises = YamlContent.load("influx/exercises.yml", InfluxExercise.class, mapper);
        this.traps = YamlContent.load("influx/traps.yml", InfluxTrap.class, mapper);
        this.grader = grader;
        this.shell = shell;
        this.db = db;
    }

    @GetMapping("/exercises")
    public List<InfluxExercise.View> exercises() {
        return exercises.stream().map(InfluxExercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public InfluxGrader.Grade check(@PathVariable String id, @RequestBody CodeRequest request) {
        InfluxGrader.Grade g = grader.grade(exercise(id), request.code());
        return new InfluxGrader.Grade(g.correct(), g.message(), shell.forDisplay(g.result()),
                g.check() == null ? null : shell.forDisplay(g.check()), g.mismatch(), g.explanation());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        InfluxExercise e = exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    @GetMapping("/traps")
    public List<InfluxTrap.View> traps() {
        return traps.stream().map(InfluxTrap::view).toList();
    }

    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        InfluxTrap trap = traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
        List<List<RunResult>> results = new ArrayList<>();
        for (InfluxTrap.Script s : trap.scripts()) {
            results.add(trap.writes() ? inScratch(s) : run(InfluxClient.User.READER, s, InfluxDb.BUCKET));
        }
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }

    private List<RunResult> inScratch(InfluxTrap.Script s) {
        synchronized (db) {
            try {
                db.clearScratch();
                return run(InfluxClient.User.LEARNER, s, InfluxDb.SCRATCH);
            } finally {
                db.clearScratch();
            }
        }
    }

    private List<RunResult> run(InfluxClient.User user, InfluxTrap.Script s, String database) {
        List<RunResult> out = new ArrayList<>();
        for (InfluxTrap.Step step : s.steps()) {
            InfluxShell.Lang lang = InfluxShell.Lang.valueOf(step.lang().toUpperCase());
            try {
                out.add(shell.forDisplay(shell.run(user, lang, step.code(), database)));
            } catch (IllegalArgumentException e) {
                out.add(new RunResult(lang, List.of(new Block(step.lang(), "error", null, List.of(), List.of(), 0, e.getMessage(), 0)), 0));
            }
        }
        return out;
    }

    private InfluxExercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
