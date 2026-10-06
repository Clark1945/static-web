package com.example.dbshowcase.cassandra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;

import jakarta.annotation.PreDestroy;

/**
 * Cassandra 的連線。認證綁在連線上，所以每種身分一個 CqlSession：
 * - admin  ：超級使用者，建 keyspace / 表 / 角色、載入資料、實驗室
 * - reader ：只能讀 shop，批改查詢題
 * - learner：可讀寫 shop，指令主控台
 * - sandbox：只能讀寫 scratch，批改寫入題（就算寫了 shop.xxx 也會被拒絕）
 * Cassandra 啟動要將近一分鐘，所以連線是第一次用到時才建立；連不上不會讓整個展示台起不來。
 */
@Component
public class CassandraSessions {

    public static final String SHOP = "shop";
    public static final String SCRATCH = "scratch";
    public static final String LAB = "lab";
    /** 複本數 3 的 keyspace：單一節點上示範一致性等級的計算。 */
    public static final String LAB_RF3 = "lab_rf3";

    private final String host;
    private final int port;
    private final String datacenter;
    private final String adminUser;
    private final String adminPassword;
    private final String readerPassword;
    private final String learnerPassword;
    private final String sandboxPassword;

    private volatile CqlSession admin;
    private volatile CqlSession reader;
    private volatile CqlSession learner;
    private volatile CqlSession sandbox;

    public CassandraSessions(@Value("${showcase.cassandra.host}") String host,
                             @Value("${showcase.cassandra.port}") int port,
                             @Value("${showcase.cassandra.datacenter}") String datacenter,
                             @Value("${showcase.cassandra.admin-user}") String adminUser,
                             @Value("${showcase.cassandra.admin-password}") String adminPassword,
                             @Value("${showcase.cassandra.reader-password}") String readerPassword,
                             @Value("${showcase.cassandra.learner-password}") String learnerPassword,
                             @Value("${showcase.cassandra.sandbox-password}") String sandboxPassword) {
        this.host = host;
        this.port = port;
        this.datacenter = datacenter;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.readerPassword = readerPassword;
        this.learnerPassword = learnerPassword;
        this.sandboxPassword = sandboxPassword;
    }

    public CqlSession admin() {
        if (admin == null) {
            synchronized (this) {
                if (admin == null) {
                    CqlSession s = open(adminUser, adminPassword);
                    bootstrap(s);
                    admin = s;
                }
            }
        }
        return admin;
    }

    public CqlSession reader() {
        if (reader == null) {
            synchronized (this) {
                if (reader == null) {
                    admin();
                    reader = open("reader", readerPassword);
                }
            }
        }
        return reader;
    }

    public CqlSession learner() {
        if (learner == null) {
            synchronized (this) {
                if (learner == null) {
                    admin();
                    learner = open("learner", learnerPassword);
                }
            }
        }
        return learner;
    }

    public CqlSession sandbox() {
        if (sandbox == null) {
            synchronized (this) {
                if (sandbox == null) {
                    admin();
                    sandbox = open("sandbox", sandboxPassword);
                }
            }
        }
        return sandbox;
    }

    private CqlSession open(String user, String password) {
        DriverConfigLoader config = DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(30))
                .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(30))
                .withInt(DefaultDriverOption.REQUEST_PAGE_SIZE, 1000)
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, "LOCAL_ONE")
                .withBoolean(DefaultDriverOption.REQUEST_WARN_IF_SET_KEYSPACE, false)
                .withBoolean(DefaultDriverOption.REQUEST_LOG_WARNINGS, false)
                .build();
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress(host, port))
                .withLocalDatacenter(datacenter)
                .withAuthCredentials(user, password)
                .withApplicationName("db-showcase")
                .withConfigLoader(config)
                .build();
    }

    /** 建 keyspace、資料表與角色。全部都是 IF NOT EXISTS / GRANT，重複執行沒有影響。 */
    private void bootstrap(CqlSession s) {
        for (String ks : List.of(SHOP, SCRATCH, LAB)) {
            s.execute("CREATE KEYSPACE IF NOT EXISTS " + ks
                    + " WITH replication = {'class': 'NetworkTopologyStrategy', '" + datacenter + "': 1}");
        }
        s.execute("CREATE KEYSPACE IF NOT EXISTS " + LAB_RF3
                + " WITH replication = {'class': 'NetworkTopologyStrategy', '" + datacenter + "': 3}");
        s.execute("CREATE TABLE IF NOT EXISTS " + LAB_RF3 + ".kv (k text PRIMARY KEY, v text)");

        String schema = read("cassandra/schema.cql");
        for (String ks : List.of(SHOP, SCRATCH)) {
            for (String stmt : CqlShell.splitStatements(schema.replace("{ks}", ks))) {
                s.execute(stmt);
            }
        }

        role(s, "reader", readerPassword);
        s.execute("GRANT SELECT ON KEYSPACE " + SHOP + " TO reader");
        role(s, "learner", learnerPassword);
        s.execute("GRANT SELECT ON KEYSPACE " + SHOP + " TO learner");
        s.execute("GRANT MODIFY ON KEYSPACE " + SHOP + " TO learner");
        role(s, "sandbox", sandboxPassword);
        s.execute("GRANT SELECT ON KEYSPACE " + SCRATCH + " TO sandbox");
        s.execute("GRANT MODIFY ON KEYSPACE " + SCRATCH + " TO sandbox");
    }

    private static void role(CqlSession s, String name, String password) {
        s.execute("CREATE ROLE IF NOT EXISTS " + name + " WITH PASSWORD = '" + password.replace("'", "''") + "' AND LOGIN = true");
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PreDestroy
    public void close() {
        for (CqlSession s : new CqlSession[] {sandbox, learner, reader, admin}) {
            if (s != null) {
                s.close();
            }
        }
    }
}
