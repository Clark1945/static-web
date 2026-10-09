package com.example.dbshowcase.elastic.practice;

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
import com.example.dbshowcase.elastic.EsClient;
import com.example.dbshowcase.elastic.EsShell;
import com.example.dbshowcase.elastic.EsShell.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Elasticsearch 練習題與陷阱題的 API。 */
@RestController
@RequestMapping("/api/elastic")
public class EsPracticeController {

    public record ScriptRequest(String commands) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String commands, String explanation) {
    }

    public record TrapResult(boolean correct, int answer, String explanation, List<RunResult> results) {
    }

    private final List<EsExercise> exercises;
    private final List<EsTrap> traps;
    private final EsGrader grader;
    private final EsScratch scratch;
    private final EsShell shell;

    public EsPracticeController(ObjectMapper mapper, EsGrader grader, EsScratch scratch, EsShell shell) {
        this.exercises = YamlContent.load("elastic/exercises.yml", EsExercise.class, mapper);
        this.traps = YamlContent.load("elastic/traps.yml", EsTrap.class, mapper);
        this.grader = grader;
        this.scratch = scratch;
        this.shell = shell;
    }

    @GetMapping("/exercises")
    public List<EsExercise.View> exercises() {
        return exercises.stream().map(EsExercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public EsGrader.Grade check(@PathVariable String id, @RequestBody ScriptRequest request) {
        EsGrader.Grade g = grader.grade(exercise(id), request.commands());
        return new EsGrader.Grade(g.correct(), g.message(), shell.forDisplay(g.result()),
                g.checks().isEmpty() ? g.checks() : shell.forDisplay(new RunResult(g.checks(), 0)).results(), g.mismatch(), g.explanation());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        EsExercise e = exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    @GetMapping("/traps")
    public List<EsTrap.View> traps() {
        return traps.stream().map(EsTrap::view).toList();
    }

    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        EsTrap trap = traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
        List<RunResult> results = new ArrayList<>();
        for (EsTrap.Script s : trap.scripts()) {
            RunResult r = trap.writes()
                    ? scratch.use(trap.fixture(), () -> shell.run(EsClient.User.LEARNER, s.commands()))
                    : shell.run(EsClient.User.READER, s.commands());
            results.add(shell.forDisplay(r));
        }
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }

    private EsExercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
