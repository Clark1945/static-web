package com.example.dbshowcase.mongo;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * MongoDB 的認證是綁在連線上的，所以每種身分一個 MongoClient：
 * - admin  ：root，載入資料、重置、實驗室（後端自己寫好的操作）
 * - reader ：只能讀 shop，批改查詢題（使用者就算寫了 deleteMany 也會被拒絕）
 * - learner：可讀寫 shop 與 scratch，指令主控台與寫入題
 */
@Configuration
public class MongoConfig {

    /** 練習資料。 */
    public static final String SHOP = "shop";
    /** 批改寫入題的隔離區。 */
    public static final String SCRATCH = "scratch";
    /** 實驗室自己建的 collection（例如參照版本的訂單）。 */
    public static final String LAB = "lab";

    private final String host;
    private final int port;

    public MongoConfig(@Value("${showcase.mongo.host}") String host, @Value("${showcase.mongo.port}") int port) {
        this.host = host;
        this.port = port;
    }

    @Bean(destroyMethod = "close")
    public MongoClient mongoAdmin(@Value("${showcase.mongo.admin-password}") String password) {
        return client("admin", password);
    }

    @Bean(destroyMethod = "close")
    public MongoClient mongoReader(@Value("${showcase.mongo.reader-password}") String password) {
        return client("reader", password);
    }

    @Bean(destroyMethod = "close")
    public MongoClient mongoLearner(@Value("${showcase.mongo.learner-password}") String password) {
        return client("learner", password);
    }

    private MongoClient client(String user, String password) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + host + ":" + port))
                .credential(MongoCredential.createCredential(user, "admin", password.toCharArray()))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.readTimeout(30, TimeUnit.SECONDS))
                .applicationName("db-showcase")
                .build();
        return MongoClients.create(settings);
    }
}
