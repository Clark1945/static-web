package com.example.dbshowcase.redis.practice;

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
import com.example.dbshowcase.redis.RedisCommandRunner.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Redis 練習題與陷阱題的 API。 */
@RestController
@RequestMapping("/api/redis")
public class RedisPracticeController {

    public record ScriptRequest(String commands) {
    }

    public record ChoiceRequest(int choice) {
    }

    public record AnswerView(String commands, String explanation) {
    }

    public record TrapResult(boolean correct, int answer, String explanation, List<RunResult> results) {
    }

    private final List<RedisExercise> exercises;
    private final List<RedisTrap> traps;
    private final RedisGrader grader;
    private final ScratchDb scratch;

    public RedisPracticeController(ObjectMapper mapper, RedisGrader grader, ScratchDb scratch) {
        this.exercises = YamlContent.load("redis/exercises.yml", RedisExercise.class, mapper);
        this.traps = YamlContent.load("redis/traps.yml", RedisTrap.class, mapper);
        this.grader = grader;
        this.scratch = scratch;
    }

    @GetMapping("/exercises")
    public List<RedisExercise.View> exercises() {
        return exercises.stream().map(RedisExercise::view).toList();
    }

    @PostMapping("/exercises/{id}/check")
    public RedisGrader.Grade check(@PathVariable String id, @RequestBody ScriptRequest request) {
        return grader.grade(exercise(id), request.commands());
    }

    @GetMapping("/exercises/{id}/answer")
    public AnswerView answer(@PathVariable String id) {
        RedisExercise e = exercise(id);
        return new AnswerView(e.answer(), e.explanation());
    }

    @GetMapping("/traps")
    public List<RedisTrap.View> traps() {
        return traps.stream().map(RedisTrap::view).toList();
    }

    /** 選完答案才執行兩段指令；每段都在一份乾淨的隔離 db 裡跑。 */
    @PostMapping("/traps/{id}/answer")
    public TrapResult answerTrap(@PathVariable String id, @RequestBody ChoiceRequest request) {
        RedisTrap trap = traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
        List<RunResult> results = new ArrayList<>();
        for (RedisTrap.Script s : trap.scripts()) {
            results.add(trap.onDataDb()
                    ? scratch.runOnDataDb(s.commands())
                    : scratch.use(trap.keys() == null ? List.of() : trap.keys(), trap.setup(),
                            jedis -> scratch.runAsAdmin(jedis, s.commands())));
        }
        return new TrapResult(request.choice() == trap.answer(), trap.answer(), trap.explanation(), results);
    }

    private RedisExercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
