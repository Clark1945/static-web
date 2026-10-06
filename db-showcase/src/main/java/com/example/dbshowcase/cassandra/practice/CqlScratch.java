package com.example.dbshowcase.cassandra.practice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.example.dbshowcase.cassandra.CassandraDataLoader;
import com.example.dbshowcase.cassandra.CassandraSessions;
import com.example.dbshowcase.cassandra.CqlShell;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 隔離用的 scratch keyspace（表結構跟 shop 一模一樣）：
 * 清空 → 從 shop 複製需要的資料列 → 執行 → 清空。
 * Cassandra 沒有交易可以 ROLLBACK，所以寫入題一律在這裡做。
 * 只有一個 scratch，所以同一時間只允許一個人使用（synchronized）。
 */
@Component
public class CqlScratch {

    private final CassandraSessions sessions;
    private final CqlShell shell;
    private final ObjectMapper json;

    public CqlScratch(CassandraSessions sessions, CqlShell shell, ObjectMapper json) {
        this.sessions = sessions;
        this.shell = shell;
        this.json = json;
    }

    /**
     * 準備好 scratch 之後執行 work，結束後清空。
     * fixture：資料表 → WHERE 條件（例如 "product_id = 540"）；條件空白代表只用到這張表，不複製資料。
     */
    public synchronized <T> T use(Map<String, String> fixture, Supplier<T> work) {
        try {
            clean();
            fixture.forEach(this::copy);
            return work.get();
        } finally {
            clean();
        }
    }

    /** sandbox 身分在 scratch 執行（寫入題）。必須在 use() 裡面呼叫。 */
    public CqlShell.RunResult runAsSandbox(String script, int maxRows) {
        return shell.run(sessions.sandbox(), CassandraSessions.SCRATCH, script, maxRows);
    }

    /** admin 身分在 scratch 執行（檢查指令、會寫入的陷阱題）。必須在 use() 裡面呼叫。 */
    public CqlShell.RunResult runAsAdmin(String script, int maxRows) {
        return shell.run(sessions.admin(), CassandraSessions.SCRATCH, script, maxRows);
    }

    /** reader 身分直接在 shop 執行（查詢題、唯讀的陷阱題）：寫入一律被拒絕。 */
    public CqlShell.RunResult runAsReader(String script, int maxRows) {
        return shell.run(sessions.reader(), CassandraSessions.SHOP, script, maxRows);
    }

    /** 只清有資料的表：TRUNCATE 一次要 100 毫秒左右。 */
    private void clean() {
        CqlSession admin = sessions.admin();
        for (String t : CassandraDataLoader.TABLES) {
            if (admin.execute("SELECT * FROM " + CassandraSessions.SCRATCH + "." + t + " LIMIT 1").one() != null) {
                admin.execute("TRUNCATE " + CassandraSessions.SCRATCH + "." + t);
            }
        }
    }

    /** 用 SELECT JSON / INSERT … JSON 複製（集合、UDT 都能原樣搬過去）；計數器表只能用 UPDATE 加上去。 */
    private void copy(String table, String where) {
        if (!CassandraDataLoader.TABLES.contains(table)) {
            throw new IllegalArgumentException("fixture 裡的資料表不存在：" + table);
        }
        if (where == null || where.isBlank()) {
            return;
        }
        CqlSession admin = sessions.admin();
        TableMetadata meta = admin.getMetadata().getKeyspace(CassandraSessions.SHOP).flatMap(k -> k.getTable(table)).orElseThrow();
        List<ColumnMetadata> counters = meta.getColumns().values().stream().filter(c -> c.getType().equals(DataTypes.COUNTER)).toList();
        String from = CassandraSessions.SHOP + "." + table;
        String to = CassandraSessions.SCRATCH + "." + table;
        if (!counters.isEmpty()) {
            List<String> sets = new ArrayList<>();
            for (ColumnMetadata c : counters) {
                String name = c.getName().asCql(true);
                sets.add(name + " = " + name + " + ?");
            }
            String keys = String.join(" AND ", meta.getPrimaryKey().stream().map(c -> c.getName().asCql(true) + " = ?").toList());
            String update = "UPDATE " + to + " SET " + String.join(", ", sets) + " WHERE " + keys;
            for (Row r : admin.execute("SELECT * FROM " + from + " WHERE " + where)) {
                List<Object> values = new ArrayList<>();
                for (ColumnMetadata c : counters) {
                    values.add(r.isNull(c.getName()) ? 0L : r.getLong(c.getName()));
                }
                for (ColumnMetadata k : meta.getPrimaryKey()) {
                    values.add(r.getObject(k.getName()));
                }
                admin.execute(SimpleStatement.newInstance(update, values.toArray()));
            }
            return;
        }
        for (Row r : admin.execute("SELECT JSON * FROM " + from + " WHERE " + where)) {
            admin.execute(SimpleStatement.newInstance("INSERT INTO " + to + " JSON ? DEFAULT UNSET", withoutNulls(r.getString(0))));
        }
    }

    /** 拿掉值是 null 的欄位，避免在 scratch 寫進墓碑。 */
    private String withoutNulls(String row) {
        try {
            ObjectNode node = (ObjectNode) json.readTree(row);
            List<String> nulls = new ArrayList<>();
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isNull()) {
                    nulls.add(e.getKey());
                }
            });
            node.remove(nulls);
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
