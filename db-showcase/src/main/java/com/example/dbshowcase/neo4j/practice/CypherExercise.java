package com.example.dbshowcase.neo4j.practice;

import java.util.List;

/**
 * Neo4j 練習題（內容來自 neo4j/exercises.yml）。
 *
 * @param check   寫入題：在同一個交易裡、你的 Cypher 執行完之後執行的檢查查詢（之後整個交易 ROLLBACK）；沒有設定時比對最後一句的結果
 * @param ordered false 時，回傳的資料列先排序再比對
 */
public record CypherExercise(
        String id,
        String chapter,
        String title,
        String prompt,
        List<String> hints,
        String answer,
        String check,
        Boolean ordered,
        String explanation) {

    public boolean isWrite() {
        return check != null && !check.isBlank();
    }

    public boolean isOrdered() {
        return ordered == null || ordered;
    }

    public record View(String id, String chapter, String title, String prompt, List<String> hints,
                       boolean write, boolean ordered) {
    }

    public View view() {
        return new View(id, chapter, title, prompt, hints, isWrite(), isOrdered());
    }
}
