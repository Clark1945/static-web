package com.example.dbshowcase.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

/** MongoDB 的 API，全部掛在 /api/mongo 底下。 */
@RestController
@RequestMapping("/api/mongo")
public class MongoController {

    public record ScriptRequest(String commands) {
    }

    public record CollectionInfo(String name, long count, long avgObjSize, long size, long indexSize,
                                 List<String> indexes, String design, Object sample) {
    }

    public record Overview(List<CollectionInfo> collections, String version, Double loadSeconds) {
    }

    /** 每個 collection 的設計說明與範例文件。 */
    private static final Map<String, Object[]> DESIGN = Map.of(
            "orders", new Object[] {"訂單明細「內嵌」在 items 陣列：一次讀取就拿到整張訂單，不用 JOIN", 77621},
            "products", new Object[] {"分類名稱與完整路徑「反正規化」放進 category；specs 是各分類不同的規格", 540},
            "customers", new Object[] {"沒填城市、生日的會員就沒有這個欄位；email 有唯一索引", 1},
            "categories", new Object[] {"同時存 parentId（找上層）與 ancestors（一次拿到所有祖先）", 5});
    private static final List<String> ORDER = List.of("orders", "products", "customers", "categories");

    private final MongoDataLoader loader;
    private final MongoShell shell;
    private final MongoClient admin;
    private final MongoClient learner;

    public MongoController(MongoDataLoader loader, MongoShell shell,
                           @Qualifier("mongoAdmin") MongoClient admin, @Qualifier("mongoLearner") MongoClient learner) {
        this.loader = loader;
        this.shell = shell;
        this.admin = admin;
        this.learner = learner;
    }

    @GetMapping("/overview")
    public Overview overview() {
        MongoDatabase db = admin.getDatabase(MongoConfig.SHOP);
        List<CollectionInfo> out = new ArrayList<>();
        for (String name : ORDER) {
            Document stats = db.getCollection(name).aggregate(List.of(
                    new Document("$collStats", new Document("storageStats", new Document())))).first();
            Document s = stats == null ? new Document() : stats.get("storageStats", new Document());
            List<String> indexes = new ArrayList<>();
            db.getCollection(name).listIndexes().forEach(i -> indexes.add(i.getString("name")));
            Object[] design = DESIGN.get(name);
            Object sample = MongoShell.plain(db.getCollection(name).find(new Document("_id", design[1])).first());
            out.add(new CollectionInfo(name, number(s, "count"), number(s, "avgObjSize"), number(s, "size"),
                    number(s, "totalIndexSize"), indexes, (String) design[0], sample));
        }
        String version = admin.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version");
        MongoDataLoader.LoadResult last = loader.lastLoad();
        return new Overview(out, version, last == null ? null : last.seconds());
    }

    /** 指令主控台：用 learner 身分在 shop 執行。寫入會保留，按「重置資料」才會還原。 */
    @PostMapping("/run")
    public MongoShell.RunResult run(@RequestBody ScriptRequest request) {
        return shell.run(learner.getDatabase(MongoConfig.SHOP), shell.parse(request.commands()), 100);
    }

    @PostMapping("/reset")
    public MongoDataLoader.LoadResult reset() {
        return loader.load();
    }

    private static long number(Document d, String key) {
        return d.get(key) instanceof Number n ? n.longValue() : 0;
    }
}
