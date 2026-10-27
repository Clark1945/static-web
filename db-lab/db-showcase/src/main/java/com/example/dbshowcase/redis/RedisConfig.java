package com.example.dbshowcase.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * 三個連線池：
 * - admin：default 使用者，載入資料、重置、實驗室（後端自己寫好的指令）
 * - learner：ACL 受限的使用者，執行「使用者輸入的指令」，操作 db 0
 * - grading：同樣是 learner，但固定在 db 1；批改時把題目需要的 key 複製過去，練完就清空
 */
@Configuration
public class RedisConfig {

    /** 練習資料所在的 db。 */
    public static final int DATA_DB = 0;
    /** 批改與陷阱題用的隔離 db。 */
    public static final int SCRATCH_DB = 1;

    private final HostAndPort address;
    private final String adminPassword;
    private final String learnerUser;
    private final String learnerPassword;

    public RedisConfig(@Value("${showcase.redis.host}") String host,
                       @Value("${showcase.redis.port}") int port,
                       @Value("${showcase.redis.admin-password}") String adminPassword,
                       @Value("${showcase.redis.learner-user}") String learnerUser,
                       @Value("${showcase.redis.learner-password}") String learnerPassword) {
        this.address = new HostAndPort(host, port);
        this.adminPassword = adminPassword;
        this.learnerUser = learnerUser;
        this.learnerPassword = learnerPassword;
    }

    @Bean(destroyMethod = "close")
    public JedisPool adminPool() {
        return pool(64, null, adminPassword, DATA_DB);     // 實驗室會同時開 50 條連線模擬搶購
    }

    @Bean(destroyMethod = "close")
    public JedisPool learnerPool() {
        return pool(8, learnerUser, learnerPassword, DATA_DB);
    }

    @Bean(destroyMethod = "close")
    public JedisPool gradingPool() {
        return pool(2, learnerUser, learnerPassword, SCRATCH_DB);
    }

    private JedisPool pool(int size, String user, String password, int db) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(size);
        config.setMaxIdle(size);
        config.setJmxEnabled(false);
        DefaultJedisClientConfig client = DefaultJedisClientConfig.builder()
                .user(user)
                .password(password)
                .database(db)
                .timeoutMillis(5000)
                .clientName("db-showcase")
                .build();
        return new JedisPool(config, address, client);
    }
}
