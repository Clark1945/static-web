package com.example.dbshowcase.elastic;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Elasticsearch 練習資料：
 * products（商品，從 PostgreSQL 複製，description 依規格產生）、reviews（商品評論，依購買紀錄用模板產生）、
 * orders（訂單，明細是 nested）、orders_object（同樣的訂單，明細是一般的 object，陷阱題用）、
 * logs（2026 年 9 月的 API 存取紀錄，有兩次事故）。全部用固定亂數種子，每次重建都一樣。
 *
 * 每次啟動：等 es-lab 準備好 → 建立 reader / learner 角色與帳號 → 練習用的索引不齊全就重新載入。
 */
@Component
public class EsDataLoader {

    private static final Logger log = LoggerFactory.getLogger(EsDataLoader.class);
    public static final List<String> INDICES = List.of("products", "reviews", "orders", "orders_object", "logs");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public record Status(boolean running, String step, String error, Double seconds) {
    }

    private final EsClient es;
    private final JdbcTemplate postgres;
    private final String readerPassword;
    private final String learnerPassword;
    private volatile boolean running;
    private volatile String step;
    private volatile String error;
    private volatile Double seconds;

    public EsDataLoader(EsClient es, JdbcTemplate postgres,
                        @Value("${showcase.elasticsearch.reader-password}") String readerPassword,
                        @Value("${showcase.elasticsearch.learner-password}") String learnerPassword) {
        this.es = es;
        this.postgres = postgres;
        this.readerPassword = readerPassword;
        this.learnerPassword = learnerPassword;
    }

    public Status status() {
        return new Status(running, step, error, seconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        Thread.ofVirtual().name("es-loader").start(() -> {
            running = true;
            try {
                step = "等待 Elasticsearch 啟動";
                waitForCluster();
                step = "建立角色與帳號";
                ensureUsers();
                boolean complete = INDICES.stream().allMatch(i -> es.send(EsClient.User.ADMIN, "HEAD", "/" + i, null).ok());
                if (!complete) {
                    load();
                }
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("Elasticsearch 練習資料準備失敗（es-lab 容器有啟動嗎？）：{}", e.getMessage());
            } finally {
                running = false;
                step = null;
            }
        });
    }

    public synchronized Status reload() {
        if (!running) {
            running = true;
            Thread.ofVirtual().name("es-loader").start(() -> {
                try {
                    load();
                } catch (Exception e) {
                    error = e.getMessage();
                    log.warn("Elasticsearch 練習資料載入失敗：{}", e.getMessage());
                } finally {
                    running = false;
                    step = null;
                }
            });
        }
        return status();
    }

    private void waitForCluster() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        while (true) {
            try {
                if (es.send(EsClient.User.ADMIN, "GET", "/_cluster/health?wait_for_status=yellow&timeout=5s", null).ok()) {
                    return;
                }
            } catch (IllegalStateException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("等了 3 分鐘 Elasticsearch 還沒準備好");
            }
            Thread.sleep(3000);
        }
    }

    /**
     * reader：只能讀練習資料；learner：再加上 scratch* 索引的所有權限（寫入題、主控台的寫入練習）。
     * _analyze 預設要 manage 權限（範圍太大），這裡只授予 analyze 這一個動作。
     */
    private void ensureUsers() {
        List<String> shop = INDICES;
        List<String> cluster = List.of("monitor", "cluster:admin/analyze");
        List<String> read = List.of("read", "view_index_metadata", "monitor", "indices:admin/analyze");
        es.admin("PUT", "/_security/role/shop_reader", Map.of(
                "cluster", cluster,
                "indices", List.of(Map.of("names", shop, "privileges", read))));
        es.admin("PUT", "/_security/role/shop_learner", Map.of(
                "cluster", cluster,
                "indices", List.of(
                        Map.of("names", shop, "privileges", read),
                        Map.of("names", List.of("scratch*"), "privileges", List.of("all")))));
        // scratch* 是單節點上的練習索引：預設不要副本（否則永遠是 yellow）
        es.admin("PUT", "/_index_template/scratch", Map.of("index_patterns", List.of("scratch*"),
                "template", Map.of("settings", Map.of("number_of_shards", 1, "number_of_replicas", 0))));
        es.admin("PUT", "/_security/user/reader", Map.of("password", readerPassword, "roles", List.of("shop_reader")));
        es.admin("PUT", "/_security/user/learner", Map.of("password", learnerPassword, "roles", List.of("shop_learner")));
    }

