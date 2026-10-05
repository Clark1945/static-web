package com.example.dbshowcase.postgres.lab;

import java.util.List;

/** 索引實驗室的一個引導步驟（內容來自 postgres/index-lab.yml）。 */
public record LabStep(
        String id,
        String title,
        String goal,
        List<LabeledSql> sqls,
        List<String> ddl,
        String question,
        String takeaway) {

    public record LabeledSql(String label, String sql) {
    }
}
