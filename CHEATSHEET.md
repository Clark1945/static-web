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
