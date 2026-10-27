# 資料庫 CheatSheet

面試導向的資料庫速查表，範例都可以直接在練習環境（`shop` 電商資料）執行。★ = 面試高頻考點。
上方分頁切換資料庫，左側目錄跳到章節；搜尋會找遍所有資料庫，分頁上會顯示各自找到幾節。

---

# PostgreSQL

> 會修改資料的範例（第 10、11 節）可以在展示台的「寫入沙盒」練習（執行完自動 ROLLBACK），或在 psql、DBeaver 裡練習。練壞了就重建資料庫：`docker compose down -v && docker compose up -d`

## 0. ★ SQL 的「執行順序」

寫的順序跟資料庫執行的順序不一樣，很多題目的答案都跟這個有關：

```
寫的順序：SELECT → FROM → WHERE → GROUP BY → HAVING → ORDER BY → LIMIT
執行順序：FROM/JOIN → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT
```

- **WHERE 不能用 SELECT 取的別名**（WHERE 執行時別名還不存在），ORDER BY 可以
- **WHERE 不能放聚合函數**（`WHERE count(*) > 5` ❌），要放 HAVING
- WHERE 先過濾「列」，HAVING 再過濾「分組」

---

## 1. 已經會的（速查）

```sql
SELECT DISTINCT city FROM customers;
SELECT count(*), max(price), min(price), round(avg(price), 2) FROM products;

SELECT c.city, count(*) AS orders
FROM orders o
JOIN customers c ON c.id = o.customer_id
WHERE o.status = 'delivered'
GROUP BY c.city
HAVING count(*) > 1000
ORDER BY orders DESC
LIMIT 5;
```

分頁：`LIMIT 20 OFFSET 40`（第 3 頁，每頁 20 筆）

---

## 2. JOIN 全家

| 寫法 | 結果 |
|---|---|
| `INNER JOIN` / `JOIN` | 兩邊都對得上的才留下 |
| `LEFT JOIN` | 左表全留，右表對不上的補 NULL |
| `RIGHT JOIN` | 右表全留（實務上通常改寫成 LEFT JOIN） |
| `FULL JOIN` | 兩邊全留，對不上的補 NULL |
| `CROSS JOIN` | 笛卡兒積：左 N 筆 × 右 M 筆 |
| SELF JOIN | 同一張表 JOIN 自己，要取不同別名 |

```sql
-- 找「沒有」對應資料：LEFT JOIN + IS NULL
SELECT c.id, c.name
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL;

-- SELF JOIN：每個分類和它的上層分類
SELECT c.name AS category, p.name AS parent
FROM categories c
LEFT JOIN categories p ON p.id = c.parent_id;
```

★ **LEFT JOIN 的陷阱：條件放 ON 還是 WHERE？**

```sql
-- 條件放 ON：所有會員都留著，只是「已取消訂單」才會對上
SELECT c.id, count(o.id)
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id AND o.status = 'cancelled'
GROUP BY c.id;

-- 條件放 WHERE：沒取消過的會員 o.status 是 NULL，被過濾掉 → 變成 INNER JOIN 了
SELECT c.id, count(o.id)
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.status = 'cancelled'
GROUP BY c.id;
```

---

## 3. ★ NULL 的處理

| 寫法 | 說明 |
|---|---|
| `col IS NULL` / `IS NOT NULL` | 判斷 NULL。**`= NULL` 永遠不成立** |
| `COALESCE(a, b, c)` | 回傳第一個不是 NULL 的值 |
| `NULLIF(a, b)` | a = b 時回傳 NULL（常用來避免除以 0） |
| `count(*)` vs `count(col)` | `count(*)` 數列數；`count(col)` 不算 NULL |
| `a IS DISTINCT FROM b` | 把 NULL 當成一般值來比較的「不等於」 |

```sql
SELECT name, COALESCE(city, '未填寫') AS city FROM customers;
SELECT count(*) AS all_rows, count(birth_date) AS has_birthday FROM customers;
SELECT revenue / NULLIF(orders, 0) FROM ...;   -- orders 是 0 時得到 NULL 而不是報錯
```

★ **`NOT IN` 遇到 NULL 會失效**：子查詢只要有一個 NULL，`NOT IN` 就一筆都查不到。找「不存在」請用 `NOT EXISTS`。

```sql
-- 找「沒有子分類」的分類。categories.parent_id 有 NULL（最上層分類）→ 這句回傳 0 筆
SELECT * FROM categories WHERE id NOT IN (SELECT parent_id FROM categories);

-- 正確
SELECT * FROM categories c
WHERE NOT EXISTS (SELECT 1 FROM categories ch WHERE ch.parent_id = c.id);
```

---

## 4. 子查詢

```sql
-- 純量子查詢：回傳單一值
SELECT name, price FROM products
WHERE price > (SELECT avg(price) FROM products);

-- IN：比對一串值
SELECT * FROM customers
WHERE id IN (SELECT customer_id FROM orders WHERE status = 'returned');

-- EXISTS：只問「有沒有」，找到一筆就停
SELECT * FROM customers c
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.status = 'cancelled');

-- 衍生表：FROM 裡面放子查詢，一定要取別名
SELECT avg(cnt) FROM (
  SELECT customer_id, count(*) AS cnt FROM orders GROUP BY customer_id
) t;

-- 相關子查詢：子查詢引用外層欄位，每一列都會重算一次
SELECT p.name, p.price
FROM products p
WHERE p.price > (SELECT avg(price) FROM products WHERE category_id = p.category_id);
```

---

## 5. CASE WHEN 與條件彙總

```sql
SELECT name, price,
       CASE WHEN price >= 10000 THEN '高價'
            WHEN price >= 1000  THEN '中價'
            ELSE '平價' END AS level
FROM products;

-- 一次算多種條件（列轉欄 / pivot）
SELECT shipping_city,
       count(*) FILTER (WHERE status = 'delivered')              AS delivered,   -- PostgreSQL 寫法
       sum(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END)   AS cancelled    -- 通用寫法
FROM orders
GROUP BY shipping_city;
```

---

## 6. 集合運算

| 寫法 | 說明 |
|---|---|
| `UNION` | 合併並**去除重複**（要排序去重，比較慢） |
| `UNION ALL` | 合併，保留重複（★ 不需要去重時用這個） |
| `INTERSECT` | 交集 |
| `EXCEPT` | 差集（A 有 B 沒有） |

兩邊的欄位數量和型別要對得上。

```sql
SELECT customer_id FROM orders
EXCEPT
SELECT customer_id FROM orders WHERE status = 'returned';   -- 下過單、但從沒退過貨的會員
```

---

## 7. CTE（WITH）

把查詢拆成有名字的步驟，比巢狀子查詢好讀。

```sql
WITH spend AS (
  SELECT o.customer_id, sum(oi.quantity * oi.unit_price) AS total
  FROM orders o JOIN order_items oi ON oi.order_id = o.id
  GROUP BY o.customer_id
)
SELECT c.name, s.total
FROM spend s JOIN customers c ON c.id = s.customer_id
ORDER BY s.total DESC
LIMIT 10;
```

**遞迴 CTE**：處理樹狀結構（組織圖、分類樹）

```sql
WITH RECURSIVE tree AS (
  SELECT id, name, 1 AS depth FROM categories WHERE parent_id IS NULL   -- 起點
  UNION ALL
  SELECT c.id, c.name, t.depth + 1                                      -- 每次往下一層
  FROM categories c JOIN tree t ON c.parent_id = t.id
)
SELECT * FROM tree;
```

---

## 8. ★ 視窗函數（Window Function）

跟 GROUP BY 最大的差別：**不會把資料列壓成一列**，每一列都保留，同時能看到整組的統計。

```
函數() OVER (PARTITION BY 分組欄位 ORDER BY 排序欄位 [視窗範圍])
```

| 函數 | 用途 |
|---|---|
| `row_number()` | 1, 2, 3, 4（同值也給不同號） |
| `rank()` | 1, 2, 2, 4（同值同名次，**會跳號**） |
| `dense_rank()` | 1, 2, 2, 3（同值同名次，不跳號） |
| `ntile(n)` | 平均切成 n 組 |
| `lag(col, n)` / `lead(col, n)` | 往前 / 往後第 n 列的值 |
| `first_value()` / `last_value()` | 視窗內第一個 / 最後一個值 |
| `sum() / avg() / count() OVER` | 分組統計，但保留每一列 |

```sql
-- ★ 分組取前 N 名：每個分類價格前 3 高的商品
SELECT * FROM (
  SELECT name, category_id, price,
         dense_rank() OVER (PARTITION BY category_id ORDER BY price DESC) AS rk
  FROM products
) t
WHERE rk <= 3;

-- 累計加總
SELECT order_date, amount,
       sum(amount) OVER (ORDER BY order_date) AS running_total
FROM ...;

-- 7 日移動平均
SELECT day, revenue,
       avg(revenue) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS ma7
FROM ...;

-- 跟上一筆比較
SELECT month, revenue, revenue - lag(revenue) OVER (ORDER BY month) AS diff
FROM ...;

-- 佔比：每件商品佔該分類營收的百分比
SELECT name, category_id, price,
       round(100.0 * price / sum(price) OVER (PARTITION BY category_id), 2) AS pct
FROM products;
```

★ 視窗函數**不能直接寫在 WHERE**（執行順序在 WHERE 之後），要包一層子查詢或 CTE。

---

## 9. 常用函數（PostgreSQL）

**字串**

```sql
'a' || 'b'                     -- 串接（NULL || 'b' 會得到 NULL）
concat('a', NULL, 'b')         -- 串接，忽略 NULL
length(s)   lower(s)   upper(s)   trim(s)
substring(s, 1, 3)   left(s, 3)   right(s, 3)
replace(s, '舊', '新')
split_part('a@b.com', '@', 2)  -- 'b.com'
s LIKE '王%'                    -- % 任意長度，_ 一個字
s ILIKE '%apple%'              -- 不分大小寫（PostgreSQL 專屬）
string_agg(name, ', ')         -- 聚合成一個字串
```

**日期時間**

```sql
now()   current_date
date_trunc('month', order_date)            -- 截到月初，做月報表必備
extract(year FROM order_date)              -- 取出年 / month / dow（星期幾）/ hour
order_date::date                           -- timestamp 轉 date
current_date - interval '30 days'
age(current_date, birth_date)              -- 算年齡
to_char(order_date, 'YYYY-MM')             -- 格式化
order_date >= '2026-01-01' AND order_date < '2026-02-01'   -- ★ 範圍查詢，比 extract 更能用到索引
```

**數字**

```sql
round(x, 2)   ceil(x)   floor(x)   abs(x)   x % 3
```

★ **整數除法陷阱**：`5 / 2 = 2`。要小數請寫 `5.0 / 2` 或 `5::numeric / 2`。

**型別轉換**：`'123'::int`、`CAST('123' AS int)`、`price::text`

---

## 10. 新增、修改、刪除（DML）

```sql
INSERT INTO customers (name, email, signup_date)
VALUES ('測試帳號', 'test@example.com', current_date)
RETURNING id;                                   -- RETURNING 拿回新 id

-- ★ UPSERT：存在就更新，不存在就新增（依主鍵或 UNIQUE 欄位判斷）
INSERT INTO categories (id, name, parent_id)
VALUES (28, '寵物用品', NULL)
ON CONFLICT (id)
DO UPDATE SET name = EXCLUDED.name;             -- EXCLUDED = 這次想插入的那一列

UPDATE products SET price = price * 0.9 WHERE category_id = 19;

-- 用其他表的資料來更新
UPDATE products p SET is_active = false
FROM categories c
WHERE c.id = p.category_id AND c.name = '生鮮';

DELETE FROM orders WHERE status = 'cancelled' RETURNING id;   -- order_items 有 ON DELETE CASCADE，明細會一起刪
```

★ **DELETE vs TRUNCATE vs DROP**

| | DELETE | TRUNCATE | DROP |
|---|---|---|---|
| 刪什麼 | 符合條件的列 | 全部的列 | 整張表（含結構） |
| 可加 WHERE | ✅ | ❌ | ❌ |
| 速度 | 慢（逐列刪除） | 快 | 快 |
| 觸發 trigger | ✅ | ❌ | ❌ |
| 可以 ROLLBACK | ✅ | ✅（PostgreSQL 可以） | ✅（PostgreSQL 可以） |

---

## 11. 建表與約束（DDL）

```sql
CREATE TABLE coupons (
  id         serial PRIMARY KEY,                        -- 自動遞增主鍵
  code       text NOT NULL UNIQUE,
  discount   numeric(3,2) CHECK (discount BETWEEN 0 AND 1),
  customer_id int REFERENCES customers(id) ON DELETE CASCADE,  -- 外鍵
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE coupons ADD COLUMN used boolean DEFAULT false;
ALTER TABLE coupons DROP COLUMN used;
DROP TABLE coupons;
```

| 約束 | 說明 |
|---|---|
| `PRIMARY KEY` | 唯一 + 不可 NULL，一張表只能有一個 |
| `UNIQUE` | 不可重複（可以有多個 NULL） |
| `NOT NULL` | 不可空值 |
| `CHECK` | 自訂條件 |
| `FOREIGN KEY` / `REFERENCES` | 必須對應到另一張表存在的值 |

**View**

```sql
CREATE VIEW v_order_total AS
SELECT order_id, sum(quantity * unit_price * (1 - discount)) AS total
FROM order_items GROUP BY order_id;            -- 只存查詢，每次查都重算

CREATE MATERIALIZED VIEW mv_order_total AS ...;  -- 把結果存起來，查詢快
REFRESH MATERIALIZED VIEW mv_order_total;        -- 資料要手動更新
```

---

## 12. ★ 索引與 EXPLAIN

```sql
CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_cust_date ON orders (customer_id, order_date);  -- 複合索引
CREATE UNIQUE INDEX ... ;
DROP INDEX idx_orders_customer;

EXPLAIN SELECT ...;          -- 預估的執行計畫
EXPLAIN ANALYZE SELECT ...;  -- 真的執行，顯示實際耗時
```

| 計畫中的字 | 意思 |
|---|---|
| `Seq Scan` | 整張表從頭掃到尾 |
| `Index Scan` | 透過索引找到位置，再回表取資料 |
| `Index Only Scan` | 索引就有全部需要的欄位，不用回表 |
| `Bitmap Heap Scan` | 先用索引收集位置，再批次讀表 |
| `Nested Loop` / `Hash Join` / `Merge Join` | 三種 JOIN 演算法 |

**索引用不到的常見情況**

- 對欄位做運算或函數：`WHERE extract(year FROM order_date) = 2026` ❌
- 開頭是萬用字元：`LIKE '%abc'` ❌（`LIKE 'abc%'` 可以）
- 複合索引 `(a, b)` 只查 `b`（最左前綴原則）
- 回傳的資料佔全表很大比例，資料庫判斷直接全掃比較快

**索引的代價**：佔空間，而且 INSERT / UPDATE / DELETE 都要同步更新索引，會變慢。

---

## 13. ★ 交易（Transaction）

```sql
BEGIN;
UPDATE products SET stock = stock - 1 WHERE id = 10;
INSERT INTO orders (...) VALUES (...);
COMMIT;      -- 確認；出錯就 ROLLBACK 全部取消
```

**ACID**

| | 意思 |
|---|---|
| Atomicity 原子性 | 全部成功或全部失敗 |
| Consistency 一致性 | 交易前後都符合約束 |
| Isolation 隔離性 | 同時執行的交易互不干擾 |
| Durability 持久性 | COMMIT 之後就算當機也不會遺失 |

**隔離等級與會發生的問題**（PostgreSQL 預設 Read Committed）

| 等級 | 髒讀 | 不可重複讀 | 幻讀 |
|---|---|---|---|
| Read Uncommitted | PG 不會發生 | 會 | 會 |
| Read Committed | ❌ | 會 | 會 |
| Repeatable Read | ❌ | ❌ | PG 不會發生 |
| Serializable | ❌ | ❌ | ❌ |

鎖住要修改的列：`SELECT ... FOR UPDATE`（例如扣庫存前先鎖，避免超賣）

---

## 14. ★ 面試經典題速答

| 題目 | 重點 |
|---|---|
| WHERE vs HAVING | WHERE 過濾分組前的列；HAVING 過濾分組後的結果，可用聚合函數 |
| UNION vs UNION ALL | UNION 會去重（較慢）；UNION ALL 不去重 |
| 第 N 高的價格（薪水） | `SELECT DISTINCT price FROM products ORDER BY price DESC LIMIT 1 OFFSET N-1`，或用 `dense_rank()` |
| 找重複資料 | `GROUP BY 欄位 HAVING count(*) > 1` |
| 刪除重複只留一筆 | 用 `row_number() OVER (PARTITION BY 重複欄位)`，刪掉 rn > 1 的 |
| 分組取前 N 名 | `row_number()` / `dense_rank()` + 外層 `WHERE rk <= N` |
| 連續登入 N 天 | 日期減去 `row_number()` 天數，相同的就是同一段連續 |
| IN vs EXISTS | 子查詢結果大用 EXISTS；`NOT IN` 遇 NULL 會失效 |
| 正規化 | 1NF 欄位不可再分、2NF 消除部分相依、3NF 消除遞移相依 |
| 為什麼不全部建索引 | 佔空間、拖慢寫入、資料庫不一定會用 |
| char vs varchar vs text | PostgreSQL 中三者效能幾乎一樣，通常直接用 text |
| serial vs identity | `GENERATED ALWAYS AS IDENTITY` 是 SQL 標準，新專案建議用 |

---

## 15. ★ 進階語法：JSONB、Array、UPSERT、進階索引

### JSONB

`products.specs` 是 JSONB，例如 `{"color": "黑", "storage_gb": 256, "warranty": {"years": 2}}`

| 寫法 | 回傳 | 說明 |
|---|---|---|
| `specs->'warranty'` | jsonb | 取出 JSON（還能繼續往下取） |
| `specs->>'color'` | text | 取出文字（最後一層用這個） |
| `specs#>>'{warranty,years}'` | text | 依路徑取值 |
| `specs @> '{"storage_gb": 256}'` | bool | ★ 包含，可以用 GIN 索引 |
| `specs ? 'battery_hours'` | bool | 有沒有這個鍵（`?|` 任一、`?&` 全部） |
| `specs \|\| '{"on_sale": true}'` | jsonb | 合併，相同的鍵會被覆蓋 |
| `specs - 'on_sale'` | jsonb | 刪除一個鍵 |
| `jsonb_set(specs, '{warranty,years}', '3')` | jsonb | 修改指定路徑的值 |
| `jsonb_array_elements_text(specs->'sizes')` | 多列 | 把 JSON 陣列展開 |
| `jsonb_build_object('a', 1)`、`jsonb_agg(…)` | jsonb | 組出 JSON |

★ 陷阱：
- `->>` 取出的是 **text**，比大小前要轉型：`(specs->>'storage_gb')::int > 64`。直接寫 `> '64'` 會變成逐字比較，`'128' > '64'` 是 false
- `NULL || '{…}'` 的結果是 NULL，要寫 `COALESCE(specs, '{}') || '{…}'`
- JDBC 的 `?` 是參數佔位符，寫 `specs ? 'key'` 要改成 `??` 或 `jsonb_exists(specs, 'key')`
- json 與 jsonb：jsonb 存成二進位、會去掉重複的鍵和空白、支援索引，**幾乎都用 jsonb**

### Array

`products.tags` 是 `text[]`，例如 `{熱銷,特價}`

| 寫法 | 說明 |
|---|---|
| `'特價' = ANY(tags)` | 其中任何一個等於 |
| `tags @> ARRAY['熱銷','限量']` | 全部都要有（可以用 GIN） |
| `tags && ARRAY['環保','獨家']` | 有任何一個相同（可以用 GIN） |
| `unnest(tags)` | 展開成多列 |
| `array_agg(name)` | 多列聚合成陣列 |
| `cardinality(tags)` | 元素個數（空陣列是 0） |
| `array_append(tags, '新品')`、`array_remove(tags, '特價')` | 加入 / 移除 |

★ `array_length(tags, 1)` 遇到空陣列會回傳 **NULL**，不是 0。

Java：`WHERE id = ANY(?)` 搭配 `ps.setArray(1, conn.createArrayOf("int", ids))`，取代自己組 `IN (…)` 字串。

### generate_series：產生連續的值

```sql
SELECT generate_series(1, 5);                                        -- 1~5
SELECT generate_series('2026-09-01'::date, '2026-09-30', '1 day');   -- 每天

-- ★ 報表補零：先產生完整時間軸，再 LEFT JOIN（條件放 ON）
SELECT d::date, count(o.id)
FROM generate_series('2026-09-01'::date, '2026-09-30', '1 day') d
LEFT JOIN orders o ON o.order_date >= d AND o.order_date < d + interval '1 day'
GROUP BY d ORDER BY d;
```

也常用來產生大量測試資料（這個練習庫就是這樣做的）。

### RETURNING 與 UPSERT

```sql
INSERT INTO customers (name, email, signup_date)
VALUES ('王小明', 'a@b.com', current_date)
RETURNING id;                                         -- 直接拿回新的 id

UPDATE products SET price = price * 1.1 WHERE id = 1 RETURNING id, price;   -- 更新後的值
DELETE FROM orders WHERE status = 'pending' RETURNING id;

-- ★ UPSERT：原子操作，沒有「先查再寫」的競態問題
INSERT INTO categories (id, name) VALUES (2, '智慧型手機')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;  -- EXCLUDED = 想插入的那一列

INSERT … ON CONFLICT (email) DO NOTHING;              -- 重複就跳過（冪等）

-- 搬資料：刪除與歸檔在同一句、同一個交易
WITH moved AS (DELETE FROM orders WHERE … RETURNING *)
INSERT INTO orders_archive SELECT * FROM moved;
```

★ 序列不受交易控制：ROLLBACK 之後用掉的 id 不會還回去，所以 id 會跳號。

### 進階索引