    // ================================================================ 載入

    private void load() {
        error = null;
        long start = System.nanoTime();
        step = "建立索引";
        JsonNode defs = readJson("elastic/indices.json");
        for (String index : INDICES) {
            es.send(EsClient.User.ADMIN, "DELETE", "/" + index, null);
            ObjectNode def = (ObjectNode) defs.get(index).deepCopy();
            ((ObjectNode) def.get("settings")).put("refresh_interval", "-1");      // 大量寫入時先關掉 refresh
            es.admin("PUT", "/" + index, def);
        }

        step = "商品";
        Map<String, Map<String, Object>> productDocs = new LinkedHashMap<>();
        Map<Integer, Product> products = loadProducts(productDocs);
        step = "訂單與評論";
        Map<Integer, int[]> ratings = loadOrdersAndReviews(products);
        // 評論的平均星等與評論數先算好再寫入商品（寫入後再更新會留下舊版本，影響 BM25 的統計）
        ratings.forEach((id, r) -> {
            Map<String, Object> doc = productDocs.get(String.valueOf(id));
            doc.put("rating", Math.round(r[0] * 10.0 / r[1]) / 10.0);
            doc.put("review_count", r[1]);
        });
        bulk("products", productDocs);
        step = "存取紀錄";
        loadLogs();

        step = "refresh";
        for (String index : INDICES) {
            es.admin("PUT", "/" + index + "/_settings", Map.of("index", Map.of("refresh_interval", "1s")));
            es.admin("POST", "/" + index + "/_refresh", null);
            es.admin("POST", "/" + index + "/_forcemerge?max_num_segments=1", null);   // 合併成一個 segment，分數與順序每次都一樣
        }
        seconds = (System.nanoTime() - start) / 1e9;
        log.info("Elasticsearch 練習資料載入完成，{} 秒", String.format("%.1f", seconds));
    }

