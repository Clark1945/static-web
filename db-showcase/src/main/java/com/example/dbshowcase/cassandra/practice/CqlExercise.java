package com.example.dbshowcase.cassandra.practice;

import java.util.List;
import java.util.Map;

/**
 * Cassandra 練習題（內容來自 cassandra/exercises.yml）。
 *
 * @param fixture 寫入題才需要：批改前把哪些資料列從 shop 複製到隔離的 scratch keyspace（資料表 → WHERE 條件，例如 "product_id = 540"）
 * @param check   寫入題執行完之後檢查資料狀態的 CQL；沒有設定時比對最後一句的結果
 * @param ordered false 時，回傳的資料列先排序再比對
 */
public record CqlExercise(
        String id,
        String chapter,
        String title,
        String prompt,
        List<String> hints,
        Map<String, String> fixture,
        String answer,
        String check,
        Boolean ordered,
        String explanation) {

    public boolean isWrite() {
        return fixture != null && !fixture.isEmpty();
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
