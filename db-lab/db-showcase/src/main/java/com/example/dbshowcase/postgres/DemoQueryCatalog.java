package com.example.dbshowcase.postgres;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** 示範查詢清單：由淺入深，對應 README 的學習路線。 */
@Component
public class DemoQueryCatalog {

    private final List<DemoQuery> queries = List.of(
            new DemoQuery("count-by-status", "基礎彙總",
                    "各狀態訂單數量",
                    "GROUP BY + COUNT，最基本的彙總。",
                    """
                    SELECT status, count(*) AS orders
                    FROM orders
                    GROUP BY status
                    ORDER BY orders DESC;
                    """),

            new DemoQuery("top-products", "多表 JOIN",
                    "銷售額前 20 名商品",
                    "orders → order_items → products → categories 四張表 JOIN，只算已送達訂單。",
                    """
                    SELECT p.name AS product,
                           c.name AS category,
                           sum(oi.quantity) AS qty,
                           round(sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS revenue
                    FROM order_items oi
                    JOIN orders o     ON o.id = oi.order_id
                    JOIN products p   ON p.id = oi.product_id
                    JOIN categories c ON c.id = p.category_id
                    WHERE o.status = 'delivered'
                    GROUP BY p.name, c.name
                    ORDER BY revenue DESC
                    LIMIT 20;
                    """),

            new DemoQuery("products-never-sold", "NOT EXISTS",
                    "從來沒賣出過的商品",
                    "NOT EXISTS 找「不存在對應資料」的列，比 NOT IN 安全（NOT IN 遇到 NULL 會整個失效）。",
                    """
                    SELECT p.id, p.name, p.price, p.created_at
                    FROM products p
                    WHERE NOT EXISTS (
                        SELECT 1 FROM order_items oi WHERE oi.product_id = p.id
                    )
                    ORDER BY p.id;
                    """),

            new DemoQuery("filter-by-city", "條件彙總",
                    "各城市訂單：成功 vs 取消/退貨",
                    "COUNT(*) FILTER (WHERE ...) 一次算出多種條件的數量，等同 SUM(CASE WHEN ...)。",
                    """
                    SELECT shipping_city,
                           count(*) AS total,
                           count(*) FILTER (WHERE status = 'delivered')               AS delivered,
                           count(*) FILTER (WHERE status IN ('cancelled', 'returned')) AS failed,
                           round(100.0 * count(*) FILTER (WHERE status IN ('cancelled', 'returned')) / count(*), 1) AS fail_pct
                    FROM orders
                    GROUP BY shipping_city
                    ORDER BY total DESC;
                    """),

            new DemoQuery("monthly-revenue", "日期函數",
                    "每月營收與月增率",
                    "date_trunc 把時間截到月份，再用 LAG 取上個月營收算成長率。",
                    """
                    WITH monthly AS (
                        SELECT date_trunc('month', o.order_date)::date AS month,
                               round(sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS revenue
                        FROM orders o
                        JOIN order_items oi ON oi.order_id = o.id
                        WHERE o.status = 'delivered'
                        GROUP BY 1
                    )
                    SELECT month,
                           revenue,
                           lag(revenue) OVER (ORDER BY month) AS prev_month,
                           round(100.0 * (revenue - lag(revenue) OVER (ORDER BY month))
                                 / lag(revenue) OVER (ORDER BY month), 1) AS growth_pct
                    FROM monthly
                    ORDER BY month;
                    """),

            new DemoQuery("price-rank", "視窗函數",
                    "分類內商品價格排名（三種排名比較）",
                    "同價時：ROW_NUMBER 照樣給不同號、RANK 會跳號、DENSE_RANK 不跳號。以「飲料」分類為例。",
                    """
                    SELECT c.name AS category,
                           p.name,
                           p.price,
                           row_number() OVER w AS row_number,
                           rank()       OVER w AS rank,
                           dense_rank() OVER w AS dense_rank
                    FROM products p
                    JOIN categories c ON c.id = p.category_id
                    WHERE c.name = '飲料'
                    WINDOW w AS (PARTITION BY p.category_id ORDER BY p.price DESC)
                    ORDER BY p.price DESC;
                    """),

            new DemoQuery("top-customer-per-city", "視窗函數",
                    "每個城市消費最高的會員",
                    "經典「分組取第一名」題：先用 ROW_NUMBER 分組排名，外層再取 rn = 1。",
                    """
                    WITH spend AS (
                        SELECT c.city, c.id, c.name,
                               round(sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS total
                        FROM customers c
                        JOIN orders o       ON o.customer_id = c.id AND o.status = 'delivered'
                        JOIN order_items oi ON oi.order_id = o.id
                        WHERE c.city IS NOT NULL
                        GROUP BY c.city, c.id, c.name
                    ), ranked AS (
                        SELECT *, row_number() OVER (PARTITION BY city ORDER BY total DESC) AS rn
                        FROM spend
                    )
                    SELECT city, id, name, total
                    FROM ranked
                    WHERE rn = 1
                    ORDER BY total DESC;
                    """),

            new DemoQuery("running-total", "視窗函數",
                    "單一會員的累計消費",
                    "SUM() OVER (ORDER BY ...) 做累計加總，資料不會被 GROUP BY 壓扁。",
                    """
                    SELECT o.id AS order_id,
                           o.order_date::date AS order_day,
                           round(sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS amount,
                           round(sum(sum(oi.quantity * oi.unit_price * (1 - oi.discount)))
                                 OVER (ORDER BY o.order_date)) AS running_total
                    FROM orders o
                    JOIN order_items oi ON oi.order_id = o.id
                    WHERE o.customer_id = 1 AND o.status = 'delivered'
                    GROUP BY o.id, o.order_date
                    ORDER BY o.order_date;
                    """),

            new DemoQuery("category-tree", "遞迴 CTE",
                    "完整分類樹與路徑",
                    "WITH RECURSIVE 從最上層分類一路往下展開，組出「3C電子 > 耳機 > 無線耳機」這種路徑。",
                    """
                    WITH RECURSIVE tree AS (
                        SELECT id, name, parent_id, 1 AS depth, name::text AS path
                        FROM categories
                        WHERE parent_id IS NULL
                        UNION ALL
                        SELECT c.id, c.name, c.parent_id, t.depth + 1, t.path || ' > ' || c.name
                        FROM categories c
                        JOIN tree t ON c.parent_id = t.id
                    )
                    SELECT id, depth, path
                    FROM tree
                    ORDER BY path;
                    """),

            new DemoQuery("orders-of-customer", "索引與效能",
                    "查某位會員的所有訂單（沒有索引）",
                    "orders.customer_id 沒建索引，PostgreSQL 只能把 8 萬筆全部掃一遍（Seq Scan）。看看耗時。",
                    """
                    SELECT id, order_date, status, shipping_city
                    FROM orders
                    WHERE customer_id = 12345
                    ORDER BY order_date;
                    """),

            new DemoQuery("explain-orders-of-customer", "索引與效能",
                    "EXPLAIN ANALYZE：看執行計畫",
                    "同一個查詢加上 EXPLAIN ANALYZE，會回傳資料庫實際怎麼執行、每一步花多久。",
                    """
                    EXPLAIN ANALYZE
                    SELECT id, order_date, status, shipping_city
                    FROM orders
                    WHERE customer_id = 12345
                    ORDER BY order_date;
                    """));

    public List<DemoQuery> all() {
        return queries;
    }

    public Optional<DemoQuery> find(String id) {
        return queries.stream().filter(q -> q.id().equals(id)).findFirst();
    }
}
