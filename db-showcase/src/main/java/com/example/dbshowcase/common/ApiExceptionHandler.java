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

    /** Redis 連不上（容器沒啟動）時，給一個看得懂的訊息。 */
    @ExceptionHandler(redis.clients.jedis.exceptions.JedisConnectionException.class)
    public ResponseEntity<Map<String, String>> redisDown(redis.clients.jedis.exceptions.JedisConnectionException e) {
        return ResponseEntity.status(503).body(Map.of("error",
                "連不上 Redis：" + e.getMessage() + "\n請確認 redis-lab 容器有在執行（docker compose up -d redis）。"));
    }

    /** Cassandra 連不上（容器沒啟動，或還在啟動中：要將近一分鐘）。 */
    @ExceptionHandler(com.datastax.oss.driver.api.core.AllNodesFailedException.class)
    public ResponseEntity<Map<String, String>> cassandraDown(com.datastax.oss.driver.api.core.AllNodesFailedException e) {
        return ResponseEntity.status(503).body(Map.of("error",
                "連不上 Cassandra：" + e.getMessage() + "\n請確認 cassandra-lab 容器有在執行（docker compose up -d cassandra），剛啟動的話要等將近一分鐘。"));
    }

    /** Neo4j 連不上（容器沒啟動）。 */
    @ExceptionHandler(org.neo4j.driver.exceptions.ServiceUnavailableException.class)
    public ResponseEntity<Map<String, String>> neo4jDown(org.neo4j.driver.exceptions.ServiceUnavailableException e) {
        return ResponseEntity.status(503).body(Map.of("error",
                "連不上 Neo4j：" + e.getMessage() + "\n請確認 neo4j-lab 容器有在執行（docker compose up -d neo4j）。"));
    }

    /** 後端自己寫的 Cypher 出錯時（例如實驗室建索引的語法錯誤），把 Neo4j 的訊息回給前端。 */
    @ExceptionHandler(org.neo4j.driver.exceptions.Neo4jException.class)
    public ResponseEntity<Map<String, String>> neo4j(org.neo4j.driver.exceptions.Neo4jException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.code() + "：" + e.getMessage()));
    }

    /** 實驗室裡後端自己組的 CQL 出錯時（例如權限、逾時），把 Cassandra 的訊息回給前端。 */
    @ExceptionHandler(com.datastax.oss.driver.api.core.DriverException.class)
    public ResponseEntity<Map<String, String>> cassandra(com.datastax.oss.driver.api.core.DriverException e) {
        return ResponseEntity.badRequest().body(Map.of("error", com.example.dbshowcase.cassandra.CqlShell.describe(e)));
    }

    /** Elasticsearch 連不上（容器沒啟動，或還在啟動中）。 */
    @ExceptionHandler(com.example.dbshowcase.elastic.EsClient.UnavailableException.class)
    public ResponseEntity<Map<String, String>> elasticDown(com.example.dbshowcase.elastic.EsClient.UnavailableException e) {
        return ResponseEntity.status(503).body(Map.of("error",
                e.getMessage() + "\n請確認 es-lab 容器有在執行（docker compose up -d elasticsearch），剛啟動的話要等半分鐘左右。"));
    }

    /** InfluxDB 連不上（容器沒啟動）。 */
    @ExceptionHandler(com.example.dbshowcase.influx.InfluxClient.UnavailableException.class)
    public ResponseEntity<Map<String, String>> influxDown(com.example.dbshowcase.influx.InfluxClient.UnavailableException e) {
        return ResponseEntity.status(503).body(Map.of("error",
                e.getMessage() + "\n請確認 influx-lab 容器有在執行（docker compose up -d influxdb）。"));
    }
}
