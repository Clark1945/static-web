package com.example.dbshowcase.mongo.practice;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.example.dbshowcase.mongo.MongoConfig;
import com.example.dbshowcase.mongo.MongoShell;
import com.mongodb.client.MongoClient;

/**
 * 隔離用的 scratch 資料庫：清空 → 用 $match + $out 從 shop 複製需要的文件 → 執行 → 清空。
 * 只有一個 scratch，所以同一時間只允許一個人使用（synchronized）。
 */
@Component
public class MongoScratch {

    private final MongoClient admin;
    private final MongoClient learner;
    private final MongoClient reader;
    private final MongoShell shell;

    public MongoScratch(@Qualifier("mongoAdmin") MongoClient admin, @Qualifier("mongoLearner") MongoClient learner,
                        @Qualifier("mongoReader") MongoClient reader, MongoShell shell) {
        this.admin = admin;
        this.learner = learner;
        this.reader = reader;
        this.shell = shell;
    }

    /** 準備好 scratch 之後執行 work，結束後刪掉整個 scratch 資料庫。 */
    public synchronized <T> T use(Map<String, String> fixture, Supplier<T> work) {
        try {
            admin.getDatabase(MongoConfig.SCRATCH).drop();
            fixture.forEach((collection, filter) -> admin.getDatabase(MongoConfig.SHOP).getCollection(collection)
                    .aggregate(List.of(
                            new Document("$match", Document.parse(filter == null || filter.isBlank() ? "{}" : filter)),
                            new Document("$out", new Document("db", MongoConfig.SCRATCH).append("coll", collection))))
                    .toCollection());
            return work.get();
        } finally {
            admin.getDatabase(MongoConfig.SCRATCH).drop();
        }
    }

    /** learner 身分在 scratch 執行（寫入題）。必須在 use() 裡面呼叫。 */
    public MongoShell.RunResult runAsLearner(String script, int maxDocs) {
        return shell.run(learner.getDatabase(MongoConfig.SCRATCH), shell.parse(script), maxDocs);
    }

    /** admin 身分在 scratch 執行（檢查指令、會寫入的陷阱題）。必須在 use() 裡面呼叫。 */
    public MongoShell.RunResult runAsAdmin(String script, int maxDocs) {
        return shell.run(admin.getDatabase(MongoConfig.SCRATCH), shell.parse(script), maxDocs);
    }

    /** reader 身分直接在 shop 執行（查詢題、唯讀的陷阱題）：寫入一律被拒絕，不需要複製資料。 */
    public MongoShell.RunResult runAsReader(String script, int maxDocs) {
        return shell.run(reader.getDatabase(MongoConfig.SHOP), shell.parse(script), maxDocs);
    }
}