    private JsonNode readJson(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return es.json().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 每 5000 筆送一次 _bulk。docs 的 key 是 _id。 */
    private void bulk(String index, Map<String, Map<String, Object>> docs) {
        StringBuilder body = new StringBuilder();
        int n = 0;
        for (var e : docs.entrySet()) {
            body.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(e.getKey()).append("\"}}\n");
            body.append(es.write(e.getValue())).append('\n');
            if (++n % 5000 == 0) {
                sendBulk(body);
                body.setLength(0);
            }
        }
        if (!body.isEmpty()) {
            sendBulk(body);
        }
    }

    private void sendBulk(StringBuilder body) {
        JsonNode r = es.admin("POST", "/_bulk", body.toString());
        if (r.path("errors").asBoolean()) {
            for (JsonNode item : r.path("items")) {
                JsonNode err = item.path("index").path("error");
                if (!err.isMissingNode()) {
                    throw new IllegalStateException("_bulk 有錯誤：" + err);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 商品

    private record Product(int id, String name, String brand, String category, List<String> path, int price, double quality) {
    }

    private Map<Integer, Product> loadProducts(Map<String, Map<String, Object>> docs) {
        Map<Integer, String[]> categories = new HashMap<>();
        postgres.query("SELECT id, name, parent_id FROM categories", rs -> {
            categories.put(rs.getInt(1), new String[] {rs.getString(2), rs.getString(3)});
        });
        Map<Integer, Product> products = new LinkedHashMap<>();
        postgres.query("""
                SELECT id, name, category_id, round(price)::int, stock, is_active, created_at, specs::text, tags
                FROM products ORDER BY id""", rs -> {
            int id = rs.getInt(1);
            String name = rs.getString(2);
            String[] parts = name.split(" ");
            String brand = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, Math.max(1, parts.length - 2)));
            List<String> path = new ArrayList<>();
            Integer c = rs.getInt(3);
            String category = categories.get(c)[0];
            while (c != null) {
                String[] cat = categories.get(c);
                path.add(0, cat[0]);
                c = cat[1] == null ? null : Integer.valueOf(cat[1]);
            }
            JsonNode specs = null;
            try {
                specs = rs.getString(8) == null ? null : es.json().readTree(rs.getString(8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            List<String> tags = List.of((String[]) rs.getArray(9).getArray());
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("name", name);
            doc.put("brand", brand);
            doc.put("category", category);
            doc.put("category_path", path);
            doc.put("description", describe(brand, category, specs, tags));
            doc.put("price", rs.getInt(4));
            doc.put("stock", rs.getInt(5));
            doc.put("is_active", rs.getBoolean(6));
            doc.put("tags", tags);
            if (specs != null) {
                doc.put("specs", specs);
            }
            doc.put("created_at", rs.getDate(7).toString());
            docs.put(String.valueOf(id), doc);
            // 每件商品的「品質」決定評論的平均星等（用 id 雜湊，每次都一樣）
            double quality = 3.0 + (Math.floorMod(id * 2654435761L, 1000) / 1000.0) * 1.9;
            products.put(id, new Product(id, name, brand, category, path, rs.getInt(4), quality));
        });
        return products;
    }

    private static String text(JsonNode specs, String field) {
        return specs == null || specs.path(field).isMissingNode() ? null : specs.path(field).asText();
    }

    /** 依規格產生一段中文描述。 */
    static String describe(String brand, String category, JsonNode s, List<String> tags) {
        List<String> p = new ArrayList<>();
        String color = text(s, "color");
        if (color != null) {
            p.add(color.equals("透明") ? "透明" : color + "色");
        }
        if (s != null) {
            switch (category) {
                case "手機" -> {
                    p.add(text(s, "screen_inch") + " 吋螢幕");
                    p.add(text(s, "storage_gb") + "GB 儲存空間");
                    if (s.path("5g").asBoolean()) {
                        p.add("支援 5G");
                    }
                }
                case "筆電" -> {
                    p.add("搭載 " + text(s, "cpu"));
                    p.add(text(s, "ram_gb") + "GB 記憶體");
                    p.add(text(s, "storage_gb") + "GB SSD");
                    p.add("重量 " + text(s, "weight_kg") + " 公斤" + (s.path("weight_kg").asDouble() <= 1.3 ? "，輕薄好攜帶" : ""));
                }
                case "無線耳機" -> {
                    p.add("藍牙無線");
                    if (s.path("noise_cancelling").asBoolean()) {
                        p.add("主動降噪");
                    }
                    p.add("續航 " + text(s, "battery_hours") + " 小時");
                }
                case "有線耳機" -> p.add("線長 " + text(s, "cable_m") + " 公尺");
                case "手機配件" -> p.add(text(s, "type"));
                case "廚房用品" -> {
                    p.add(text(s, "material") + "材質");
                    p.add("容量 " + text(s, "capacity_ml") + " 毫升");
                }
                case "寢具", "收納" -> {
                    p.add(text(s, "material") + "材質");
                    p.add(text(s, "size") + "尺寸");
                }
                case "男裝", "女裝" -> p.add(text(s, "material") + "材質");
                case "運動鞋", "休閒鞋" -> p.add(text(s, "gender") + "款");
                case "零食", "生鮮" -> {
                    p.add(text(s, "origin") + "產");
                    p.add(text(s, "weight_g") + " 公克");
                }
                case "飲料" -> {
                    p.add(text(s, "origin") + "產");
                    p.add(text(s, "volume_ml") + " 毫升");
                    if (s.path("nutrition").path("calories").asInt(-1) == 0) {
                        p.add("零卡");
                    }
                }
                case "保養", "彩妝" -> {
                    p.add("適合" + text(s, "skin_type") + "肌膚");
                    p.add(text(s, "volume_ml") + " 毫升");
                }
                case "健身器材", "露營用品" -> p.add("重量 " + text(s, "weight_kg") + " 公斤" + (s.path("weight_kg").asDouble() <= 1 ? "，輕量好攜帶" : ""));
                default -> {
                }
            }
        }
        String head = brand + " " + category;
        String body = p.isEmpty() ? "" : "：" + String.join("，", p);
        String tail = tags.isEmpty() ? "" : "。" + String.join("、", tags);
        return head + body + tail;
    }

    // ---------------------------------------------------------------- 訂單與評論

    /** 回傳每件商品的評論星等加總與則數。 */
    private Map<Integer, int[]> loadOrdersAndReviews(Map<Integer, Product> products) {
        Map<Integer, Map<String, Object>> customers = new HashMap<>();
        postgres.query("SELECT id, name, city, vip_level, gender FROM customers", rs -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", rs.getString(2));
            c.put("city", rs.getString(3));
            c.put("vip_level", rs.getString(4));
            c.put("gender", rs.getString(5));
            customers.put(rs.getInt(1), c);
        });
        Map<Integer, Map<String, Object>> orders = new LinkedHashMap<>();
        postgres.query("""
                SELECT o.id, o.customer_id, o.status, o.order_date, o.shipping_city, o.shipping_fee,
                       oi.product_id, oi.quantity, round(oi.unit_price)::int, oi.discount
                FROM orders o JOIN order_items oi ON oi.order_id = o.id
                ORDER BY o.id, oi.product_id""", rs -> {
            int id = rs.getInt(1);
            Map<String, Object> o = orders.computeIfAbsent(id, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    m.put("customer_id", rs.getInt(2));
                    m.put("customer", customers.get(rs.getInt(2)));
                    m.put("status", rs.getString(3));
                    m.put("order_date", rs.getObject(4, OffsetDateTime.class).atZoneSameInstant(TAIPEI).format(ISO));
                    m.put("shipping_city", rs.getString(5));
                    m.put("shipping_fee", rs.getInt(6));
                    m.put("items", new ArrayList<Map<String, Object>>());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
                return m;
            });
            Product p = products.get(rs.getInt(7));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product_id", p.id());
            item.put("name", p.name());
            item.put("brand", p.brand());
            item.put("category", p.category());
            item.put("quantity", rs.getInt(8));
            item.put("unit_price", rs.getInt(9));
            item.put("discount", rs.getDouble(10));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) o.get("items");
            items.add(item);
        });

        Map<String, Map<String, Object>> orderDocs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> objectDocs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> reviews = new LinkedHashMap<>();
        Random random = new Random(20261009);
        Map<Integer, int[]> ratings = new HashMap<>();
        int reviewId = 0;
        for (var e : orders.entrySet()) {
            Map<String, Object> o = e.getValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) o.get("items");
            long total = 0;
            for (Map<String, Object> it : items) {
                total += Math.round((Integer) it.get("quantity") * (Integer) it.get("unit_price") * (1 - (Double) it.get("discount")));
            }
            o.put("total", total);
            o.put("item_count", items.size());
            orderDocs.put(String.valueOf(e.getKey()), o);
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("customer_id", o.get("customer_id"));
            flat.put("status", o.get("status"));
            flat.put("order_date", o.get("order_date"));
            flat.put("total", total);
            flat.put("items", items);
            objectDocs.put(String.valueOf(e.getKey()), flat);

            String status = (String) o.get("status");
            if (!status.equals("delivered") && !status.equals("returned")) {
                continue;                                         // 只有收到貨的訂單才會有評論
            }
            for (Map<String, Object> it : items) {
                if (random.nextDouble() >= 0.12) {
                    continue;
                }
                Product p = products.get((Integer) it.get("product_id"));
                int rating = (int) Math.round(p.quality() + random.nextGaussian() * 0.9 - (status.equals("returned") ? 2 : 0));
                rating = Math.max(1, Math.min(5, rating));
                ZonedDateTime date = OffsetDateTime.parse((String) o.get("order_date")).atZoneSameInstant(TAIPEI)
                        .plusDays(2 + random.nextInt(18)).plusMinutes(random.nextInt(600));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("product_id", p.id());
                r.put("product_name", p.name());
                r.put("brand", p.brand());
                r.put("category", p.category());
                r.put("customer_id", o.get("customer_id"));
                r.put("rating", rating);
                r.put("title", pick(random, TITLES.get(rating)));
                r.put("content", review(random, p.category(), rating));
                r.put("helpful", (int) Math.floor(Math.pow(random.nextDouble(), 3) * 40));
                r.put("verified", random.nextDouble() < 0.85);
                r.put("created_at", date.format(ISO));
                reviews.put(String.valueOf(++reviewId), r);
                int[] sum = ratings.computeIfAbsent(p.id(), k -> new int[2]);
                sum[0] += rating;
                sum[1]++;
            }
        }
        bulk("orders", orderDocs);
        bulk("orders_object", objectDocs);
        bulk("reviews", reviews);
        return ratings;
    }

    private static final Map<Integer, List<String>> TITLES = Map.of(
            5, List.of("非常滿意", "超級推薦", "大推", "買了不後悔"),
            4, List.of("滿意", "還不錯", "值得買", "整體不錯"),
            3, List.of("普通", "還可以", "中規中矩", "有好有壞"),
            2, List.of("有點失望", "不太滿意", "不如預期"),
            1, List.of("很失望", "不推薦", "踩雷了", "退貨"));

    /** 各類別的優點與缺點，評論內容由這些片語組成。 */
    private static final Map<String, String[][]> ASPECTS = Map.ofEntries(
            Map.entry("手機", new String[][] {{"螢幕很清楚", "拍照效果很好", "電池很耐用", "運行很順暢", "5G 網速很快"}, {"電池消耗很快", "機身容易發燙", "拍照效果普通", "容量不太夠"}}),
            Map.entry("筆電", new String[][] {{"開機速度很快", "很輕薄方便攜帶", "螢幕色彩很漂亮", "打電動很順", "鍵盤手感很好"}, {"風扇有點吵", "機身偏重", "電池續航不夠", "容易發燙"}}),
            Map.entry("無線耳機", new String[][] {{"音質很好", "降噪效果很棒", "通勤戴很舒服", "續航很久", "藍牙連線很穩定"}, {"電池續航不太夠", "戴久了耳朵會痛", "藍牙偶爾會斷線", "降噪效果普通"}}),
            Map.entry("有線耳機", new String[][] {{"音質很清楚", "低音很飽滿", "線材很耐用", "戴起來很舒服"}, {"線容易打結", "低音有點弱", "戴久了耳朵會痛"}}),
            Map.entry("手機配件", new String[][] {{"充電速度很快", "做工很紮實", "手感很好", "尺寸剛好"}, {"充電有點慢", "用沒多久就壞了", "尺寸不太合"}}),
            Map.entry("廚房用品", new String[][] {{"很好清洗", "保溫效果很好", "煮飯很方便", "質感很好"}, {"容易沾鍋", "有點重", "清洗不方便"}}),
            Map.entry("寢具", new String[][] {{"睡起來很舒服", "很透氣", "材質很柔軟", "洗過也不會變形"}, {"有點悶熱", "洗了會縮水", "有異味"}}),
            Map.entry("收納", new String[][] {{"空間很大", "組裝很簡單", "很堅固", "收納很方便"}, {"組裝有點麻煩", "有點搖晃", "尺寸比想像中小"}}),
            Map.entry("男裝", new String[][] {{"版型很好看", "布料很舒服", "很保暖", "顏色跟圖片一樣", "尺寸剛好"}, {"洗了會縮水", "容易起毛球", "尺寸偏小", "有點透"}}),
            Map.entry("女裝", new String[][] {{"版型很好看", "布料很舒服", "很保暖", "顏色跟圖片一樣", "尺寸剛好"}, {"洗了會縮水", "容易起毛球", "尺寸偏小", "有點透"}}),
            Map.entry("運動鞋", new String[][] {{"穿起來很輕", "跑步很舒服", "避震效果很好", "很好搭配"}, {"鞋底很快就磨平", "尺寸偏小", "有點磨腳"}}),
            Map.entry("休閒鞋", new String[][] {{"很好搭配", "穿起來很舒服", "很耐穿"}, {"有點磨腳", "尺寸偏大", "容易髒"}}),
            Map.entry("零食", new String[][] {{"很好吃", "口味很特別", "份量很多", "小朋友很喜歡"}, {"有點太甜", "太鹹了", "份量有點少"}}),
            Map.entry("飲料", new String[][] {{"很好喝", "很解渴", "不會太甜"}, {"太甜了", "味道有點怪", "有點貴"}}),
            Map.entry("生鮮", new String[][] {{"很新鮮", "肉質很好", "包裝很仔細"}, {"不太新鮮", "收到時已經退冰", "份量比標示少"}}),
            Map.entry("保養", new String[][] {{"很保濕", "很溫和不刺激", "吸收很快", "皮膚變得很好"}, {"用了會過敏", "有點黏膩", "味道太重"}}),
            Map.entry("彩妝", new String[][] {{"很顯色", "持久度很好", "很好推開"}, {"容易脫妝", "顏色跟圖片不一樣", "有點乾"}}),
            Map.entry("健身器材", new String[][] {{"很穩固", "在家運動很方便", "品質很好"}, {"使用時有點吵", "組裝很麻煩", "有異味"}}),
            Map.entry("露營用品", new String[][] {{"很輕巧好攜帶", "搭設很簡單", "防水效果很好"}, {"有點重", "收納不方便", "防水效果普通"}}));
    private static final String[] GENERIC_GOOD = {"出貨很快", "包裝很完整", "CP值很高", "會再回購", "品質很好"};
    private static final String[] GENERIC_BAD = {"出貨有點慢", "跟圖片不太一樣", "有小瑕疵", "客服回覆很慢", "價格偏高"};

    private static String review(Random r, String category, int rating) {
        String[][] a = ASPECTS.get(category);
        String[] good = a[0], bad = a[1];
        List<String> parts = new ArrayList<>();
        if (rating >= 4) {
            parts.add(pick(r, good));
            String second = pick(r, good);
            if (!second.equals(parts.get(0))) {
                parts.add(second);
            }
            if (rating == 4 && r.nextDouble() < 0.4) {
                String b = pick(r, bad);
                for (int i = 0; i < 5 && sameTopic(parts, b); i++) {
                    b = pick(r, bad);
                }
                parts.add("不過" + b);
            } else {
                parts.add(pick(r, GENERIC_GOOD));
            }
        } else if (rating == 3) {
            String g = pick(r, good), b = pick(r, bad);
            for (int i = 0; i < 5 && b.substring(0, 2).equals(g.substring(0, 2)); i++) {
                b = pick(r, bad);                                 // 避免「降噪效果很棒，但是降噪效果普通」這種自相矛盾的句子
            }
            parts.add(g);
            parts.add("但是" + b);
        } else {
            parts.add(pick(r, bad));
            parts.add(pick(r, GENERIC_BAD));
            if (rating == 1) {
                parts.add("不會再買了");
            }
        }
        return String.join("，", parts) + "。";
    }

    /** 開頭兩個字一樣，視為在講同一件事（例如「降噪效果很棒」與「降噪效果普通」）。 */
    private static boolean sameTopic(List<String> parts, String phrase) {
        return parts.stream().anyMatch(p -> p.substring(0, 2).equals(phrase.substring(0, 2)));
    }

    private static String pick(Random r, String[] options) {
        return options[r.nextInt(options.length)];
    }

    private static String pick(Random r, List<String> options) {
        return options.get(r.nextInt(options.size()));
    }

    // ---------------------------------------------------------------- 存取紀錄

    private record Route(String service, String method, String path, double weight, double latency) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route("product", "GET", "/api/products/{id}", 33, 30),
            new Route("search", "GET", "/api/search", 20, 80),
            new Route("cart", "GET", "/api/cart", 10, 25),
            new Route("cart", "POST", "/api/cart/items", 8, 35),
            new Route("order", "GET", "/api/orders/{id}", 7, 40),
            new Route("order", "POST", "/api/orders", 5, 120),
            new Route("payment", "POST", "/api/payments", 3, 400),
            new Route("auth", "POST", "/api/login", 7, 60),
            new Route("gateway", "GET", "/health", 7, 3));

    /** 9/18 14:00～14:40 金流逾時；9/25 03:00～03:20 購物車連不上 Redis；9/12 有一個 IP 在掃描不存在的網址。 */
    private void loadLogs() {
        Random r = new Random(9_2026);
        ZonedDateTime start = LocalDate.of(2026, 9, 1).atStartOfDay(TAIPEI);
        long startMs = start.toInstant().toEpochMilli();
        long month = 30L * 24 * 3600 * 1000;
        double[] hourWeight = {0.3, 0.2, 0.15, 0.1, 0.1, 0.15, 0.3, 0.6, 0.9, 1, 1, 1.1, 1.2, 1, 1, 1, 1, 1.1, 1.3, 1.6, 1.9, 2.0, 1.6, 0.8};
        double totalWeight = ROUTES.stream().mapToDouble(Route::weight).sum();
        Instant incidentPayStart = ZonedDateTime.of(2026, 9, 18, 14, 0, 0, 0, TAIPEI).toInstant();
        Instant incidentPayEnd = incidentPayStart.plusSeconds(40 * 60);
        Instant incidentCartStart = ZonedDateTime.of(2026, 9, 25, 3, 0, 0, 0, TAIPEI).toInstant();
        Instant incidentCartEnd = incidentCartStart.plusSeconds(20 * 60);

        List<Map<String, Object>> entries = new ArrayList<>();
        while (entries.size() < 200_000) {
            long ts = startMs + (long) (r.nextDouble() * month);
            Instant t = Instant.ofEpochMilli(ts);
            int hour = t.atZone(TAIPEI).getHour();
            if (r.nextDouble() * 2.0 > hourWeight[hour]) {
                continue;                                         // 依時段的流量比例取樣（晚上 8～10 點最多）
            }
            double pick = r.nextDouble() * totalWeight;
            Route route = ROUTES.get(0);
            for (Route x : ROUTES) {
                pick -= x.weight();
                if (pick <= 0) {
                    route = x;
                    break;
                }
            }
            boolean payIncident = !t.isBefore(incidentPayStart) && t.isBefore(incidentPayEnd);
            boolean cartIncident = !t.isBefore(incidentCartStart) && t.isBefore(incidentCartEnd);
            entries.add(entry(r, ts, route, payIncident, cartIncident));
        }
        // 事故期間使用者一直重試：金流與下單的請求暴增；購物車在凌晨也有一波重試
        Route pay = ROUTES.get(6), createOrder = ROUTES.get(5), cart = ROUTES.get(2), addCart = ROUTES.get(3);
        for (int i = 0; i < 900; i++) {
            long ts = incidentPayStart.toEpochMilli() + (long) (r.nextDouble() * 40 * 60_000);
            entries.add(entry(r, ts, r.nextDouble() < 0.7 ? pay : createOrder, true, false));
        }
        for (int i = 0; i < 500; i++) {
            long ts = incidentCartStart.toEpochMilli() + (long) (r.nextDouble() * 20 * 60_000);
            entries.add(entry(r, ts, r.nextDouble() < 0.6 ? cart : addCart, false, true));
        }
        // 9/12 凌晨 198.51.100.66 掃描不存在的網址
        long scanStart = ZonedDateTime.of(2026, 9, 12, 2, 0, 0, 0, TAIPEI).toInstant().toEpochMilli();
        String[] probes = {"/wp-login.php", "/.env", "/admin", "/phpmyadmin", "/.git/config", "/api/v1/debug", "/server-status", "/backup.zip"};
        for (int i = 0; i < 1500; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            long ts = scanStart + (long) (r.nextDouble() * 3600_000);
            e.put("@timestamp", Instant.ofEpochMilli(ts).atZone(TAIPEI).format(ISO));
            e.put("level", "WARN");
            e.put("service", "gateway");
            e.put("method", "GET");
            e.put("path", probes[r.nextInt(probes.length)]);
            e.put("status", 404);
            e.put("latency_ms", 1 + r.nextInt(4));
            e.put("ip", "198.51.100.66");
            e.put("trace_id", traceId(r));
            e.put("message", "Not found: " + e.get("path"));
            entries.add(e);
        }
        entries.sort((a, b) -> ((String) a.get("@timestamp")).compareTo((String) b.get("@timestamp")));
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            docs.put(String.valueOf(i + 1), entries.get(i));
        }
        bulk("logs", docs);
    }

