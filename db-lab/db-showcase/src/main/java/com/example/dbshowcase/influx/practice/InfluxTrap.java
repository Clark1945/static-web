package com.example.dbshowcase.influx.practice;

import java.util.List;

/**
 * InfluxDB 陷阱題（內容來自 influx/traps.yml）。每段腳本由一或多個步驟組成（語言 + 內容），依序執行。
 * scratch: true 的題目會寫入：每段腳本執行前後清空 scratch bucket，用 learner token 執行；否則用 reader 唯讀執行。
 */
public record InfluxTrap(
        String id,
        String title,
        String question,
        Boolean scratch,
        List<Script> scripts,
        List<String> options,
        int answer,
        String explanation) {

    public record Step(String lang, String code) {
    }

    public record Script(String label, List<Step> steps) {
    }

    public boolean writes() {
        return Boolean.TRUE.equals(scratch);
    }

    public record View(String id, String title, String question, boolean writes, List<Script> scripts, List<String> options) {
    }

    public View view() {
        return new View(id, title, question, writes(), scripts, options);
    }
}
