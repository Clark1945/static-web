package com.example.dbshowcase.elastic.practice;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.example.dbshowcase.elastic.EsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 寫入題與會寫入的陷阱題用的隔離區：清掉所有 scratch* 索引 → 依 fixture 從練習資料複製（同樣的 settings 與 mapping）→ 執行 → 清掉。
 * fixture 的格式：scratch 索引名稱 → { "source": 來源索引, "query": 篩選條件（可省略） }。
 * 只有一組 scratch，同一時間只允許一個人使用（synchronized）。
 */
@Component
public class EsScratch {

    private final EsClient es;

    public EsScratch(EsClient es) {
        this.es = es;
    }

    public synchronized <T> T use(Map<String, Map<String, Object>> fixture, Supplier<T> work) {
        try {
            clear();
            fixture.forEach((target, spec) -> copy((String) spec.get("source"), target, spec.get("query")));
            return work.get();
        } finally {
            clear();
        }
    }

    /** action.destructive_requires_name = true：不能用 DELETE /scratch*，要先列出名稱再逐一刪除。 */
    public void clear() {
        JsonNode list = es.admin("GET", "/_cat/indices/scratch*?format=json&h=index&expand_wildcards=all", null);
        for (JsonNode row : list) {
            es.send(EsClient.User.ADMIN, "DELETE", "/" + row.path("index").asText(), null);
        }
    }

    private void copy(String source, String target, Object query) {
        JsonNode src = es.admin("GET", "/" + source, null).path(source);
        ObjectNode def = es.json().createObjectNode();
        ObjectNode settings = def.putObject("settings");
        settings.put("number_of_shards", 1).put("number_of_replicas", 0);
        JsonNode analysis = src.path("settings").path("index").path("analysis");
        if (!analysis.isMissingNode()) {
            settings.set("analysis", analysis);
        }
        def.set("mappings", src.path("mappings"));
        es.admin("PUT", "/" + target, def);
        Map<String, Object> body = query == null
                ? Map.of("source", Map.of("index", source), "dest", Map.of("index", target))
                : Map.of("source", Map.of("index", source, "query", query), "dest", Map.of("index", target));
        es.admin("POST", "/_reindex?refresh=true", body);
    }
}
