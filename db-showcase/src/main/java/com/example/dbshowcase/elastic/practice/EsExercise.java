package com.example.dbshowcase.elastic.practice;

import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 練習題（內容來自 elastic/exercises.yml）。
 *
 * @param fixture 寫入題才需要：批改前要準備哪些 scratch 索引（名稱 → { source, query }）
 * @param check   寫入題執行完之後，用來檢查資料狀態的請求（以管理者身分執行，比對最後一個請求的結果）
 * @param ordered false 時，hits 先依 _id 排序再比對
 */
public record EsExercise(
        String id,
        String chapter,
        String title,
        String prompt,
        List<String> hints,
        Map<String, Map<String, Object>> fixture,
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
