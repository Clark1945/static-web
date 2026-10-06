package com.example.dbshowcase.mongo.practice;

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
import com.example.dbshowcase.mongo.MongoShell.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** MongoDB 練習題與陷阱題的 API。 */
@RestController
@RequestMapping("/api/mongo")
public class MongoPracticeController {

    public record ScriptRequest(String commands) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String commands, String explanation) {
    }

    public record TrapResult(boolean correct, int answer, String explanation, List<RunResult> results) {
    }

    private final List<MongoExercise> exercises;
    private final List<MongoTrap> traps;
    private final MongoGrader grader;
    private final MongoScratch scratch;

    public MongoPracticeController(ObjectMapper mapper, MongoGrader grader, MongoScratch scratch) {
        this.exercises = YamlContent.load("mongo/exercises.yml", MongoExercise.class, mapper);
        this.traps = YamlContent.load("mongo/traps.yml", MongoTrap.class, mapper);
        this.grader = grader;
        this.scratch = scratch;
    }

    @GetMapping("/exercises")
    public List<MongoExercise.View> exercises() {
        return exercises.stream().map(MongoExercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public MongoGrader.Grade check(@PathVariable String id, @RequestBody ScriptRequest request) {
        return grader.grade(exercise(id), request.commands());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        MongoExercise e = exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    @GetMapping("/traps")
    public List<MongoTrap.View> traps() {
        return traps.stream().map(MongoTrap::view).toList();
    }

    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        MongoTrap trap = traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
        List<RunResult> results = new ArrayList<>();
        for (MongoTrap.Script s : trap.scripts()) {
            results.add(trap.writes()
                    ? scratch.use(trap.fixture(), () -> scratch.runAsAdmin(s.commands(), 50))
                    : scratch.runAsReader(s.commands(), 50));
        }
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }

    private MongoExercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
