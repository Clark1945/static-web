package com.example.dbshowcase.common;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 所有資料庫 API 共用的錯誤格式：{ "error": "訊息" }。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** 語法錯誤、逾時等，把資料庫的原始訊息回給前端，方便學習除錯。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> dataAccess(DataAccessException e) {
        String message = e.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", message == null ? e.getMessage() : message));
    }
}
