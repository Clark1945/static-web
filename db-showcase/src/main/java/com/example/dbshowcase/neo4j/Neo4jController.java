package com.example.dbshowcase.neo4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Neo4j 的 API，全部掛在 /api/neo4j 底下。 */
@RestController
@RequestMapping("/api/neo4j")
public class Neo4jController {

    public record ScriptRequest(String commands) {
    }

    public record LabelInfo(String label, long count, List<String> properties, String design, String sample) {
    }

    public record RelInfo(String type, long count, String pattern, String design) {
    }

    public record IndexInfo(String name, String type, String entity, List<String> labels, List<String> properties,
                            String owningConstraint) {
    }

    public record Overview(List<LabelInfo> labels, List<RelInfo> relationships, List<IndexInfo> indexes,
                           CypherShell.Graph schema, String version, Neo4jDataLoader.Status load) {
    }

    private static final Map<String, String[]> LABELS = new LinkedHashMap<>();
    private static final Map<String, String[]> RELS = new LinkedHashMap<>();

    static {
        LABELS.put("Customer", new String[] {"會員；城市不是屬性，而是 LIVES_IN 關係指向 City 節點",
                "MATCH (c:Customer {id: 4242}) RETURN c"});
        LABELS.put("Order", new String[] {"訂單；orderDate 是含時區的 datetime",
                "MATCH (o:Order {id: 77621}) RETURN o"});
        LABELS.put("Product", new String[] {"商品；tags 是字串清單",
                "MATCH (p:Product {id: 540}) RETURN p"});
        LABELS.put("Category", new String[] {"分類樹：子分類 -[:SUBCATEGORY_OF]-> 上層分類",
                "MATCH (c:Category)-[:SUBCATEGORY_OF]->(p:Category) RETURN c, p"});
        LABELS.put("City", new String[] {"城市；會員住在這裡（LIVES_IN），訂單寄到這裡（SHIPPED_TO）",
                "MATCH (c:City) RETURN c.name"});
        LABELS.put("Brand", new String[] {"品牌（商品名稱的第一個字）",
                "MATCH (b:Brand)<-[:MADE_BY]-(p:Product) RETURN b.name, count(p) ORDER BY count(p) DESC LIMIT 5"});
        RELS.put("PLACED", new String[] {"(:Customer)-[:PLACED]->(:Order)", "會員下了這筆訂單"});
        RELS.put("CONTAINS", new String[] {"(:Order)-[:CONTAINS]->(:Product)", "訂單明細；數量、單價、折扣放在關係的屬性上"});
        RELS.put("FOLLOWS", new String[] {"(:Customer)-[:FOLLOWS]->(:Customer)", "會員追蹤會員（模擬的社群資料，固定亂數種子產生）"});
        RELS.put("SHIPPED_TO", new String[] {"(:Order)-[:SHIPPED_TO]->(:City)", "訂單的寄送城市"});
        RELS.put("LIVES_IN", new String[] {"(:Customer)-[:LIVES_IN]->(:City)", "會員住的城市（沒填城市的會員沒有這條關係）"});
        RELS.put("IN_CATEGORY", new String[] {"(:Product)-[:IN_CATEGORY]->(:Category)", "商品屬於哪個分類"});
        RELS.put("MADE_BY", new String[] {"(:Product)-[:MADE_BY]->(:Brand)", "商品的品牌"});
        RELS.put("SUBCATEGORY_OF", new String[] {"(:Category)-[:SUBCATEGORY_OF]->(:Category)", "分類樹"});
    }

    private final Driver driver;
    private final CypherShell shell;
    private final Neo4jDataLoader loader;

    public Neo4jController(Driver driver, CypherShell shell, Neo4jDataLoader loader) {
        this.driver = driver;
        this.shell = shell;
        this.loader = loader;
    }

    @GetMapping("/overview")
    public Overview overview() {
        try (Session s = driver.session(SessionConfig.forDatabase(Neo4jConfig.DATABASE))) {
            List<LabelInfo> labels = new ArrayList<>();
            for (Map.Entry<String, String[]> e : LABELS.entrySet()) {
                // 計數用 count store，不用掃節點；屬性名稱從前 100 個節點收集
                long n = s.run("MATCH (n:" + e.getKey() + ") RETURN count(n) AS n").single().get("n").asLong();
                List<String> props = s.run("MATCH (n:" + e.getKey() + ") WITH n LIMIT 100 UNWIND keys(n) AS k RETURN DISTINCT k ORDER BY k")
                        .list(r -> r.get("k").asString());
                labels.add(new LabelInfo(e.getKey(), n, props, e.getValue()[0], e.getValue()[1]));
            }
            List<RelInfo> rels = new ArrayList<>();
            for (Map.Entry<String, String[]> e : RELS.entrySet()) {
                long n = s.run("MATCH ()-[r:" + e.getKey() + "]->() RETURN count(r) AS n").single().get("n").asLong();
                rels.add(new RelInfo(e.getKey(), n, e.getValue()[0], e.getValue()[1]));
            }
            String version = s.run("CALL dbms.components() YIELD versions, edition RETURN versions[0] + ' ' + edition AS v")
                    .single().get("v").asString();
            CypherShell.Outcome schema = shell.run("CALL db.schema.visualization()", 10).last();
            return new Overview(labels, rels, indexes(s), schema.graph(), version, loader.status());
        }
    }

    static List<IndexInfo> indexes(Session s) {
        return s.run("SHOW INDEXES YIELD name, type, entityType, labelsOrTypes, properties, owningConstraint "
                        + "WHERE type <> 'LOOKUP' RETURN * ORDER BY name")
                .list(r -> new IndexInfo(r.get("name").asString(), r.get("type").asString(), r.get("entityType").asString(),
                        r.get("labelsOrTypes").asList(v -> v.asString()), r.get("properties").asList(v -> v.asString()),
                        r.get("owningConstraint").isNull() ? null : r.get("owningConstraint").asString()));
    }

    /** 主控台：所有句子在同一個交易裡執行，最後 ROLLBACK。 */
    @PostMapping("/run")
    public CypherShell.RunResult run(@RequestBody ScriptRequest request) {
        return shell.run(request.commands(), 200);
    }

    @GetMapping("/load")
    public Neo4jDataLoader.Status load() {
        return loader.status();
    }

    @PostMapping("/reset")
    public Neo4jDataLoader.Status reset() {
        return loader.startReload();
    }
}
