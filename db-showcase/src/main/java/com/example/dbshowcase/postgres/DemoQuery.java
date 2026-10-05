package com.example.dbshowcase.postgres;

/** 預先寫好的示範查詢。 */
public record DemoQuery(
        String id,
        String topic,
        String title,
        String description,
        String sql) {
}