| 索引 | 寫法 | 適用情境 |
|---|---|---|
| B-tree（預設） | `CREATE INDEX … (col)` | =、<、>、BETWEEN、ORDER BY、前綴 LIKE 'abc%' |
| 複合索引 | `(customer_id, order_date)` | 等值條件放前面、排序欄位放後面（最左前綴） |
| ★ Partial Index | `(order_date) WHERE status = 'pending'` | 只查一小部分資料；索引小很多 |
| ★ Expression Index | `(lower(email))` | 查詢條件對欄位做了運算；函數必須 IMMUTABLE |
| 覆蓋索引 | `(a, b) INCLUDE (c)` | 讓查詢變成 Index Only Scan |
| GIN | `USING gin (specs)` | JSONB 的 @>、?；陣列的 @>、&&；全文檢索 |
| BRIN | `USING brin (event_time)` | 依時間附加寫入的超大表（log、IoT）；只有幾十 KB |
| Hash | `USING hash (col)` | 只有 = 查詢，實務上很少用 |

BRIN 的前提：資料在磁碟上的順序要和欄位值一致（相關性高）。隨機寫入的欄位建 BRIN 完全沒用。

### ★ Partial Index（部分索引）

只替「符合條件的資料列」建索引。適合「大部分資料永遠不會用這個條件查」的情況。

```sql
-- 客服只查待處理的訂單：pending 只佔 3%
CREATE INDEX idx_orders_pending ON orders (order_date) WHERE status = 'pending';

SELECT id, customer_id FROM orders
WHERE status = 'pending'            -- 條件要「蘊含」索引的 WHERE，才用得到
ORDER BY order_date DESC LIMIT 20;  -- 索引依 order_date 排序：Index Scan Backward，不用排序
```

實測（索引實驗室的 perf.orders_big，200 萬筆，pending 約 6 萬筆）：

| 索引 | 大小 | 「最新 20 筆 pending」 |
|---|---:|---|
| `(status)` 完整索引 | 13 MB | 找出 6 萬筆再排序 |
| `(order_date) WHERE status = 'pending'` | 1.3 MB | Index Scan Backward，0.1 ms |

| 常見用途 | 寫法 |
|---|---|
| 軟刪除：只索引還沒刪的資料 | `CREATE INDEX … (email) WHERE deleted_at IS NULL` |
| 工作佇列：只索引未處理的工作 | `CREATE INDEX … (created_at) WHERE done = false` |
| ★ 條件式唯一：例如每位會員只能有一個預設地址 | `CREATE UNIQUE INDEX … (customer_id) WHERE is_default` |
| 排除大量的 NULL | `CREATE INDEX … (birth_date) WHERE birth_date IS NOT NULL` |

- 好處：索引小（更容易整個放進記憶體）、寫入時不符合條件的資料列不用維護索引
- ★ 查詢的 WHERE 必須讓優化器「證明」符合索引條件：`status = 'pending'` 可以；`status IN ('pending', 'paid')`、`status = $1`（參數）都不行
- ★ Java / JDBC 的坑：用 PreparedStatement 帶參數 `WHERE status = ?` 時，PostgreSQL 執行幾次之後可能改用「通用計畫」（generic plan），通用計畫不知道參數值，就**用不到**部分索引（實測：改走完整的 status 索引 + 排序）。固定的條件直接寫在 SQL 裡（`WHERE status = 'pending'`），不要當參數
- 條件式唯一：表格上的 `UNIQUE` 約束不能加 WHERE，要用 `CREATE UNIQUE INDEX … WHERE …`（或 EXCLUDE 約束）
- 面試說法：「只有一小部分資料會被這樣查，而且查詢條件是固定的，就用部分索引；索引小、寫入成本低。」

> 以下實測都在索引實驗室的 perf 大表（各 200 萬筆）上，在交易裡建索引、量完就 ROLLBACK。

### ★ Expression Index（運算式索引）

索引存的是「運算之後的值」。查詢條件對欄位做了運算（函式、型別轉換、時區換算）時，一般索引用不到，就建運算式索引。

```sql
-- 依「台灣時間的日期」查訂單：order_date 是 timestamptz
CREATE INDEX idx_orders_tw_day ON orders (((order_date AT TIME ZONE 'Asia/Taipei')::date));
SELECT count(*) FROM orders
WHERE (order_date AT TIME ZONE 'Asia/Taipei')::date = '2025-06-01';   -- 運算式要「一模一樣」

CREATE INDEX idx_customers_email_lower ON customers (lower(email));     -- 不分大小寫的 email
SELECT * FROM customers WHERE lower(email) = lower('User04242@Example.com');
```

| | 沒有索引 | 運算式索引 |
|---|---|---|
| 計畫 | Seq Scan，濾掉 199.8 萬筆 | Bitmap Index Scan |
| 時間 | 488 ms | 5 ms |

- ★ 查詢的運算式要跟索引**完全一樣**：建的是 `(order_date AT TIME ZONE 'Asia/Taipei')::date`，查詢寫成 `order_date::date` 就用不到（實測：Seq Scan）
- ★ 只能用 **IMMUTABLE** 函式（輸入一樣、結果永遠一樣）：`now()` 不行；`timestamptz::date` 依賴 session 的時區設定也不行，所以要明確寫 `AT TIME ZONE`
- 也常用在 JSONB 的單一鍵：`CREATE INDEX … ((meta->>'coupon'))`，搭配 `WHERE meta->>'coupon' = 'FALL10'`
- 代價：每次寫入都要算一次運算式；索引大小跟一般 B-tree 差不多（這裡 14 MB）
- 同義的寫法：與其對欄位做運算，不如把運算移到「值」那一邊，例如 `WHERE order_date >= '2025-06-01 00:00+08' AND order_date < '2025-06-02 00:00+08'`，一般的 order_date 索引就能用

### ★ GIN（Generalized Inverted Index，倒排索引）

把一個值「拆開」來索引：JSONB 的每個鍵值、陣列的每個元素、文章的每個詞，記錄它們出現在哪些資料列。適合「一欄裡有很多值，要查包含某個值的列」。

```sql
CREATE INDEX idx_events_meta ON order_events USING gin (meta);                   -- 支援 @> ? ?| ?&
CREATE INDEX idx_events_meta_path ON order_events USING gin (meta jsonb_path_ops); -- 只支援 @>，但小一半
SELECT count(*) FROM order_events WHERE meta @> '{"coupon": "VIP2026"}';           -- ★ 要寫 @>

CREATE INDEX idx_products_tags ON products USING gin (tags);                     -- text[]：@> && <@
SELECT * FROM products WHERE tags @> ARRAY['熱銷'];

CREATE EXTENSION pg_trgm;                                                        -- 三字元組：LIKE '%…%'
CREATE INDEX idx_customers_email_trgm ON customers USING gin (email gin_trgm_ops);
SELECT * FROM customers WHERE email LIKE '%04242%';

CREATE INDEX idx_docs_fts ON docs USING gin (to_tsvector('simple', body));       -- 全文檢索
```

| JSONB `meta @> '{"coupon": "VIP2026"}'` | 時間 | 索引大小 |
|---|---|---|
| 沒有索引（Seq Scan） | 524 ms | — |
| `gin (meta)` | — | 8.7 MB |
| `gin (meta jsonb_path_ops)`（優化器選了這個） | 6.8 ms | 4.5 MB |

- ★ `meta->>'coupon' = 'VIP2026'` **用不到** GIN（`->>` 加 `=` 不是 GIN 支援的運算子），要寫成 `meta @> '{"coupon": "VIP2026"}'`，或另外建運算式 B-tree
- `jsonb_ops`（預設）：支援 `@>`、`?`（有沒有這個鍵）、`?|`、`?&`；`jsonb_path_ops`：只支援 `@>`，但更小、更快
- pg_trgm + GIN 讓 `LIKE '%中間%'`、`ILIKE` 也能用索引（實測 2.7 ms → 0.04 ms，2 萬筆）；B-tree 只能處理 `LIKE 'abc%'`（前綴）
- 代價：寫入比較慢（一筆資料要更新很多個索引項目），PostgreSQL 用 pending list 延後合併（`fastupdate`）來緩和
- 跟 Elasticsearch 的核心一樣是倒排索引；Elasticsearch 另外有分詞、相關性分數、分散式

### GiST（Generalized Search Tree）

一種「可以自訂比較方式」的平衡樹，用來索引**會重疊、有遠近**的資料：範圍（tstzrange）、幾何（point、polygon、PostGIS）、全文檢索。B-tree 只懂「大小順序」，GiST 懂「重疊 `&&`」「包含 `@>`」「距離 `<->`」。

```sql
-- ★ 排除約束：同一間房間的預約時段不能重疊（UNIQUE 做不到「重疊」的檢查）
CREATE EXTENSION btree_gist;                          -- 讓 GiST 也能處理 room 的 =
CREATE TABLE bookings (
  room   int,
  during tstzrange,
  EXCLUDE USING gist (room WITH =, during WITH &&)
);
INSERT INTO bookings VALUES (101, '[2026-10-07 14:00, 2026-10-07 16:00)');
INSERT INTO bookings VALUES (101, '[2026-10-07 15:00, 2026-10-07 17:00)');  -- ✗ conflicting key value violates exclusion constraint
INSERT INTO bookings VALUES (101, '[2026-10-07 16:00, 2026-10-07 18:00)');  -- ✓ [) 半開區間，16:00 剛好接上

-- 最近鄰（KNN）：離台北車站最近的 5 家店
CREATE INDEX idx_stores_loc ON stores USING gist (loc);
SELECT id FROM stores ORDER BY loc <-> point(121.5, 25.03) LIMIT 5;
```

| 最近的 5 家店（20 萬家） | 計畫 | 時間 |
|---|---|---|
| 沒有索引 | Seq Scan 20 萬筆 + top-N 排序 | 27 ms |
| GiST | Index Scan，依距離直接讀出前 5 筆 | 0.15 ms |

- ★ 排除約束（EXCLUDE）是 GiST 最常被問的用途：會議室 / 飯店訂房不重疊、同一位員工的排班不重疊、價格區間不重疊
- KNN 搜尋：`ORDER BY 欄位 <-> 目標 LIMIT n`，索引可以直接「由近到遠」讀出，不用算完全部距離再排序
- 全文檢索也可以用 GiST：比 GIN 小、更新快，但查詢比較慢（會有誤判要重新檢查）；以查詢為主時選 GIN
- PostGIS 的空間索引就是 GiST；pgvector 的向量索引則是 HNSW / IVFFlat（另外的索引類型）

### ★ BRIN（Block Range Index）

不記錄每一筆資料，只記錄「每一段磁碟區塊（預設 128 個 page）裡的最小值和最大值」。查詢時跳過範圍不符的區塊，再讀剩下的區塊逐筆檢查。

```sql
CREATE INDEX idx_events_time_brin ON order_events USING brin (event_time);
SELECT count(*) FROM order_events
WHERE event_time >= '2025-06-01' AND event_time < '2025-06-02';
```

| order_events.event_time（依時間寫入） | 時間 | 索引大小 |
|---|---|---|
| 沒有索引（Seq Scan） | 108 ms | — |
| BRIN | 1.5 ms（Heap Blocks: lossy=128，多讀了 8,527 筆再過濾） | **24 kB** |
| B-tree | 0.12 ms（Index Only Scan：count 不必讀資料表） | 43 MB |

- BRIN 比 B-tree 慢一點（要讀整段區塊再過濾），但索引小了 1,800 倍；資料量越大、記憶體越吃緊，這個取捨越划算
- ★ 前提：資料在磁碟上的順序要跟欄位值一致（相關性高）。依時間附加寫入的 log、IoT、交易紀錄最適合；隨機寫入的欄位建 BRIN 完全沒用（實驗室的 orders_big：優化器直接放棄用它）
- 大量 UPDATE / DELETE 之後順序會亂掉，效果變差
- 「lossy」：BRIN 只能說「這一段區塊可能有」，所以一定要重新檢查（Rows Removed by Index Recheck）
- 適合：幾億筆的時間序列、只追加的資料；以極小的索引換取「大致定位」。TimescaleDB 也大量利用這個特性

### 怎麼選索引

| 查詢長這樣 | 用 |
|---|---|
| `=`、`<`、`>`、`BETWEEN`、`ORDER BY`、`LIKE 'abc%'` | B-tree（預設） |
| 只查一小部分資料、條件固定（`WHERE status = 'pending'`） | Partial Index |
| 條件對欄位做了運算（`lower(email)`、時區換算） | Expression Index |
| JSONB `@>` / `?`、陣列 `@>` `&&`、全文檢索、`LIKE '%…%'`（pg_trgm） | GIN |
| 範圍重疊、排除約束、幾何、最近鄰（`<->`） | GiST |
| 超大、依時間附加寫入的表，查時間範圍 | BRIN |
| 只有 `=`，而且值很長 | Hash（實務上很少用） |
| 向量相似度（AI 語意搜尋） | pgvector 的 HNSW / IVFFlat |

## 16. psql 常用指令

| 指令 | 作用 |
|---|---|
| `\l` | 列出資料庫 |
| `\dt` | 列出資料表 |
| `\d 表名` | 看表結構、索引、外鍵 |
| `\di` | 列出索引 |
| `\x` | 切換直式顯示（欄位很多時好用） |
| `\timing` | 顯示每句 SQL 耗時 |
| `\e` | 用編輯器寫長 SQL |
| `\q` | 離開 |

---

# Redis

> 範例的 key 都來自練習環境（`redis-lab` 容器，port 6380），可以直接貼到展示台的「指令主控台」執行。資料是從 PostgreSQL 轉進來的，練壞了按「重置資料」。

## 1. ★ 基本觀念：Redis 為什麼這麼快

| 觀念 | 說明 |
|---|---|
| 資料放在記憶體 | 讀寫是微秒等級；硬碟只用來做持久化備份 |
| 單執行緒執行指令 | 一次只執行一個指令，不用加鎖、沒有切換成本；**每個指令天生是原子的** |
| I/O 多工 | 用 epoll 同時處理上萬條連線；Redis 6 起網路讀寫可以開多執行緒（`io-threads`），執行指令仍是單執行緒 |
| 高效的資料結構 | 依資料大小自動切換底層編碼（listpack、skiplist、intset…） |

★ 單執行緒的代價：**一個慢指令會卡住所有人**。`KEYS *`、對大 key 做 `HGETALL` / `SMEMBERS` / `DEL`、跑很久的 Lua 腳本，都會讓整台 Redis 停住。

Key 命名慣例：用冒號分層 `物件類型:id:欄位`，例如 `product:540`、`customer:1:recent_orders`、`cache:category-report:2`。

## 2. 通用指令（所有型別都能用）

| 指令 | 說明 |
|---|---|
| `EXISTS k`、`TYPE k` | 存在嗎、什麼型別 |
| `DEL k`、`UNLINK k` | 刪除；UNLINK 在背景釋放記憶體，刪大 key 不會卡住 |
| `EXPIRE k 60`、`PEXPIRE k 500` | 設定過期（秒 / 毫秒） |
| `TTL k`、`PTTL k` | 剩幾秒；**-1 = 沒有過期時間，-2 = key 不存在** |
| `PERSIST k` | 移除過期時間 |
| `RENAME k k2`、`COPY k k2` | 改名、複製 |
| `SCAN 0 MATCH product:* COUNT 100` | ★ 分批找 key（要拿回傳的游標一直掃到 0） |
| `OBJECT ENCODING k`、`MEMORY USAGE k` | 底層編碼、佔多少記憶體 |
| `DBSIZE`、`INFO memory`、`SLOWLOG GET 10` | key 數量、記憶體、慢指令紀錄 |

★ 正式環境禁用 `KEYS *`：它會一次掃完所有 key，期間其他請求全部排隊。用 `SCAN`。

## 3. String

```redis
SET k v                       -- 覆蓋整個值，也會清掉原本的過期時間
SET k v EX 1800               -- 寫入同時設定 30 分鐘過期
SET k v NX                    -- 不存在才寫入（分散式鎖）
SET k v XX                    -- 存在才寫入
SET k v KEEPTTL               -- 保留原本的過期時間（6.0+）
GET k        MGET k1 k2       MSET k1 v1 k2 v2
INCR k       INCRBY k 10      DECR k      INCRBYFLOAT k 1.5     -- 原子計數
GETDEL k     GETEX k EX 60    APPEND k v  STRLEN k
```

用途：快取（JSON 字串）、計數器、Session、分散式鎖、限流。單一值最大 512 MB，但超過 10 KB 就算「大 key」了。

## 4. Hash

```redis
HSET product:540 name 手機 price 29949 stock 11   -- 回傳新增的欄位數
HGET product:540 price
HMGET customer:1 name city                       -- 欄位不存在的位置回傳 nil
HGETALL product:540                               -- 大 Hash 不要用，改用 HSCAN
HINCRBY product:540 stock -2                      -- 原子增減
HDEL k f    HEXISTS k f    HLEN k    HKEYS k    HVALS k
HEXPIRE k 60 FIELDS 1 f                           -- 單一欄位過期（7.4+）
```

★ 物件快取用 Hash 還是 JSON String？Hash 可以只讀、只改部分欄位；JSON String 適合整包讀寫、結構有巢狀的資料。

## 5. List

```redis
LPUSH k a b c     RPUSH k x          -- 從左 / 右放入
LPOP k            RPOP k 2           -- 從左 / 右取出
LRANGE k 0 9                          -- ★ 結尾包含在內：0 9 是 10 個；0 -1 是全部
LTRIM k 0 9                           -- 只保留前 10 個
LINDEX k 0     LLEN k     LREM k 0 v
BLPOP k 5                             -- 沒有資料就等最多 5 秒（簡易佇列）
LMOVE src dst LEFT RIGHT              -- 原子搬移（可靠佇列）
```

用途：最新 N 筆（`LPUSH` + `LTRIM`）、簡易訊息佇列。需要確認機制、多個消費者分工時改用 Stream。

## 6. Set

```redis
SADD tag:特價 540 541       SREM k m      SCARD k       SISMEMBER k m
SMEMBERS k                                  -- 大 Set 不要用，改用 SSCAN
SINTER a b     SUNION a b     SDIFF a b     -- 交集、聯集、差集（A 有 B 沒有）
SINTERCARD 2 a b                            -- 只要交集的數量（7.0+）
SINTERSTORE dst a b                         -- 結果存起來，回傳個數
SRANDMEMBER k 3     SPOP k                  -- 隨機取（抽獎）
```

用途：標籤、共同好友（交集）、是否按過讚、黑名單、抽獎。元素沒有順序、不重複。

## 7. ★ Sorted Set（排行榜）

```redis
ZADD board 100 alice 90 bob             -- 分數 成員
ZINCRBY board 10 alice                  -- 原子加分，回傳新分數
ZSCORE board alice
ZRANGE board 0 9 REV WITHSCORES         -- 前 10 名（舊寫法 ZREVRANGE board 0 9 WITHSCORES）
ZREVRANK board alice                    -- ★ 名次從 0 開始
ZRANK board alice                       -- 由低到高的名次
ZCOUNT board 50 100                     -- 分數範圍內有幾個；(50 表示不含 50
ZRANGE board 50 100 BYSCORE             -- 依分數範圍取
ZREMRANGEBYSCORE board -inf 10          -- 刪掉分數範圍
ZUNIONSTORE week 7 day1 day2 ...        -- 合併多個榜（日榜 → 週榜）
```

★ 同分時依成員名稱的字典順序排；加 REV 時整個反過來。想要「同分先達到的排前面」，把時間編進分數。

底層：小的時候是 listpack，大了變成**跳表（skiplist）+ 雜湊表**，所以依分數範圍查詢是 O(log N)，用成員查分數是 O(1)。

用途：排行榜、延遲佇列（分數 = 執行時間）、滑動視窗限流（分數 = 請求時間）、Geo。

## 8. 特殊型別：Stream、HyperLogLog、Bitmap、Geo

**Stream**（5.0+）：只能附加的日誌，像輕量版 Kafka

```redis
XADD orders:stream * order_id 80000 status paid     -- * = 自動產生 ID（毫秒-序號）
XLEN orders:stream
XRANGE orders:stream - + COUNT 10                   -- 最舊到最新
XREVRANGE orders:stream + - COUNT 3                 -- 最新 3 筆
XGROUP CREATE orders:stream g1 $                    -- 建立消費者群組
XREADGROUP GROUP g1 worker-1 COUNT 10 STREAMS orders:stream >
XACK orders:stream g1 <ID>                          -- 處理完要確認，沒確認的會留在 Pending
```

**HyperLogLog**：估算不重複數量，固定最多 12 KB，誤差約 0.81%

```redis
PFADD uv:2026-09-01 user1 user2
PFCOUNT uv:2026-09-01                 -- 估計值
PFCOUNT uv:day1 uv:day2               -- 多天合併後的不重複數
PFMERGE uv:2026-09 uv:day1 uv:day2    -- 合併存成新的 key
```

**Bitmap**：一個 bit 一個人，精確，大小跟最大 id 成正比

```redis
SETBIT active:2026-09-01 500 1        -- 會員 500 這天有活動
GETBIT active:2026-09-01 500
BITCOUNT active:2026-09-01            -- 幾個人
BITOP AND both active:day1 active:day2   -- 兩天都有（OR = 任一天）
```

**Geo**：底層是 Sorted Set

```redis
GEOADD store:locations 121.5654 25.0330 台北市     -- 經度 緯度 成員
GEODIST store:locations 台北市 高雄市 km
GEOSEARCH store:locations FROMMEMBER 台北市 BYRADIUS 60 km ASC WITHDIST
GEOSEARCH store:locations FROMLONLAT 121.5 25.0 BYBOX 20 20 km
```

★ 統計 UV 怎麼選：量小用 Set（可以列出名單）、id 是連續整數用 Bitmap（精確又省）、量很大又允許誤差用 HyperLogLog。

## 9. ★ 交易與 Lua 腳本

```redis
MULTI                        -- 開始；之後的指令回傳 QUEUED
INCR stats:orders:total
LPUSH customer:1:recent_orders 90001
EXEC                         -- 一起執行；DISCARD 放棄

WATCH stock                  -- 樂觀鎖：EXEC 前 stock 被別人改過，整個交易就不執行（回傳 nil）
```

