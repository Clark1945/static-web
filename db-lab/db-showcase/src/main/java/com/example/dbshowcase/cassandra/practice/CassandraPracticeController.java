package com.example.dbshowcase.cassandra.practice;

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
import com.example.dbshowcase.cassandra.CqlShell.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Cassandra 練習題與陷阱題的 API。 */
@RestController
@RequestMapping("/api/cassandra")
public class CassandraPracticeController {

    public record ScriptRequest(String commands) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String commands, String explanation) {
    }

    public record TrapResult(boolean correct, int answer, String explanation, List<RunResult> results) {
    }

    private final List<CqlExercise> exercises;
    private final List<CqlTrap> traps;
    private final CqlGrader grader;
    private final CqlScratch scratch;

    public CassandraPracticeController(ObjectMapper mapper, CqlGrader grader, CqlScratch scratch) {
        this.exercises = YamlContent.load("cassandra/exercises.yml", CqlExercise.class, mapper);
        this.traps = YamlContent.load("cassandra/traps.yml", CqlTrap.class, mapper);
        this.grader = grader;
        this.scratch = scratch;
    }

    @GetMapping("/exercises")
    public List<CqlExercise.View> exercises() {
        return exercises.stream().map(CqlExercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public CqlGrader.Grade check(@PathVariable String id, @RequestBody ScriptRequest request) {
        return grader.grade(exercise(id), request.commands());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        CqlExercise e = exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    @GetMapping("/traps")
    public List<CqlTrap.View> traps() {
        return traps.stream().map(CqlTrap::view).toList();
    }

    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        CqlTrap trap = traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
        List<RunResult> results = new ArrayList<>();
        for (CqlTrap.Script s : trap.scripts()) {
            results.add(trap.writes()
                    ? scratch.use(trap.fixture(), () -> scratch.runAsAdmin(s.commands(), 50))
                    : scratch.runAsReader(s.commands(), 50));
        }
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }

    private CqlExercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
