package com.example.dbshowcase.elastic.practice;

import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 陷阱題（內容來自 elastic/traps.yml）。
 * 沒有 fixture 的題目用 reader 直接在練習資料執行；有 fixture 的題目每段請求各自在一份乾淨的 scratch 副本用 learner 執行。
 */
public record EsTrap(
        String id,
        String title,
        String question,
        Map<String, Map<String, Object>> fixture,
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