★ Redis 交易**沒有 ROLLBACK**：
- 排隊時就發現語法錯誤 → `EXEC` 回傳 EXECABORT，全部不執行
- 執行時才出錯（例如型別錯誤）→ **只有那一句失敗，其他照樣生效**

需要「判斷之後再修改」的原子操作，用 Lua 腳本：整個腳本執行期間不會插入其他指令。

```redis
EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0" 1 lock:order:1 my-token
```

注意：腳本要短，跑太久會卡住整台 Redis（預設 5 秒後才能用 SCRIPT KILL）。Redis 7 起建議用 `FUNCTION` 取代 EVAL。

## 10. Pipeline

每個指令都要等一次網路來回（RTT）。Pipeline 把很多指令一次送出、最後一次收回結果。

| 寫入 1000 個 key（練習環境實測） | 耗時 |
|---|---:|
| 一個一個 SET | 約 200 ~ 500 ms |
| Pipeline | 約 2 ms |
| MSET | 約 1 ~ 2 ms |

Pipeline **不是交易**：中間可能插入其他客戶端的指令。一次不要塞幾十萬個，要分批。

## 11. ★ 實戰模式

**Cache-Aside（旁路快取）**

```redis
讀：GET cache:x → 有就回傳；沒有 → 查資料庫 → SET cache:x <值> EX 60 → 回傳
寫：先更新資料庫 → 再 DEL cache:x（刪除，不是更新快取）
```

| 問題 | 情境 | 解法 |
|---|---|---|
| ★ 快取穿透 | 查「根本不存在」的資料，每次都打到資料庫 | 快取空結果（短 TTL）、布隆過濾器 |
| ★ 快取擊穿 | 一個熱門 key 過期的瞬間，大量請求同時查資料庫 | 互斥鎖只讓一個請求回填、熱門資料不過期 + 背景更新 |
| ★ 快取雪崩 | 大量 key 同時過期，或 Redis 整台掛掉 | 過期時間加隨機值、多層快取、限流降級、高可用架構 |

快取與資料庫的一致性：「先更新資料庫再刪快取」最常用；要求更高時用延遲雙刪，或監聽資料庫變更（Canal / Debezium）再刪快取。

**分散式鎖**

```redis
加鎖：SET lock:order:1 <uuid> NX PX 30000      -- 一個指令完成「不存在才寫」+「過期時間」
解鎖：Lua 比對 value 是自己的 uuid 才 DEL       -- 避免刪到別人的鎖
```

- ★ 不要用 `SETNX` + `EXPIRE` 兩個指令：中間當掉會留下永不過期的鎖；SETNX 失敗時接著的 EXPIRE 還會改到別人的鎖
- 工作時間可能超過鎖的過期時間 → Redisson 的看門狗會自動續期
- Java 實務直接用 Redisson 的 `RLock`

**限流**

| 演算法 | 做法 |
|---|---|
| 固定視窗 | `INCR` 計數，第一次時 `EXPIRE`（包在 Lua 裡）；缺點是視窗交界處可能兩倍流量 |
| 滑動視窗 | Sorted Set 記每次請求時間，`ZREMRANGEBYSCORE` 刪掉視窗外的，`ZCARD` 計數 |
| 令牌桶 | Lua 計算補充的令牌數；Spring Cloud Gateway 的 RequestRateLimiter 就是這個 |

**庫存扣減（防超賣）**：`GET` → 判斷 → `SET` 會超賣；用 `DECR`（小於 0 再加回）或 Lua「判斷後扣減」。

**其他常見用法**：計數器（`INCR`）、Session 共享（`SET … EX`）、冪等性（`SET request:<id> 1 NX EX 86400`，重複請求會失敗）、排行榜（Sorted Set）、最新動態（`LPUSH` + `LTRIM`）、延遲佇列（Sorted Set，分數 = 執行時間）。

## 12. ★ 持久化

| | RDB（快照） | AOF（指令日誌） |
|---|---|---|
| 做法 | 定期把整個資料庫存成二進位檔（`BGSAVE`，fork 子行程） | 把每個寫入指令附加到檔案 |
| 遺失資料 | 上次快照之後的全部 | 依 `appendfsync`：`always` 不遺失、`everysec` 最多 1 秒、`no` 由作業系統決定 |
| 檔案大小 / 重啟速度 | 小、快 | 大、慢（會定期 `BGREWRITEAOF` 壓縮） |

Redis 4.0 起可以混合使用（AOF 檔開頭是 RDB 快照），兼顧重啟速度和資料安全。練習環境兩個都關了，因為資料都能從 PostgreSQL 重建。

## 13. ★ 過期刪除與記憶體淘汰

**過期的 key 怎麼刪**：惰性刪除（被存取時才檢查）＋ 定期刪除（每秒抽樣檢查一部分）。所以過期的 key 不一定馬上釋放記憶體。

**記憶體滿了怎麼辦**（`maxmemory-policy`）：

| 策略 | 說明 |
|---|---|
| `noeviction` | 預設，拒絕寫入（練習環境用這個） |
| `allkeys-lru` | ★ 所有 key 裡淘汰最久沒用的，純快取最常用 |
| `volatile-lru` | 只淘汰有設過期時間的 key |
| `allkeys-lfu` / `volatile-lfu` | 淘汰使用頻率最低的（4.0+） |
| `allkeys-random` / `volatile-random` | 隨機 |
| `volatile-ttl` | 淘汰最快要過期的 |

LRU 是近似演算法：每次隨機抽幾個 key（`maxmemory-samples`）挑最舊的，不是精確的 LRU。

## 14. ★ 高可用：主從、哨兵、叢集

| 架構 | 說明 |
|---|---|
| 主從複製 | 主節點寫、從節點讀；非同步複製，主節點掛掉可能遺失最後一點資料 |
| Sentinel（哨兵） | 監控主節點，掛掉時自動把從節點升級成主節點（自動故障轉移） |
| Cluster（叢集） | 資料分散到多個主節點：**16384 個 slot**，`CRC16(key) % 16384` 決定放哪個節點 |

★ Cluster 的限制：一個指令用到的多個 key 必須在同一個 slot，否則報錯（CROSSSLOT）。用 hash tag 讓它們落在同一個 slot：`{order:1}:items`、`{order:1}:status` 只會用 `{}` 裡的內容計算。

## 15. 底層編碼（面試加分題）

| 型別 | 資料少的時候 | 資料多的時候 |
|---|---|---|
| String | int（整數）、embstr（≤ 44 bytes） | raw |
| List | listpack | quicklist（多個 listpack 串起來） |
| Hash | listpack | hashtable |
| Set | intset（全是整數）、listpack | hashtable |
| Sorted Set | listpack | skiplist + hashtable |

用 `OBJECT ENCODING key` 查看。門檻由設定決定，例如 `hash-max-listpack-entries 128`。小資料用緊湊的 listpack 省記憶體，所以「拆成很多小 Hash」常比一個巨大的 Hash 省空間。

## 16. Java / Spring Boot

| 工具 | 說明 |
|---|---|
| Lettuce | Spring Boot 預設的客戶端，基於 Netty、執行緒安全，一條連線可以共用 |
| Jedis | 傳統同步客戶端，要搭配連線池（展示台用的就是 Jedis） |
| Redisson | 分散式鎖、看門狗、限流器、延遲佇列等進階功能 |
| `RedisTemplate` | Spring Data Redis 的操作入口：`opsForValue()`、`opsForHash()`、`opsForZSet()`… |
| `@Cacheable` / `@CacheEvict` | Spring Cache 註解，搭配 `RedisCacheManager` 自動做 Cache-Aside |

★ 常見坑：
- `RedisTemplate` 預設用 JDK 序列化，key 會變成 `\xac\xed\x00\x05t\x00…` 這種亂碼。設定 `StringRedisSerializer` 與 JSON 序列化器，或直接用 `StringRedisTemplate`
- `@Cacheable` 預設沒有過期時間，要在 `RedisCacheConfiguration.entryTtl(...)` 設定
- 同一個類別裡呼叫自己的 `@Cacheable` 方法不會經過代理，快取不會生效

## 17. ★ 面試題速答

| 題目 | 重點 |
|---|---|
| Redis 為什麼快 | 記憶體、單執行緒執行指令（無鎖）、I/O 多工、高效資料結構 |
| Redis 是單執行緒嗎 | 執行指令是單執行緒；6.0 起網路 I/O 可以多執行緒；持久化、UNLINK 用背景執行緒 |
| 五種基本型別與用途 | String 快取 / 計數、Hash 物件、List 佇列 / 最新列表、Set 標籤 / 去重、Sorted Set 排行榜 |
| 穿透、擊穿、雪崩 | 不存在的資料、熱門 key 過期、大量 key 同時過期（見第 11 節） |
| 怎麼保證快取一致性 | 先更新資料庫再刪快取；延遲雙刪；訂閱 binlog 刪快取 |
| 分散式鎖怎麼做 | SET NX PX + 唯一 token + Lua 釋放；Redisson 看門狗；RedLock |
| 交易有 ROLLBACK 嗎 | 沒有；執行時的錯誤不影響其他指令。要原子性用 Lua |
| RDB 和 AOF | 快照 vs 指令日誌；混合持久化 |
| 記憶體滿了會怎樣 | 依 maxmemory-policy；快取用 allkeys-lru |
| 過期 key 怎麼刪 | 惰性刪除 + 定期刪除 |
| 什麼是大 key、怎麼處理 | 單一 key 太大（String > 10 KB、集合 > 數千元素）；拆分、用 UNLINK 刪除、用 SCAN 類指令讀 |
| 熱 key 怎麼處理 | 本地快取（Caffeine）多一層、key 加後綴分散到多個節點 |
| Cluster 怎麼分片 | 16384 個 slot、CRC16；跨 slot 用 hash tag |
| KEYS 和 SCAN | KEYS 一次掃完會阻塞；SCAN 分批、可能重複、要掃到游標為 0 |

## 18. redis-cli 常用指令

| 指令 | 作用 |
|---|---|
| `redis-cli -p 6380 --user learner --pass learner-lab` | 連到練習環境 |
| `docker exec -it redis-lab redis-cli --user default --pass admin-lab` | 從容器裡用管理員身分連線 |
| `--scan --pattern 'product:*'` | 安全地列出 key |
| `--bigkeys`、`--memkeys` | 找出最大的 key |
| `--latency` | 量測延遲 |
| `MONITOR` | 即時看所有指令（很耗效能，只在開發環境用） |
| `INFO`、`INFO memory`、`INFO stats` | 伺服器狀態 |
| `CLIENT LIST` | 目前的連線 |

---

# MongoDB

> 範例都來自練習環境（`mongo-lab` 容器，port 27018，`shop` 資料庫），可以直接貼到展示台的「指令主控台」執行。資料是從 PostgreSQL 轉進來的，練壞了按「重置資料」。

## 1. ★ 基本觀念與 SQL 對照

| SQL | MongoDB |
|---|---|
| database | database |
| table | collection（集合） |
| row | document（文件，BSON 格式） |
| column | field（欄位，每份文件可以不一樣） |
| primary key | `_id`（每份文件都有，預設是 ObjectId） |
| JOIN | 內嵌文件，或 `$lookup` |
| GROUP BY | 聚合管線的 `$group` |
| index | index（概念幾乎一樣） |

- **BSON**：二進位的 JSON，多了 Date、ObjectId、Decimal128、Int32 / Int64 等型別
- **ObjectId**：12 bytes，前 4 bytes 是建立時間，所以大致依時間遞增，可以用 `ObjectId(…).getTimestamp()` 取出
- **單一文件上限 16 MB**
- **彈性 schema**：同一個集合裡的文件欄位可以不同；需要時可以用 JSON Schema 驗證（`$jsonSchema`）
- 錢不要用 double：用 `Decimal128`（`NumberDecimal("19.99")`）或存成「分」的整數

## 2. 查詢：find

```mongo
db.orders.find({ status: "paid" })                          // 條件
db.orders.find({ status: "paid" }, { total: 1, _id: 0 })    // 投影：1 要、0 不要
db.orders.find({}).sort({ orderDate: -1 }).skip(20).limit(10)   // 排序、分頁
db.orders.findOne({ _id: 77621 })
db.orders.countDocuments({ status: "paid" })                // 依條件計數
db.orders.estimatedDocumentCount()                          // 讀統計值，很快、不能加條件
db.orders.distinct("shipping.city")                         // 不重複值
```

- ★ find 的 `sort`、`skip`、`limit` 不管怎麼串，都固定依「sort → skip → limit」執行
- 投影裡除了 `_id`，不能混用 1 和 0
- 大量分頁別用很大的 `skip`（要先掃過前面所有資料），改用「上一頁最後一筆的值」當條件：`{ orderDate: { $lt: 上一頁最後的時間 } }`

## 3. 查詢運算子

| 類別 | 運算子 |
|---|---|
| 比較 | `$eq` `$ne` `$gt` `$gte` `$lt` `$lte` `$in` `$nin` |
| 邏輯 | `$and` `$or` `$nor` `$not`（同一個物件裡的多個條件本來就是 AND） |
| 欄位 | `$exists`（欄位存不存在）、`$type`（型別） |
| 陣列 | `$all` `$elemMatch` `$size` |
| 其他 | `$regex`（或直接寫 `/^Apple/`）、`$expr`（在條件裡用聚合運算式，例如比較兩個欄位） |

```mongo
db.products.find({ price: { $gte: 1000, $lte: 2000 } })
db.orders.find({ status: { $in: ["cancelled", "returned"] } })
db.customers.find({ $or: [{ city: "花蓮縣" }, { vipLevel: "gold" }] })
db.customers.find({ birthDate: { $exists: false } })
db.products.find({ $expr: { $gt: ["$price", { $multiply: ["$cost", 2] }] } })   // 售價超過成本兩倍
```

★ 型別要一樣：`{ _id: "540" }`（字串）查不到 `_id: 540`（數字），不會自動轉型，也不會報錯。

★ `{ city: null }` 會同時找到「值是 null」和「沒有這個欄位」的文件。只要沒有欄位的用 `{ $exists: false }`。

## 4. 陣列與內嵌文件

```mongo
db.orders.find({ "shipping.city": "台北市" })                // 內嵌欄位用點號（要加引號）
db.orders.find({ "items.productId": 540 })                  // 點號也能穿過陣列
db.products.find({ tags: "特價" })                           // ★ 陣列裡「任一元素」等於
db.products.find({ tags: ["特價"] })                         // 整個陣列「完全等於」["特價"]
db.products.find({ tags: { $all: ["熱銷", "限量"] } })        // 包含全部，順序不拘
db.products.find({ tags: { $size: 3 } })                     // 剛好 3 個元素
db.orders.find({ items: { $elemMatch: { qty: { $gte: 3 }, unitPrice: { $gte: 10000 } } } })
```

★ `$elemMatch` 是面試常考題：`{ "items.qty": { $gte: 3 }, "items.unitPrice": { $gte: 10000 } }` 的兩個條件可以由「不同的元素」分別滿足；要求同一個元素同時符合，一定要用 `$elemMatch`。

## 5. ★ 聚合管線

文件依序流過每一個 stage，前一個的輸出是下一個的輸入。

| Stage | 作用 | 對照 SQL |
|---|---|---|
| `$match` | 篩選（放越前面越好，開頭的可以用索引） | WHERE |
| `$project` / `$addFields` / `$set` / `$unset` | 選欄位、新增或計算欄位 | SELECT |
| `$group` | 分組彙總：`$sum` `$avg` `$min` `$max` `$first` `$push` `$addToSet` | GROUP BY |
| `$sort` / `$limit` / `$skip` | 排序、筆數 | ORDER BY / LIMIT |
| `$unwind` | 把陣列攤平成多份文件 | unnest / JOIN 明細表 |
| `$lookup` | 關聯另一個集合，結果是陣列 | LEFT JOIN |
| `$bucket` / `$bucketAuto` | 分級統計 | CASE WHEN + GROUP BY |
| `$facet` | 同一份輸入跑多組管線（例如一次算總數與分頁） | 多個查詢 |
| `$count` | 計數 | count(*) |
| `$out` / `$merge` | 結果寫進集合 | INSERT INTO … SELECT |

```mongo
db.orders.aggregate([
  { $match: { status: "delivered" } },
  { $unwind: "$items" },
  { $group: { _id: "$items.productId", name: { $first: "$items.name" }, qty: { $sum: "$items.qty" } } },
  { $sort: { qty: -1 } },
  { $limit: 5 }
])

// 會員最新 3 筆訂單 + 會員姓名：先 $limit 再 $lookup
db.orders.aggregate([
  { $match: { customerId: 1 } },
  { $sort: { orderDate: -1 } },
  { $limit: 3 },
  { $lookup: { from: "customers", localField: "customerId", foreignField: "_id", as: "customer" } },
  { $project: { total: 1, customerName: { $first: "$customer.name" } } }
])
```

- ★ 管線是照 stage 的順序執行的：`[{ $limit: 3 }, { $sort: … }]` 真的是先取 3 筆再排序
- `$lookup` 的 `foreignField` 要有索引，不然每一筆都要掃整個集合
- 每個 stage 記憶體上限 100 MB，超過要 `allowDiskUse`（6.0 起預設允許）
- ★ 日期一律以 UTC 儲存：篩選寫 `ISODate("2026-09-01T00:00:00+08:00")`，`$dateToString`、`$dateTrunc` 要給 `timezone: "Asia/Taipei"`

## 6. 寫入

```mongo
db.customers.insertOne({ name: "王小明", email: "a@b.com" })
db.customers.insertMany([{ … }, { … }])
db.products.updateOne({ _id: 540 }, { $set: { stock: 20 } })
db.products.updateMany({ "category.name": "飲料" }, { $inc: { price: 5 } })
db.customers.updateOne({ email: "a@b.com" }, { $set: { name: "新會員" } }, { upsert: true })
db.products.findOneAndUpdate({ _id: 540, stock: { $gt: 0 } }, { $inc: { stock: -1 } }, { returnDocument: "after" })
db.orders.deleteMany({ status: "cancelled" })
```

| 運算子 | 作用 |
|---|---|
| `$set` / `$unset` | 設定 / 移除欄位 |
| `$inc` / `$mul` | 加 / 乘（原子操作） |
| `$min` / `$max` | 比原本小 / 大才更新 |
| `$rename` | 改欄位名稱 |
| `$setOnInsert` | 只在 upsert「新增」時才設定 |
| `$push` / `$addToSet` | 加入陣列 / 不重複才加入（`$each` 一次多個、`$slice` 限制長度） |
| `$pull` / `$pop` | 依條件移除 / 移除頭或尾 |
| `$[]` / `$[elem]` | 更新陣列的全部元素 / 符合 arrayFilters 的元素 |

- ★ `replaceOne` 會用新文件「整份取代」舊文件，只想改部分欄位一定要用 `$set`
- `updateOne` / `deleteOne` 只處理第一份符合的文件，全部處理用 `updateMany` / `deleteMany`
- upsert 要搭配條件欄位的唯一索引，不然高並發下可能新增出重複的文件

## 7. ★ Schema 設計：內嵌還是參照

**核心原則：一起讀取的資料就一起存放。**

| 內嵌（Embedding） | 參照（Referencing） |
|---|---|
| 一次讀取就拿到全部，不用 JOIN | 資料不重複，可以單獨查詢與更新 |
| 單一文件的更新是原子的，不需要交易 | 讀取時要 `$lookup`，或在程式裡查兩次 |
| 適合一對少量、總是一起讀（訂單明細、地址、規格） | 適合一對很多、數量會無限增長、多對多、常被單獨更新的資料 |

常見設計模式：

| 模式 | 說明 |
|---|---|
| Extended Reference | 參照之外，再複製幾個常用欄位（例如訂單裡存商品名稱），省掉 `$lookup` |
| Subset | 只內嵌最常用的一部分（例如商品文件只放最新 10 則評論，其他評論放另一個集合） |
| Computed | 事先算好放進文件（例如訂單的 `total`），讀的時候不用再算 |
| Bucket | 時間序列資料依時間分桶，一份文件存一小時的資料（MongoDB 5.0 起有原生的 Time Series 集合） |
| Tree | 樹狀結構存 `parentId`、`ancestors` 陣列或路徑字串（練習環境的 categories） |

★ 反模式：陣列無限增長（例如把一位會員的所有訂單都塞進會員文件）會碰到 16 MB 上限，更新也會越來越慢。

## 8. ★ 索引

```mongo
db.orders.createIndex({ customerId: 1, orderDate: -1 })
db.customers.createIndex({ email: 1 }, { unique: true })
db.orders.createIndex({ orderDate: 1 }, { partialFilterExpression: { status: "pending" } })
db.sessions.createIndex({ createdAt: 1 }, { expireAfterSeconds: 3600 })   // TTL：自動刪除過期文件
db.orders.getIndexes()
db.orders.dropIndex("customerId_1")
db.orders.find({ customerId: 4242 }).explain("executionStats")
```

| 類型 | 說明 |
|---|---|
| 單欄 / 複合 | 最常用；複合索引的欄位順序很重要 |
| 多鍵（multikey） | 對陣列欄位建索引，每個元素一個項目；一個複合索引最多一個陣列欄位 |
| unique / partial / sparse | 唯一、只收錄部分文件、只收錄有該欄位的文件 |
| TTL | 時間到自動刪除文件（Session、驗證碼、日誌） |
| text / Atlas Search | 全文檢索 |
| 2dsphere | 地理位置查詢 |
| hashed | 雜湊值，主要用在分片鍵 |
| wildcard | 欄位不固定時，對 `specs.$**` 這類動態欄位建索引 |

★ **ESR 規則**（複合索引的欄位順序）：**E**quality（等值）→ **S**ort（排序）→ **R**ange（範圍）。
例如 `find({ status: "delivered", total: { $gte: 50000 } }).sort({ orderDate: -1 })` 建 `{ status: 1, orderDate: -1, total: 1 }`，可以省掉記憶體排序，分頁查詢特別有效。

**explain 怎麼看**

