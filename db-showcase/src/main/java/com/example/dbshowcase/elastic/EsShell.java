package com.example.dbshowcase.elastic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * 解析並執行 Kibana Dev Tools 寫法的請求：
 * <pre>
 * # 註解
 * GET /products/_search
 * { "query": { "match": { "name": "耳機" } } }
 *
 * POST /scratch/_bulk
 * {"index": {"_id": 1}}
 * {"name": "x"}
 * </pre>
 * 一行「方法 路徑」開始一個請求，接下來到下一個請求之前的行是 body。權限交給 Elasticsearch 的角色把關（reader 只能讀，learner 只能寫 scratch*）。
 */
@Component
public class EsShell {

    private static final Pattern REQUEST_LINE = Pattern.compile("^\\s*(GET|POST|PUT|DELETE|HEAD)\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_REQUESTS = 20;
    /** 顯示時每個請求最多保留幾筆 hits（批改用的是完整結果）。 */
    private static final int DISPLAY_HITS = 20;

    public record Request(String method, String path, String body, int line) {
        public String statement() {
            return method + " " + path;
        }
    }

    public record Result(String statement, String body, int status, JsonNode response, double millis) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public record RunResult(List<Result> results, double totalMillis) {
        public Result last() {
            return results.get(results.size() - 1);
        }
    }

    private final EsClient client;

    public EsShell(EsClient client) {
        this.client = client;
    }

    public List<Request> parse(String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("請輸入請求，例如：GET /products/_search");
        }
        List<Request> requests = new ArrayList<>();
        String method = null, path = null;
        int startLine = 0;
        StringBuilder body = new StringBuilder();
        String[] lines = script.replace("\r", "").split("\n", -1);
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : null;
            Matcher m = line == null ? null : REQUEST_LINE.matcher(line);
            if (line == null || m.matches()) {
                if (method != null) {
                    requests.add(build(method, path, body.toString().strip(), startLine));
                }
                if (line == null) {
                    break;
                }
                method = m.group(1).toUpperCase(Locale.ROOT);
                path = m.group(2);
                startLine = i + 1;
                body.setLength(0);
                continue;
            }
            String t = line.strip();
            if (t.startsWith("#") || t.startsWith("//")) {
                continue;                                     // 整行註解
            }
            if (method == null) {
                if (!t.isEmpty()) {
                    throw new IllegalArgumentException("第 " + (i + 1) + " 行：每個請求要用「方法 路徑」開頭，例如 GET /products/_search");
                }
                continue;
            }
            body.append(line).append('\n');
        }
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("沒有找到請求：每個請求要用「方法 路徑」開頭，例如 GET /products/_search");
        }
        if (requests.size() > MAX_REQUESTS) {
            throw new IllegalArgumentException("一次最多執行 " + MAX_REQUESTS + " 個請求。");
        }
        return requests;
    }

    private Request build(String method, String path, String body, int line) {
        String p = path.startsWith("/") ? path : "/" + path;
        if (!body.isEmpty() && !p.contains("_bulk") && !p.contains("_msearch")) {
            try {
                client.json().readTree(body);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("第 " + line + " 行「" + method + " " + p + "」的內容不是合法的 JSON："
                        + e.getOriginalMessage());
            } catch (IOException e) {
                throw new IllegalArgumentException("第 " + line + " 行「" + method + " " + p + "」的內容不是合法的 JSON");
            }
        }
        return new Request(method, p, body.isEmpty() ? null : body, line);
    }

    public RunResult run(EsClient.User user, String script) {
        return run(user, parse(script));
    }

    /** 依序執行；某一句失敗（4xx / 5xx）也繼續執行後面的，跟 Kibana 一樣把錯誤顯示出來。 */
    public RunResult run(EsClient.User user, List<Request> requests) {
        List<Result> results = new ArrayList<>();
        double total = 0;
        for (Request r : requests) {
            EsClient.Response response = client.send(user, r.method(), r.path(), r.body());
            results.add(new Result(r.statement(), r.body(), response.status(), response.body(), response.millis()));
            total += response.millis();
        }
        return new RunResult(results, total);
    }

    /** 顯示用：hits 太多時只留前 20 筆，並註記原本有幾筆。 */
    public RunResult forDisplay(RunResult run) {
        List<Result> out = new ArrayList<>();
        for (Result r : run.results()) {
            JsonNode body = r.response();
            if (body instanceof ObjectNode o && o.path("hits").path("hits") instanceof ArrayNode hits && hits.size() > DISPLAY_HITS) {
                ObjectNode copy = o.deepCopy();
                ArrayNode trimmed = ((ObjectNode) copy.get("hits")).putArray("hits");
                for (int i = 0; i < DISPLAY_HITS; i++) {
                    trimmed.add(hits.get(i));
                }
                ((ObjectNode) copy.get("hits")).set("_展示台", TextNode.valueOf("hits 共 " + hits.size() + " 筆，只顯示前 " + DISPLAY_HITS + " 筆"));
                body = copy;
            }
            out.add(new Result(r.statement(), r.body(), r.status(), body, r.millis()));
        }
        return new RunResult(out, run.totalMillis());
    }
}
