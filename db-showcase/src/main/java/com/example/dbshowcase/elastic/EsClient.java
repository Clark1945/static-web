package com.example.dbshowcase.elastic;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * 呼叫 Elasticsearch REST API（es-lab 容器）。
 * 不用官方的 Java client：加了它 Spring Boot 會自動建立連到 localhost:9200 的連線；這裡只需要「送出一個 HTTP 請求」，
 * 而且展示台要原封不動地執行使用者寫的 Dev Tools 請求，用 JDK 的 HttpClient 最直接。
 *
 * 三個身分：elastic（超級使用者，建索引、建帳號、載入資料、實驗室）、reader（只能讀練習資料）、learner（只能寫 scratch* 索引）。
 */
@Component
public class EsClient {

    public enum User { ADMIN, READER, LEARNER }

    /** 連不上 es-lab（容器沒啟動）。 */
    public static class UnavailableException extends IllegalStateException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 一次請求的結果：HTTP 狀態碼、回應內容（JSON 解析失敗時是文字）、耗時。 */
    public record Response(int status, JsonNode body, double millis) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final String base;
    private final String admin;
    private final String reader;
    private final String learner;

    public EsClient(@Value("${showcase.elasticsearch.url}") String base,
                    @Value("${showcase.elasticsearch.admin-password}") String adminPassword,
                    @Value("${showcase.elasticsearch.reader-password}") String readerPassword,
                    @Value("${showcase.elasticsearch.learner-password}") String learnerPassword,
                    ObjectMapper json) {
        this.base = base.replaceAll("/+$", "");
        this.json = json;
        this.admin = basic("elastic", adminPassword);
        this.reader = basic("reader", readerPassword);
        this.learner = basic("learner", learnerPassword);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public Response send(User user, String method, String path, String body) {
        return send(user, method, path, body, Duration.ofSeconds(30));
    }

    /** body 可以是 null；_bulk 這類 NDJSON 請求的 body 是多行文字。 */
    public Response send(User user, String method, String path, String body, Duration timeout) {
        String p = path.startsWith("/") ? path : "/" + path;
        boolean ndjson = p.contains("_bulk") || p.contains("_msearch");
        HttpRequest.BodyPublisher publisher = body == null || body.isBlank()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(ndjson && !body.endsWith("\n") ? body + "\n" : body, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + p))
                .timeout(timeout)
                .header("Authorization", switch (user) {
                    case ADMIN -> admin;
                    case READER -> reader;
                    case LEARNER -> learner;
                })
                .header("Content-Type", ndjson ? "application/x-ndjson" : "application/json")
                .method(method, publisher)
                .build();
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            double millis = (System.nanoTime() - start) / 1e6;
            return new Response(response.statusCode(), parse(response.body()), millis);
        } catch (IOException e) {
            throw new UnavailableException("連不上 Elasticsearch：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("請求被中斷", e);
        }
    }

    /** 管理用：失敗就丟出例外。 */
    public JsonNode admin(String method, String path, Object body) {
        Response r = send(User.ADMIN, method, path, body == null ? null : body instanceof String s ? s : write(body), Duration.ofMinutes(5));
        if (!r.ok()) {
            throw new IllegalStateException(method + " " + path + " 失敗（" + r.status() + "）：" + r.body());
        }
        return r.body();
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return TextNode.valueOf("");
        }
        try {
            return json.readTree(text);
        } catch (IOException e) {
            return TextNode.valueOf(text);           // _cat API 預設回傳純文字表格
        }
    }

    public String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public ObjectMapper json() {
        return json;
    }
}