| 欄位 / Stage | 意思 |
|---|---|
| `COLLSCAN` | 全集合掃描（缺索引） |
| `IXSCAN` → `FETCH` | 用索引找到位置，再讀文件 |
| `SORT` | 在記憶體裡排序（沒有索引提供順序） |
| `PROJECTION_COVERED` | 覆蓋查詢：只讀索引、不讀文件（記得 `_id: 0`） |
| `nReturned` / `totalKeysExamined` / `totalDocsExamined` | 回傳幾份 / 看了幾個索引項目 / 讀了幾份文件；三者越接近越好 |

## 9. ★ 交易與一致性

- **單一文件的操作一律是原子的**，所以好的內嵌設計常常不需要交易
- 多文件交易（4.0 起）需要**副本集**或分片叢集，單機模式不能用；有效能代價，預設 60 秒逾時
- **Write Concern**：寫入要幾個節點確認才算成功。`w: 1`（主節點）、`w: "majority"`（多數節點，5.0 起的預設）
- **Read Concern**：讀到什麼程度的資料。`local`、`majority`（不會讀到之後可能被回滾的資料）、`linearizable`
- **Read Preference**：讀哪個節點。`primary`（預設）、`secondaryPreferred`（分散讀取，但可能讀到稍舊的資料）

```java
// Spring：@Transactional 搭配 MongoTransactionManager（需要副本集）
try (ClientSession session = client.startSession()) {
    session.withTransaction(() -> {
        orders.insertOne(session, order);
        products.updateOne(session, eq("_id", 540), inc("stock", -1));
        return null;
    });
}
```

## 10. ★ 副本集與分片

| 架構 | 說明 |
|---|---|
| 副本集（Replica Set） | 一個 Primary 負責寫入，多個 Secondary 透過 oplog 複製；Primary 掛掉會自動選出新的（通常數秒內），至少要 3 個節點（或 2 + 仲裁者） |
| 分片（Sharding） | 資料依「分片鍵」分散到多個分片；應用程式連 `mongos` 路由，設定存在 config servers |

★ 分片鍵怎麼選：
- 基數要高（值很多種）、分布要平均、查詢條件常常帶到它
- 單調遞增的鍵（時間、ObjectId）會讓寫入全部集中在最後一個分片 → 用 hashed 分片，或複合分片鍵
- 查詢沒帶分片鍵就要問遍所有分片（scatter-gather），比較慢

## 11. 常見陷阱

| 陷阱 | 說明 |
|---|---|
| `{ field: null }` | 也會找到沒有這個欄位的文件 |
| 型別不同 | `"540"` 和 `540` 不相等，不會自動轉型 |
| 陣列等號 | `{ tags: "a" }` 是包含；`{ tags: ["a"] }` 是完全相等 |
| 陣列多條件 | 要同一個元素同時符合，用 `$elemMatch` |
| 時區 | 日期以 UTC 儲存，查詢與分組都要明確給時區 |
| `replaceOne` / `save()` | 整份取代，沒帶到的欄位會消失 |
| `updateOne` | 只更新一份 |
| 大 skip 分頁 | 越後面越慢，改用範圍條件分頁 |
| JDBC 式的思維 | 硬把每張表變成一個集合、到處 `$lookup`，就失去文件資料庫的優勢 |

## 12. Java / Spring Data MongoDB

| 工具 | 說明 |
|---|---|
| MongoDB Java Driver | 官方驅動程式（展示台用的就是 sync 版） |
| `MongoTemplate` | Spring 的操作入口：`find(Query, Class)`、`updateFirst`、`aggregate` |
| `MongoRepository` | 依方法名稱產生查詢：`findByStatusOrderByOrderDateDesc(…)` |
| `@Document` / `@Id` / `@Field` / `@Indexed` | 對應集合、主鍵、欄位名稱、索引 |
| `Criteria` / `Query` / `Update` | 組條件：`Query.query(Criteria.where("status").is("paid"))` |

★ 常見坑：
- `repository.save(entity)` 是整份取代：只載入部分欄位的物件存回去，其他欄位就消失了。部分更新用 `MongoTemplate.updateFirst` + `Update.update(…)`
- Spring Data 預設會在文件裡多存一個 `_class` 欄位（記錄 Java 類別），可以用 `MappingMongoConverter` 關掉
- `@Indexed` 預設**不會**自動建立索引（Spring Boot 3 起要設定 `spring.data.mongodb.auto-index-creation=true`，正式環境建議用遷移工具建）
- `LocalDateTime` 沒有時區，存進 MongoDB 會被當成 UTC，讀回來可能差 8 小時

## 13. ★ 面試題速答

| 題目 | 重點 |
|---|---|
| MongoDB 和關聯式資料庫差在哪 | 文件模型、彈性 schema、內嵌取代 JOIN、水平擴展（分片）較容易 |
| 什麼時候該用 MongoDB | 資料結構多變、讀寫以「整份文件」為單位、需要水平擴展；強關聯、複雜交易多的系統用關聯式較好 |
| 內嵌還是參照 | 一起讀就一起存；一對少量內嵌、一對很多或無限增長用參照 |
| `$elemMatch` 什麼時候用 | 陣列裡「同一個元素」要同時符合多個條件時 |
| 複合索引欄位順序 | ESR：等值 → 排序 → 範圍 |
| 怎麼判斷查詢有沒有用索引 | explain("executionStats")：IXSCAN vs COLLSCAN、docsExamined vs nReturned |
| MongoDB 支援交易嗎 | 單文件一律原子；多文件交易 4.0 起支援，需要副本集 |
| 副本集怎麼容錯 | oplog 複製、Primary 掛掉自動選舉；write concern majority 避免資料回滾 |
| 分片鍵怎麼選 | 高基數、分布平均、常被查詢；避免單調遞增 |
| `_id` 一定要是 ObjectId 嗎 | 不用，任何不重複的值都可以（練習環境用的是 PostgreSQL 的整數 id） |
| 16 MB 限制怎麼辦 | 改設計（參照、Subset、Bucket）；大檔案用 GridFS |

## 14. mongosh 常用指令

| 指令 | 作用 |
|---|---|
| `mongosh "mongodb://learner:learner-lab@localhost:27018/shop?authSource=admin"` | 連到練習環境 |
| `docker exec -it mongo-lab mongosh -u admin -p admin-lab` | 從容器裡用管理員身分連線 |
| `show dbs`、`use shop`、`show collections` | 列出資料庫、切換、列出集合 |
| `db.orders.stats()`、`db.stats()` | 集合 / 資料庫的大小與統計 |
| `db.currentOp()`、`db.killOp(id)` | 查看 / 中止執行中的操作 |
| `db.setProfilingLevel(1, { slowms: 100 })` | 記錄超過 100 ms 的慢查詢到 `system.profile` |
| `it` | 顯示下一批結果（find 一次只顯示 20 筆） |

# Cassandra

> 範例都來自練習環境（`cassandra-lab` 容器，port 9043，keyspace `shop`），可以直接貼到展示台的「cqlsh 主控台」執行。資料是從 PostgreSQL 轉進來的，練壞了按「重新載入資料」。

## 1. ★ 基本觀念與 SQL 對照

| SQL | Cassandra |
|---|---|
| database / schema | keyspace（同時決定複寫策略與複本數） |
| table | table（舊稱 column family） |
| row | row（屬於某個 partition） |
| primary key | partition key ＋ clustering columns |
| JOIN | 沒有。查詢需要的資料要事先放進同一張表（反正規化） |
| GROUP BY / ORDER BY | 只能用主鍵欄位，而且有很多限制 |
| transaction | 沒有一般交易；只有單一分區的輕量交易（LWT）與 BATCH |

- **無主架構（masterless）**：每個節點地位相同，沒有單點故障；任何節點都能當「協調節點」接收請求
- **一致性雜湊環**：分區鍵經過雜湊（Murmur3）得到 token，token 決定資料放在哪些節點；每個節點負責很多段 token 範圍（vnodes，預設 `num_tokens: 16`）
- **寫入路徑**：commitlog（循序寫入，保證持久）→ memtable（記憶體）→ 滿了 flush 成不可修改的 SSTable。寫入不需要先讀，所以非常快
- **讀取路徑**：memtable ＋ 可能好幾個 SSTable 合併；用 bloom filter 跳過不含這個分區的 SSTable，再用分區索引找到位置
- **compaction**：背景把多個 SSTable 合併成一個，順便清掉過期資料與墓碑
- 適合：大量寫入、時間序列、事件紀錄、使用者活動、IoT、需要多機房且不能停機的服務
- 不適合：需要 JOIN、隨意查詢、交易、強一致的計數（庫存、帳戶餘額）

## 2. ★ 主鍵：分區鍵與叢集鍵

```cql
CREATE TABLE orders_by_customer (
  customer_id int,
  order_time  timestamp,
  order_id    int,
  status      text,
  total       int,
  PRIMARY KEY ((customer_id), order_time, order_id)
) WITH CLUSTERING ORDER BY (order_time DESC, order_id ASC);
```

| 部分 | 寫法 | 作用 |
|---|---|---|
| 分區鍵 | `(customer_id)`，複合的寫成 `((a, b), …)` | 決定資料放在**哪個節點**；同一個分區的資料存在一起 |
| 叢集鍵 | `order_time, order_id` | 決定資料在**分區裡的順序**，可以用範圍查詢 |
| 主鍵 | 分區鍵 ＋ 叢集鍵 | 唯一識別一列；寫入同樣的主鍵就是覆蓋 |

- `PRIMARY KEY (a, b, c)`：a 是分區鍵，b、c 是叢集鍵；`PRIMARY KEY ((a, b), c)`：a、b 一起當分區鍵
- ★ 叢集鍵要能讓主鍵唯一：只用 `order_time` 時，同一毫秒的兩筆訂單會互相覆蓋，所以加上 `order_id`
- ★ 分區大小建議：100 MB 以內、10 萬列以內。會無限長大的分區（例如「所有訂單」「某個感測器的所有資料」）要分桶
- static 欄位：`col text STATIC`，同一個分區的所有列共用一個值（例如分區層級的屬性）

## 3. ★ 查詢先行的資料模型

關聯式是「先設計資料，再寫查詢」；Cassandra 是「**先列出要怎麼查，再為每一種查詢設計一張表**」。

| 查詢 | 表 | 主鍵 |
|---|---|---|
| 用訂單編號查訂單 | `orders` | `(order_id)` |
| 某會員的訂單，新的在前 | `orders_by_customer` | `((customer_id), order_time DESC, order_id)` |
| 某一天的訂單 | `orders_by_day` | `((order_day), order_time, order_id)` |
| 某分類的商品，價格由高到低 | `products_by_category` | `((category), price DESC, product_id)` |
| 用 email 登入 | `customers_by_email` | `(email)` |

- 同一份資料寫進好幾張表是常態（寫入便宜、讀取跨分區才貴）；用 logged BATCH 讓多張表最終一致
- **時間分桶**：`((sensor_id, day), ts)`、`((order_day), order_time)`，讓分區大小固定
- **熱點**：分區鍵的值分布要平均。例如用「狀態」當分區鍵，`delivered` 那個分區會非常大
- 一對多的子資料可以用集合或 UDT 放在同一列（`list<frozen<order_item>>`），數量少、一起讀時適用

## 4. 查詢：SELECT 的規則

```cql
SELECT * FROM orders_by_customer WHERE customer_id = 4242;                 -- 單一分區
SELECT * FROM orders_by_customer WHERE customer_id = 1 LIMIT 3;            -- 分區已排好序，最新 3 筆
SELECT * FROM orders_by_customer
WHERE customer_id = 1 AND order_time >= '2025-01-01 00:00:00+0800';       -- 叢集鍵範圍
SELECT * FROM orders_by_customer WHERE customer_id = 4242 ORDER BY order_time ASC;   -- 反向讀取
SELECT * FROM products WHERE product_id IN (540, 541, 542);                -- 分區鍵 IN
SELECT order_day, COUNT(*) FROM orders_by_day
WHERE order_day IN ('2026-09-01', '2026-09-02') GROUP BY order_day;      -- GROUP BY 只能用主鍵
SELECT * FROM orders_by_day WHERE order_day IN ('2026-09-01', '2026-09-02') PER PARTITION LIMIT 1;
SELECT DISTINCT category FROM products_by_category;                        -- 只能用在分區鍵
SELECT name, WRITETIME(name), TTL(city) FROM customers WHERE customer_id = 4242;
SELECT customer_id, token(customer_id) FROM customers LIMIT 5;             -- 看分區的 token
```

| 規則 | 說明 |
|---|---|
| ★ 分區鍵要完整給 | 用 `=` 或 `IN`；沒給就是全表掃描，會被拒絕（除非加 `ALLOW FILTERING`） |
| 叢集鍵從左邊開始限定 | 不能跳過前面的叢集鍵；前一個用了範圍，後面的就不能再限定 |
| 範圍只能用在叢集鍵 | 分區鍵只能 `=` / `IN`（或 `token()` 範圍） |
| ORDER BY | 只能用叢集鍵，而且只能是建表的順序或完全相反 |
| 非主鍵欄位 | 不能放在 WHERE，除非有索引（SAI）或 `ALLOW FILTERING` |
| aggregate | `COUNT`、`SUM`、`AVG`、`MIN`、`MAX`；★ `AVG(int)` 回傳 int（小數被捨去），要先 `CAST(x AS double)` |
| PER PARTITION LIMIT | 每個分區只取前 n 列，Cassandra 版的「每組前 n 名」 |

★ `ALLOW FILTERING` 的成本 = 為了找到結果要讀過的列數。限定了分區鍵之後在分區內過濾很便宜；沒有分區鍵就是全表掃描。

★ 時間字串沒寫時區時，用**伺服器的時區**解讀。一律寫清楚：`'2026-09-01 20:00:00+0800'`。timestamp 一律以 UTC 儲存（毫秒精度）。

## 5. 寫入：INSERT、UPDATE、DELETE

```cql
INSERT INTO customers_by_email (email, customer_id, name) VALUES ('a@example.com', 1, '王小明');
UPDATE products SET stock = 0, tags = tags + {'缺貨'} WHERE product_id = 540;
UPDATE products SET specs['color'] = '黑' WHERE product_id = 540;            -- map 的單一 key
UPDATE customers USING TTL 86400 SET vip_level = 'gold' WHERE customer_id = 4242;
INSERT INTO kv (k, v) VALUES ('session:1', '…') USING TTL 1800;             -- 整列 30 分鐘後過期
DELETE birth_date FROM customers WHERE customer_id = 4242;                   -- 刪一個欄位
DELETE FROM orders_by_customer
WHERE customer_id = 1 AND order_time < '2023-01-01 00:00:00+0800';          -- 範圍刪除
UPDATE product_sales SET units = units + 2 WHERE product_id = 540;           -- 計數器
```

- ★ **INSERT 和 UPDATE 都是 upsert**：寫入前不會先讀，主鍵已存在就覆蓋、不存在就新增，不會報錯
- ★ **last write wins**：每個欄位值（cell）都帶著寫入時間戳記，讀取時時間戳記大的勝出，跟執行順序無關（`USING TIMESTAMP` 可以指定）。各台應用程式伺服器的時鐘要同步
- ★ **TTL 是設在每個 cell 上**：UPDATE 只對這次寫的欄位設 TTL；要整列過期就用 INSERT … USING TTL，或建表時設 `default_time_to_live`
- ★ **寫 null = 刪除 = 墓碑**：沒有值的欄位不要寫（driver 4 的 prepared statement 可以讓參數保持 unset）
- 計數器：只能 `UPDATE … SET c = c + n`，不能 INSERT、不能設 TTL、表裡除了主鍵只能有 counter 欄位；重試可能重複加，不是冪等的

## 6. 資料型別

| 類別 | 型別 |
|---|---|
| 文字 | `text`（= `varchar`）、`ascii` |
| 整數 | `tinyint` `smallint` `int` `bigint` `varint`（任意長度） |
| 小數 | `float` `double` `decimal`（金額用 decimal） |
| 時間 | `timestamp`（毫秒）、`date`、`time`、`duration` |
| 識別 | `uuid`、`timeuuid`（含時間，可依時間排序，`now()` 產生） |
| 其他 | `boolean` `blob` `inet` `counter` `vector<float, n>`（5.0，向量搜尋） |
| 集合 | `list<T>`（有順序、可重複）、`set<T>`（不重複、排序）、`map<K, V>` |
| 自訂 | UDT（`CREATE TYPE`）、`tuple<…>`、`frozen<…>`（整個當成一個值，只能整個換掉） |

- 集合適合少量資料（幾十個以內），整個集合會一起讀出來
- ★ `tags = {'a'}` 是整個換掉（會先寫一個墓碑）；`tags = tags + {'a'}` 只新增元素
- list 的 `+` 會重複加入；set 不會

## 7. ★ 墓碑與 compaction

- SSTable 寫入後不會修改，**刪除是寫入一個墓碑（tombstone）**；DELETE、寫 null、TTL 過期、整個換掉集合都會產生墓碑
- 讀取時要讀到墓碑才知道資料被刪了：超過 `tombstone_warn_threshold`（1,000）會警告，超過 `tombstone_failure_threshold`（100,000）查詢直接失敗
- 墓碑要等 `gc_grace_seconds`（預設 10 天）過後、compaction 時才清掉。這段時間是給下線的複本回來時同步刪除，否則被刪的資料會「復活」（zombie）；所以 **repair 一定要在 gc_grace_seconds 內跑完一輪**
- ★ 範圍刪除只寫一個範圍墓碑；逐筆刪除每一列一個墓碑
- ★ 反模式：把 Cassandra 當佇列（一直寫入再刪除）、頻繁更新後又刪除

| compaction 策略 | 適合 |
|---|---|
| STCS（SizeTiered，預設） | 寫入為主 |
| LCS（Leveled） | 讀取為主、常更新；讀取時要碰的 SSTable 少，但 compaction 的 I/O 多 |
| TWCS（TimeWindow） | 時間序列＋TTL：同一時間窗的資料放一起，整個過期後整個檔案丟掉 |
| UCS（Unified，5.0） | 可以調整成接近上面任何一種，新版建議 |

## 8. ★ 複寫與一致性等級

```cql
CREATE KEYSPACE shop WITH replication = {'class': 'NetworkTopologyStrategy', 'dc1': 3, 'dc2': 3};
CONSISTENCY QUORUM;     -- cqlsh 指令：之後的請求用 QUORUM
```

- **RF（複本數）**：每份資料存幾份。正式環境常用 3；`NetworkTopologyStrategy` 可以每個機房各設
- **CL（一致性等級）**：每一次讀寫各自指定「要幾個複本回應才算成功」

| CL | 需要幾個複本回應 |
|---|---|
| `ONE` / `TWO` / `THREE` | 1 / 2 / 3 個 |
| `QUORUM` | ⌊RF / 2⌋ + 1（RF = 3 時是 2），跨所有機房計算 |
| `LOCAL_QUORUM` | 本地機房的 quorum，不必等跨機房的延遲（最常用） |
| `EACH_QUORUM` | 每個機房各自達到 quorum（寫入用） |
| `ALL` | 全部複本，任何一台掛掉就失敗 |
| `ANY` | 只用在寫入：連 hint 都算成功 |

- ★ **R + W > RF 就是強一致**：讀寫都用 QUORUM（2 + 2 > 3），讀到的複本裡一定有最新的那份
- 寫 ONE、讀 ONE：最快、最終一致，可能讀到舊資料
- 複本不足時直接失敗：`UnavailableException: … QUORUM (2 required but only 1 alive)`
- 修復機制：**hinted handoff**（複本暫時掛掉時，協調節點先記下 hint，回來後補寫，預設保留 3 小時）、**read repair**（讀取時發現複本不一致就修正）、**anti-entropy repair**（`nodetool repair`，定期全面比對）
- CAP：Cassandra 是 AP 系統，但一致性可以依每個請求調整（tunable consistency）

## 9. ★ 輕量交易（LWT）與 BATCH

```cql
INSERT INTO customers_by_email (email, customer_id, name) VALUES ('a@example.com', 1, '王小明') IF NOT EXISTS;
UPDATE products SET price = 27999 WHERE product_id = 540 IF price = 30000;   -- compare-and-set
UPDATE products SET stock = 9 WHERE product_id = 540 IF EXISTS;

BEGIN BATCH
  INSERT INTO customers (customer_id, name, email) VALUES (99999, '測試', 't@example.com');
  INSERT INTO customers_by_email (email, customer_id, name) VALUES ('t@example.com', 99999, '測試');
APPLY BATCH;
```

- **LWT**：用 Paxos 達成「先檢查再寫入」，回傳 `[applied]`（失敗時附上目前的值）。只限單一分區；延遲是一般寫入的好幾倍，衝突多時會一直重試
- 讀取 LWT 寫入的資料用 `SERIAL` / `LOCAL_SERIAL`
- ★ 同一份資料不要混用 LWT 與一般寫入（一般寫入不經過 Paxos，會破壞 LWT 的保證）
- **logged BATCH**（預設）：先寫進 batchlog，保證全部「最終都會套用」，用來維持反正規化的多張表一致；**不是交易**：沒有隔離、不能 ROLLBACK
- **unlogged BATCH**：只有同一個分區時才合理（一次寫入）
- ★ BATCH 不是用來加速的：跨很多分區的 batch 會壓垮協調節點（`batch_size_warn_threshold` 5 KiB、`batch_size_fail_threshold` 50 KiB）
- 庫存、餘額這種要「條件扣減」的資料，通常放在關聯式資料庫或 Redis

## 10. 索引：SAI、二級索引、物化視圖

```cql
CREATE INDEX orders_customer_idx ON orders (customer_id) USING 'sai';
CREATE INDEX products_tags_idx ON products (tags) USING 'sai';     -- 集合：CONTAINS
SELECT * FROM products WHERE tags CONTAINS '熱銷' AND price < 1000;  -- 多個 SAI 條件取交集（price 也要有索引）
DROP INDEX orders_customer_idx;
```

- **SAI**（Storage-Attached Index，5.0）：索引跟著每個 SSTable 建立，支援等號、範圍、集合、向量搜尋（ANN），多個條件可以一起用
- ★ 索引是每個節點的**本地索引**：沒有分區鍵時要問遍所有節點（scatter-gather），節點越多越貴。適合低頻查詢、搭配分區鍵、或資料量小的表
- 舊的二級索引（2i）、SASI 有很多限制；5.0 之後建議用 SAI
- 物化視圖（MV）：自動維護另一個主鍵的表，但一直是實驗功能、預設關閉，實務上多半自己用 BATCH 寫多張表

