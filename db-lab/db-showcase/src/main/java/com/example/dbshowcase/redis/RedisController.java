package com.example.dbshowcase.redis;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** Redis 的 API，全部掛在 /api/redis 底下。 */
@RestController
@RequestMapping("/api/redis")
public class RedisController {

    public record ScriptRequest(String commands) {
    }

    public record Overview(List<RedisDataLoader.KeyGroup> groups, long keys, String memory,
                           String version, double loadSeconds) {
    }

    private final RedisDataLoader loader;
    private final RedisCommandRunner runner;
    private final JedisPool admin;
    private final JedisPool learner;

    public RedisController(RedisDataLoader loader, RedisCommandRunner runner,
                           @Qualifier("adminPool") JedisPool admin, @Qualifier("learnerPool") JedisPool learner) {
        this.loader = loader;
        this.runner = runner;
        this.admin = admin;
        this.learner = learner;
    }

    @GetMapping("/overview")
    public Overview overview() {
        RedisDataLoader.LoadResult load = loader.lastLoad();
        if (load == null) {
            load = loader.load();
        }
        try (Jedis jedis = admin.getResource()) {
            Map<String, String> memory = parseInfo(jedis.info("memory"));
            Map<String, String> server = parseInfo(jedis.info("server"));
            return new Overview(load.groups(), jedis.dbSize(), memory.get("used_memory_human"),
                    server.get("redis_version"), load.seconds());
        }
    }

    /** 指令主控台：用 learner 身分在 db 0 執行。寫入會保留，按「重置資料」才會還原。 */
    @PostMapping("/run")
    public RedisCommandRunner.RunResult run(@RequestBody ScriptRequest request) {
        List<List<String>> commands = runner.parse(request.commands());
        try (Jedis jedis = learner.getResource()) {
            return runner.run(jedis, commands);
        }
    }

    /** 清空 db 0，重新從 PostgreSQL 載入。 */
    @PostMapping("/reset")
    public RedisDataLoader.LoadResult reset() {
        return loader.load();
    }

    private static Map<String, String> parseInfo(String info) {
        Map<String, String> map = new java.util.HashMap<>();
        for (String line : info.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i > 0 && !line.startsWith("#")) {
                map.put(line.substring(0, i), line.substring(i + 1).trim());
            }
        }
        return map;
    }
}
