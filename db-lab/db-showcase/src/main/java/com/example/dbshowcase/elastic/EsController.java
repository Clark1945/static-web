package com.example.dbshowcase.elastic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

/** Elasticsearch 總覽、主控台、重新載入：/api/elastic/… */
@RestController
@RequestMapping("/api/elastic")
public class EsController {

    public record ScriptRequest(String commands) {
    }

    public record Field(String name, String type, String detail) {
    }

    public record Index(String name, String design, long docs, String size, List<Field> fields, String sample) {
    }

    public record Overview(List<Index> indices, String version, EsDataLoader.Status load) {
    }

    private static final Map<String, String[]> DESIGN = Map.of(
            "products", new String[] {"商品（從 PostgreSQL 複製）。name、description 用 cjk 分析器（中文兩字一組），brand、category、tags 是 keyword；rating 是評論的平均星等",
                    "GET /products/_search\n{ \"query\": { \"match\": { \"description\": \"降噪\" } } }"},
            "reviews", new String[] {"商品評論（依購買紀錄用模板產生）。content 用 cjk 分析器，content.std 是預設的 standard 分析器（一字一詞），可以比較",
                    "GET /reviews/_search\n{ \"query\": { \"match\": { \"content\": \"音質\" } } }"},
            "orders", new String[] {"訂單（從 PostgreSQL 複製）。items 是 nested：每個明細是獨立的隱藏文件，條件可以要求「同一個明細」同時成立",
                    "GET /orders/_doc/77621"},
            "orders_object", new String[] {"同樣的訂單，但 items 是一般的 object（陷阱題用）：明細的欄位會被攤平成陣列，失去「同一個明細」的關係",
                    "GET /orders_object/_mapping"},
            "logs", new String[] {"API 存取紀錄（2026 年 9 月，用固定亂數種子產生）。ELK 最典型的用途：查錯誤、看趨勢、找事故",
                    "GET /logs/_search\n{ \"size\": 3, \"sort\": [{ \"@timestamp\": \"desc\" }] }"});

    private final EsClient es;
    private final EsShell shell;
    private final EsDataLoader loader;

    public EsController(EsClient es, EsShell shell, EsDataLoader loader) {
        this.es = es;
        this.shell = shell;
        this.loader = loader;
    }

    @GetMapping("/overview")
    public Overview overview() {
        List<Index> indices = new ArrayList<>();
        String version = null;
        try {
            version = es.admin("GET", "/", null).path("version").path("number").asText();
            JsonNode cat = es.admin("GET", "/_cat/indices/" + String.join(",", EsDataLoader.INDICES) + "?format=json&bytes=b&ignore_unavailable=true", null);
            for (String name : EsDataLoader.INDICES) {
                JsonNode row = null;
                for (JsonNode r : cat) {
                    if (r.path("index").asText().equals(name)) {
                        row = r;
                    }
                }
                if (row == null) {
                    continue;
                }
                JsonNode props = es.admin("GET", "/" + name + "/_mapping", null).path(name).path("mappings").path("properties");
                List<Field> fields = new ArrayList<>();
                flatten("", props, fields);
                String[] d = DESIGN.get(name);
                // _cat 的 docs.count 是 Lucene 層級（nested 明細各算一份），總覽用 _count（最上層的文件數）
                long docs = es.admin("GET", "/" + name + "/_count", null).path("count").asLong();
                indices.add(new Index(name, d[0], docs, mb(row.path("store.size").asLong()), fields, d[1]));
            }
        } catch (IllegalStateException e) {
            // 容器沒啟動：回傳空的總覽，頁面顯示載入狀態
        }
        return new Overview(indices, version, loader.status());
    }

    private static String mb(long bytes) {
        return bytes >= 1048576 ? String.format("%.1f MB", bytes / 1048576.0) : String.format("%d KB", bytes / 1024);
    }

    /** 把 mapping 攤平成「欄位 → 型別」清單；text 欄位附上分析器與子欄位。 */
    private static void flatten(String prefix, JsonNode props, List<Field> out) {
        props.fields().forEachRemaining(e -> {
            String name = prefix + e.getKey();
            JsonNode def = e.getValue();
            String type = def.path("type").asText(def.has("properties") ? "object" : "");
            List<String> detail = new ArrayList<>();
            if (def.has("analyzer")) {
                detail.add("analyzer: " + def.path("analyzer").asText());
            }
            if (def.has("fields")) {
                def.path("fields").fields().forEachRemaining(f -> detail.add("." + f.getKey() + "（" + f.getValue().path("type").asText()
                        + (f.getValue().has("analyzer") ? "，" + f.getValue().path("analyzer").asText() : "") + "）"));
            }
            if (name.equals("specs")) {
                out.add(new Field(name, "object", "依商品類別各有不同欄位（dynamic mapping）"));
                return;
            }
            out.add(new Field(name, type, String.join("、", detail)));
            if (def.has("properties")) {
                flatten(name + ".", def.path("properties"), out);
            }
        });
    }

    @GetMapping("/load")
    public EsDataLoader.Status load() {
        return loader.status();
    }

    @PostMapping("/reset")
    public EsDataLoader.Status reset() {
        return loader.reload();
    }

    /** 主控台：用 learner 身分執行（練習資料只能讀，scratch* 可以隨意寫）。 */
    @PostMapping("/run")
    public EsShell.RunResult run(@RequestBody ScriptRequest request) {
        return shell.forDisplay(shell.run(EsClient.User.LEARNER, request.commands()));
    }
}
