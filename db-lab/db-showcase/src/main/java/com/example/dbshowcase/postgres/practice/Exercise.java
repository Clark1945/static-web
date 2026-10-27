package com.example.dbshowcase.postgres.practice;

import java.util.List;

/**
 * 練習題（內容來自 postgres/exercises.yml）。
 * mode = "write" 的題目在寫入沙盒執行（一律 ROLLBACK），批改時比對最後一個回傳的資料列（通常是 RETURNING）。
 */
public record Exercise(
        String id,
        String chapter,
        String title,
        String prompt,
        List<String> hints,
        boolean ordered,
        String mode,
        String answer,
        String explanation) {

    public boolean isWrite() {
        return "write".equals(mode);
    }

    /** 給前端的版本：不含答案與解說。 */
    public record View(String id, String chapter, String title, String prompt, List<String> hints,
                       boolean ordered, boolean write) {
    }

    public View view() {
        return new View(id, chapter, title, prompt, hints, ordered, isWrite());
    }
}
