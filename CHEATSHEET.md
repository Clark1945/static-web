# 資料庫 CheatSheet

面試導向的資料庫速查表，範例都可以直接在練習環境（`shop` 電商資料）執行。★ = 面試高頻考點。
每個資料庫是一個大章節，左側目錄可以快速跳轉，上方可以搜尋。

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
