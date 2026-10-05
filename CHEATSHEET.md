# PostgreSQL SQL CheatSheet

範例都可以直接在 `shop` 資料庫執行。★ = 面試高頻考點。

> 會修改資料的範例（第 10、11 節）請在 psql 或 DBeaver 裡練習；展示台是唯讀的。練壞了就重建資料庫：`docker compose down -v && docker compose up -d`

---

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

## 15. ★ PostgreSQL 進階

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