## 11. 常見陷阱

| 陷阱 | 說明 |
|---|---|
| INSERT 不會報主鍵重複 | upsert，直接覆蓋；要用 `IF NOT EXISTS` |
| UPDATE 不存在的列會長出一列 | 也是 upsert；要用 `IF EXISTS` |
| 沒有分區鍵就查不了 | 要建專用的表、SAI，或接受 `ALLOW FILTERING` 的全表掃描 |
| ORDER BY 任意欄位 | 只能用叢集鍵 |
| `COUNT(*)` 整張表 | 真的把每一列讀出來數，會逾時；用計數器表或 `nodetool tablestats` 的估計值 |
| `AVG(int)` | 回傳 int，小數被捨去 |
| 寫入 null | 等於刪除，產生墓碑 |
| 時鐘不同步 | last write wins，後寫的可能輸給先寫的 |
| 把 Cassandra 當佇列 | 墓碑堆積，讀取越來越慢，最後失敗 |
| 分區無限長大 | 時間序列要分桶 |
| 分區鍵用 `IN` 帶上千個值 | 協調節點壓力大；改成平行送出多個單一分區查詢 |
| 計數器重試 | 不是冪等的，可能加兩次 |

## 12. Java / Spring Data Cassandra

```java
CqlSession session = CqlSession.builder()
        .addContactPoint(new InetSocketAddress("localhost", 9043))
        .withLocalDatacenter("dc1")
        .withAuthCredentials("learner", "learner-lab")
        .build();
PreparedStatement ps = session.prepare(
        "SELECT order_id, total FROM shop.orders_by_customer WHERE customer_id = ?");
for (Row r : session.execute(ps.bind(4242).setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM))) {
    System.out.println(r.getInt("order_id") + " " + r.getInt("total"));
}
```

```java
@Table("orders_by_customer")
public record OrderByCustomer(
        @PrimaryKeyColumn(name = "customer_id", type = PrimaryKeyType.PARTITIONED) int customerId,
        @PrimaryKeyColumn(name = "order_time", type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING) Instant orderTime,
        @PrimaryKeyColumn(name = "order_id", type = PrimaryKeyType.CLUSTERED) int orderId,
        String status, int total) {
}
```

- ★ CqlSession 很重（連線池、metadata），整個應用程式共用一個；一定要用 **prepared statement**（只解析一次，而且知道分區鍵，可以直接送到負責的節點：token-aware）
- driver 4 的 Maven 座標已經改成 `org.apache.cassandra:java-driver-core`
- 大結果自動分頁（預設一頁 5,000 列）；做 API 分頁時用 `getPagingState()` 傳給下一次請求，不要用 OFFSET（Cassandra 沒有）
- Spring Data 的衍生查詢只能用主鍵欄位；用到其他欄位要 `@AllowFiltering` 或自己寫 CQL
- 寫入重試要小心：一般的 INSERT / UPDATE 是冪等的，可以安全重試；計數器、`list` 的 append、LWT 不行

## 13. ★ 面試題速答

| 問題 | 重點 |
|---|---|
| Cassandra 為什麼寫入快？ | 寫入前不讀；commitlog 循序寫入＋memtable；SSTable 不修改，合併交給背景 compaction（LSM tree） |
| 分區鍵和叢集鍵差在哪？ | 分區鍵決定資料在哪個節點；叢集鍵決定分區內的排序，可以範圍查詢 |
| 怎麼設計資料模型？ | 查詢先行：列出所有查詢 → 每個查詢一張表；反正規化；控制分區大小（分桶）；避免熱點 |
| 一致性等級怎麼選？ | 一般用 LOCAL_QUORUM 讀寫；R + W > RF 就是強一致；ONE 最快但可能讀到舊資料 |
| 一台節點掛了會怎樣？ | RF = 3、QUORUM 時照常運作；hinted handoff 先記下，回來後補寫；之後 repair |
| 什麼是墓碑？為什麼要 gc_grace_seconds？ | 刪除是寫入標記；等複本都同步刪除後才能清掉，否則資料會復活 |
| LWT 是什麼？代價？ | Paxos 的 compare-and-set；只限單一分區、延遲高；用在唯一性檢查等低頻操作 |
| BATCH 可以當交易用嗎？ | 不行。logged batch 只保證最終全部套用，沒有隔離、不能 rollback |
| 二級索引為什麼要小心？ | 本地索引，沒有分區鍵時要問遍所有節點；高頻查詢應該建專用的表 |
| Cassandra vs MongoDB？ | Cassandra：無主、寫入量極大、多機房、查詢模式固定；MongoDB：文件模型、查詢彈性高、有交易，主從複寫 |
| 什麼時候不要用 Cassandra？ | 需要 JOIN、隨意查詢、交易、強一致的計數，或資料量小（一台 PostgreSQL 就夠） |

## 14. cqlsh 與 nodetool 常用指令

```text
DESCRIBE KEYSPACES;              -- 列出 keyspace（4.0 起在伺服器端執行）
DESCRIBE TABLE shop.orders;      -- 看建表語句
CONSISTENCY LOCAL_QUORUM;        -- 之後的請求用這個一致性等級
TRACING ON;                      -- 之後的查詢附上查詢追蹤
EXPAND ON;                       -- 每個欄位一行顯示（欄位很多時好讀）
COPY shop.products TO 'p.csv' WITH HEADER = true;   -- 匯出 / 匯入 CSV

nodetool status                  -- 節點狀態（UN = Up / Normal）、負載、token 數
nodetool tablestats shop.orders  -- 估計筆數、分區大小、SSTable 數量
nodetool flush / compact / repair
nodetool getendpoints shop orders_by_customer 4242   -- 這個分區在哪些節點
```

# Neo4j

> 範例都來自練習環境（`neo4j-lab` 容器，Bolt port 7688，Neo4j Browser http://localhost:7475），可以直接貼到展示台的「Cypher 主控台」執行（一律 ROLLBACK，可以放心試寫入）。資料是從 PostgreSQL 轉進來的，追蹤關係（FOLLOWS）是模擬資料。

## 1. ★ 基本觀念與 SQL 對照

| 關聯式 | Neo4j（屬性圖） |
|---|---|
| table | label（標籤），一個節點可以有多個標籤：`(:Customer:Vip)` |
| row | node（節點） |
| column | property（屬性），每個節點可以不一樣 |
| 外鍵 / 中間表 | relationship（關係）：一定有**型別**和**方向**，也可以有屬性 |
| JOIN | 沿著關係走（traversal） |
| SQL | Cypher：用 ASCII 圖案「畫」出要找的圖樣 |

```text
(:Customer)-[:PLACED]->(:Order)-[:CONTAINS {qty, unitPrice}]->(:Product)-[:IN_CATEGORY]->(:Category)
(:Customer)-[:FOLLOWS]->(:Customer)      (:Customer)-[:LIVES_IN]->(:City)
```

- ★ **index-free adjacency**：每個節點直接記著自己的關係，走一步的成本跟資料庫總大小無關；關聯式資料庫每一次 JOIN 都要查一次索引
- 適合：社群關係、推薦、詐騙偵測（環狀轉帳）、權限繼承、知識圖譜、供應鏈、網路拓撲——「關係本身就是重點」、而且要走好幾層的問題
- 不適合：整張表的彙總報表、大量欄位的篩選、單純的 CRUD
- 索引只用來找「起點」，找到起點之後沿著關係走

## 2. 圖樣語法

```cypher
(c)                          // 任意節點，變數 c
(c:Customer)                 // 標籤
(c:Customer {id: 4242})      // 屬性條件
(a)-[:FOLLOWS]->(b)          // a 追蹤 b（有方向）
(a)<-[:FOLLOWS]-(b)          // b 追蹤 a
(a)-[:FOLLOWS]-(b)           // 不管方向（兩個方向都配對）
(a)-[r:FOLLOWS]->(b)         // 關係也可以有變數，r.since 取屬性
(a)-[:FOLLOWS|LIKES]->(b)    // 多種型別
(a)-[:FOLLOWS*1..3]->(b)     // 可變長度：1 到 3 步（★ 一定要給上限）
p = (a)-[:FOLLOWS*..5]->(b)  // 整條路徑存成變數 p
```

## 3. 查詢：MATCH、WHERE、RETURN、WITH

```cypher
MATCH (c:Customer {id: 4242})-[:PLACED]->(o:Order)
WHERE o.total >= 1000 AND o.orderDate >= datetime('2025-01-01T00:00:00+08:00')
RETURN o.id, o.total
ORDER BY o.orderDate DESC
SKIP 0 LIMIT 10;

MATCH (c:Customer) WHERE NOT (c)-[:PLACED]->() RETURN count(c);   // 從沒下過單（圖樣當條件）
MATCH (c:Customer) WHERE EXISTS { (c)-[:PLACED]->(:Order {status: 'returned'}) } RETURN count(c);

MATCH (c:Customer) WHERE c.id IN [1, 2, 4242]
OPTIONAL MATCH (c)-[:PLACED]->(o:Order) WHERE o.total >= 50000    // 像 LEFT JOIN … ON
RETURN c.id, count(o);

MATCH (:Customer {id: 4242})-[:FOLLOWS]->(f)
WITH f ORDER BY f.id                       // WITH = 中間的 RETURN：排序、過濾、聚合後交給下一段
RETURN collect(f.name);

UNWIND [4242, 1, 2] AS id                  // 清單展開成多列
MATCH (c:Customer {id: id}) RETURN c.name;
```

| 子句 / 函式 | 用途 |
|---|---|
| `WITH` | 把結果交給下一段（可以搭配 WHERE、ORDER BY、LIMIT、聚合） |
| `OPTIONAL MATCH` | 找不到時變數是 null，整列保留（LEFT JOIN） |
| `UNWIND` | 清單展開成多列（批次寫入常用） |
| `collect()` | 多列收集成清單 |
| `[x IN list WHERE 條件 \| 運算式]` | list comprehension |
| `EXISTS { 圖樣 }`、`COUNT { 圖樣 }` | 子查詢條件 |
| `CASE WHEN … THEN … END` | 跟 SQL 一樣 |
| `coalesce()`、`toInteger()`、`toFloat()`、`size()`、`keys()`、`labels()`、`type()` | 常用函式 |

★ 參數用 `$name`：`MATCH (c:Customer {id: $id})`。不要把值串進字串（Cypher injection、也無法重用執行計畫）。

## 4. 聚合

```cypher
MATCH (c:Customer)<-[:FOLLOWS]-(fan)
RETURN c.id, c.name, count(fan) AS followers      // 沒有 GROUP BY：非聚合的欄位就是分組依據
ORDER BY followers DESC LIMIT 5;
```

- 聚合函式：`count()`、`count(DISTINCT x)`、`sum()`、`avg()`、`min()`、`max()`、`collect()`、`percentileCont()`
- ★ `count(*)` 算列數；`count(x)` 不算 null
- ★ 整數相除還是整數：`7 / 2 = 3`，`avg()` 才是浮點數

## 5. ★ 路徑與走訪

```cypher
// 朋友的朋友（你可能認識的人）
MATCH (me:Customer {id: 224})-[:FOLLOWS]->()-[:FOLLOWS]->(fof)
WHERE fof <> me AND NOT (me)-[:FOLLOWS]->(fof)
RETURN count(DISTINCT fof);

// 最短路徑（雙向廣度優先，找到就停）
MATCH p = shortestPath((a:Customer {id: 4242})-[:FOLLOWS*..10]->(b:Customer {id: 19999}))
RETURN length(p), [n IN nodes(p) | n.name];

MATCH p = allShortestPaths((a)-[:FOLLOWS*..10]->(b)) RETURN p;   // 所有一樣短的路徑

// 樹：分類底下的所有子分類（SQL 要 WITH RECURSIVE）
MATCH (c:Category)-[:SUBCATEGORY_OF*1..]->(:Category {name: '3C電子'}) RETURN c.name;
```

- `nodes(p)`、`relationships(p)`、`length(p)`（關係數）
- ★ **關係唯一性**：同一個 MATCH 圖樣裡，同一條關係不會走兩次（避免無限繞圈）；但同一個節點可以出現多次。所以「朋友的朋友」可能包含自己（互相追蹤時）
- ★ 一條長圖樣 `(p)<-[:CONTAINS]-(:Order)<-[:PLACED]-(c)-[:PLACED]->(:Order)-[:CONTAINS]->(x)` 的兩個 PLACED 必須是不同的關係 → 漏掉「同一張訂單」的情況；要的話拆成兩個 MATCH
- ★ MATCH 回傳的是「每一種配對方式」：同一個人經由兩條路徑到達就出現兩次，算人數要 DISTINCT
- 進階演算法（PageRank、社群偵測、相似度）用 GDS（Graph Data Science）函式庫

## 6. 寫入：CREATE、MERGE、SET、DELETE

```cypher
MATCH (city:City {name: '台北市'})
CREATE (c:Customer {id: 99999, name: '測試'})-[:LIVES_IN]->(city);   // 連到「既有」的節點

MATCH (a:Customer {id: 4242}), (b:Customer {id: 1})
MERGE (a)-[:FOLLOWS]->(b);                                       // 有就用，沒有才建立

MERGE (c:Customer {id: 4242})
ON CREATE SET c.name = '新會員', c.createdAt = datetime()
ON MATCH SET c.lastLogin = datetime();

MATCH (p:Product {id: 540})
SET p.stock = 0, p.tags = p.tags + '缺貨', p += {isActive: false}
REMOVE p.discount;                                               // 刪屬性（等於 SET p.discount = null）

MATCH (:Customer {id: 4242})-[r:FOLLOWS]->(:Customer {id: 15084}) DELETE r;   // 刪關係
MATCH (c:Customer {id: 99999}) DETACH DELETE c;                  // 刪節點和它所有的關係

UNWIND $rows AS row                                              // ★ 批次寫入：一次送一批
MERGE (c:Customer {id: row.id}) SET c.name = row.name;
```

- ★ **MERGE 是整個圖樣一起比對**：`MERGE (a:Person {name:'A'})-[:KNOWS]->(b:Person {name:'B'})` 找不到完整圖樣時，會連兩個節點一起重新建立（產生重複節點）。正確：先 MATCH / MERGE 兩端，再 MERGE 關係
- MERGE 要搭配唯一約束：沒有約束時兩個交易同時 MERGE 可能建出兩個節點
- `SET p = {…}` 會把所有屬性換掉；`SET p += {…}` 只更新給的屬性
- 屬性設成 null = 刪除這個屬性
- 有關係的節點不能直接 DELETE：交易 commit 時會報錯（交易內看起來成功），要 DETACH DELETE
- ★ MATCH 找不到時，後面的 CREATE / SET 會執行 0 次，**不會報錯**；要確認有寫入就看回傳的列或寫入統計
- 大量刪除 / 更新要分批：`CALL { … } IN TRANSACTIONS OF 10000 ROWS`

## 7. ★ 資料模型設計

| 問題 | 建議 |
|---|---|
| 屬性還是節點？ | 會被「拿來連」或「拿來走訪」的東西做成節點（城市、品牌、標籤）；只是描述的就當屬性 |
| 關係屬性 | 描述「兩者之間」的資訊放在關係上（數量、時間、權重） |
| 多對多帶很多資訊 | 中間節點（例如 Order 連接 Customer 與 Product），比把一切塞進關係彈性 |
| 標籤 | 用來分類、加速篩選（`:Customer:Vip`）；不要把會變的狀態做成大量標籤 |
| 關係型別要具體 | `:PLACED`、`:FOLLOWS` 比通用的 `:RELATED_TO` 好：查詢時可以只走需要的型別 |
| ★ 超級節點 | 有幾十萬條關係的節點（名人、熱門標籤）會讓經過它的查詢變慢：限制方向與型別、依時間拆關係型別、預先算好統計值 |
| 方向 | 依語意選一個方向存就好（不需要雙向各存一條）；查詢時可以不寫方向 |

## 8. 索引、約束與 PROFILE

```cypher
CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (c:Customer) REQUIRE c.id IS UNIQUE;  // 唯一約束（自帶索引）
CREATE INDEX customer_email IF NOT EXISTS FOR (c:Customer) ON (c.email);              // RANGE 索引
CREATE INDEX order_comp FOR (o:Order) ON (o.status, o.orderDate);                     // 複合索引
CREATE TEXT INDEX product_name FOR (p:Product) ON (p.name);                           // CONTAINS / ENDS WITH
CREATE FULLTEXT INDEX product_ft FOR (p:Product) ON EACH [p.name];                    // 全文搜尋
CREATE INDEX follows_since FOR ()-[r:FOLLOWS]-() ON (r.since);                        // 關係屬性也能建索引
SHOW INDEXES;  SHOW CONSTRAINTS;  DROP INDEX customer_email;

PROFILE MATCH (c:Customer) WHERE c.email = 'user04242@example.com' RETURN c;          // 執行並顯示 db hits
EXPLAIN MATCH …;                                                                      // 只看計畫，不執行
```

| 運算子 | 意思 |
|---|---|
| `AllNodesScan` | 掃描所有節點（沒寫標籤）—— 最差 |
| `NodeByLabelScan` | 掃描某個標籤的所有節點（沒有索引可用） |
| `NodeIndexSeek` / `NodeUniqueIndexSeek` | 用索引找到起點 ✓ |
| `NodeIndexSeekByRange` / `NodeIndexContainsScan` | 範圍 / TEXT 索引 |
| `Expand(All)` / `Expand(Into)` | 沿著關係走 |
| `Filter` | 逐列過濾 |
| `CartesianProduct` | 兩個沒有連在一起的圖樣 ⚠ |
| `Eager` | 整批讀完才寫入（避免讀寫衝突），大量資料時耗記憶體 |

- ★ **db hits** = 存取儲存層的次數，比毫秒更穩定的成本指標（實驗室：沒索引 40,003 → 有索引 4）
- 在屬性上做運算（`c.id + 0 = 4242`、`toString(c.id) = '4242'`）就用不到索引
- RANGE 索引：=、範圍、STARTS WITH、IS NOT NULL；TEXT 索引：CONTAINS、ENDS WITH
- 社群版有唯一約束；屬性存在約束、Node Key、屬性型別約束是企業版功能

## 9. 交易與叢集

- ACID 交易，預設隔離等級是 read committed；寫入時對節點 / 關係加鎖，可能發生 deadlock（TransientException，driver 的 managed transaction 會自動重試）
- ★ 一個交易裡的所有句子要嘛全部 commit、要嘛全部 rollback；有些檢查（例如刪除有關係的節點）在 commit 時才做
- 叢集（企業版）：primary 伺服器用 Raft 達成多數決寫入，secondary 伺服器負責讀取擴展
- **bookmark**：寫入後拿到 bookmark，下一次讀取帶著它，保證讀得到自己剛寫的資料（causal consistency），driver 的 session 會自動處理
- 社群版：單機、只有一個使用者資料庫（neo4j）、沒有角色權限

## 10. 常見陷阱

| 陷阱 | 說明 |
|---|---|
| MERGE 整個圖樣 | 找不到完整圖樣就全部重新建立，產生重複節點 |
| 不寫方向 | 兩個方向都會配對（追蹤 6 + 粉絲 4 = 10） |
| `= null` | 永遠找不到，要用 `IS NULL` |
| OPTIONAL MATCH 後面的 WHERE | 寫在 OPTIONAL MATCH 裡是配對條件（列會保留）；寫在後面的 WITH … WHERE 會把 null 濾掉 |
| 逗號連接兩個不相關的圖樣 | 笛卡兒積（891 × 83 = 73,953 列），伺服器會給警告 |
| 忘了 DISTINCT | 每一條路徑算一列 |
| 朋友的朋友包含自己 | 關係唯一、節點可以重複；記得 `fof <> me` |
| 一條長圖樣漏資料 | 同一條關係不會用兩次 → 拆成兩個 MATCH |
| 型別不一致 | `{id: '4242'}`（字串）找不到 `id: 4242`，也不報錯 |
| 整數相除 | `sum(x) / count(x)` 捨去小數 |
| MATCH 找不到就沒事發生 | 後面的 CREATE 執行 0 次，不報錯 |
| 沒有上限的 `*` | 大圖上可能走出幾百萬條路徑 |

## 11. Java / Spring Data Neo4j

```java
try (Driver driver = GraphDatabase.driver("bolt://localhost:7688", AuthTokens.basic("neo4j", "neo4j-lab"));
     Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
    List<String> names = session.executeRead(tx -> tx.run(
            "MATCH (:Customer {id: $id})-[:FOLLOWS]->(f) RETURN f.name AS name",
            Map.of("id", 4242)).list(r -> r.get("name").asString()));
    session.executeWrite(tx -> tx.run("UNWIND $rows AS r MERGE (c:Customer {id: r.id}) SET c.name = r.name",
            Map.of("rows", rows)).consume());
}
```

```java
@Node("Customer")
public class Customer {
    @Id private Long id;                       // 業務 id；或 @Id @GeneratedValue 用內部 id
    private String name;
    @Relationship(type = "FOLLOWS", direction = Relationship.Direction.OUTGOING)
    private Set<Customer> follows;
}

public interface CustomerRepository extends Neo4jRepository<Customer, Long> {
    @Query("MATCH (:Customer {id: $id})-[:FOLLOWS]->(f) RETURN f")
    List<Customer> following(Long id);
}
```

- ★ Driver 很重，整個應用程式共用一個；Session 很輕，用完就關
- `executeRead` / `executeWrite`（managed transaction）遇到暫時性錯誤會自動重試，交易函式要能重複執行（不要在裡面寄信）
- 一律用參數 `$id`，不要字串串接
- 大量寫入用 `UNWIND $rows` 一次送一批（本專案載入資料：一批 5,000 筆）
- Spring Data Neo4j 載入有關係的實體時，可能一次把很大一片圖拉回來；大型查詢用自訂 Cypher 或投影（projection）
- 不要用 `elementId()` / 內部 id 當業務 id：刪除後可能被重複使用

