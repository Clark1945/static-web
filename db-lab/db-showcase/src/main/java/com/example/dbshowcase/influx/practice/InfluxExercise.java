package com.example.dbshowcase.influx.practice;

import java.util.List;

/**
 * InfluxDB 練習題（內容來自 influx/exercises.yml）。
 *
 * @param lang      你要寫的語言：influxql、flux、write（line protocol，寫入 scratch bucket）
 * @param check     write 題寫入之後，用來檢查資料的查詢（以管理者身分在 scratch 執行）
 * @param checkLang check 的語言（influxql 或 flux，預設 influxql）
 * @param ordered   false 時，結果列先排序再比對（Flux 預設不比順序）
 */
public record InfluxExercise(
        String id,
        String chapter,
        String title,
        String lang,
        String prompt,
        List<String> hints,
        String answer,
        String check,
        String checkLang,
        Boolean ordered,
        String explanation) {

    public boolean isWrite() {
        return "write".equalsIgnoreCase(lang);
    }

    public boolean isOrdered() {
        return ordered != null ? ordered : !"flux".equalsIgnoreCase(lang);
    }

    public record View(String id, String chapter, String title, String lang, String prompt, List<String> hints, boolean ordered) {
    }

    public View view() {
        return new View(id, chapter, title, lang, prompt, hints, isOrdered());
    }
}
