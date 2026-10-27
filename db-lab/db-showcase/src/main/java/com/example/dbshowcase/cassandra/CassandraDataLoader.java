package com.example.dbshowcase.cassandra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把 PostgreSQL 的 shop 資料轉進 Cassandra。同一份資料依「要怎麼查」寫進好幾張表：
 * 一筆訂單會寫進 orders、orders_by_customer、orders_by_day，每一項明細再寫進 items_by_product。
 * 約 50 萬次寫入，用非同步寫入（同時最多 MAX_IN_FLIGHT 個請求）大約 20～30 秒。
 * 載入在背景執行緒進行，頁面可以看到進度。
 */
@Service
public class CassandraDataLoader {

    private static final Logger log = LoggerFactory.getLogger(CassandraDataLoader.class);
    private static final int MAX_IN_FLIGHT = 256;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    public static final List<String> TABLES = List.of("customers", "customers_by_email", "products", "products_by_category",
            "orders", "orders_by_customer", "orders_by_day", "items_by_product", "product_sales");

    public record TableLoad(String name, long rows) {
    }

    public record LoadResult(List<TableLoad> tables, long writes, double seconds) {
    }

    /** 載入狀態：running 時 written / expected 是進度。 */
    public record Status(boolean running, long written, long expected, String error, LoadResult last) {
    }

    private final JdbcTemplate jdbc;
    private final CassandraSessions sessions;
    private final ObjectMapper json;
    private final AtomicLong written = new AtomicLong();
    private volatile long expected;
    private volatile boolean running;
    private volatile String error;
    private volatile LoadResult last;

    public CassandraDataLoader(JdbcTemplate jdbc, CassandraSessions sessions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.json = json;
    }

    /** 啟動時如果 shop 還是空的（容器剛建立）就在背景載入；已經有資料就保留。 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadIfEmpty() {
        Thread.ofVirtual().name("cassandra-loader").start(() -> {
            try {
                if (sessions.admin().execute("SELECT order_id FROM shop.orders LIMIT 1").one() == null) {
                    load();
                }
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("Cassandra 練習資料載入失敗（cassandra-lab 容器有啟動嗎？啟動要將近一分鐘）：{}", e.getMessage());
            }
        });
    }

    public Status status() {
        return new Status(running, written.get(), expected, error, last);
    }

    /** 在背景重新載入；已經在載入就直接回傳目前的狀態。 */
    public Status startReload() {
        synchronized (this) {
            if (!running) {
                running = true;
                written.set(0);
                error = null;
                Thread.ofVirtual().name("cassandra-loader").start(() -> {
                    try {
                        load();
                    } catch (Exception e) {
                        error = e.getMessage();
                        running = false;
                        log.warn("Cassandra 練習資料載入失敗：{}", e.getMessage());
                    }
                });
            }
        }
        return status();
    }

    public synchronized LoadResult load() {
        running = true;
        error = null;
        written.set(0);
        long start = System.nanoTime();
        CqlSession s = sessions.admin();
        try {
            for (String t : TABLES) {
                s.execute("TRUNCATE shop." + t);
            }
            Long orderCount = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
            Long itemCount = jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class);
            Long customerCount = jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);
            Long productCount = jdbc.queryForObject("SELECT count(*) FROM products", Long.class);
            expected = customerCount * 2 + productCount * 3 + orderCount * 3 + itemCount;
            Writer w = new Writer(s);
            List<TableLoad> loaded = new ArrayList<>();

            // ---------- 分類：只用來產生商品的 category 與 category_path ----------
            Map<Integer, String> catName = new HashMap<>();
            Map<Integer, Integer> catParent = new HashMap<>();
            jdbc.query("SELECT id, name, parent_id FROM categories", rs -> {
                catName.put(rs.getInt("id"), rs.getString("name"));
                int p = rs.getInt("parent_id");
                if (!rs.wasNull()) {
                    catParent.put(rs.getInt("id"), p);
                }
            });