## 12. ★ 面試題速答

| 問題 | 重點 |
|---|---|
| 圖資料庫跟關聯式資料庫差在哪？ | 關係是存起來的（index-free adjacency），走一步的成本跟資料總量無關；JOIN 是查詢時才用索引比對 |
| 什麼時候用圖資料庫？ | 關係本身是重點、要走好幾層、層數不固定：社群、推薦、詐騙偵測、權限、知識圖譜 |
| 圖資料庫一定比較快嗎？ | 不一定。實驗室：8 萬條追蹤關係時，「幾步內可到達幾人」PostgreSQL 一樣快；但最短路徑（8 步）SQL 慢了幾十倍 |
| Cypher 的 MERGE 要注意什麼？ | 整個圖樣一起比對；先 MATCH 兩端再 MERGE 關係；搭配唯一約束 |
| 什麼是超級節點？怎麼處理？ | 關係非常多的節點；限制方向與型別、拆關係型別、預先計算 |
| 怎麼看查詢效能？ | PROFILE 看運算子與 db hits；確認起點是 IndexSeek 而不是 LabelScan |
| 屬性還是節點？ | 會拿來連結、走訪的做成節點；只是描述的當屬性 |
| 怎麼做推薦？ | 協同過濾：商品 ← 買過的人 → 這些人買的其他商品，依共同購買人數排序 |
| Neo4j 怎麼擴展？ | 企業版叢集：primary（Raft 寫入）＋ secondary（讀取擴展）；資料量極大時用 Fabric / 複合資料庫分片 |

## 13. 工具與指令

```text
cypher-shell -a bolt://localhost:7688 -u neo4j -p neo4j-lab     # 命令列
docker exec -it neo4j-lab cypher-shell -u neo4j -p neo4j-lab
http://localhost:7475                                            # Neo4j Browser（結果畫成圖）

SHOW INDEXES;  SHOW CONSTRAINTS;  SHOW TRANSACTIONS;
CALL db.schema.visualization();                                  # 看標籤與關係型別的結構
CALL db.labels();  CALL db.relationshipTypes();  CALL db.propertyKeys();
neo4j-admin database import full …                               # 離線大量匯入 CSV（最快）
```

- APOC：常用的工具程序（匯入匯出、批次、日期處理）；GDS：圖演算法（PageRank、最短路徑、社群偵測）

# TimescaleDB

> 範例都來自練習環境（`timescale-lab` 容器，port 5435，資料庫 `metrics`），可以直接貼到展示台的「SQL 主控台」執行。page_views 是 2026-04 ～ 09 的商品瀏覽紀錄（191 萬筆），sensor_readings 是 2026-09 的倉庫感測器讀數（每分鐘一筆）。

## 1. ★ 基本觀念

- **TimescaleDB = PostgreSQL 的擴充**：照樣寫 SQL、JOIN、交易、索引，PostgreSQL 的工具（psql、JDBC、Spring Data JPA）都能直接用
- **hypertable**：看起來是一張表，底層依時間自動切成很多個 **chunk**（一般的 PostgreSQL 表），寫入時自動建新的 chunk
- 時間序列資料的特性：大量寫入、幾乎只「附加在最後」、很少修改舊資料、查詢大多是「某段時間」「依時間彙總」、舊資料要降精度或刪掉
- TimescaleDB 針對這些特性加上：**chunk exclusion**、**time_bucket** 等時間函式、**連續聚合**、**壓縮（columnstore）**、**資料保留政策**

| | PostgreSQL 原生分區 | TimescaleDB hypertable |
|---|---|---|
| 建立分區 | 要自己（或用 pg_partman）先建好每個分區 | 寫入時自動建立 chunk |
| 分區大小 | 自己設計 | `chunk_time_interval`（預設 7 天） |
| 時間函式 | `date_trunc` | `time_bucket`（任意長度）、gapfill、first / last |
| 預先彙總 | MATERIALIZED VIEW（每次整個重算） | 連續聚合（增量更新、可即時合併最新資料） |
| 壓縮 | 沒有（只有 TOAST） | 欄式壓縮，常見 90% 以上 |
| 刪舊資料 | DROP 分區 | `drop_chunks`、保留政策自動執行 |

## 2. Hypertable 與 chunk

```sql
CREATE TABLE page_views (
  view_time   timestamptz NOT NULL,
  product_id  int         NOT NULL,
  customer_id int,
  device      text        NOT NULL
);
SELECT create_hypertable('page_views', by_range('view_time', INTERVAL '7 days'));   -- 2.13 起的寫法
-- 舊寫法：SELECT create_hypertable('page_views', 'view_time', chunk_time_interval => INTERVAL '7 days');
SELECT set_chunk_time_interval('page_views', INTERVAL '1 day');                    -- 之後新建的 chunk 生效

SELECT show_chunks('page_views');
SELECT * FROM timescaledb_information.chunks WHERE hypertable_name = 'page_views';
SELECT hypertable_size('page_views'), approximate_row_count('page_views');
```

- ★ **chunk exclusion**：查詢有時間範圍時，只讀相關的 chunk（實驗室：9/1 一天只讀 1 / 27 個 chunk，0.6 ms；全部要 30 ms）
- ★ 在時間欄位上做運算（`view_time::date = …`、`date_trunc('day', view_time) = …`）就無法排除 chunk，27 個全讀（慢 100 倍）；一律寫 `time >= 開始 AND time < 結束`
- `now() - interval '7 days'` 一樣能排除 chunk：TimescaleDB 在規劃時把它先算成常數（計畫的 Index Cond 會多一個算好的時間）
- 在 hypertable 上建的索引會自動建到每個 chunk；`create_hypertable` 預設會建 `(time DESC)` 的索引
- ★ **唯一索引 / 主鍵一定要包含時間欄位**（每個 chunk 各自檢查唯一性）
- chunk 大小建議：最近一個 chunk（和它的索引）能放進記憶體的 25% 左右；太小 → chunk 太多，沒有時間條件的查詢要查很多次索引；太大 → 排除效果差
- chunk 的邊界依 UTC 對齊（7 天的 chunk 從星期四 00:00 UTC 開始）
- 可以加第二個維度（空間分區）：`add_dimension('t', by_hash('device_id', 4))`，多數情況不需要

## 3. ★ time_bucket 與時間序列函式

```sql
SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day, count(*)       -- ★ 天以上要給時區
FROM page_views
WHERE view_time >= '2026-09-01 00:00+08' AND view_time < '2026-10-01 00:00+08'
GROUP BY day ORDER BY day;

SELECT time_bucket('15 minutes', time) AS t, avg(temperature) FROM sensor_readings … GROUP BY t;
SELECT time_bucket('1 month', view_time, 'Asia/Taipei') AS month, count(*) …;   -- 依日曆月份
SELECT time_bucket('1 week', time, 'Asia/Taipei', origin => '2000-01-02') …;     -- 從星期日開始

SELECT sensor_id, first(temperature, time), last(temperature, time)            -- 依時間的第一筆 / 最後一筆
FROM sensor_readings WHERE time >= … GROUP BY sensor_id;

SELECT time_bucket_gapfill('1 hour', time) AS hour,                            -- 沒有資料的區間也列出來
       avg(temperature),
       locf(avg(temperature)),                                                  -- 用前一個值補
       interpolate(avg(temperature))                                            -- 線性內插
FROM sensor_readings
WHERE sensor_id = 7 AND time >= '2026-09-10 08:00+08' AND time < '2026-09-10 16:00+08'
GROUP BY hour ORDER BY hour;
```

- ★ timestamptz 的 time_bucket **預設依 UTC 切**：台灣的一天會從早上 8 點開始；`'1 day'`、`'1 week'`、`'1 month'` 都要加時區參數（資料庫的 timezone 設定不影響 time_bucket）
- `'30 days'` ≠ 一個月：固定長度的區間從 origin（2000-01-03）開始切，跟月份對不齊
- `'1 week'` 預設從星期一開始
- ★ `time_bucket_gapfill` 一定要能從 WHERE 推算出開始與結束，否則報錯
- 一般 time_bucket 只回傳「有資料」的區間，畫圖表時中間的空白會消失；監控資料常用 gapfill
- 移動平均、與前一期比較：time_bucket 加上視窗函數（`avg() OVER (ORDER BY day ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)`、`lag()`）
- 進階函式（approx_percentile、time_weight、counter_agg、HyperLogLog）在 timescaledb-toolkit 擴充

## 4. ★ 連續聚合（Continuous Aggregate）

```sql
CREATE MATERIALIZED VIEW sensor_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', time) AS hour, sensor_id,
       avg(temperature) AS avg_temp, max(temperature) AS max_temp, count(*) AS readings
FROM sensor_readings
GROUP BY hour, sensor_id
WITH NO DATA;

CALL refresh_continuous_aggregate('sensor_hourly', '2026-09-01', '2026-10-01');   -- 手動 refresh 一段時間
SELECT add_continuous_aggregate_policy('sensor_hourly',
  start_offset => INTERVAL '3 days', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '30 minutes');
ALTER MATERIALIZED VIEW sensor_hourly SET (timescaledb.materialized_only = false);  -- 即時聚合
```

- 增量更新：只重算「有資料變動的時間範圍」（PostgreSQL 的 MATERIALIZED VIEW 每次整個重算）
- ★ **2.13 起預設 `materialized_only = true`**：還沒 refresh 的最新資料查不到（陷阱題：9/24 之後是 0）
- 即時聚合（`materialized_only = false`）：已物化的部分 + watermark 之後的原始資料即時算，資料最新，但查詢比較慢
- 實驗室：同一份報表，原始資料 40 ms、讀連續聚合 7 ms
- ★ 聚合後的數字再彙總：count、sum 可以再 sum；**平均值不能再平均**（存 sum 與 count，最後相除）；聚合表的 `count(*)` 是組數不是原始筆數
- 限制：聚合要能分段計算再合併，`count(DISTINCT …)` 不能用；可以在連續聚合上再建連續聚合（分層：分鐘 → 小時 → 天）
- 刪除原始資料（保留政策）不會刪掉已物化的聚合：常見做法是「原始資料留 30 天、每小時聚合留 2 年」

## 5. ★ 壓縮（columnstore）

```sql
ALTER TABLE page_views SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'device',
  timescaledb.compress_orderby   = 'view_time DESC'
);
SELECT compress_chunk(c) FROM show_chunks('page_views', older_than => INTERVAL '7 days') c;
SELECT add_compression_policy('page_views', INTERVAL '7 days');   -- 自動壓縮 7 天前的 chunk
SELECT * FROM chunk_compression_stats('page_views');              -- 壓縮前後的大小
-- 2.18 起也叫 columnstore：ALTER TABLE … SET (timescaledb.enable_columnstore, timescaledb.segmentby = …)、add_columnstore_policy
```

| segmentby（實驗室實測，9 月的 33 萬筆） | 壓縮率 | 說明 |
|---|---:|---|
| 不分組 | 7.6 倍 | |
| `device`（3 種值） | 9.6 倍 | ★ 依裝置的查詢 45 ms → 4 ms |
| `product_id`（1,500 種值） | 1.9 倍 | 每組只有幾列，湊不滿一批，壓縮率很差 |

- 壓縮後的 chunk 改成欄式存放：每 1,000 列打包成一筆、每個欄位用適合的演算法（delta-of-delta、字典、Gorilla…）
- ★ **segmentby** 選「常拿來過濾、值的種類不多」的欄位（裝置、感測器 id、地區）；**orderby** 通常放時間
- 適合壓縮「不再常變動的舊資料」；壓縮後仍可 INSERT / UPDATE / DELETE（2.11 起），但成本較高
- 只讀少數欄位的分析查詢在壓縮後通常更快（讀的資料少）；只要一兩列的點查詢可能變慢一點

## 6. 資料保留

```sql
SELECT drop_chunks('page_views', older_than => '2026-05-01'::timestamptz);     -- 直接丟掉整個 chunk
SELECT add_retention_policy('page_views', INTERVAL '6 months');                -- 自動執行
SELECT * FROM timescaledb_information.jobs;                                    -- 所有背景工作（壓縮、refresh、保留）
```

- ★ DELETE 一筆一筆刪、寫 WAL、還要 VACUUM：實測刪 7 萬筆要 5 秒；`drop_chunks` 丟 5 個 chunk 只要幾毫秒
- 只會丟「整個範圍都早於條件」的 chunk，邊界的 chunk 會留著
- 常見的分層：原始資料 30 天 → 壓縮 → 保留政策刪除；連續聚合保留更久

## 7. 寫入

- 寫入跟一般的表一樣（INSERT、COPY、批次）；時間序列大多附加在最後，正在寫入的 chunk 小、索引在記憶體裡，所以寫入很快
- 批次寫入：多筆 VALUES、`COPY`、JDBC batch（`reWriteBatchedInserts=true`）
- 遲到的資料（late data）會寫進舊 chunk；已物化的連續聚合要等下一次 refresh 才會更新；寫進已壓縮的 chunk 成本較高
- UPSERT：`INSERT … ON CONFLICT (sensor_id, time) DO UPDATE`（唯一索引要包含時間欄位）

## 8. 常見陷阱

| 陷阱 | 說明 |
|---|---|
| time_bucket 沒給時區 | 依 UTC 切，台灣的一天從早上 8 點開始，每天的數字都錯 |
| `'30 days'` 當成一個月 | 跟月份對不齊，要用 `'1 month'` |
| 在時間欄位上轉型 | `time::date = …` 讓 chunk exclusion 失效 |
| gapfill 沒給範圍 | 報錯：could not infer start from WHERE clause |
| 連續聚合查不到最新資料 | materialized_only 預設 true，要 refresh 政策或打開即時聚合 |
| 聚合表的 count(*) | 數的是組數，要 sum(views) |
| 平均值再平均 | 每組筆數不同時結果錯誤 |
| 唯一索引沒有時間欄位 | 建不起來 |
| segmentby 選了值很多的欄位 | 壓縮率很差 |
| 用 DELETE 刪舊資料 | 慢、產生死資料；用 drop_chunks / 保留政策 |
| chunk 切太小 | chunk 太多，規劃與沒有時間條件的查詢都變慢 |

## 9. ★ 面試題速答

| 問題 | 重點 |
|---|---|
| TimescaleDB 是什麼？ | PostgreSQL 的時序擴充：hypertable 依時間自動分 chunk，加上時間函式、連續聚合、壓縮、保留政策 |
| 為什麼資料越來越多，查最近的資料還是快？ | chunk exclusion：只讀相關時間範圍的 chunk；最近的 chunk 和索引在記憶體裡 |
| chunk 大小怎麼決定？ | 最近一個 chunk 能放進記憶體的 25%；依寫入量調整 chunk_time_interval |
| 連續聚合跟物化視圖差在哪？ | 增量更新（只算變動的部分）、可以排程、可以即時合併最新資料 |
| 壓縮怎麼設定？ | segmentby 放常過濾、值少的欄位，orderby 放時間；只壓縮舊 chunk |
| 舊資料怎麼處理？ | 壓縮 → 降精度（連續聚合）→ drop_chunks / 保留政策 |
| TimescaleDB vs InfluxDB？ | TimescaleDB 是 SQL、可以 JOIN 關聯資料、PostgreSQL 生態系；InfluxDB 專門為指標設計，寫入與 tag 模型更簡單 |
| 什麼時候不需要 TimescaleDB？ | 資料量小（幾百萬筆以內）、不需要依時間大量彙總，PostgreSQL 加上 BRIN 或原生分區就夠 |

## 10. Java / Spring

- 就是 PostgreSQL：JDBC URL、JPA、JdbcTemplate、Flyway 都照用；hypertable 在 Flyway migration 裡用 `SELECT create_hypertable(…)` 建立
- JPA 實體的主鍵要包含時間欄位（複合主鍵 `@IdClass` / `@EmbeddedId`），或不在 hypertable 上用 JPA，改用 JdbcTemplate
- 大量寫入：`reWriteBatchedInserts=true` + JDBC batch，或 PostgreSQL 的 `CopyManager`（COPY）
- time_bucket 這類函式在 JPQL 不能直接用：用原生 SQL（`@Query(nativeQuery = true)`）或 JdbcTemplate

# pgvector

> 範例都來自練習環境（`pg-lab` 容器的 `vectors` 資料庫，port 5434），可以直接貼到展示台的「SQL 主控台」執行。products 是 1,500 件商品（64 維），`embed(文字)` 是用詞庫做的迷你嵌入模型；passages 是 10 萬筆 128 維的模擬文件片段（索引實驗用）。

## 1. ★ 基本觀念

- **嵌入（embedding）**：模型把文字、圖片轉成一串數字（向量），意思相近的東西向量也相近；常見 384～3072 維
- **向量搜尋**：拿查詢的向量，找「距離最近的 k 筆」（k-nearest neighbors），用來做語意搜尋、推薦、去重、分類、RAG
- **pgvector = PostgreSQL 的擴充**：加上 `vector` 型別、距離運算子、向量索引（HNSW、IVFFlat）；向量跟一般欄位放在同一張表，可以 WHERE、JOIN、交易
- **RAG（Retrieval-Augmented Generation）**：文件切段 → 每段嵌入存進資料庫 → 問題也嵌入 → 找最近的幾段 → 連同問題交給 LLM 回答

| | pgvector | 專用向量資料庫（Pinecone、Milvus、Qdrant、Weaviate） |
|---|---|---|
| 過濾、JOIN | 一般 SQL，跟關聯資料一起查 | 只能用另外存的 metadata 過濾 |
| 交易、備份、權限 | PostgreSQL 現成的 | 各自的機制 |
| 規模 | 單機千萬筆等級沒問題；更大要分區、讀取副本 | 為十億筆、分散式設計 |
| 維運 | 不用多一套系統 | 多一套服務（或付費 SaaS） |

★ 面試結論：已經在用 PostgreSQL、資料量在千萬筆以內，先用 pgvector；需要超大規模、極低延遲或多模態的進階功能，再考慮專用資料庫。

## 2. 型別、運算子、函式

```sql
CREATE EXTENSION vector;
CREATE TABLE products (id int PRIMARY KEY, name text, embedding vector(64));   -- 宣告維度，維度不對寫不進去
INSERT INTO products VALUES (1, 'x', '[0.1, 0.2, …]');

SELECT id FROM products ORDER BY embedding <=> '[…]' LIMIT 5;                  -- 最近的 5 筆
SELECT 1 - (a.embedding <=> b.embedding) AS cosine_similarity FROM …;
SELECT avg(embedding), sum(embedding) FROM products;                            -- 向量也能聚合
SELECT vector_dims(embedding), vector_norm(embedding), l2_normalize(embedding);
SELECT subvector(embedding, 1, 16), embedding::halfvec, binary_quantize(embedding);
```

| 運算子 | 距離 | 索引的 operator class | 說明 |
|---|---|---|---|
| `<->` | L2（歐幾里得） | `vector_l2_ops` | 看長度與方向 |
| `<=>` | cosine 距離 = 1 - cosine 相似度 | `vector_cosine_ops` | 只看方向，範圍 0～2 |
| `<#>` | ★ **負的**內積 | `vector_ip_ops` | 越小越像；向量長度都是 1 時最快 |
| `<+>` | L1（曼哈頓） | `vector_l1_ops` | 0.7 起 |
| `<~>`、`<%>` | Hamming、Jaccard | `bit_hamming_ops`、`bit_jaccard_ops` | bit 型別 |

| 型別 | 每維 | 索引上限 | 用途 |
|---|---|---|---|
| `vector` | 4 bytes（float4） | 2,000 維 | 預設 |
| `halfvec` | 2 bytes（float2） | 4,000 維 | 空間減半，召回率幾乎不變 |
| `bit` | 1 bit | 64,000 維 | binary quantization |
| `sparsevec` | 只存非零值 | 1,000 個非零值 | 稀疏向量（SPLADE、BM25 類） |

- ★ 向量都正規化成長度 1 時，cosine、L2、內積排出來的名次一樣（OpenAI 等模型的輸出已經是長度 1），可以選內積（最快）
- ★ 所有運算子都是「越小越近」：索引只支援由小到大的排序，所以內積取負號
- 同一個欄位只能放同一個模型產生的向量；不同模型的向量不能互相比較

## 3. ★ 語意搜尋

```sql
-- 文字 → 向量：真實系統是應用程式呼叫嵌入模型 API；這裡用 embed()
SELECT id, name FROM products ORDER BY embedding <=> embed('通勤 安靜') LIMIT 5;   -- 找到主動降噪耳機

-- 跟一般條件一起用
SELECT id, name, price FROM products
WHERE price <= 1000 AND category IN ('男裝', '女裝')
ORDER BY embedding <=> embed('冬天 保暖') LIMIT 5;

-- 相似商品（記得排除自己）
SELECT id, name FROM products WHERE id <> 806
ORDER BY embedding <=> (SELECT embedding FROM products WHERE id = 806) LIMIT 5;

-- 每一筆各找 k 個鄰居：LATERAL
SELECT s.id, n.id FROM products s
CROSS JOIN LATERAL (SELECT p.id FROM products p WHERE p.id <> s.id ORDER BY p.embedding <=> s.embedding LIMIT 2) n;

-- 推薦：使用者向量 = 買過商品的平均
WITH taste AS (SELECT avg(p.embedding) AS v FROM purchases u JOIN products p ON p.id = u.product_id WHERE u.customer_id = 224)
SELECT p.id FROM products p, taste WHERE p.id NOT IN (…買過的…) ORDER BY p.embedding <=> taste.v LIMIT 5;

-- k-NN 分類：最近的 15 件投票
SELECT category, count(*) FROM (SELECT category FROM products ORDER BY embedding <=> embed('上班 通勤') LIMIT 15) s
GROUP BY category ORDER BY count(*) DESC;
```

- 語意搜尋強在「意思相近」（同義詞、換個說法），弱在專有名詞：品牌、型號、料號模型常不認得或弄錯 → 用關鍵字或混合搜尋
- 距離門檻要看資料決定：高維空間裡不相關的東西 cosine 相似度在 0 附近，相關的不一定到 0.8
- RAG 的品質大多取決於「切段（chunking）」：太長混進不相關內容、太短失去上下文；常見 200～800 tokens、相鄰段落重疊一些

