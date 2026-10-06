package com.example.dbshowcase.redis.practice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.redis.RedisCommandRunner;
import com.example.dbshowcase.redis.RedisCommandRunner.CommandResult;
import com.example.dbshowcase.redis.RedisCommandRunner.RunResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批改 Redis 練習題：你的指令與標準答案各自在一份乾淨的隔離 db 執行，再比對結果。
 * 有 check 指令時比對「執行後的資料狀態」，沒有時比對「最後一個指令的回傳值」。
 */
@Service
public class RedisGrader {

    public record Mismatch(String what, Object yours, Object expected) {
    }

    public record Grade(boolean correct, String message, RunResult result, List<CommandResult> checks,
                        Mismatch mismatch, String explanation) {
    }

    private record Attempt(RunResult run, List<CommandResult> checks) {
    }

    private final ScratchDb scratch;
    private final RedisCommandRunner runner;
    private final ObjectMapper json;

    public RedisGrader(ScratchDb scratch, RedisCommandRunner runner, ObjectMapper json) {
        this.scratch = scratch;
        this.runner = runner;
        this.json = json;
    }

    public Grade grade(RedisExercise ex, String userScript) {
        List<List<String>> userCommands = runner.parse(userScript);
        Attempt yours = attempt(ex, userCommands);
        Attempt expected = attempt(ex, runner.parse(ex.answer()));
        boolean byState = ex.check() != null && !ex.check().isBlank();

        if (byState) {
            for (int i = 0; i < expected.checks().size(); i++) {
                CommandResult y = yours.checks().get(i), e = expected.checks().get(i);
                if (!same(y.reply(), e.reply(), ex.isOrdered())) {
                    return wrong(yours, "執行完之後的資料不對：檢查指令「" + e.command() + "」的結果不同。",
                            new Mismatch(e.command(), y.reply(), e.reply()));
                }
            }
        } else {
            CommandResult last = yours.run().results().get(yours.run().results().size() - 1);
            Object expectedReply = expected.run().lastReply();
            if (!same(last.reply(), expectedReply, ex.isOrdered())) {
                String msg = last.error()
                        ? "最後一個指令出錯了。"
                        : "最後一個指令（" + last.command() + "）的回傳值不同。";
                return wrong(yours, msg, new Mismatch("最後一個指令的回傳值", last.reply(), expectedReply));
            }
        }
        return new Grade(true, "完全正確！", yours.run(), yours.checks(), null, ex.explanation());
    }

    private Attempt attempt(RedisExercise ex, List<List<String>> commands) {
        return scratch.use(ex.keysOrEmpty(), ex.setup(), jedis -> {
            RunResult run = scratch.runAsLearner(commands);
            List<CommandResult> checks = ex.check() == null || ex.check().isBlank()
                    ? List.of()
                    : scratch.runAsAdmin(jedis, ex.check()).results();
            return new Attempt(run, checks);
        });
    }

    private static Grade wrong(Attempt yours, String message, Mismatch mismatch) {
        return new Grade(false, message, yours.run(), yours.checks(), mismatch, null);
    }

    private boolean same(Object a, Object b, boolean ordered) {
        return key(normalize(a, ordered)).equals(key(normalize(b, ordered)));
    }

    /** 順序不拘時，把陣列排序後再比；數字一律轉成字串（INCR 回整數、GET 回字串，值一樣就算一樣）。 */
    private Object normalize(Object v, boolean ordered) {
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                out.add(normalize(o, ordered));
            }
            if (!ordered) {
                out.sort(Comparator.comparing(this::key));
            }
            return out;
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof Map<?, ?> m) {
            return m;
        }
        return v;
    }

    private String key(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            return String.valueOf(v);
        }
    }
}
