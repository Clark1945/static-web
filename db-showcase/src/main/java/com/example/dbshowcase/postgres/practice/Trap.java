package com.example.dbshowcase.postgres.practice;

import java.util.List;

/** 陷阱題（內容來自 postgres/traps.yml）。answer 是 options 的索引。 */
public record Trap(
        String id,
        String title,
        String question,
        List<LabeledSql> sqls,
        List<String> options,
        int answer,
        String explanation) {

    public record LabeledSql(String label, String sql) {
    }

    /** 給前端的版本：不含正確答案與解說。 */
    public record View(String id, String title, String question, List<LabeledSql> sqls, List<String> options) {
    }

    public View view() {
        return new View(id, title, question, sqls, options);
    }
}