            // ---------- 會員：customers + customers_by_email ----------
            PreparedStatement customer = s.prepare("INSERT INTO shop.customers (customer_id, name, email, gender, birth_date, city, signup_date, vip_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement byEmail = s.prepare("INSERT INTO shop.customers_by_email (email, customer_id, name) VALUES (?, ?, ?)");
            long[] customers = {0};
            jdbc.query("SELECT * FROM customers ORDER BY id", rs -> {
                // 沒有值的欄位不綁定（unset），才不會寫進墓碑
                BoundStatement b = customer.bind()
                        .setInt("customer_id", rs.getInt("id"))
                        .setString("name", rs.getString("name"))
                        .setString("email", rs.getString("email"))
                        .setLocalDate("signup_date", rs.getDate("signup_date").toLocalDate())
                        .setString("vip_level", rs.getString("vip_level"));
                if (rs.getString("gender") != null) {
                    b = b.setString("gender", rs.getString("gender"));
                }
                if (rs.getDate("birth_date") != null) {
                    b = b.setLocalDate("birth_date", rs.getDate("birth_date").toLocalDate());
                }
                if (rs.getString("city") != null) {
                    b = b.setString("city", rs.getString("city"));
                }
                w.write(b);
                w.write(byEmail.bind(rs.getString("email"), rs.getInt("id"), rs.getString("name")));
                customers[0]++;
            });
            loaded.add(new TableLoad("customers", customers[0]));
            loaded.add(new TableLoad("customers_by_email", customers[0]));

            // ---------- 商品：products + products_by_category ----------
            PreparedStatement product = s.prepare("INSERT INTO shop.products (product_id, name, category, category_path, price, cost, stock, is_active, created_at, tags, specs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement byCategory = s.prepare("INSERT INTO shop.products_by_category (category, price, product_id, name, stock) VALUES (?, ?, ?, ?, ?)");
            Map<Integer, String> productNames = new HashMap<>();
            long[] products = {0};
            jdbc.query("SELECT id, name, category_id, price, cost, stock, is_active, created_at, specs::text AS specs, tags FROM products ORDER BY id", rs -> {
                int cid = rs.getInt("category_id");
                List<String> path = new ArrayList<>();
                for (Integer c = cid; c != null; c = catParent.get(c)) {
                    path.add(0, catName.get(c));
                }
                int price = rs.getBigDecimal("price").intValue();
                w.write(product.bind(rs.getInt("id"), rs.getString("name"), catName.get(cid), path, price,
                        rs.getBigDecimal("cost").intValue(), rs.getInt("stock"), rs.getBoolean("is_active"),
                        rs.getDate("created_at").toLocalDate(), new HashSet<>(List.of((String[]) rs.getArray("tags").getArray())),
                        flatten(rs.getString("specs"))));
                w.write(byCategory.bind(catName.get(cid), price, rs.getInt("id"), rs.getString("name"), rs.getInt("stock")));
                productNames.put(rs.getInt("id"), rs.getString("name"));
                products[0]++;
            });
            loaded.add(new TableLoad("products", products[0]));
            loaded.add(new TableLoad("products_by_category", products[0]));

            // ---------- 訂單：orders + orders_by_customer + orders_by_day + items_by_product ----------
            UserDefinedType itemType = s.getMetadata().getKeyspace("shop").flatMap(k -> k.getUserDefinedType("order_item"))
                    .orElseThrow(() -> new IllegalStateException("找不到 shop.order_item 型別"));
            PreparedStatement order = s.prepare("INSERT INTO shop.orders (order_id, customer_id, order_time, status, shipping_city, shipping_fee, total, items) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement byCustomer = s.prepare("INSERT INTO shop.orders_by_customer (customer_id, order_time, order_id, status, total, item_count) VALUES (?, ?, ?, ?, ?, ?)");
            PreparedStatement byDay = s.prepare("INSERT INTO shop.orders_by_day (order_day, order_time, order_id, customer_id, status, total, shipping_city) VALUES (?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement byProduct = s.prepare("INSERT INTO shop.items_by_product (product_id, order_time, order_id, customer_id, qty, unit_price) VALUES (?, ?, ?, ?, ?, ?)");
            Map<Integer, long[]> sales = new HashMap<>();
            long[] counts = {0, 0};
            AtomicReference<OrderBuffer> current = new AtomicReference<>();
            Runnable finish = () -> {
                OrderBuffer o = current.get();
                if (o == null) {
                    return;
                }
                int total = o.sum.setScale(0, RoundingMode.HALF_UP).intValue();
                BoundStatement ob = order.bind().setInt("order_id", o.id).setInt("customer_id", o.customerId)
                        .setInstant("order_time", o.time).setString("status", o.status)
                        .setInt("shipping_fee", o.fee).setInt("total", total)
                        .setList("items", o.items, UdtValue.class);
                if (o.city != null) {
                    ob = ob.setString("shipping_city", o.city);
                }
                w.write(ob);
                w.write(byCustomer.bind(o.customerId, o.time, o.id, o.status, total, o.items.size()));
                BoundStatement db = byDay.bind().setLocalDate("order_day", LocalDate.ofInstant(o.time, TAIPEI))
                        .setInstant("order_time", o.time).setInt("order_id", o.id).setInt("customer_id", o.customerId)
                        .setString("status", o.status).setInt("total", total);
                if (o.city != null) {
                    db = db.setString("shipping_city", o.city);
                }
                w.write(db);
                counts[0]++;
            };
            jdbc.query("""
                    SELECT o.id, o.customer_id, o.order_date, o.status, o.shipping_city, o.shipping_fee,
                           oi.product_id, oi.quantity, oi.unit_price, oi.discount
                    FROM orders o JOIN order_items oi ON oi.order_id = o.id
                    ORDER BY o.id, oi.product_id
                    """, rs -> {
                int id = rs.getInt("id");
                if (current.get() == null || current.get().id != id) {
                    finish.run();
                    current.set(new OrderBuffer(id, rs.getInt("customer_id"), rs.getTimestamp("order_date").toInstant(),
                            rs.getString("status"), rs.getString("shipping_city"), rs.getInt("shipping_fee")));
                }
                OrderBuffer o = current.get();
                int pid = rs.getInt("product_id");
                int qty = rs.getInt("quantity");
                BigDecimal price = rs.getBigDecimal("unit_price");
                BigDecimal discount = rs.getBigDecimal("discount");
                o.sum = o.sum.add(price.multiply(BigDecimal.valueOf(qty)).multiply(BigDecimal.ONE.subtract(discount)));
                o.items.add(itemType.newValue(pid, productNames.get(pid), qty, price.intValue(), discount.stripTrailingZeros()));
                w.write(byProduct.bind(pid, o.time, id, o.customerId, qty, price.intValue()));
                if (!o.status.equals("cancelled")) {
                    long[] c = sales.computeIfAbsent(pid, k -> new long[2]);
                    c[0] += qty;
                    c[1]++;
                }
                counts[1]++;
            });
            finish.run();
            loaded.add(new TableLoad("orders", counts[0]));
            loaded.add(new TableLoad("orders_by_customer", counts[0]));
            loaded.add(new TableLoad("orders_by_day", counts[0]));
            loaded.add(new TableLoad("items_by_product", counts[1]));

            // ---------- 計數器：每件商品賣出幾件、出現在幾張訂單（不含取消的訂單） ----------
            PreparedStatement counter = s.prepare("UPDATE shop.product_sales SET units = units + ?, orders = orders + ? WHERE product_id = ?");
            sales.forEach((pid, c) -> w.write(counter.bind(c[0], c[1], pid)));
            loaded.add(new TableLoad("product_sales", sales.size()));

            w.await();
            double seconds = (System.nanoTime() - start) / 1e9;
            last = new LoadResult(loaded, written.get(), seconds);
            log.info("Cassandra 練習資料載入完成：{} 次寫入，{} 秒", written.get(), String.format("%.1f", seconds));
            return last;
        } finally {
            running = false;
        }
    }

    /** 巢狀 JSON 攤平成 map<text, text>：{"warranty": {"years": 2}} → {"warranty.years": "2"}。 */
    private Map<String, String> flatten(String specs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (specs == null) {
            return out;
        }
        try {
            flatten("", json.readTree(specs), out);
        } catch (Exception e) {
            throw new IllegalStateException("specs 不是合法的 JSON：" + specs, e);
        }
        return out;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
        } else {
            out.put(prefix, node.isTextual() ? node.asText() : node.toString());
        }
    }

    private static final class OrderBuffer {
        final int id;
        final int customerId;
        final Instant time;
        final String status;
        final String city;
        final int fee;
        final List<UdtValue> items = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;

        OrderBuffer(int id, int customerId, Instant time, String status, String city, int fee) {
            this.id = id;
            this.customerId = customerId;
            this.time = time;
            this.status = status;
            this.city = city;
            this.fee = fee;
        }
    }

    /** 非同步寫入，同時最多 MAX_IN_FLIGHT 個請求；第一個錯誤會在 await() 丟出來。 */
    private final class Writer {
        private final CqlSession session;
        private final Semaphore permits = new Semaphore(MAX_IN_FLIGHT);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Writer(CqlSession session) {
            this.session = session;
        }

        void write(BoundStatement st) {
            if (failure.get() != null) {
                throw new IllegalStateException("寫入 Cassandra 失敗：" + failure.get().getMessage(), failure.get());
            }
            permits.acquireUninterruptibly();
            CompletionStage<?> f = session.executeAsync(st);
            f.whenComplete((r, e) -> {
                if (e != null) {
                    failure.compareAndSet(null, e);
                } else {
                    written.incrementAndGet();
                }
                permits.release();
            });
        }

        void await() {
            permits.acquireUninterruptibly(MAX_IN_FLIGHT);
            permits.release(MAX_IN_FLIGHT);
            if (failure.get() != null) {
                throw new IllegalStateException("寫入 Cassandra 失敗：" + failure.get().getMessage(), failure.get());
            }
        }
    }
}
