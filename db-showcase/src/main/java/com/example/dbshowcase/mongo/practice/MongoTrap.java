package com.example.dbshowcase.mongo.practice;

import java.util.List;
import java.util.Map;

/**
 * MongoDB 陷阱題（內容來自 mongo/traps.yml）。
 * 沒有 fixture 的題目用 reader 直接在 shop 執行（唯讀）；有 fixture 的題目每段指令各自在一份乾淨的 scratch 副本執行。
 */
public record MongoTrap(
        String id,
        String title,
        String question,
        Map<String, String> fixture,
        List<Script> scripts,
        List<String> options,
        int answer,
        String explanation) {

    public record Script(String label, String commands) {
    }

    public boolean writes() {
        return fixture != null && !fixture.isEmpty();
    }

    public record View(String id, String title, String question, boolean writes, List<Script> scripts, List<String> options) {
    }

    public View view() {
        return new View(id, title, question, writes(), scripts, options);
    }
}
