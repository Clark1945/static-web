package com.example.dbshowcase.mongo.practice;

import java.util.List;
import java.util.Map;

/**
 * MongoDB 練習題（內容來自 mongo/exercises.yml）。
 *
 * @param fixture 寫入題才需要：批改前把哪些文件複製到隔離的 scratch 資料庫（collection 名稱 → 篩選條件，例如 "{_id: 540}"）
 * @param check   寫入題執行完之後檢查資料狀態的指令；沒有設定時比對最後一句的結果
 * @param ordered false 時，回傳的文件清單先排序再比對
 */
public record MongoExercise(
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
