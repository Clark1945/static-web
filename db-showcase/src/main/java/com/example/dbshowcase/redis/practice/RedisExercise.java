package com.example.dbshowcase.redis.practice;

import java.util.List;

/**
 * Redis 練習題（內容來自 redis/exercises.yml）。
 *
 * @param keys    批改前要從 db 0 複製到隔離 db 的 key
 * @param setup   批改前在隔離 db 先執行的指令（例如先放一把別人的鎖）
 * @param check   執行完之後用來檢查資料狀態的指令；有設定時比對這些指令的結果，沒有時比對最後一個指令的回傳值
 * @param ordered false 時，回傳的陣列先排序再比對（SMEMBERS、SINTER 這類沒有順序的結果）
 */
public record RedisExercise(
        String id,
        String chapter,
        String title,
        String prompt,
        List<String> hints,
        List<String> keys,
        String setup,
        String answer,
        String check,
        Boolean ordered,
        String explanation) {

    public boolean isOrdered() {
        return ordered == null || ordered;
    }

    public List<String> keysOrEmpty() {
        return keys == null ? List.of() : keys;
    }

    public record View(String id, String chapter, String title, String prompt, List<String> hints,
                       List<String> keys, boolean ordered, boolean checksState) {
    }

    public View view() {
        return new View(id, chapter, title, prompt, hints, keysOrEmpty(), isOrdered(), check != null && !check.isBlank());
    }
}
