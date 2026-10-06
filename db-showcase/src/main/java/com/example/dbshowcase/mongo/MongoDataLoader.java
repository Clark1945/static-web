package com.example.dbshowcase.mongo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertManyOptions;

/**
 * 把 PostgreSQL 的 shop 資料轉成 MongoDB 文件，示範「關聯式 → 文件式」的設計取捨：
 * - orders：訂單明細「內嵌」在訂單裡（一對少量、總是一起讀取）
 * - products：分類名稱與路徑「反正規化」放進商品（讀多寫少）
 * - customers：沒有值的欄位直接省略（彈性 schema）
 * - categories：同時存 parentId 與 ancestors（樹狀結構的兩種存法）
 * 日期只有「日」的欄位存成 UTC 午夜；下單時間存成實際的時間點（MongoDB 一律以 UTC 儲存）。
 */
@Service
public class MongoDataLoader {

    private static final Logger log = LoggerFactory.getLogger(MongoDataLoader.class);
    private static final int BATCH = 5000;

    public record CollectionLoad(String name, long count) {
    }

    public record LoadResult(List<CollectionLoad> collections, double seconds) {
    }

    private final JdbcTemplate jdbc;
    private final MongoClient admin;
    private volatile LoadResult last;

    public MongoDataLoader(JdbcTemplate jdbc, @Qualifier("mongoAdmin") MongoClient admin) {
        this.jdbc = jdbc;
        this.admin = admin;
    }

