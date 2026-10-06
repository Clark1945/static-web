package com.example.dbshowcase.redis.practice;

import java.util.List;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.example.dbshowcase.redis.RedisCommandRunner;
import com.example.dbshowcase.redis.RedisConfig;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * 隔離用的 db 1：清空 → 從 db 0 複製需要的 key → 跑 setup → 執行 → 清空。
 * 只有一個 db 1，所以同一時間只允許一個人使用（synchronized）。
 */
@Component
public class ScratchDb {

    private final JedisPool admin;
    private final JedisPool grading;
    private final RedisCommandRunner runner;

    public ScratchDb(@Qualifier("adminPool") JedisPool admin, @Qualifier("gradingPool") JedisPool grading,
                     RedisCommandRunner runner) {
        this.admin = admin;
        this.grading = grading;
        this.runner = runner;
    }

    /** 準備好隔離 db，交給 work 執行（work 拿到的是 admin 連線，已經切到 db 1），結束後清空。 */
    public synchronized <T> T use(List<String> keys, String setup, Function<Jedis, T> work) {
        try (Jedis jedis = admin.getResource()) {
            try {
                prepare(jedis, keys, setup);
                return work.apply(jedis);
            } finally {
                jedis.select(RedisConfig.SCRATCH_DB);
                jedis.flushDB();
                jedis.select(RedisConfig.DATA_DB);           // 連線要切回 db 0 再還給連線池
            }
        }
    }

    /** 用 learner 身分在 db 1 執行使用者的指令。必須在 use() 裡面呼叫。 */
    public RedisCommandRunner.RunResult runAsLearner(List<List<String>> commands) {
        try (Jedis jedis = grading.getResource()) {
            return runner.run(jedis, commands);
        }
    }

    /** 用 admin 身分在 db 1 執行（檢查指令、陷阱題）。 */
    public RedisCommandRunner.RunResult runAsAdmin(Jedis scratch, String script) {
        return runner.run(scratch, runner.parse(script));
    }

    /** 直接在 db 0 用 admin 執行（只給內容固定、唯讀的陷阱題用，例如示範 KEYS 掃整個資料庫）。 */
    public RedisCommandRunner.RunResult runOnDataDb(String script) {
        try (Jedis jedis = admin.getResource()) {
            return runner.run(jedis, runner.parse(script));
        }
    }

    private void prepare(Jedis jedis, List<String> keys, String setup) {
        jedis.select(RedisConfig.SCRATCH_DB);
        jedis.flushDB();
        jedis.select(RedisConfig.DATA_DB);
        for (String key : keys) {
            jedis.copy(key, key, RedisConfig.SCRATCH_DB, true);
        }
        jedis.select(RedisConfig.SCRATCH_DB);
        if (setup != null && !setup.isBlank()) {
            runner.run(jedis, runner.parse(setup));
        }
    }
}
