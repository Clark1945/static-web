package com.example.dbshowcase.influx;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * 呼叫 InfluxDB 2.x 的 HTTP API（influx-lab 容器）：
 * Flux（/api/v2/query，回傳 annotated CSV）、InfluxQL（1.x 相容的 /query，回傳 JSON）、line protocol 寫入（/api/v2/write）。
 * InfluxDB 2 沒有角色，權限綁在 token 上：admin（初始化時建立）、reader、learner（展示台啟動時建立，見 InfluxSetup）。
 */
@Component
public class InfluxClient {

    public enum User { ADMIN, READER, LEARNER }

    /** 連不上 influx-lab（容器沒啟動）。 */
    public static class UnavailableException extends IllegalStateException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record Response(int status, String body, double millis) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Flux 的一個結果表：result、table 編號、欄位、型別、資料列（值已依型別轉換）。 */
    public record FluxTable(String result, int table, List<String> columns, List<String> types, List<List<Object>> rows) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final String base;
    private final String org;
    private final String adminToken;
    private volatile String readerToken;
    private volatile String learnerToken;

    public InfluxClient(@Value("${showcase.influx.url}") String base,
                        @Value("${showcase.influx.org}") String org,
                        @Value("${showcase.influx.admin-token}") String adminToken,
                        ObjectMapper json) {
        this.base = base.replaceAll("/+$", "");
        this.org = org;
        this.adminToken = adminToken;
        this.json = json;
    }

    public String org() {
        return org;
    }

    void setTokens(String reader, String learner) {
        this.readerToken = reader;
        this.learnerToken = learner;
    }

    private String token(User user) {
        String t = switch (user) {
            case ADMIN -> adminToken;
            case READER -> readerToken;
            case LEARNER -> learnerToken;
        };
        if (t == null) {
            throw new IllegalStateException("InfluxDB 的 token 還沒建立好（展示台啟動時建立，influx-lab 容器有啟動嗎？）");
        }
        return t;
    }

    public Response send(User user, String method, String pathAndQuery, String contentType, String body, String accept) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Token " + token(user))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        if (accept != null) {
            b.header("Accept", accept);
        }
        long start = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body(), (System.nanoTime() - start) / 1e6);
        } catch (IOException e) {
            throw new UnavailableException("連不上 InfluxDB：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("請求被中斷", e);
        }
    }

    /** 管理用的 JSON API：失敗就丟出例外。 */
    public JsonNode admin(String method, String path, Object body) {
        Response r = send(User.ADMIN, method, path, "application/json", body == null ? null : write(body), "application/json");
        if (!r.ok()) {
            throw new IllegalStateException(method + " " + path + " 失敗（" + r.status() + "）：" + r.body());
        }
        return parse(r.body());
    }

    // ---------------------------------------------------------------- 查詢與寫入

    public Response influxql(User user, String db, String q) {
        String path = "/query?db=" + enc(db) + "&epoch=s&q=" + enc(q);
        return send(user, "POST", path, "application/x-www-form-urlencoded", "", "application/json");
    }

    public Response flux(User user, String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("type", "flux");
        body.put("dialect", Map.of("annotations", List.of("datatype", "group", "default"), "header", true));
        return send(user, "POST", "/api/v2/query?org=" + enc(org), "application/json", write(body), "application/csv");
    }

    public Response write(User user, String bucket, String precision, String lines) {
        return send(user, "POST", "/api/v2/write?org=" + enc(org) + "&bucket=" + enc(bucket) + "&precision=" + precision,
                "text/plain; charset=utf-8", lines, "application/json");
    }

    /** 解析 Flux 的 annotated CSV：#datatype、#group、#default 三行註記 + 標題列，表與表之間空一行。 */
    public static List<FluxTable> parseCsv(String csv) {
        List<FluxTable> tables = new ArrayList<>();
        List<String> types = null;
        List<String> header = null;
        Map<String, FluxTable> byKey = new LinkedHashMap<>();
        for (String raw : csv.replace("\r", "").split("\n")) {
            if (raw.isBlank()) {
                header = null;
                continue;
            }
            List<String> cells = splitCsv(raw);
            if (cells.get(0).equals("#datatype")) {
                types = cells;
                header = null;
                continue;
            }
            if (cells.get(0).startsWith("#")) {
                continue;
            }
            if (header == null) {
                header = cells;
                continue;
            }
            int resultIdx = header.indexOf("result"), tableIdx = header.indexOf("table");
            String result = resultIdx >= 0 ? cells.get(resultIdx) : "_result";
            int table = tableIdx >= 0 && !cells.get(tableIdx).isEmpty() ? Integer.parseInt(cells.get(tableIdx)) : 0;
            List<String> cols = new ArrayList<>();
            List<String> ts = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            for (int i = 1; i < header.size(); i++) {
                if (i == resultIdx || i == tableIdx) {
                    continue;
                }
                cols.add(header.get(i));
                ts.add(types == null ? "string" : types.get(i));
                idx.add(i);
            }
            String key = result + "#" + table + "#" + String.join(",", cols);
            FluxTable t = byKey.computeIfAbsent(key, k -> {
                FluxTable n = new FluxTable(result, table, cols, ts, new ArrayList<>());
                tables.add(n);
                return n;
            });
            List<Object> row = new ArrayList<>();
            for (int j = 0; j < idx.size(); j++) {
                row.add(convert(cells.get(idx.get(j)), ts.get(j)));
            }
            t.rows().add(row);
        }
        return tables;
    }

    private static Object convert(String v, String type) {
        if (v.isEmpty()) {
            return null;
        }
        return switch (type) {
            case "long", "unsignedLong" -> Long.parseLong(v);
            case "double" -> Double.parseDouble(v);
            case "boolean" -> Boolean.parseBoolean(v);
            default -> v;
        };
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    public JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return TextNode.valueOf("");
        }
        try {
            return json.readTree(text);
        } catch (IOException e) {
            return TextNode.valueOf(text);
        }
    }

    public String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