## 4. ★ 向量索引：HNSW vs IVFFlat

```sql
CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops);                          -- m = 16, ef_construction = 64
CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops) WITH (m = 32, ef_construction = 128);
SET hnsw.ef_search = 100;                                                                    -- 預設 40

CREATE INDEX ON passages USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);     -- 資料載入「之後」再建
SET ivfflat.probes = 10;                                                                     -- 預設 1

SET maintenance_work_mem = '2GB';                -- ★ 圖放得進記憶體，建 HNSW 才快
SET max_parallel_maintenance_workers = 7;        -- 平行建索引（Docker 預設 /dev/shm 只有 64 MB，要加大 shm_size）
```

實測（10 萬筆 128 維，100 個查詢取平均，召回率 = 跟精確搜尋的前 10 名比）：

| 做法 | 召回率 | 每次查詢 | 建索引 | 索引大小 |
|---|---|---|---|---|
| 精確搜尋（不用索引） | 100% | 19 ms | — | — |
| HNSW，ef_search = 10 | 94% | 1.2 ms | 38 秒 | 79 MB |
| HNSW，ef_search = 40（預設） | 99% | 1.3 ms | | |
| HNSW，ef_search = 200 | 100% | 2.9 ms | | |
| IVFFlat lists = 100，probes = 1 | 45% | 0.9 ms | 0.8 秒 | 53 MB |
| IVFFlat lists = 100，probes = 10 | 81% | 2.7 ms | | |
| IVFFlat lists = 100，probes = 30 | 95% | 6.6 ms | | |
| IVFFlat lists = 1000，probes = 1 | 88% | 0.9 ms | 9 秒 | 56 MB |

| | HNSW | IVFFlat |
|---|---|---|
| 原理 | 多層的鄰居圖，從上層往更近的鄰居走 | k-means 分成 lists 群，只看最近的 probes 群 |
| 查詢參數 | `hnsw.ef_search`（也是最多回傳幾筆） | `ivfflat.probes` |
| 建索引參數 | `m`、`ef_construction` | `lists`（建議 筆數 / 1000，百萬筆以上 √筆數） |
| 召回率 / 速度 | 較好 | 同樣召回率時較慢 |
| 建索引 | 慢、吃記憶體 | 快 |
| 空資料表就建 | 可以（邊寫入邊建圖） | 不行：分群依建索引當下的資料，資料變多要重建 |

- ★ 都是**近似**最近鄰（ANN），上線前用自己的資料量測召回率，再調 ef_search / probes
- ★ 用得到索引的三個條件：`ORDER BY 欄位 運算子 值`（由小到大）＋ `LIMIT` ＋ 運算子跟 operator class 一致
- 小資料表（幾萬筆以內）精確搜尋就夠快，不一定要建向量索引；精確搜尋召回率永遠 100%
- 查詢參數用 `SET LOCAL` 只影響目前的交易，適合「這一句要特別準」的情況

## 5. ★ 過濾與多租戶

```sql
SELECT id FROM passages WHERE tenant_id = 7 ORDER BY embedding <=> $1 LIMIT 10;   -- ★ 可能不到 10 筆

SET hnsw.iterative_scan = relaxed_order;     -- 0.8 起：候選不夠時繼續往下找（strict_order 保證依距離排序）
SET hnsw.max_scan_tuples = 20000;            -- 最多掃幾筆（預設 2 萬）
SET ivfflat.iterative_scan = relaxed_order;  -- IVFFlat 也有（ivfflat.max_probes）

CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops) WHERE tenant_id = 7;   -- 大租戶的部分索引
```

- ★ **post-filtering**：HNSW 先找 ef_search（40）個候選，之後才套用 WHERE；租戶 7 只佔 2%，實測平均只回傳 0.7 筆，而且不會報錯
- 實測（租戶 7）：iterative_scan = relaxed_order 一定湊滿 10 筆、召回率 77%、8 ms；ef_search = 1000 召回率 89%、12 ms；精確搜尋 100%、10 ms
- 過濾後剩下的資料不多時，精確搜尋又準又快（`WITH t AS MATERIALIZED (SELECT … WHERE tenant_id = 7) SELECT … FROM t ORDER BY … LIMIT 10`）
- 租戶很多、每個都大：依 tenant_id 分區（`PARTITION BY LIST`），每個分區各自有 HNSW 索引

## 6. 量化與降維

```sql
CREATE INDEX ON passages USING hnsw ((embedding::halfvec(128)) halfvec_cosine_ops);
SELECT id FROM passages ORDER BY embedding::halfvec(128) <=> $1::halfvec(128) LIMIT 10;   -- 要寫成同樣的運算式

CREATE INDEX ON passages USING hnsw ((binary_quantize(embedding)::bit(128)) bit_hamming_ops);
SELECT id FROM (                                                  -- bit 撈候選 → 原始向量重排
  SELECT id, embedding FROM passages ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize($1) LIMIT 100
) c ORDER BY embedding <=> $1 LIMIT 10;
```

| 索引（10 萬筆 128 維） | 大小 | 召回率 |
|---|---|---|
| vector | 79 MB | 99% |
| halfvec | 54 MB | 99.9% |
| bit | 30 MB | 42% |
| bit 撈 100 筆 → 重排 | 30 MB | 95% |

- 1536 維 × 1000 萬筆 = 60 GB 的向量，HNSW 索引再一份；量化用精度換空間
- ★ halfvec 幾乎沒有損失；3072 維的模型（超過 vector 索引的 2,000 維上限）一定要用 halfvec 建索引
- binary quantization 適合 1,000 維以上的模型，一定要搭配重排
- 降維：Matryoshka 類模型（OpenAI text-embedding-3）可以直接取前 256 / 512 維（`subvector`，記得重新正規化）

## 7. 混合搜尋（hybrid search）

```sql
WITH semantic AS (
  SELECT id, row_number() OVER (ORDER BY embedding <=> embed('Sony 降噪')) AS r
  FROM products ORDER BY embedding <=> embed('Sony 降噪') LIMIT 50
), keyword AS (
  SELECT id, row_number() OVER (ORDER BY ts_rank(tsv, query) DESC) AS r
  FROM products, plainto_tsquery('simple', 'Sony 降噪') query WHERE tsv @@ query LIMIT 50
)
SELECT coalesce(s.id, k.id) AS id,
       coalesce(1.0 / (60 + s.r), 0) + coalesce(1.0 / (60 + k.r), 0) AS rrf
FROM semantic s FULL JOIN keyword k ON k.id = s.id
ORDER BY rrf DESC LIMIT 10;
```

- 語意搜尋負責「意思相近」，關鍵字（全文檢索 / BM25）負責專有名詞、型號、精確字串；兩邊都做再合併
- ★ **RRF（Reciprocal Rank Fusion）**：每份名單排第 r 名得 1 / (60 + r) 分，加總排序；只看名次，不用管兩邊分數的單位不同
- 中文全文檢索要處理斷詞（pg_bigm、zhparser），或關鍵字那一邊交給 Elasticsearch
- 進一步：取前 50 筆交給 reranker（cross-encoder）模型重新排序

## 8. 寫入與維護

- 向量是從文字算出來的衍生資料：★ **文字改了要重算向量**；換模型要全部重算，所以要記錄每筆向量的模型與版本
- 嵌入很慢又要花錢：批次呼叫、非同步（寫入文字後由背景工作補向量）、只對變動的資料重算
- HNSW 會在 INSERT 時更新，大量匯入時「先匯入、再建索引」比較快；IVFFlat 一定要在資料載入後才建
- UPDATE / DELETE 會在索引留下死資料，靠 VACUUM 清掉；大量變動後 `REINDEX INDEX CONCURRENTLY`
- 一筆 1536 維就有 6 KB，超過 2 KB 會被 TOAST；`SELECT *` 會把向量一起傳回來，查詢時只選需要的欄位

## 9. 常見陷阱

| 陷阱 | 說明 |
|---|---|
| `<#>` 由大到小排 | `<#>` 是負內積，DESC 找到的是最不像的 |
| `ORDER BY 1 - 距離 DESC` | 結果一樣，但用不到索引 |
| 運算子跟索引不一致 | cosine 索引配 `<->`：整張表掃描 |
| 沒有 LIMIT | 用不到索引 |
| 只有距離門檻 `WHERE dist < 0.3` | 用不到索引，要 ORDER BY … LIMIT 再過濾 |
| 過濾條件很嚴格 | HNSW 回傳不足 k 筆，不會報錯 |
| 相似商品沒排除自己 | 第一名是自己（距離 0） |
| 模型不認得的文字 | embed() 回傳 NULL / 不準的向量，結果看起來有答案但是錯的 |
| 平均向量 | 長度小於 1，拿去跟固定門檻比要先 l2_normalize |
| IVFFlat 建在空表 | 分群沒有意義，召回率很差；資料載入後再建 |
| HNSW ef_search 小於 LIMIT | 最多只回傳 ef_search 筆 |
| 建 HNSW 很慢 | maintenance_work_mem 太小（圖放不進記憶體） |
| 文字改了沒重算向量 | 搜尋結果跟內容對不上 |

## 10. ★ 面試題速答

| 問題 | 重點 |
|---|---|
| 向量搜尋是什麼？ | 把資料嵌入成向量，找距離最近的 k 筆；用在語意搜尋、推薦、去重、RAG |
| cosine、L2、內積怎麼選？ | 照模型建議；向量都正規化時三者名次一樣，用內積最快 |
| HNSW vs IVFFlat？ | HNSW 召回率與速度較好、可邊寫入邊建，但建得慢、吃記憶體；IVFFlat 建得快、較小，要資料載入後才建 |
| 召回率怎麼調？ | HNSW 調 ef_search（建索引時 m、ef_construction）；IVFFlat 調 probes、lists；用自己的資料量測 |
| 加上 WHERE 結果變少？ | post-filtering；iterative scan、提高 ef_search、部分索引、分區，或過濾後精確搜尋 |
| 向量太佔空間？ | halfvec（減半、幾乎無損）、binary quantization + 重排、降維 |
| 語意搜尋找不到型號？ | 混合搜尋：全文檢索 + 向量，RRF 合併 |
| pgvector 還是專用向量資料庫？ | 千萬筆以內、需要跟關聯資料一起查 → pgvector；十億筆、分散式 → 專用資料庫 |
| RAG 的流程？ | 切段 → 嵌入 → 存向量 → 問題嵌入 → 找最近的段落（+ 過濾、重排）→ 交給 LLM |
| 換嵌入模型要做什麼？ | 全部重新嵌入、重建索引；新舊模型的向量不能混用（可以先寫新欄位，切換後刪舊的） |

## 11. Java / Spring

- JDBC：`com.pgvector:pgvector` 套件，`PGvector.addVectorType(conn)` 後用 `new PGvector(float[])` 當參數；或直接傳字串 `?::vector`
- Hibernate 6.4+：`hibernate-vector` 模組，`@JdbcTypeCode(SqlTypes.VECTOR) @Array(length = 1536) float[] embedding`
- ★ Spring AI：`PgVectorStore`（starter：`spring-ai-starter-vector-store-pgvector`），設定前綴 `spring.ai.vectorstore.pgvector`：`index-type=HNSW`、`distance-type=COSINE_DISTANCE`、`dimensions`
- `vectorStore.add(documents)`：呼叫 EmbeddingModel 嵌入後寫入；查詢 `vectorStore.similaritySearch(request)`，request 用 `SearchRequest.builder()` 設定 `query("…")`、`topK(5)`、`filterExpression("tenant == 7")`
- 嵌入呼叫要批次、要重試、要限流；向量欄位不要在列表 API 裡 SELECT 出來（很大）

# Elasticsearch

> 範例都來自練習環境（`es-lab` 容器，http://localhost:9201，Elasticsearch 8.17 單節點），可以直接貼到展示台的「Dev Tools 主控台」或 Kibana Dev Tools 執行。products（1,500 件商品）、reviews（2 萬多則中文評論）、orders（8 萬筆訂單，明細是 nested）、logs（9 月的 API 存取紀錄，20 萬筆）。

## 1. ★ 基本觀念

- **倒排索引（inverted index）**：把每份文件切成「詞」，建立「詞 → 哪些文件有這個詞（與位置）」的對照表；查詢時直接用詞找文件，不必掃描全部
- 每個欄位依型別用不同的結構：text → 倒排索引；keyword、數字、日期 → 倒排索引 + **doc values**（依欄位存放，給排序、聚合用）；數字、日期另有 BKD 樹（範圍查詢）
- **近即時（near real-time）**：寫入後要等 refresh（預設 1 秒）才搜得到
- 底層是 Lucene：一個**分片（shard）**就是一個 Lucene 索引，由許多不可修改的 **segment** 組成

| 關聯式資料庫 | Elasticsearch |
|---|---|
| 資料表 | 索引（index） |
| 一列 | 文件（document，JSON） |
| 欄位 | 欄位（field） |
| schema | mapping |
| SQL | Query DSL（JSON）；也有 SQL API（`POST /_sql`） |
| B-tree 索引 | 倒排索引、doc values、BKD 樹（每個欄位預設都有索引） |
| JOIN | 幾乎沒有：反正規化、nested、join 欄位（parent-child） |
| 交易 | 沒有：只有單一文件的原子寫入、樂觀鎖 |

★ 面試結論：Elasticsearch 是**搜尋與分析引擎**，不是主要的資料庫。常見架構：PostgreSQL / MySQL 存正本，同步一份到 Elasticsearch 做全文檢索、篩選、聚合（商品搜尋、站內搜尋、日誌分析）。

## 2. ★ 分析器（analyzer）

```es
POST /_analyze
{ "analyzer": "standard", "text": "Sony 無線耳機，降噪效果很棒" }
# → sony、無、線、耳、機、降、噪、效、果、很、棒（中文一字一詞）

POST /_analyze
{ "analyzer": "cjk", "text": "Sony 無線耳機，降噪效果很棒" }
# → sony、無線、線耳、耳機、降噪、噪效、效果、果很、很棒（兩字一組）

POST /products/_analyze
{ "field": "name", "text": "主動降噪耳機" }          # 用欄位設定的分析器
```

- 分析器 = 字元過濾器（char_filter，例如去掉 HTML）→ **切詞器（tokenizer）** → 詞過濾器（filter，例如小寫、同義詞、詞幹還原、停用詞）
- ★ 寫入時與搜尋時要用相容的分析器，詞才對得上；可以分開設定 `analyzer`（寫入）與 `search_analyzer`（搜尋）
- 中文：standard 一字一詞（「音質」= 音 OR 質，雜訊多）；**cjk** 兩字一組（不用外掛，召回率高）；**IK、smartcn、jieba** 真的斷詞（要裝外掛）
- 實測（評論）：match「音質」用 cjk 找到 695 則，用 standard 找到 4,509 則（混進「品質」「肉質」）
- 同義詞放在 search_analyzer（`synonym_graph`）：改同義詞表不用重建索引
- 分析器在建索引時決定，之後改要建新索引、reindex

## 3. Mapping 與型別

```es
PUT /products
{
  "mappings": {
    "dynamic": "strict",                                       # 沒定義的欄位直接拒絕
    "properties": {
      "name":     { "type": "text", "analyzer": "cjk",
                    "fields": { "keyword": { "type": "keyword" } } },   # multi-field：同一個值兩種索引
      "brand":    { "type": "keyword" },
      "price":    { "type": "integer" },
      "created_at": { "type": "date" },
      "items":    { "type": "nested", "properties": { … } }
    }
  }
}
GET /products/_mapping
```

| 型別 | 用途 |
|---|---|
| `text` | 全文檢索（會分析）；不能排序、聚合 |
| `keyword` | 精確比對、排序、聚合（品牌、狀態、標籤、ID） |
| `integer` / `long` / `float` / `scaled_float` | 數值、範圍查詢；金額可用 scaled_float |
| `date` | 存成 UTC 毫秒；查詢時注意時區 |
| `boolean`、`ip`、`geo_point` | 布林、IP（可以查網段）、經緯度（距離查詢） |
| `object` / `nested` | 物件；陣列裡的物件要「同一個物件」比對時用 nested |
| `dense_vector` | 向量（kNN 搜尋，跟 pgvector 同類的功能） |

- ★ **動態 mapping**：沒定義的欄位第一次出現時自動推斷（數字 → long、字串 → text + keyword），之後型別就固定；第一份是數字、第二份是 "10A" → 第二份被拒絕
- ★ 已存在的欄位**不能改型別**：建新索引 → `_reindex` → 用**別名（alias）**切換；程式一律透過 alias 存取
- 正式環境事先定義 mapping 或 **index template**；欄位太多（mapping explosion）會拖垮叢集

## 4. ★ Query DSL

```es
GET /products/_search
{
  "query": {
    "bool": {
      "must":     [{ "match": { "description": "輕薄" } }],             # 要符合、計分
      "filter":   [{ "term": { "category": "筆電" } },                  # 要符合、不計分、可快取
                   { "range": { "price": { "gte": 20000, "lte": 40000 } } }],
      "should":   [{ "term": { "tags": "熱銷" } }],                     # 加分
      "must_not": [{ "term": { "brand": "Apple" } }]                   # 排除
    }
  },
  "sort": [{ "_score": "desc" }, { "price": "asc" }],
  "from": 0, "size": 10,
  "_source": ["name", "price"],
  "highlight": { "fields": { "description": {} } }
}

GET /reviews/_count
{ "query": { "match_phrase": { "content": "通勤戴很舒服" } } }
```

| 查詢 | 說明 |
|---|---|
| `match` | 全文：先分析，再找含有這些詞的文件；預設 OR（`operator: and`、`minimum_should_match`） |
| `match_phrase` | 詞要依序相鄰（用位置判斷）；`slop` 容許間隔 |
| `multi_match` | 多個欄位，`"fields": ["name^3", "description"]` 加權；best_fields / most_fields / cross_fields |
| `term` / `terms` | 精確比對（不分析），用在 keyword、數字 |
| `range` | 數值、日期範圍（`gte`、`lt`，日期可寫 `now-7d/d`） |
| `exists` | 欄位有值 |
| `fuzzy` / `fuzziness: "AUTO"` | 容許拼錯（編輯距離） |
| `prefix` / `wildcard` / `regexp` | 開頭是…；前面有萬用字元的很慢 |
| `nested` | 對 nested 欄位查詢 |
| `function_score` | 依欄位值、距離、腳本調整分數 |

- ★ **query context vs filter context**：must / should 計分；filter / must_not 不計分、結果可以快取 → 不需要相關性的條件一律放 filter
- ★ text 欄位用 match；keyword、數字、日期用 term / range。對 text 用 term 常常找不到（存的是小寫、切開的詞）
- 實測：match「通勤戴很舒服」找到 2,756 則（任何一個 bigram 就算），match_phrase 只有 266 則
- `hits.total` 預設只精確算到 10,000（`relation: gte`）；要精確：`track_total_hits: true` 或 `_count`

## 5. ★ 相關性：BM25

- 分數 ≈ **IDF**（越少文件有這個詞越高）× **TF**（出現次數，會飽和，參數 k1 = 1.2）× **欄位長度調整**（越短越高，參數 b = 0.75）
- 實測：「降噪」（404 則有）第一名 4.3 分，「很好」（8 千多則有）第一名只有 1.5 分
- `"explain": true` 看分數怎麼算；分數只能在同一個查詢裡比較名次，不能當門檻
- 調整排序：欄位權重（`^3`）、should 加分、`function_score`（評分、銷量、新舊、距離）、`rescore`
- 舊版（5.0 以前）預設是 TF-IDF；BM25 對高頻詞與長文件的處理比較合理

## 6. ★ 聚合（aggregations）

```es
GET /orders/_search
{
  "size": 0,                                                      # 只要聚合，不要文件
  "query": { "range": { "order_date": { "gte": "2026-09-01T00:00:00+08:00" } } },
  "aggs": {
    "per_day": {
      "date_histogram": { "field": "order_date", "calendar_interval": "day", "time_zone": "+08:00" },
      "aggs": { "revenue": { "sum": { "field": "total" } } }
    },
    "by_city": { "terms": { "field": "shipping_city", "size": 5 } },
    "p95": { "percentiles": { "field": "total", "percents": [95] } },
    "customers": { "cardinality": { "field": "customer_id" } }
  }
}
```

| 類型 | 聚合 | SQL 對照 |
|---|---|---|
| bucket（分組） | `terms`、`date_histogram`、`histogram`、`range`、`filters`、`composite` | GROUP BY |
| metric（計算） | `avg`、`sum`、`min`、`max`、`stats`、`percentiles`、`cardinality`、`top_hits` | 聚合函式 |
| pipeline（對結果再算） | `bucket_sort`、`derivative`、`cumulative_sum`、`moving_fn` | 視窗函數 |

- 聚合用 doc values：text 欄位不能聚合（fielddata 預設關閉），用 keyword 或 `.keyword` 子欄位
- ★ 近似值：`terms` 在多分片時可能漏算（`doc_count_error_upper_bound`）、`cardinality` 是 HyperLogLog++、`percentiles` 是 TDigest
- ★ date_histogram 沒給 `time_zone` 依 UTC 切（台灣的一天從早上 8 點開始）
- 要翻完所有分組（匯出）用 `composite` + `after`

## 7. 關聯資料：nested、object、parent-child

```es
GET /orders/_count
{
  "query": { "nested": { "path": "items", "query": { "bool": { "filter": [
    { "term": { "items.category": "手機" } },
    { "range": { "items.quantity": { "gte": 2 } } }
  ] } } } }
}
```

- ★ **object 陣列會被攤平**：items.category = [手機, 男裝]、items.quantity = [1, 3]，條件來自不同明細也會符合（實測 object 6,884 筆，nested 正確答案 3,465 筆）
- **nested**：每個物件是一份隱藏的 Lucene 文件（orders 8 萬筆、明細 20 萬 → `_cat/indices` 顯示 28 萬份）；查詢、聚合要用 nested / reverse_nested；更新一個明細要整份重寫
- **join 欄位（parent-child）**：父子各自是文件、可以分開更新，但查詢慢、要在同一個分片（routing）
- ★ 首選**反正規化**：把需要搜尋的欄位直接放進文件（訂單裡放商品名稱、類別），資料變了再重建

