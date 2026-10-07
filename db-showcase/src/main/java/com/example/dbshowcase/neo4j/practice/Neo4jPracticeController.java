package com.example.dbshowcase.neo4j.practice;

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
import com.example.dbshowcase.neo4j.CypherShell;
import com.example.dbshowcase.neo4j.CypherShell.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Neo4j 練習題與陷阱題的 API。 */
@RestController
@RequestMapping("/api/neo4j")
public class Neo4jPracticeController {

    public record ScriptRequest(String commands) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String commands, String explanation) {
    }

    public record TrapResult(boolean correct, int answer, String explanation, List<RunResult> results) {
    }

    private final List<CypherExercise> exercises;
    private final List<CypherTrap> traps;
    private final CypherGrader grader;
    private final CypherShell shell;

    public Neo4jPracticeController(ObjectMapper mapper, CypherGrader grader, CypherShell shell) {
        this.exercises = YamlContent.load("neo4j/exercises.yml", CypherExercise.class, mapper);
        this.traps = YamlContent.load("neo4j/traps.yml", CypherTrap.class, mapper);
        this.grader = grader;
        this.shell = shell;
    }

    @GetMapping("/exercises")
    public List<CypherExercise.View> exercises() {
        return exercises.stream().map(CypherExercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public CypherGrader.Grade check(@PathVariable String id, @RequestBody ScriptRequest request) {
        return grader.grade(exercise(id), request.commands());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        CypherExercise e = exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    @GetMapping("/traps")
    public List<CypherTrap.View> traps() {
        return traps.stream().map(CypherTrap::view).toList();
    }

    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        CypherTrap trap = traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
        List<RunResult> results = new ArrayList<>();
        for (CypherTrap.Script s : trap.scripts()) {
            results.add(shell.run(s.commands(), 50));
        }
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }

    private CypherExercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
