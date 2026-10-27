package com.example.dbshowcase.neo4j;

import java.time.Duration;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 的連線。社群版只有一個帳號、沒有角色權限，所以「使用者寫的 Cypher 不能改到資料」
 * 是由 CypherShell 保證的：一律在交易裡執行，最後 ROLLBACK。
 * Driver 建立時不會馬上連線，容器沒啟動也不會讓展示台起不來。
 */
@Configuration
public class Neo4jConfig {

    /** 社群版只有一個使用者資料庫。 */
    public static final String DATABASE = "neo4j";

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(@Value("${showcase.neo4j.uri}") String uri,
                              @Value("${showcase.neo4j.user}") String user,
                              @Value("${showcase.neo4j.password}") String password) {
        Config config = Config.builder()
                .withConnectionTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(5, java.util.concurrent.TimeUnit.SECONDS)
                .withUserAgent("db-showcase")
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
    }

    /** 使用者 Cypher 的單一交易上限（伺服器端另外設了 db.transaction.timeout=30s）。 */
    public static final Duration USER_TX_TIMEOUT = Duration.ofSeconds(20);
}
