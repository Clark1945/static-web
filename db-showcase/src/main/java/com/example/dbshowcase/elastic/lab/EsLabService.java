package com.example.dbshowcase.elastic.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.dbshowcase.common.YamlContent;
import com.example.dbshowcase.elastic.EsClient;
import com.example.dbshowcase.elastic.EsShell;
import com.example.dbshowcase.elastic.practice.EsScratch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Elasticsearch 實驗室：
 * 1. 分析器：同一段文字經過不同分析器切出哪些詞（_analyze），以及 cjk 與 standard 分析器的搜尋結果差多少
 * 2. 相關性：步驟式的查詢比較（relevance-lab.yml），可以看第一名的分數怎麼算（explain）
 * 3. 寫入行為：步驟式的請求（behavior-lab.yml），在 scratch 索引上示範近即時、版本衝突、深分頁、mapping 不能改
 */
@Service
public class EsLabService {

    private static final Set<String> ANALYZERS = Set.of("standard", "cjk", "whitespace", "simple", "keyword", "english");

    public record Step(String id, String title, String goal, String commands, String question, String takeaway) {
    }

    public record Tokens(String analyzer, List<String> tokens) {
    }

    public record Hit(String id, Double score, String text) {
    }

    public record Compare(String label, String request, long total, List<Hit> hits) {
    }

    public record ExplainLine(int depth, double value, String description) {
    }

    public record Explained(String id, double score, String text, List<ExplainLine> lines) {
    }

    private final EsClient es;
    private final EsShell shell;
    private final EsScratch scratch;
    private final ObjectMapper json;
    private final List<Step> relevance;
    private final List<Step> behavior;

    public EsLabService(EsClient es, EsShell shell, EsScratch scratch, ObjectMapper json) {
        this.es = es;
        this.shell = shell;
        this.scratch = scratch;
        this.json = json;
        this.relevance = YamlContent.load("elastic/relevance-lab.yml", Step.class, json);
        this.behavior = YamlContent.load("elastic/behavior-lab.yml", Step.class, json);
    }

    // ================================================================ 1. 分析器

    public List<Tokens> analyze(String text, List<String> analyzers) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.length() > 200) {
            throw new IllegalArgumentException("請輸入 1～200 個字。");
        }
        List<Tokens> out = new ArrayList<>();
        for (String a : analyzers) {
            if (!ANALYZERS.contains(a)) {
                throw new IllegalArgumentException("分析器只能是 " + ANALYZERS);
            }
            JsonNode r = es.admin("POST", "/_analyze", Map.of("analyzer", a, "text", t));
            List<String> tokens = new ArrayList<>();
            r.path("tokens").forEach(k -> tokens.add(k.path("token").asText()));
            out.add(new Tokens(a, tokens));
        }
        return out;
    }

    /** 同一段文字，用 cjk 與 standard 分析的欄位、match 與 match_phrase 各搜一次評論。 */
    public List<Compare> compare(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.length() > 50) {
            throw new IllegalArgumentException("請輸入 1～50 個字。");
        }
        List<Compare> out = new ArrayList<>();
        String[][] variants = {
                {"match，content（cjk：兩字一組）", "match", "content"},
                {"match，content.std（standard：一字一詞）", "match", "content.std"},
                {"match_phrase，content（cjk）", "match_phrase", "content"},
                {"match_phrase，content.std（standard）", "match_phrase", "content.std"}};
        for (String[] v : variants) {
            Map<String, Object> body = Map.of("size", 5, "track_total_hits", true, "_source", List.of("content"),
                    "query", Map.of(v[1], Map.of(v[2], t)));
            JsonNode r = es.admin("POST", "/reviews/_search", body);
            List<Hit> hits = new ArrayList<>();
            r.path("hits").path("hits").forEach(h -> hits.add(new Hit(h.path("_id").asText(), h.path("_score").asDouble(),
                    h.path("_source").path("content").asText())));
            String request = "GET /reviews/_search\n" + pretty(Map.of("query", Map.of(v[1], Map.of(v[2], t))));
            out.add(new Compare(v[0], request, r.path("hits").path("total").path("value").asLong(), hits));
        }
        return out;
    }

    private String pretty(Object o) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    // ================================================================ 2. 相關性

    public List<Step> relevanceSteps() {
        return relevance;
    }

    public EsShell.RunResult runReadOnly(String commands) {
        return shell.forDisplay(shell.run(EsClient.User.READER, commands));
    }

    /** 只取第一個請求：加上 explain，回傳前 3 名的分數計算過程（攤平成一行一行）。 */
    public List<Explained> explain(String commands) {
        EsShell.Request r = shell.parse(commands).get(0);
        if (!r.path().contains("_search")) {
            throw new IllegalArgumentException("只有 _search 請求可以看分數計算。");
        }
        ObjectNode body;
        try {
            body = r.body() == null ? json.createObjectNode() : (ObjectNode) json.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalArgumentException("請求內容不是合法的 JSON");
        }
        body.put("explain", true);
        body.put("size", 3);
        EsClient.Response resp = es.send(EsClient.User.READER, "POST", r.path(), body.toString());
        if (!resp.ok()) {
            throw new IllegalArgumentException("執行失敗：" + resp.body());
        }
        List<Explained> out = new ArrayList<>();
        for (JsonNode h : resp.body().path("hits").path("hits")) {
            List<ExplainLine> lines = new ArrayList<>();
            flatten(h.path("_explanation"), 0, lines);
            out.add(new Explained(h.path("_id").asText(), h.path("_score").asDouble(), summary(h.path("_source")), lines));
        }
        return out;
    }

    private static void flatten(JsonNode node, int depth, List<ExplainLine> out) {
        if (node.isMissingNode() || out.size() > 80) {
            return;
        }
        out.add(new ExplainLine(depth, node.path("value").asDouble(), node.path("description").asText()));
        for (JsonNode d : node.path("details")) {
            flatten(d, depth + 1, out);
        }
    }

    private static String summary(JsonNode source) {
        for (String f : List.of("name", "title", "content", "message")) {
            if (source.has(f)) {
                String s = source.path(f).asText();
                if (f.equals("title") && source.has("content")) {
                    s += "｜" + source.path("content").asText();
                }
                return s;
            }
        }
        return source.toString();
    }

    // ================================================================ 3. 寫入行為

    public List<Step> behaviorSteps() {
        return behavior;
    }

    /** 每一步在乾淨的 scratch 上用 learner 執行，結束後清掉。 */
    public EsShell.RunResult runScratch(String commands) {
        List<EsShell.Request> requests = shell.parse(commands);
        return scratch.use(Map.of(), () -> shell.forDisplay(shell.run(EsClient.User.LEARNER, requests)));
    }
}
