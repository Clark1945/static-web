package com.example.dbshowcase.cassandra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;

/** Cassandra 的 API，全部掛在 /api/cassandra 底下。 */
@RestController
@RequestMapping("/api/cassandra")
public class CassandraController {

    public record ScriptRequest(String commands) {
    }

    public record Column(String name, String type, String role) {
    }

    public record TableInfo(String name, Long rows, String query, String design, List<Column> columns,
                            String createStatement, String sample) {
    }

    public record Overview(List<TableInfo> tables, String version, CassandraDataLoader.Status load) {
    }

    /** 每張表回答哪個問題、為什麼這樣設計、範例查詢。 */
    private static final Map<String, String[]> DESIGN = new LinkedHashMap<>();

    static {
        DESIGN.put("orders_by_customer", new String[] {"某位會員的訂單，新的在前",
                "分區鍵 customer_id：一位會員的訂單全部放在同一個分區；叢集鍵 order_time DESC 讓分區裡的資料已經依時間排好",
                "SELECT * FROM orders_by_customer WHERE customer_id = 4242"});
        DESIGN.put("orders", new String[] {"用訂單編號查整張訂單",
                "明細是 list<frozen<order_item>>，跟訂單存在同一列，一次讀完；不能用 customer_id 查（它不是主鍵）",
                "SELECT * FROM orders WHERE order_id = 77621"});
        DESIGN.put("orders_by_day", new String[] {"某一天的訂單（台灣時間）",
                "時間分桶：一天一個分區，分區不會無限長大；要查一週就查 7 個分區",
                "SELECT * FROM orders_by_day WHERE order_day = '2026-09-01' LIMIT 5"});
        DESIGN.put("products_by_category", new String[] {"某分類的商品，價格由高到低",
                "分區鍵 category，叢集鍵 (price DESC, product_id)：可以查價格區間，同價的商品不會互相覆蓋",
                "SELECT * FROM products_by_category WHERE category = '手機' LIMIT 5"});
        DESIGN.put("products", new String[] {"用商品編號查商品",
                "tags 是 set<text>、specs 是 map<text, text>（巢狀規格攤平成 warranty.years 這種 key）",
                "SELECT * FROM products WHERE product_id = 540"});
        DESIGN.put("items_by_product", new String[] {"某件商品最近被誰買走",
                "訂單明細依商品再存一次：寫入時多寫一份，查詢時只讀一個分區",
                "SELECT * FROM items_by_product WHERE product_id = 540 LIMIT 5"});
        DESIGN.put("customers", new String[] {"用會員編號查會員",
                "沒填的城市、生日不寫入（unset），不會產生墓碑",
                "SELECT * FROM customers WHERE customer_id = 4242"});
        DESIGN.put("customers_by_email", new String[] {"用 email 查會員（登入）",
                "同一份會員資料換一個分區鍵再存一次；email 改了要兩張表一起改",
                "SELECT * FROM customers_by_email WHERE email = 'user04242@example.com'"});
        DESIGN.put("product_sales", new String[] {"每件商品賣出幾件（不含取消的訂單）",
                "計數器表：只能用 UPDATE … SET units = units + 1 加減，不能 INSERT，也不能跟一般欄位放在同一張表",
                "SELECT * FROM product_sales WHERE product_id = 540"});
    }

    private final CassandraSessions sessions;
    private final CassandraDataLoader loader;
    private final CqlShell shell;
    private final Map<String, Long> counts = new ConcurrentHashMap<>();
    private volatile CassandraDataLoader.LoadResult countedFrom;

    public CassandraController(CassandraSessions sessions, CassandraDataLoader loader, CqlShell shell) {
        this.sessions = sessions;
        this.loader = loader;
        this.shell = shell;
    }

    @GetMapping("/overview")
    public Overview overview() {
        CqlSession admin = sessions.admin();
        CassandraDataLoader.Status status = loader.status();
        List<TableInfo> tables = new ArrayList<>();
        for (Map.Entry<String, String[]> e : DESIGN.entrySet()) {
            TableMetadata t = admin.getMetadata().getKeyspace(CassandraSessions.SHOP)
                    .flatMap(k -> k.getTable(e.getKey()))
                    .orElseThrow(() -> new IllegalStateException("找不到資料表 shop." + e.getKey()));
            List<Column> columns = new ArrayList<>();
            for (ColumnMetadata c : t.getPartitionKey()) {
                columns.add(new Column(c.getName().asInternal(), c.getType().asCql(false, true), "分區鍵"));
            }
            for (Map.Entry<ColumnMetadata, ClusteringOrder> c : t.getClusteringColumns().entrySet()) {
                columns.add(new Column(c.getKey().getName().asInternal(), c.getKey().getType().asCql(false, true),
                        "叢集鍵 " + c.getValue().name()));
            }
            for (ColumnMetadata c : t.getColumns().values()) {
                if (!t.getPrimaryKey().contains(c)) {
                    columns.add(new Column(c.getName().asInternal(), c.getType().asCql(false, true), null));
                }
            }
            tables.add(new TableInfo(e.getKey(), status.running() ? null : count(e.getKey()), e.getValue()[0],
                    e.getValue()[1], columns, t.describe(true), e.getValue()[2]));
        }
        String version = admin.execute("SELECT release_version FROM system.local").one().getString(0);
        return new Overview(tables, version, status);
    }

    /** 筆數用 COUNT(*) 要掃整張表，算一次就記住；重新載入後再重算。 */
    private Long count(String table) {
        CassandraDataLoader.LoadResult last = loader.status().last();
        if (last != countedFrom) {
            counts.clear();
            countedFrom = last;
        }
        if (last != null) {
            for (CassandraDataLoader.TableLoad t : last.tables()) {
                counts.putIfAbsent(t.name(), t.rows());
            }
        }
        return counts.computeIfAbsent(table, t -> sessions.admin().execute("SELECT COUNT(*) FROM shop." + t).one().getLong(0));
    }

    /** 指令主控台：用 learner 身分在 shop 執行。寫入會保留，按「重新載入」才會還原。 */
    @PostMapping("/run")
    public CqlShell.RunResult run(@RequestBody ScriptRequest request) {
        return shell.run(sessions.learner(), CassandraSessions.SHOP, request.commands(), 100);
    }

    @GetMapping("/load")
    public CassandraDataLoader.Status load() {
        return loader.status();
    }

    @PostMapping("/reset")
    public CassandraDataLoader.Status reset() {
        return loader.startReload();
    }
}
