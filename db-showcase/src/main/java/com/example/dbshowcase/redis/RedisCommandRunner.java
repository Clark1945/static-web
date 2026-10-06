package com.example.dbshowcase.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.SafeEncoder;

/**
 * 解析並執行 redis-cli 風格的指令（一行一個指令，可以用引號包住含空白的參數）。
 * 回傳值轉成 JSON 友善的格式：字串、整數、null（nil）、陣列，錯誤是 {"error": "..."}。
 */
@Component
public class RedisCommandRunner {

    /** 應用層再擋一次：這些指令會改變連線狀態（切換 db、換使用者），ACL 擋不住或不方便擋。 */
    private static final Set<String> BLOCKED = Set.of(
            "SELECT", "AUTH", "HELLO", "CLIENT", "RESET", "QUIT", "MOVE", "SWAPDB", "MONITOR", "SYNC", "PSYNC");
    private static final int MAX_COMMANDS = 30;

    public record CommandResult(String command, Object reply, boolean error, double micros) {
    }

    public record RunResult(List<CommandResult> results, double totalMicros) {

        /** 最後一個指令的回傳值（批改用）。 */
        public Object lastReply() {
            return results.isEmpty() ? null : results.get(results.size() - 1).reply();
        }
    }

    /** 把多行文字切成指令清單；每個指令是 [指令, 參數...]。 */
    public List<List<String>> parse(String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("請輸入 Redis 指令，一行一個。");
        }
        List<List<String>> commands = new ArrayList<>();
        for (String line : script.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            List<String> tokens = tokenize(trimmed);
            if (!tokens.isEmpty()) {
                commands.add(tokens);
            }
        }
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("沒有可以執行的指令（# 開頭的是註解）。");
        }
        if (commands.size() > MAX_COMMANDS) {
            throw new IllegalArgumentException("一次最多 " + MAX_COMMANDS + " 個指令。");
        }
        for (List<String> c : commands) {
            String name = c.get(0).toUpperCase(Locale.ROOT);
            if (BLOCKED.contains(name)) {
                throw new IllegalArgumentException(name + " 會改變連線狀態（切換資料庫或身分），這裡不開放使用。");
            }
        }
        return commands;
    }

    /** 依序執行；某個指令出錯會記下錯誤並繼續（跟 redis-cli 一樣）。 */
    public RunResult run(Jedis jedis, List<List<String>> commands) {
        List<CommandResult> results = new ArrayList<>(commands.size());
        long total = 0;
        boolean inMulti = false;
        try {
            for (List<String> c : commands) {
                String name = c.get(0).toUpperCase(Locale.ROOT);
                String[] args = c.subList(1, c.size()).toArray(String[]::new);
                long start = System.nanoTime();
                Object reply;
                boolean error = false;
                try {
                    reply = convert(jedis.sendCommand(command(name), args));
                } catch (JedisDataException e) {
                    reply = Map.of("error", e.getMessage());
                    error = true;
                }
                long elapsed = System.nanoTime() - start;
                total += elapsed;
                if (!error && name.equals("MULTI")) {
                    inMulti = true;
                } else if (name.equals("EXEC") || name.equals("DISCARD")) {
                    inMulti = false;
                }
                results.add(new CommandResult(String.join(" ", c), reply, error, elapsed / 1000.0));
            }
        } finally {
            cleanUp(jedis, inMulti);
        }
        return new RunResult(results, total / 1000.0);
    }

    /** 沒有 EXEC 的 MULTI、還在 WATCH 的 key，都要清掉，連線才能安全地還給連線池。 */
    private static void cleanUp(Jedis jedis, boolean inMulti) {
        try {
            if (inMulti) {
                jedis.sendCommand(command("DISCARD"));
            }
            jedis.sendCommand(command("UNWATCH"));
        } catch (Exception ignored) {
            // 連線有問題時，連線池會在下次借出前檢查
        }
    }

    static ProtocolCommand command(String name) {
        byte[] raw = SafeEncoder.encode(name);
        return () -> raw;
    }

    static Object convert(Object reply) {
        if (reply == null || reply instanceof Long || reply instanceof Double) {
            return reply;
        }
        if (reply instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (reply instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(convert(o));
            }
            return out;
        }
        if (reply instanceof JedisDataException e) {           // EXEC 裡某一句失敗
            return Map.of("error", e.getMessage());
        }
        return reply.toString();
    }

    /** 支援 "雙引號"（可用 \" \\ \n 跳脫）與 '單引號'，其餘以空白分隔。 */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else if (ch == '\\' && quote == '"' && i + 1 < line.length()) {
                    char next = line.charAt(++i);
                    cur.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
                inToken = true;
            } else if (Character.isWhitespace(ch)) {
                if (inToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(ch);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("引號沒有成對：" + line);
        }
        if (inToken) {
            tokens.add(cur.toString());
        }
        return tokens;
    }
}