    /** 啟動時如果還沒有資料就載入（容器重建後會是空的）；已經有資料就保留，按「重置資料」才重新載入。 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadIfEmpty() {
        try {
            if (admin.getDatabase(MongoConfig.SHOP).getCollection("orders").estimatedDocumentCount() == 0) {
                load();
            }
        } catch (Exception e) {
            log.warn("MongoDB 練習資料載入失敗（mongo-lab 容器有啟動嗎？）：{}", e.getMessage());
        }
    }

    public LoadResult lastLoad() {
        return last;
    }

    public synchronized LoadResult load() {
        long start = System.nanoTime();
        MongoDatabase db = admin.getDatabase(MongoConfig.SHOP);
        db.drop();
        List<CollectionLoad> loaded = new ArrayList<>();

        // ---------- categories：parentId（找上層）＋ ancestors（找所有祖先） ----------
        Map<Integer, Document> categories = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, parent_id FROM categories ORDER BY id", rs -> {
            Document d = new Document("_id", rs.getInt("id")).append("name", rs.getString("name"));
            int parent = rs.getInt("parent_id");
            d.append("parentId", rs.wasNull() ? null : parent);
            categories.put(rs.getInt("id"), d);
        });
        Map<Integer, List<Document>> ancestors = new HashMap<>();
        for (Document c : categories.values()) {
            List<Document> chain = new ArrayList<>();
            Integer p = c.getInteger("parentId");
            while (p != null) {
                Document parent = categories.get(p);
                chain.add(0, new Document("_id", parent.getInteger("_id")).append("name", parent.getString("name")));
                p = parent.getInteger("parentId");
            }
            ancestors.put(c.getInteger("_id"), chain);
            c.append("ancestors", chain);
        }
        loaded.add(insert(db, "categories", new ArrayList<>(categories.values())));

        // ---------- products：分類資訊反正規化 ----------
        List<Document> products = new ArrayList<>();
        Map<Integer, String> productNames = new HashMap<>();
        jdbc.query("""
                SELECT id, name, category_id, price, cost, stock, is_active, created_at, specs::text AS specs, tags
                FROM products ORDER BY id
                """, rs -> {
            int cid = rs.getInt("category_id");
            Document cat = categories.get(cid);
            List<String> path = new ArrayList<>(ancestors.get(cid).stream().map(a -> a.getString("name")).toList());
            path.add(cat.getString("name"));
            Document d = new Document("_id", rs.getInt("id"))
                    .append("name", rs.getString("name"))
                    .append("category", new Document("_id", cid).append("name", cat.getString("name")).append("path", path))
                    .append("price", rs.getBigDecimal("price").intValue())
                    .append("cost", rs.getBigDecimal("cost").intValue())
                    .append("stock", rs.getInt("stock"))
                    .append("isActive", rs.getBoolean("is_active"))
                    .append("createdAt", day(rs, "created_at"))
                    .append("tags", List.of((String[]) rs.getArray("tags").getArray()));
            if (rs.getString("specs") != null) {
                d.append("specs", Document.parse(rs.getString("specs")));
            }
            products.add(d);
            productNames.put(rs.getInt("id"), rs.getString("name"));
        });
        loaded.add(insert(db, "products", products));

        // ---------- customers：沒有值的欄位直接省略 ----------
        List<Document> customers = new ArrayList<>();
        jdbc.query("SELECT * FROM customers ORDER BY id", rs -> {
            Document d = new Document("_id", rs.getInt("id"))
                    .append("name", rs.getString("name"))
                    .append("email", rs.getString("email"))
                    .append("gender", rs.getString("gender"));
            if (rs.getDate("birth_date") != null) d.append("birthDate", day(rs, "birth_date"));
            if (rs.getString("city") != null) d.append("city", rs.getString("city"));
            d.append("signupDate", day(rs, "signup_date")).append("vipLevel", rs.getString("vip_level"));
            customers.add(d);
        });
        loaded.add(insert(db, "customers", customers));
        db.getCollection("customers").createIndex(new Document("email", 1), new IndexOptions().unique(true).name("email_unique"));

        // ---------- orders：明細內嵌 ----------
        MongoCollection<Document> orders = db.getCollection("orders");
        List<Document> batch = new ArrayList<>();
        long[] count = {0};
        Document[] current = {null};
        BigDecimal[] sum = {BigDecimal.ZERO};
        Runnable finish = () -> {
            if (current[0] != null) {
                current[0].append("total", sum[0].setScale(0, RoundingMode.HALF_UP).intValue());
                batch.add(current[0]);
                count[0]++;
                if (batch.size() >= BATCH) {
                    orders.insertMany(new ArrayList<>(batch), new InsertManyOptions().ordered(false));
                    batch.clear();
                }
            }
        };
        jdbc.query("""
                SELECT o.id, o.customer_id, o.order_date, o.status, o.shipping_city, o.shipping_fee,
                       oi.product_id, oi.quantity, oi.unit_price, oi.discount
                FROM orders o JOIN order_items oi ON oi.order_id = o.id
                ORDER BY o.id, oi.product_id
                """, rs -> {
            int id = rs.getInt("id");
            if (current[0] == null || current[0].getInteger("_id") != id) {
                finish.run();
                current[0] = new Document("_id", id)
                        .append("customerId", rs.getInt("customer_id"))
                        .append("orderDate", new Date(rs.getTimestamp("order_date").getTime()))
                        .append("status", rs.getString("status"))
                        .append("shipping", new Document("city", rs.getString("shipping_city")).append("fee", rs.getInt("shipping_fee")))
                        .append("items", new ArrayList<Document>());
                sum[0] = BigDecimal.ZERO;
            }
            BigDecimal price = rs.getBigDecimal("unit_price");
            BigDecimal discount = rs.getBigDecimal("discount");
            int qty = rs.getInt("quantity");
            sum[0] = sum[0].add(price.multiply(BigDecimal.valueOf(qty)).multiply(BigDecimal.ONE.subtract(discount)));
            current[0].getList("items", Document.class).add(new Document("productId", rs.getInt("product_id"))
                    .append("name", productNames.get(rs.getInt("product_id")))
                    .append("qty", qty)
                    .append("unitPrice", price.intValue())
                    .append("discount", discount.doubleValue()));
        });
        finish.run();
        if (!batch.isEmpty()) {
            orders.insertMany(batch, new InsertManyOptions().ordered(false));
        }
        loaded.add(new CollectionLoad("orders", count[0]));

        double seconds = (System.nanoTime() - start) / 1e9;
        last = new LoadResult(loaded, seconds);
        log.info("MongoDB 練習資料載入完成：{}，{} 秒", loaded, String.format("%.2f", seconds));
        return last;
    }

    private static CollectionLoad insert(MongoDatabase db, String name, List<Document> docs) {
        MongoCollection<Document> coll = db.getCollection(name);
        for (int i = 0; i < docs.size(); i += BATCH) {
            coll.insertMany(docs.subList(i, Math.min(docs.size(), i + BATCH)), new InsertManyOptions().ordered(false));
        }
        return new CollectionLoad(name, docs.size());
    }

    /** 只有日期的欄位：存成 UTC 的午夜，顯示時就是 2026-09-01T00:00:00Z。 */
    private static Date day(ResultSet rs, String column) throws SQLException {
        LocalDate d = rs.getDate(column).toLocalDate();
        return Date.from(d.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
