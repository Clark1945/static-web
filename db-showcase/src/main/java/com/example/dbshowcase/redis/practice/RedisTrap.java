package com.example.dbshowcase.redis.practice;

import java.util.List;

/**
 * Redis 陷阱題（內容來自 redis/traps.yml）。兩段指令各自在乾淨的隔離 db 執行（先複製 keys、跑 setup）。
 * dataDb = true 時直接在 db 0 執行（只放唯讀指令，例如示範 KEYS 要掃過整個資料庫）。
 */
public record RedisTrap(
        String id,
        String title,
        String question,
        List<String> keys,
        String setup,
        Boolean dataDb,
        List<Script> scripts,
        List<String> options,
        int answer,
        String explanation) {

    public record Script(String label, String commands) {
    }

    public boolean onDataDb() {
        return Boolean.TRUE.equals(dataDb);
    }

    public record View(String id, String title, String question, String setup, List<Script> scripts, List<String> options) {
    }

    public View view() {
        return new View(id, title, question, setup, scripts, options);
    }
}