    private static String traceId(Random r) {
        return String.format("%016x", r.nextLong());
    }

    private Map<String, Object> entry(Random r, long ts, Route route, boolean payIncident, boolean cartIncident) {
        Map<String, Object> e = new LinkedHashMap<>();
        String path = route.path().replace("{id}", String.valueOf(1 + r.nextInt(route.service().equals("order") ? 80000 : 1600)));
        int status = route.method().equals("POST") && !route.service().equals("auth") ? 201 : 200;
        int latency = (int) Math.max(1, Math.round(route.latency() * Math.exp(r.nextGaussian() * 0.5)));
        String level = "INFO";
        String message = null;
        Integer user = r.nextDouble() < 0.7 ? 1 + r.nextInt(20000) : null;
        double x = r.nextDouble();
        if (payIncident && route.service().equals("payment") && x < 0.65) {
            status = 504;
            latency = 30000 + r.nextInt(50);
            message = "Timeout calling payment gateway after 30000 ms";
        } else if (payIncident && route.service().equals("order") && route.method().equals("POST") && x < 0.4) {
            status = 500;
            latency = 30000 + r.nextInt(200);
            message = "Payment service unavailable: order creation failed";
        } else if (cartIncident && route.service().equals("cart") && x < 0.8) {
            status = 503;
            latency = 2000 + r.nextInt(100);
            message = "Connection refused: redis-cart:6379";
        } else if (route.service().equals("product") && x < 0.02) {
            status = 404;
            message = "Product not found: " + path.substring(path.lastIndexOf('/') + 1);
        } else if (route.service().equals("auth") && x < 0.08) {
            status = 401;
            message = "Invalid password for user " + (user == null ? 1 + r.nextInt(20000) : user);
        } else if (route.service().equals("cart") && route.method().equals("POST") && x < 0.03) {
            status = 400;
            message = "Validation failed: quantity must be greater than 0";
        } else if (route.service().equals("order") && route.method().equals("POST") && x < 0.004) {
            status = 500;
            message = "java.lang.NullPointerException: Cannot invoke \"Coupon.getRate()\" because \"coupon\" is null";
        } else if (route.service().equals("search") && x < 0.002) {
            status = 500;
            message = "Search timeout: query took longer than 5000 ms";
            latency = 5000 + r.nextInt(100);
        }
        if (status >= 500) {
            level = "ERROR";
        } else if (status >= 400) {
            level = "WARN";
        }
        if (message == null) {
            message = route.method() + " " + path + " " + status + " " + latency + "ms";
        }
        e.put("@timestamp", Instant.ofEpochMilli(ts).atZone(TAIPEI).format(ISO));
        e.put("level", level);
        e.put("service", route.service());
        e.put("method", route.method());
        e.put("path", route.path());
        e.put("status", status);
        e.put("latency_ms", latency);
        if (user != null) {
            e.put("user_id", user);
        }
        e.put("ip", ip(r));
        e.put("trace_id", traceId(r));
        e.put("message", message);
        return e;
    }

    private static String ip(Random r) {
        String[] nets = {"203.0.113.", "198.51.100.", "192.0.2."};
        return nets[r.nextInt(3)] + (1 + r.nextInt(254));
    }
}
