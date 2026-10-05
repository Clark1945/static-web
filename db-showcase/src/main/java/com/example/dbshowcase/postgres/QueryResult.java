package com.example.dbshowcase.postgres;

import java.util.List;

/**
 * 一次查詢的結果。
 *
 * @param columns   欄位名稱
 * @param rows      資料列（最多 max-rows 筆）
 * @param rowCount  實際回傳給前端的筆數
 * @param truncated 是否因為超過 max-rows 而被截斷
 * @param elapsedMs 資料庫執行 + 讀取結果的耗時（毫秒）
 */
public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean truncated,
        double elapsedMs) {
}