## 8. 寫入與近即時

```es
PUT  /scratch/_doc/1            { … }             # 指定 _id：新增或整份覆蓋
POST /scratch/_doc              { … }             # 自動產生 _id
PUT  /scratch/_create/1         { … }             # 已存在就失敗
POST /scratch/_update/1         { "doc": { "price": 2990 } }
POST /scratch/_update_by_query  { "query": …, "script": { "source": "ctx._source.tags.add(params.t)", "params": { "t": "週年慶" } } }
POST /scratch/_delete_by_query  { "query": … }
POST /_bulk                     （NDJSON：一行動作、一行內容）
PUT  /scratch/_doc/1?if_seq_no=5&if_primary_term=1   { … }   # 樂觀鎖：被改過就 409
```

- 寫入流程：記憶體 buffer + **translog**（防遺失）→ **refresh**（變成可搜尋的 segment，預設每 1 秒）→ **flush**（fsync 到磁碟、清 translog）→ **merge**（合併 segment、清掉已刪除的文件）
- ★ 搜尋要等 refresh；`GET /_doc/id` 是即時的。需要寫完就搜得到：`?refresh=wait_for`（不要每次 `refresh=true`）
- ★ segment 不能修改：**更新 = 標記刪除 + 重新寫入**、刪除只是標記 → 頻繁更新同一份文件（計數器）不適合
- `_bulk` 不是交易：每個動作各自成功或失敗，要檢查回應的 `errors` 與每個 `items`
- 大量匯入：`refresh_interval: -1`、`number_of_replicas: 0`，匯入完再改回來
- 刪舊資料不要用 delete_by_query：依時間切索引，直接刪整個索引

## 9. ★ 分片與叢集

- 索引分成多個 **primary shard**（建立後不能改，只能 `_split` / `_shrink` 或 reindex）；每個 primary 有 **replica**（可以隨時改）
- 文件放在哪個分片：`hash(_routing 或 _id) % 分片數` → 這就是分片數不能改的原因
- 搜尋是 **query then fetch**：每個分片各自找出前 from + size 名 → 協調節點合併排序 → 再去拿文件內容
- 叢集健康：**green**（全部都分配好）/ **yellow**（有 replica 沒地方放，例如單節點）/ **red**（有 primary 不見，資料不完整）
- 節點角色：master（管理叢集狀態，要奇數個避免 split brain）、data（hot / warm / cold）、ingest、coordinating
- 分片大小建議 10～50 GB；太多小分片（oversharding）浪費記憶體、拖慢 master
- replica 提高可用性與讀取吞吐量，但寫入要寫每一份

## 10. 分頁與大量讀取

```es
GET /logs/_search
{ "size": 100, "sort": [{ "@timestamp": "asc" }, { "trace_id": "asc" }],
  "search_after": [1788192208672, "722ada4508737385"] }        # 上一頁最後一筆的 sort 值

POST /logs/_pit?keep_alive=1m                                  # point in time：固定快照
```

- ★ `from + size` 最多 10,000（`index.max_result_window`）：每個分片都要排出前 from + size 名，越後面越貴
- 深分頁用 **search_after**（+ PIT 讓翻頁過程結果不變）；排序最後一定要有唯一欄位
- scroll 用於大量匯出（已不建議，改用 PIT + search_after）

## 11. 日誌與時間序列：data stream、ILM

- 日誌依時間切索引（logs-2026.09.18），或用 **data stream**（自動 rollover 的一組隱藏索引，只能新增）
- **ILM**（Index Lifecycle Management）：hot（新資料、SSD）→ warm（唯讀、forcemerge）→ cold / frozen（便宜的儲存）→ delete
- ELK / Elastic Stack：Beats / Logstash 收集 → Elasticsearch 存放 → Kibana 查詢、儀表板；OpenSearch 是 AWS 分支出來的開源版本

## 12. 常見陷阱

| 陷阱 | 說明 |
|---|---|
| 對 text 欄位用 term | 存的是分析後的詞（小寫、切開），找不到 |
| match 一整句 | 預設 OR，任何一個詞符合就算，結果很多 |
| 中文用 standard 分析器 | 一字一詞，「音質」會找到「品質」 |
| object 陣列 | 條件會跨物件交叉比對，要用 nested |
| hits.total 是 10000 | 預設只精確算到一萬，`relation: gte` |
| date_histogram 沒給時區 | 依 UTC 切，每天的數字都錯 |
| 對 text 欄位聚合、排序 | 報錯：fielddata is disabled，用 keyword |
| from 10000 | Result window is too large，用 search_after |
| 動態 mapping | 第一份文件決定型別，之後寫不進去 |
| 改欄位型別 | 不能改，建新索引 + reindex + alias |
| 寫完馬上搜尋 | 近即時，要等 refresh |
| 分片數設太多 / 太少 | 建立後不能改；太多浪費資源，太少無法水平擴充 |
| 把 Elasticsearch 當主資料庫 | 沒有交易、mapping 不能改、可能遺失最近的寫入（取決於設定） |

## 13. ★ 面試題速答

| 問題 | 重點 |
|---|---|
| 為什麼搜尋快？ | 倒排索引：用詞直接找文件；doc values 給排序聚合；分片平行處理 |
| text 和 keyword 差在哪？ | text 會分析，給全文檢索；keyword 不分析，給精確比對、排序、聚合 |
| 相關性怎麼算？ | BM25：IDF、TF（會飽和）、欄位長度；可用 boost、function_score 調整 |
| query 和 filter 差在哪？ | query 計分；filter 不計分、可以快取，比較快 |
| 為什麼寫入後搜不到？ | 近即時：refresh（預設 1 秒）後才變成可搜尋的 segment |
| 分片數怎麼決定？ | 依資料量（每個分片 10～50 GB）與節點數；primary 數建立後不能改 |
| 叢集變 yellow / red？ | yellow：replica 沒分配（單節點）；red：primary 不見，資料不完整 |
| 怎麼跟資料庫同步？ | 應用程式雙寫（可能不一致）、CDC（Debezium → Kafka → Elasticsearch）、定期全量重建；以資料庫為準 |
| 深分頁怎麼做？ | search_after + PIT；from + size 限制 10,000 |
| nested 和 object？ | object 陣列會攤平，跨物件交叉比對；nested 保留物件邊界，但成本高 |
| Elasticsearch 和資料庫的 LIKE 比？ | LIKE '%詞%' 要全表掃描、沒有相關性排序、不懂斷詞；ES 用倒排索引、BM25、分析器 |
| Elasticsearch 和 pgvector？ | ES 擅長關鍵字（BM25）與聚合，也有 dense_vector 做 kNN；混合搜尋常兩者並用 |

## 14. Java / Spring

- 官方 **Elasticsearch Java API Client**（`co.elastic.clients:elasticsearch-java`）：型別安全的 builder，`client.search(s -> s.index("products").query(q -> q.match(m -> m.field("name").query("耳機"))), Product.class)`
- **Spring Data Elasticsearch**：`@Document(indexName = "products")`、`@Field(type = FieldType.Text, analyzer = "cjk")`、`ElasticsearchRepository<Product, String>`（衍生查詢 `findByBrand`）、複雜查詢用 `NativeQuery`
- ★ 同步策略：交易 commit 之後才送出（`@TransactionalEventListener(phase = AFTER_COMMIT)`），失敗要能重試；大量資料用 CDC
- 批次寫入用 `BulkIngester`；查詢記得設 timeout；不要把 Elasticsearch 的連線池開太大

# InfluxDB

> 範例都來自練習環境（`influx-lab` 容器，http://localhost:8087，InfluxDB 2.7，組織 shop、bucket metrics），可以直接貼到展示台的「主控台」執行。cpu、mem（5 台主機，每分鐘）、http（API 請求的累計計數器）、sensors（倉庫溫溼度，每 5 分鐘）都是 2026 年 9 月；orders 從 PostgreSQL 複製。

## 1. ★ 基本觀念

- **point（點）**= measurement + tag set + field set + 時間戳記：`cpu,host=db-01,role=db usage_user=35.8,usage_system=10.2 1790783940`
- **measurement** ≈ 資料表名稱；**tag** 有索引、值一律是字串（用來篩選、分組）；**field** 沒有索引、存量測值（浮點數、整數、字串、布林）
- **series**：measurement + tag set（+ field）決定一條時間序列，各自依時間存放、壓縮。cpu 有 5 台主機 → 5 條 series
- 2.x 的組織方式：**organization** → **bucket**（資料庫 + 保留時間）；權限綁在 **token** 上（沒有角色，每個 token 列出可以讀 / 寫哪些 bucket）
- 查詢語言：**InfluxQL**（類似 SQL，1.x 起就有，2.x 要先建立 DBRP 對應）、**Flux**（2.x 的管線式語言，已不再開發新功能）；3.x 改成 **SQL** + InfluxQL

| 關聯式資料庫 / TimescaleDB | InfluxDB |
|---|---|
| 資料表 | measurement |
| 有索引的欄位（主機、地區） | tag（一律是字串） |
| 一般欄位（數值） | field |
| 一列 | point |
| 資料庫 + 保留政策 | bucket（2.x）、database + retention policy（1.x） |
| JOIN、子查詢、視窗函數 | InfluxQL 沒有 JOIN；Flux 可以 join |
| UPDATE | 沒有：同一個 series、同一個時間戳記重新寫入就覆蓋 |

## 2. 寫入：line protocol

```text
# measurement,tag1=值,tag2=值 field1=值,field2=值 時間戳記
cpu,host=db-01,region=tpe,role=db usage_user=35.84,usage_system=10.2 1790783940
orders,city=台北市,status=paid order_id=1001i,total=1990i,coupon="WELCOME",gift=true 1790726400
```

- measurement 與 tag 用逗號、tag 與 field 之間一個空白、field 與時間之間一個空白；空白、逗號、等號要用反斜線跳脫
- field 型別：浮點數（預設）、**整數加 i**（`1990i`）、字串用雙引號、布林 `true` / `false`
- 時間戳記的單位由 `precision` 參數決定（ns 預設、us、ms、s）；沒給時間就用伺服器收到的時間
- ★ 同一條 series + 同一個時間戳記 = 同一個點：後寫的覆蓋前面的（不同 field 會合併）。同一秒兩筆訂單、同一組 tag → 第一筆不見，而且不報錯
- ★ 同一個 shard 裡，一個 field 只能有一種型別：先寫 `total=100i` 再寫 `total=1.5` → field type conflict，整批可能被拒絕
- 批次寫入（每批約 5,000 行），tag 依名稱排序後寫入比較快；刪除用 delete API（時間範圍 + tag 條件）

## 3. ★ 資料模型：tag 還是 field

| | tag | field |
|---|---|---|
| 索引 | 有（TSI） | 沒有 |
| 型別 | 只有字串 | 浮點數、整數、字串、布林 |
| WHERE 篩選 | 查索引，直接找到 series | 整條 series 讀出來逐筆比對 |
| GROUP BY | 可以 | 不行（會全部擠在同一組，不報錯） |
| 計算（mean、sum） | 不行 | 可以 |
| 值的種類很多時 | ★ series 數量暴增（high cardinality） | 沒影響 |

- 實測（實驗室）：1,000 位使用者 × 20 個事件，user_id 放 tag → **1,000 條 series**；放 field → **1 條**
- ★ 規則：拿來篩選、分組、而且值的種類有限（主機、區域、服務、狀態、感測器編號）→ tag；量測值、ID、高基數的值（使用者 ID、訂單編號、trace id、IP、完整 URL）→ field
- high cardinality 是 1.x / 2.x 效能問題的頭號原因：索引吃記憶體、寫入變慢、查詢要合併大量 series；3.x 改用欄式儲存（Arrow / Parquet）就是為了解決它
- `SHOW SERIES EXACT CARDINALITY`（InfluxQL）、`influxdb.cardinality()`（Flux）查 series 數量

## 4. ★ InfluxQL

```sql
SHOW MEASUREMENTS;
SHOW TAG KEYS FROM cpu;
SHOW TAG VALUES FROM cpu WITH KEY = "host";
SHOW FIELD KEYS FROM orders;                       -- 欄位與型別

SELECT mean(usage_user) FROM cpu
WHERE host = 'db-01'                               -- ★ 字串值用單引號
  AND time >= '2026-09-18T12:00:00+08:00' AND time < '2026-09-18T17:00:00+08:00'
GROUP BY time(1h), host fill(null) tz('Asia/Taipei');

SELECT last(usage_user) FROM cpu GROUP BY host;    -- 每台主機最後一次回報
SELECT top(total, 5), order_id, city FROM orders WHERE time >= '2026-09-01T00:00:00+08:00';
SELECT max(mean) FROM (SELECT mean(usage_user) FROM cpu WHERE … GROUP BY time(1h), host) GROUP BY host;   -- 子查詢
SELECT non_negative_derivative(last(requests), 1m) FROM http WHERE service = 'api' AND … GROUP BY time(1m);
```

| 類型 | 函式 | time 欄位 |
|---|---|---|
| aggregate | `count`、`mean`、`sum`、`median`、`spread`、`stddev` | 區間的開始（沒有 GROUP BY time 時是 0） |
| selector | `first`、`last`、`max`、`min`、`top`、`bottom`、`percentile` | 那一筆的時間（有 GROUP BY time 時是區間開始） |
| transformation | `derivative`、`non_negative_derivative`、`difference`、`moving_average`、`cumulative_sum` | 每一列 |

- ★ 單引號 = 字串值，雙引號 = 識別字。`host = "db-01"` 找不到任何資料，也不報錯
- ★ 天以上的 GROUP BY time() 一定要 `tz('Asia/Taipei')`，否則依 UTC 切（多出一列，每天的值都錯）
- `fill(null | none | 0 | previous | linear)` 決定沒有資料的區間怎麼顯示
- 只能 `ORDER BY time`；依值排名用 `top()` / `bottom()`
- 有 GROUP BY tag 時 `LIMIT` 是「每條 series」各自限制；限制 series 數量用 `SLIMIT`
- 只 SELECT tag（沒有 field）→ 不回傳任何資料；沒有 JOIN

## 5. ★ Flux

```flux
import "timezone"
option location = timezone.location(name: "Asia/Taipei")

from(bucket: "metrics")
  |> range(start: 2026-09-18T00:00:00+08:00, stop: 2026-09-20T00:00:00+08:00)   // ★ 一定要有 range
  |> filter(fn: (r) => r._measurement == "cpu" and r._field == "usage_user")
  |> aggregateWindow(every: 1d, fn: max, createEmpty: false)                     // _time 是區間的「結束」
  |> group()                                                                     // 合成一張表才能一起排序
  |> sort(columns: ["_value"], desc: true)
  |> limit(n: 3)
  |> keep(columns: ["_time", "host", "_value"])

// 兩個 field 相加：先 pivot 成同一列
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> map(fn: (r) => ({ r with total: r.usage_user + r.usage_system }))

// 計數器 → 每分鐘的量（跳過重置的負值）
  |> derivative(unit: 1m, nonNegative: true)
```

- 資料模型：**一列一個值**（`_value`），欄位名稱在 `_field`；每條 series 是一張「表」，函式對每張表各自執行
- 分組 = 表：`group(columns: ["role"])` 重新分表，`group()` 全部合成一張；聚合、排序、limit 都是每張表各自做
- 跟 InfluxQL 的差異：`aggregateWindow` 的 `_time` 預設是區間的**結束**（`timeSrc: "_start"` 改成開始）；時區用 `option location`
- Flux 能做 InfluxQL 做不到的：join 不同 measurement / bucket、`map` 任意計算、`to()` 寫回 bucket（降低精度）、Task 排程
- 安全：Flux 有 `sql`、`http` 等套件可以連到外部系統，token 的權限管不到

## 6. 計數器與變化率

- 監控系統（Telegraf、Prometheus exporter）收集的大多是**累計計數器**：只回報「到目前為止總共幾次」，查詢時算差值
- ★ 程式重啟後計數器歸零，`derivative` 會算出巨大的負數（實測 -360 萬）→ 一律用 `non_negative_derivative` / `non_negative_difference`（Flux：`nonNegative: true`、`increase()`）
- `difference` = 後一個減前一個；`derivative(…, 1m)` = 再除以時間單位，變成速率
- 錯誤率：`100 * non_negative_difference(last(errors)) / non_negative_difference(last(requests))`

## 7. ★ 保留期限與降低精度（downsampling）

- bucket 的 **retention period**：超過期限的資料自動刪除（依 shard group 整塊刪，很便宜）
- 降低精度：用 **Task**（排程執行的 Flux）把舊資料彙總到另一個 bucket，例如：
  `option task = {name: "downsample_cpu", every: 1h}` + `aggregateWindow(every: 1h, fn: mean) |> to(bucket: "cpu_1h")`
- 1.x 用 Continuous Query + Retention Policy；TimescaleDB 用連續聚合 + 保留政策，觀念一樣
- 實測：9 月的 cpu 從 200,880 個點變成 3,348 個（1/60），同樣的查詢快好幾倍；但每小時平均看不出 db-01 滿載的尖峰 → 常同時存 mean、max、min、count
- 遲到的資料可能錯過已經跑過的 Task：排程處理「1 小時前」的區間，留一點緩衝

## 8. 儲存引擎與版本

- 1.x / 2.x：**TSM**（Time-Structured Merge Tree）引擎：寫入先進 WAL 與記憶體快取，再壓縮成 TSM 檔；依時間切成 **shard group**（保留期限無限時每 7 天一組）；**TSI** 索引 tag → series
- 欄位依型別壓縮（時間用 delta-of-delta、浮點數用 Gorilla XOR），時序資料的壓縮率很高
- 3.x（IOx）：Apache **Arrow**（記憶體）+ **Parquet**（物件儲存）+ **DataFusion**（SQL 查詢引擎），欄式儲存、對高基數友善，查詢改用 SQL / InfluxQL，**不支援 Flux**

| | 1.x | 2.x | 3.x |
|---|---|---|---|
| 查詢 | InfluxQL | Flux、InfluxQL（相容 API） | SQL、InfluxQL |
| 組織方式 | database + retention policy | organization + bucket + token | database（table = measurement） |
| 排程彙總 | Continuous Query | Task（Flux） | 外部排程 / 處理引擎 |
| 儲存 | TSM + TSI | TSM + TSI | Arrow + Parquet |
| 高基數 | 弱 | 弱 | 好很多 |

## 9. 常見陷阱

| 陷阱 | 說明 |
|---|---|
| 字串用雙引號 `host = "db-01"` | 雙引號是識別字，找不到資料也不報錯 |
| tag 的值當數字 `sensor_id = 2` | tag 一律是字串，要寫 `'2'` |
| GROUP BY time(1d) 沒有 tz() | 依 UTC 切，每天的值都錯 |
| 對計數器用 derivative | 重啟時出現巨大的負數 |
| GROUP BY 一個 field | 全部擠在同一組，不報錯 |
| 只 SELECT tag | 不回傳任何資料 |
| GROUP BY tag + LIMIT | 每條 series 各自 LIMIT（用 SLIMIT 限制 series 數） |
| Flux 沒有 range | 報錯：cannot submit unbounded read |
| Flux 與 InfluxQL 的時間 | aggregateWindow 標在區間結束，GROUP BY time 標在開始 |
| 同一秒、同一組 tag 寫兩筆 | 後面的覆蓋前面的 |
| 同一個 field 寫不同型別 | field type conflict，整批可能被拒絕 |
| 高基數的值放 tag | series 數量爆炸，記憶體與效能出問題 |

## 10. ★ 面試題速答

| 問題 | 重點 |
|---|---|
| InfluxDB 的資料模型？ | measurement + tags（有索引的字串）+ fields（量測值）+ 時間；measurement + tag set = series |
| tag 與 field 怎麼選？ | 篩選、分組、值的種類有限 → tag；量測值、ID、高基數 → field |
| 什麼是 high cardinality？ | series 數量太多（例如把使用者 ID 放 tag），索引吃記憶體、寫入查詢變慢 |
| 怎麼處理舊資料？ | retention period 自動刪除 + Task 降低精度到另一個 bucket |
| 計數器怎麼算速率？ | non_negative_derivative / increase，避免重置造成負值 |
| InfluxDB vs TimescaleDB？ | InfluxDB：專用的時序引擎、寫入與壓縮快、監控生態系（Telegraf、Grafana）；TimescaleDB：完整 SQL、JOIN、交易，可以跟關聯資料放在一起、高基數也沒問題 |
| InfluxDB vs Prometheus？ | Prometheus 用拉取（pull）收集指標、PromQL、適合 Kubernetes 監控與告警，本地儲存不適合長期保存；InfluxDB 是推送（push）寫入的通用時序資料庫，可以存任意事件與長期資料 |
| 為什麼 3.x 改回 SQL？ | Flux 學習成本高、生態系小；欄式儲存（Arrow / Parquet / DataFusion）讓 SQL 與高基數都好處理 |
| 怎麼修改寫錯的資料？ | 同一條 series、同一個時間戳記重新寫入；或用 delete API 刪除後重寫 |

## 11. Java / Spring

- 2.x 官方 client：`com.influxdb:influxdb-client-java`
  - `Point.measurement("cpu").addTag("host", "db-01").addField("usage_user", 35.8).time(Instant.now(), WritePrecision.MS)`
  - 寫入用 `WriteApi`（非同步、自動批次與重試）或 `WriteApiBlocking`；查詢 `QueryApi.query(flux)` 回傳 `FluxTable`
- 3.x：`influxdb3-java`（Arrow Flight 查詢、SQL / InfluxQL）
- 應用程式指標：Spring Boot Actuator + Micrometer 的 `micrometer-registry-influx`（設定 `management.influx.metrics.export.*`），自動送出 JVM、HTTP 請求等指標
- ★ 寫入一定要批次、非同步；數字要明確決定型別（整數用 `addField(name, long)`），避免 field type conflict；高基數的值不要放 tag
