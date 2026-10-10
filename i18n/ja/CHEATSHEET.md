# データベース CheatSheet

面接向けのデータベース早見表です。例はすべて練習環境（`shop` EC データ）でそのまま実行できます。★ = 面接頻出ポイント。
上部のタブでデータベースを切り替え、左側の目次で章に移動できます。検索はすべてのデータベースが対象で、タブにはそれぞれ何節見つかったかが表示されます。

---

# PostgreSQL

> データを変更する例（第 10、11 節）は、ショーケースの「書き込みサンドボックス」（実行後に自動で ROLLBACK）か、psql、DBeaver で練習できます。壊してしまったらデータベースを作り直しましょう：`docker compose down -v && docker compose up -d`

## 0. ★ SQL の「実行順序」

書く順序とデータベースが実行する順序は違い、多くの問題の答えがこれに関係しています：

```
書く順序：SELECT → FROM → WHERE → GROUP BY → HAVING → ORDER BY → LIMIT
実行順序：FROM/JOIN → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT
```

- **WHERE では SELECT で付けた別名を使えない**（WHERE の実行時には別名がまだ存在しない）。ORDER BY では使える
- **WHERE に集約関数は書けない**（`WHERE count(*) > 5` ❌）。HAVING に書く
- WHERE がまず「行」を絞り込み、次に HAVING が「グループ」を絞り込む

---

## 1. 基本（早見）

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

ページング：`LIMIT 20 OFFSET 40`（3 ページ目、1 ページ 20 件）

---

## 2. JOIN の一覧

| 書き方 | 結果 |
|---|---|
| `INNER JOIN` / `JOIN` | 両側で一致する行だけを残す |
| `LEFT JOIN` | 左表はすべて残し、右表で一致しないものは NULL |
| `RIGHT JOIN` | 右表はすべて残す（実務では通常 LEFT JOIN に書き換える） |
| `FULL JOIN` | 両側をすべて残し、一致しないものは NULL |
| `CROSS JOIN` | デカルト積：左 N 行 × 右 M 行 |
| SELF JOIN | 同じテーブル同士を JOIN。別々の別名を付ける |

```sql
-- 対応するデータが「ない」ものを探す：LEFT JOIN + IS NULL
SELECT c.id, c.name
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL;

-- SELF JOIN：各カテゴリとその親カテゴリ
SELECT c.name AS category, p.name AS parent
FROM categories c
LEFT JOIN categories p ON p.id = c.parent_id;
```

★ **LEFT JOIN の落とし穴：条件は ON か WHERE か？**

```sql
-- 条件を ON に：全会員が残り、「キャンセルした注文」だけが一致する
SELECT c.id, count(o.id)
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id AND o.status = 'cancelled'
GROUP BY c.id;

-- 条件を WHERE に：キャンセルしたことのない会員は o.status が NULL で除外される → INNER JOIN になってしまう
SELECT c.id, count(o.id)
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.status = 'cancelled'
GROUP BY c.id;
```

---

## 3. ★ NULL の扱い

| 書き方 | 説明 |
|---|---|
| `col IS NULL` / `IS NOT NULL` | NULL の判定。**`= NULL` は決して成立しない** |
| `COALESCE(a, b, c)` | NULL でない最初の値を返す |
| `NULLIF(a, b)` | a = b のとき NULL を返す（0 除算を避けるのによく使う） |
| `count(*)` vs `count(col)` | `count(*)` は行数、`count(col)` は NULL を数えない |
| `a IS DISTINCT FROM b` | NULL を普通の値として扱う「等しくない」 |

```sql
SELECT name, COALESCE(city, '未填寫') AS city FROM customers;
SELECT count(*) AS all_rows, count(birth_date) AS has_birthday FROM customers;
SELECT revenue / NULLIF(orders, 0) FROM ...;   -- orders が 0 のときはエラーではなく NULL になる
```

★ **`NOT IN` は NULL があると機能しない**：サブクエリに NULL が 1 つでもあると、`NOT IN` は 1 件も見つけません。「存在しない」ものは `NOT EXISTS` で探しましょう。

```sql
-- 「子カテゴリのない」カテゴリを探す。categories.parent_id には NULL（最上位カテゴリ）がある → この文は 0 件を返す
SELECT * FROM categories WHERE id NOT IN (SELECT parent_id FROM categories);

-- 正しい書き方
SELECT * FROM categories c
WHERE NOT EXISTS (SELECT 1 FROM categories ch WHERE ch.parent_id = c.id);
```

---

## 4. サブクエリ

```sql
-- スカラーサブクエリ：単一の値を返す
SELECT name, price FROM products
WHERE price > (SELECT avg(price) FROM products);

-- IN：値のリストと比較
SELECT * FROM customers
WHERE id IN (SELECT customer_id FROM orders WHERE status = 'returned');

-- EXISTS：「あるかどうか」だけを問い、1 件見つかれば止まる
SELECT * FROM customers c
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.status = 'cancelled');

-- 派生テーブル：FROM 内のサブクエリには必ず別名を付ける
SELECT avg(cnt) FROM (
  SELECT customer_id, count(*) AS cnt FROM orders GROUP BY customer_id
) t;

-- 相関サブクエリ：外側の列を参照し、行ごとに再計算される
SELECT p.name, p.price
FROM products p
WHERE p.price > (SELECT avg(price) FROM products WHERE category_id = p.category_id);
```

---

## 5. CASE WHEN と条件付き集計

```sql
SELECT name, price,
       CASE WHEN price >= 10000 THEN '高價'
            WHEN price >= 1000  THEN '中價'
            ELSE '平價' END AS level
FROM products;

-- 複数の条件を一度に（行から列へ / pivot）
SELECT shipping_city,
       count(*) FILTER (WHERE status = 'delivered')              AS delivered,   -- PostgreSQL の書き方
       sum(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END)   AS cancelled    -- 汎用的な書き方
FROM orders
GROUP BY shipping_city;
```

---

## 6. 集合演算

| 書き方 | 説明 |
|---|---|
| `UNION` | 結合して**重複を除く**（重複除去に並べ替えが必要で遅い） |
| `UNION ALL` | 結合し、重複を残す（★ 重複除去が不要ならこちら） |
| `INTERSECT` | 積集合 |
| `EXCEPT` | 差集合（A にあって B にない） |

両側の列の数と型が一致している必要があります。

```sql
SELECT customer_id FROM orders
EXCEPT
SELECT customer_id FROM orders WHERE status = 'returned';   -- 注文したことはあるが、返品したことのない会員
```

---

## 7. CTE（WITH）

クエリを名前の付いたステップに分けられ、入れ子のサブクエリより読みやすくなります。

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

**再帰 CTE**：ツリー構造（組織図、カテゴリツリー）を扱う

```sql
WITH RECURSIVE tree AS (
  SELECT id, name, 1 AS depth FROM categories WHERE parent_id IS NULL   -- 起点
  UNION ALL
  SELECT c.id, c.name, t.depth + 1                                      -- 毎回 1 階層下へ
  FROM categories c JOIN tree t ON c.parent_id = t.id
)
SELECT * FROM tree;
```

---

## 8. ★ ウィンドウ関数（Window Function）

GROUP BY との最大の違い：**行を 1 行にまとめない**。すべての行を残したまま、グループ全体の統計も見られます。

```
関数() OVER (PARTITION BY グループ化の列 ORDER BY 並べ替えの列 [フレーム])
```

| 関数 | 用途 |
|---|---|
| `row_number()` | 1, 2, 3, 4（同じ値でも別の番号） |
| `rank()` | 1, 2, 2, 4（同じ値は同順位、**番号が飛ぶ**） |
| `dense_rank()` | 1, 2, 2, 3（同じ値は同順位、番号は飛ばない） |
| `ntile(n)` | n 個のグループに均等に分ける |
| `lag(col, n)` / `lead(col, n)` | n 行前 / n 行後の値 |
| `first_value()` / `last_value()` | フレーム内の最初 / 最後の値 |
| `sum() / avg() / count() OVER` | グループの統計を取りつつ、各行を残す |

```sql
-- ★ グループごとの上位 N 件：各カテゴリで価格の高い上位 3 件の商品
SELECT * FROM (
  SELECT name, category_id, price,
         dense_rank() OVER (PARTITION BY category_id ORDER BY price DESC) AS rk
  FROM products
) t
WHERE rk <= 3;

-- 累計
SELECT order_date, amount,
       sum(amount) OVER (ORDER BY order_date) AS running_total
FROM ...;

-- 7 日移動平均
SELECT day, revenue,
       avg(revenue) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS ma7
FROM ...;

-- 前の行と比較
SELECT month, revenue, revenue - lag(revenue) OVER (ORDER BY month) AS diff
FROM ...;

-- 割合：各商品がそのカテゴリの売上に占める割合
SELECT name, category_id, price,
       round(100.0 * price / sum(price) OVER (PARTITION BY category_id), 2) AS pct
FROM products;
```


★ ウィンドウ関数は **WHERE に直接書けません**（実行順序が WHERE より後）。サブクエリか CTE で包みます。

---

## 9. よく使う関数（PostgreSQL）

**文字列**

```sql
'a' || 'b'                     -- 連結（NULL || 'b' は NULL になる）
concat('a', NULL, 'b')         -- 連結、NULL は無視
length(s)   lower(s)   upper(s)   trim(s)
substring(s, 1, 3)   left(s, 3)   right(s, 3)
replace(s, '舊', '新')
split_part('a@b.com', '@', 2)  -- 'b.com'
s LIKE '王%'                    -- % は任意の長さ、_ は 1 文字
s ILIKE '%apple%'              -- 大文字小文字を区別しない（PostgreSQL 専用）
string_agg(name, ', ')         -- 1 つの文字列に集約
```

**日付と時刻**

```sql
now()   current_date
date_trunc('month', order_date)            -- 月初に切り捨て。月次レポートに必須
extract(year FROM order_date)              -- 年 / month / dow（曜日）/ hour を取り出す
order_date::date                           -- timestamp を date に変換
current_date - interval '30 days'
age(current_date, birth_date)              -- 年齢を計算
to_char(order_date, 'YYYY-MM')             -- 書式化
order_date >= '2026-01-01' AND order_date < '2026-02-01'   -- ★ 範囲検索の方が extract よりインデックスを使いやすい
```

**数値**

```sql
round(x, 2)   ceil(x)   floor(x)   abs(x)   x % 3
```

★ **整数除算の落とし穴**：`5 / 2 = 2`。小数が欲しければ `5.0 / 2` か `5::numeric / 2` と書きます。

**型変換**：`'123'::int`、`CAST('123' AS int)`、`price::text`

---

## 10. 追加・更新・削除（DML）

```sql
INSERT INTO customers (name, email, signup_date)
VALUES ('測試帳號', 'test@example.com', current_date)
RETURNING id;                                   -- RETURNING で新しい id を受け取る

-- ★ UPSERT：存在すれば更新、なければ追加（主キーか UNIQUE 列で判断）
INSERT INTO categories (id, name, parent_id)
VALUES (28, '寵物用品', NULL)
ON CONFLICT (id)
DO UPDATE SET name = EXCLUDED.name;             -- EXCLUDED = 今回挿入しようとした行

UPDATE products SET price = price * 0.9 WHERE category_id = 19;

-- 他のテーブルのデータで更新
UPDATE products p SET is_active = false
FROM categories c
WHERE c.id = p.category_id AND c.name = '生鮮';

DELETE FROM orders WHERE status = 'cancelled' RETURNING id;   -- order_items には ON DELETE CASCADE があるので、明細も一緒に削除される
```

★ **DELETE vs TRUNCATE vs DROP**

| | DELETE | TRUNCATE | DROP |
|---|---|---|---|
| 削除対象 | 条件に合う行 | すべての行 | テーブル全体（構造を含む） |
| WHERE を付けられる | ✅ | ❌ | ❌ |
| 速度 | 遅い（1 行ずつ削除） | 速い | 速い |
| trigger の起動 | ✅ | ❌ | ❌ |
| ROLLBACK できる | ✅ | ✅（PostgreSQL では可） | ✅（PostgreSQL では可） |

---

## 11. テーブル作成と制約（DDL）

```sql
CREATE TABLE coupons (
  id         serial PRIMARY KEY,                        -- 自動採番の主キー
  code       text NOT NULL UNIQUE,
  discount   numeric(3,2) CHECK (discount BETWEEN 0 AND 1),
  customer_id int REFERENCES customers(id) ON DELETE CASCADE,  -- 外部キー
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE coupons ADD COLUMN used boolean DEFAULT false;
ALTER TABLE coupons DROP COLUMN used;
DROP TABLE coupons;
```

| 制約 | 説明 |
|---|---|
| `PRIMARY KEY` | 一意 + NULL 不可。1 つのテーブルに 1 つだけ |
| `UNIQUE` | 重複不可（NULL は複数あってよい） |
| `NOT NULL` | 空値不可 |
| `CHECK` | 独自の条件 |
| `FOREIGN KEY` / `REFERENCES` | 別のテーブルに存在する値と対応していなければならない |

**View**

```sql
CREATE VIEW v_order_total AS
SELECT order_id, sum(quantity * unit_price * (1 - discount)) AS total
FROM order_items GROUP BY order_id;            -- クエリだけを保存し、検索のたびに再計算

CREATE MATERIALIZED VIEW mv_order_total AS ...;  -- 結果を保存するので検索が速い
REFRESH MATERIALIZED VIEW mv_order_total;        -- データは手動で更新する
```

---

## 12. ★ インデックスと EXPLAIN

```sql
CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_cust_date ON orders (customer_id, order_date);  -- 複合インデックス
CREATE UNIQUE INDEX ... ;
DROP INDEX idx_orders_customer;

EXPLAIN SELECT ...;          -- 推定の実行計画
EXPLAIN ANALYZE SELECT ...;  -- 実際に実行し、実際の所要時間を表示
```

| 実行計画の用語 | 意味 |
|---|---|
| `Seq Scan` | テーブル全体を先頭から末尾までスキャン |
| `Index Scan` | インデックスで位置を見つけ、テーブルに戻ってデータを取る |
| `Index Only Scan` | 必要な列がすべてインデックスにあり、テーブルに戻らない |
| `Bitmap Heap Scan` | まずインデックスで位置を集め、まとめてテーブルを読む |
| `Nested Loop` / `Hash Join` / `Merge Join` | 3 種類の JOIN アルゴリズム |

**インデックスが使われないよくあるケース**

- 列に演算や関数をかける：`WHERE extract(year FROM order_date) = 2026` ❌
- 先頭がワイルドカード：`LIKE '%abc'` ❌（`LIKE 'abc%'` は可）
- 複合インデックス `(a, b)` で `b` だけを検索する（最左プレフィックスの原則）
- 返すデータがテーブル全体の大きな割合を占め、データベースが全件スキャンの方が速いと判断する

**インデックスの代償**：容量を使い、INSERT / UPDATE / DELETE のたびにインデックスも更新する必要があるので、遅くなります。

---

## 13. ★ トランザクション（Transaction）

```sql
BEGIN;
UPDATE products SET stock = stock - 1 WHERE id = 10;
INSERT INTO orders (...) VALUES (...);
COMMIT;      -- 確定。エラーなら ROLLBACK ですべて取り消す
```

**ACID**

| | 意味 |
|---|---|
| Atomicity 原子性 | すべて成功するか、すべて失敗する |
| Consistency 一貫性 | トランザクションの前後で制約を満たしている |
| Isolation 分離性 | 同時に実行するトランザクションが互いに干渉しない |
| Durability 永続性 | COMMIT した後はクラッシュしても失われない |

**分離レベルと起こり得る問題**（PostgreSQL の既定は Read Committed）

| レベル | ダーティリード | ノンリピータブルリード | ファントムリード |
|---|---|---|---|
| Read Uncommitted | PG では起きない | 起きる | 起きる |
| Read Committed | ❌ | 起きる | 起きる |
| Repeatable Read | ❌ | ❌ | PG では起きない |
| Serializable | ❌ | ❌ | ❌ |

変更する行をロックする：`SELECT ... FOR UPDATE`（例えば在庫を減らす前にロックして、売り越しを防ぐ）

---

## 14. ★ 面接の定番問題への即答

| 問題 | ポイント |
|---|---|
| WHERE vs HAVING | WHERE はグループ化前の行を、HAVING はグループ化後の結果を絞り込み、集約関数を使える |
| UNION vs UNION ALL | UNION は重複を除く（遅い）、UNION ALL は除かない |
| N 番目に高い価格（給与） | `SELECT DISTINCT price FROM products ORDER BY price DESC LIMIT 1 OFFSET N-1`、または `dense_rank()` |
| 重複データを探す | `GROUP BY 列 HAVING count(*) > 1` |
| 重複を削除して 1 件だけ残す | `row_number() OVER (PARTITION BY 重複する列)` を使い、rn > 1 のものを削除 |
| グループごとの上位 N 件 | `row_number()` / `dense_rank()` + 外側の `WHERE rk <= N` |
| N 日連続ログイン | 日付から `row_number()` 日を引き、同じ値になるものが同じ連続区間 |
| IN vs EXISTS | サブクエリの結果が大きければ EXISTS。`NOT IN` は NULL があると機能しない |
| 正規化 | 1NF は列をこれ以上分けられない、2NF は部分関数従属の排除、3NF は推移的関数従属の排除 |
| なぜすべてにインデックスを作らないのか | 容量を使い、書き込みを遅くし、データベースが使うとも限らない |
| char vs varchar vs text | PostgreSQL では 3 つの性能はほぼ同じなので、通常は text を使う |
| serial vs identity | `GENERATED ALWAYS AS IDENTITY` は SQL 標準で、新しいプロジェクトでは推奨 |

---

## 15. ★ 応用構文：JSONB、配列、UPSERT、応用インデックス

### JSONB

`products.specs` は JSONB で、例えば `{"color": "黑", "storage_gb": 256, "warranty": {"years": 2}}`

| 書き方 | 返す型 | 説明 |
|---|---|---|
| `specs->'warranty'` | jsonb | JSON を取り出す（さらに下へたどれる） |
| `specs->>'color'` | text | テキストを取り出す（最後の階層ではこれを使う） |
| `specs#>>'{warranty,years}'` | text | パスで値を取り出す |
| `specs @> '{"storage_gb": 256}'` | bool | ★ 包含。GIN インデックスを使える |
| `specs ? 'battery_hours'` | bool | このキーがあるか（`?|` はいずれか、`?&` はすべて） |
| `specs \|\| '{"on_sale": true}'` | jsonb | 結合。同じキーは上書きされる |
| `specs - 'on_sale'` | jsonb | キーを 1 つ削除 |
| `jsonb_set(specs, '{warranty,years}', '3')` | jsonb | 指定したパスの値を変更 |
| `jsonb_array_elements_text(specs->'sizes')` | 複数行 | JSON 配列を展開 |
| `jsonb_build_object('a', 1)`、`jsonb_agg(…)` | jsonb | JSON を組み立てる |

★ 落とし穴：
- `->>` で取り出すのは **text** なので、大小比較の前に型変換します：`(specs->>'storage_gb')::int > 64`。`> '64'` と書くと 1 文字ずつの比較になり、`'128' > '64'` は false です
- `NULL || '{…}'` の結果は NULL なので、`COALESCE(specs, '{}') || '{…}'` と書きます
- JDBC では `?` がパラメータのプレースホルダなので、`specs ? 'key'` は `??` か `jsonb_exists(specs, 'key')` に変えます
- json と jsonb：jsonb はバイナリで保存され、重複キーと空白を取り除き、インデックスに対応しています。**ほぼ常に jsonb を使います**

### Array

`products.tags` は `text[]` で、例えば `{熱銷,特價}`

| 書き方 | 説明 |
|---|---|
| `'特價' = ANY(tags)` | いずれかの要素が等しい |
| `tags @> ARRAY['熱銷','限量']` | すべてを含む（GIN を使える） |
| `tags && ARRAY['環保','獨家']` | 共通の要素が 1 つでもある（GIN を使える） |
| `unnest(tags)` | 複数行に展開 |
| `array_agg(name)` | 複数行を配列に集約 |
| `cardinality(tags)` | 要素数（空配列は 0） |
| `array_append(tags, '新品')`、`array_remove(tags, '特價')` | 追加 / 削除 |

★ `array_length(tags, 1)` は空配列に対して 0 ではなく **NULL** を返します。

Java：`WHERE id = ANY(?)` と `ps.setArray(1, conn.createArrayOf("int", ids))` を組み合わせれば、`IN (…)` の文字列を自分で組み立てる必要がなくなります。

### generate_series：連続した値を生成する

```sql
SELECT generate_series(1, 5);                                        -- 1〜5
SELECT generate_series('2026-09-01'::date, '2026-09-30', '1 day');   -- 毎日

-- ★ レポートの 0 埋め：まず完全な時間軸を生成し、それから LEFT JOIN（条件は ON に）
SELECT d::date, count(o.id)
FROM generate_series('2026-09-01'::date, '2026-09-30', '1 day') d
LEFT JOIN orders o ON o.order_date >= d AND o.order_date < d + interval '1 day'
GROUP BY d ORDER BY d;
```

大量のテストデータの生成にもよく使います（この練習用データベースもそうして作っています）。

### RETURNING と UPSERT

```sql
INSERT INTO customers (name, email, signup_date)
VALUES ('王小明', 'a@b.com', current_date)
RETURNING id;                                         -- 新しい id をそのまま受け取る

UPDATE products SET price = price * 1.1 WHERE id = 1 RETURNING id, price;   -- 更新後の値
DELETE FROM orders WHERE status = 'pending' RETURNING id;

-- ★ UPSERT：アトミックな操作で、「確認してから書く」競合状態がない
INSERT INTO categories (id, name) VALUES (2, '智慧型手機')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;  -- EXCLUDED = 挿入しようとした行

INSERT … ON CONFLICT (email) DO NOTHING;              -- 重複はスキップ（冪等）

-- データの移動：削除とアーカイブを 1 文・1 トランザクションで
WITH moved AS (DELETE FROM orders WHERE … RETURNING *)
INSERT INTO orders_archive SELECT * FROM moved;
```

★ シーケンスはトランザクションの制御を受けません：ROLLBACK しても使った id は戻らないので、id は飛び番になります。

### 応用インデックス

| インデックス | 書き方 | 向いている場面 |
|---|---|---|
| B-tree（既定） | `CREATE INDEX … (col)` | =、<、>、BETWEEN、ORDER BY、前方一致 LIKE 'abc%' |
| 複合インデックス | `(customer_id, order_date)` | 等値条件を前に、並べ替えの列を後ろに（最左プレフィックス） |
| ★ Partial Index | `(order_date) WHERE status = 'pending'` | データのごく一部だけを検索する。インデックスがずっと小さい |
| ★ Expression Index | `(lower(email))` | 検索条件で列に演算をしている。関数は IMMUTABLE でなければならない |
| カバリングインデックス | `(a, b) INCLUDE (c)` | クエリを Index Only Scan にする |
| GIN | `USING gin (specs)` | JSONB の @>、?、配列の @>、&&、全文検索 |
| BRIN | `USING brin (event_time)` | 時刻順に追記される巨大なテーブル（ログ、IoT）。数十 KB しかない |
| Hash | `USING hash (col)` | = の検索だけ。実務ではほとんど使わない |

BRIN の前提：ディスク上のデータの順序が列の値と一致している（相関が高い）こと。ランダムに書き込まれる列に BRIN を作っても、まったく役に立ちません。

### ★ Partial Index（部分インデックス）

「条件に合う行」だけにインデックスを作ります。「データの大部分はこの条件で検索されることがない」場合に向いています。

```sql
-- カスタマーサポートは処理待ちの注文だけを検索する：pending は 3% だけ
CREATE INDEX idx_orders_pending ON orders (order_date) WHERE status = 'pending';

SELECT id, customer_id FROM orders
WHERE status = 'pending'            -- 条件がインデックスの WHERE を「含意」していないと使われない
ORDER BY order_date DESC LIMIT 20;  -- インデックスは order_date 順：Index Scan Backward で並べ替え不要
```

実測（インデックス実験室の perf.orders_big、200 万件、pending は約 6 万件）：

| インデックス | サイズ | 「最新 20 件の pending」 |
|---|---:|---|
| `(status)` の完全なインデックス | 13 MB | 6 万件を探してから並べ替え |
| `(order_date) WHERE status = 'pending'` | 1.3 MB | Index Scan Backward、0.1 ms |

| よくある用途 | 書き方 |
|---|---|
| 論理削除：まだ削除されていないデータだけをインデックス化 | `CREATE INDEX … (email) WHERE deleted_at IS NULL` |
| ジョブキュー：未処理のジョブだけをインデックス化 | `CREATE INDEX … (created_at) WHERE done = false` |
| ★ 条件付き一意：例えば会員ごとに既定の住所は 1 つだけ | `CREATE UNIQUE INDEX … (customer_id) WHERE is_default` |
| 大量の NULL を除外 | `CREATE INDEX … (birth_date) WHERE birth_date IS NOT NULL` |

- 利点：インデックスが小さい（丸ごとメモリに載せやすい）、書き込み時に条件に合わない行はインデックスを維持しなくてよい
- ★ クエリの WHERE は、インデックスの条件に合うことをオプティマイザが「証明」できる必要があります：`status = 'pending'` は可、`status IN ('pending', 'paid')` や `status = $1`（パラメータ）は不可
- ★ Java / JDBC の落とし穴：PreparedStatement でパラメータ `WHERE status = ?` を渡すと、PostgreSQL は何回か実行した後で「汎用プラン」（generic plan）に切り替えることがあり、汎用プランはパラメータの値を知らないので部分インデックスを**使えません**（実測：完全な status インデックス + 並べ替えに切り替わる）。固定の条件は SQL に直接書き（`WHERE status = 'pending'`）、パラメータにしないこと
- 条件付き一意：テーブルの `UNIQUE` 制約には WHERE を付けられないので、`CREATE UNIQUE INDEX … WHERE …`（または EXCLUDE 制約）を使います
- 面接での説明：「データのごく一部だけがこのように検索され、しかも検索条件が固定なら、部分インデックスを使います。インデックスが小さく、書き込みのコストも低いからです。」

> 以下の実測はすべてインデックス実験室の perf の大きなテーブル（各 200 万件）で、トランザクション内でインデックスを作り、計測後に ROLLBACK しています。

### ★ Expression Index（式インデックス）

インデックスには「演算した後の値」が保存されます。検索条件で列に演算（関数、型変換、タイムゾーン換算）をしていて普通のインデックスが使えないときは、式インデックスを作ります。

```sql
-- 「台湾時間の日付」で注文を検索：order_date は timestamptz
CREATE INDEX idx_orders_tw_day ON orders (((order_date AT TIME ZONE 'Asia/Taipei')::date));
SELECT count(*) FROM orders
WHERE (order_date AT TIME ZONE 'Asia/Taipei')::date = '2025-06-01';   -- 式は「まったく同じ」でなければならない

CREATE INDEX idx_customers_email_lower ON customers (lower(email));     -- 大文字小文字を区別しない email
SELECT * FROM customers WHERE lower(email) = lower('User04242@Example.com');
```

| | インデックスなし | 式インデックス |
|---|---|---|
| 実行計画 | Seq Scan、199.8 万件を除外 | Bitmap Index Scan |
| 時間 | 488 ms | 5 ms |

- ★ クエリの式はインデックスと**完全に同じ**でなければなりません：作成したのは `(order_date AT TIME ZONE 'Asia/Taipei')::date` なので、クエリで `order_date::date` と書くと使われません（実測：Seq Scan）
- ★ 使えるのは **IMMUTABLE** な関数（入力が同じなら結果は常に同じ）だけです：`now()` は不可。`timestamptz::date` もセッションのタイムゾーン設定に依存するので不可で、だから `AT TIME ZONE` を明記します
- JSONB の単一のキーにもよく使います：`CREATE INDEX … ((meta->>'coupon'))` と `WHERE meta->>'coupon' = 'FALL10'` の組み合わせ
- 代償：書き込みのたびに式を 1 回計算します。インデックスのサイズは普通の B-tree とほぼ同じです（ここでは 14 MB）
- 同じ意味の書き方：列に演算をする代わりに、演算を「値」の側に移します。例えば `WHERE order_date >= '2025-06-01 00:00+08' AND order_date < '2025-06-02 00:00+08'` なら、普通の order_date インデックスが使えます

### ★ GIN（Generalized Inverted Index、転置インデックス）

1 つの値を「分解して」インデックス化します：JSONB の各キーと値、配列の各要素、文章の各単語が、どの行に現れるかを記録します。「1 つの列に多くの値があり、ある値を含む行を検索する」場合に向いています。

```sql
CREATE INDEX idx_events_meta ON order_events USING gin (meta);                   -- @> ? ?| ?& に対応
CREATE INDEX idx_events_meta_path ON order_events USING gin (meta jsonb_path_ops); -- @> だけに対応するが、半分の大きさ
SELECT count(*) FROM order_events WHERE meta @> '{"coupon": "VIP2026"}';           -- ★ @> と書く必要がある

CREATE INDEX idx_products_tags ON products USING gin (tags);                     -- text[]：@> && <@
SELECT * FROM products WHERE tags @> ARRAY['熱銷'];

CREATE EXTENSION pg_trgm;                                                        -- トライグラム：LIKE '%…%'
CREATE INDEX idx_customers_email_trgm ON customers USING gin (email gin_trgm_ops);
SELECT * FROM customers WHERE email LIKE '%04242%';

CREATE INDEX idx_docs_fts ON docs USING gin (to_tsvector('simple', body));       -- 全文検索
```

| JSONB `meta @> '{"coupon": "VIP2026"}'` | 時間 | インデックスのサイズ |
|---|---|---|
| インデックスなし（Seq Scan） | 524 ms | — |
| `gin (meta)` | — | 8.7 MB |
| `gin (meta jsonb_path_ops)`（オプティマイザが選択） | 6.8 ms | 4.5 MB |

- ★ `meta->>'coupon' = 'VIP2026'` では GIN を**使えません**（`->>` と `=` は GIN の対応演算子ではない）。`meta @> '{"coupon": "VIP2026"}'` と書くか、別途 B-tree の式インデックスを作ります
- `jsonb_ops`（既定）：`@>`、`?`（キーがあるか）、`?|`、`?&` に対応。`jsonb_path_ops`：`@>` だけに対応しますが、より小さく速い
- pg_trgm + GIN で `LIKE '%中間%'` や `ILIKE` もインデックスを使えます（実測 2.7 ms → 0.04 ms、2 万件）。B-tree が扱えるのは `LIKE 'abc%'`（前方一致）だけです
- 代償：書き込みが遅くなります（1 行で多数のインデックス項目を更新する）。PostgreSQL は pending list で後からまとめてマージ（`fastupdate`）して緩和しています
- Elasticsearch の核心と同じく転置インデックスです。Elasticsearch にはさらに分かち書き、関連度スコア、分散があります

### GiST（Generalized Search Tree）

「比較方法をカスタマイズできる」平衡木で、**重なりや遠近がある**データのインデックスに使います：範囲（tstzrange）、幾何（point、polygon、PostGIS）、全文検索。B-tree は「大小の順序」しか分かりませんが、GiST は「重なり `&&`」「包含 `@>`」「距離 `<->`」が分かります。

```sql
-- ★ 排他制約：同じ部屋の予約時間帯は重なってはいけない（UNIQUE では「重なり」をチェックできない）
CREATE EXTENSION btree_gist;                          -- GiST で room の = も扱えるようにする
CREATE TABLE bookings (
  room   int,
  during tstzrange,
  EXCLUDE USING gist (room WITH =, during WITH &&)
);
INSERT INTO bookings VALUES (101, '[2026-10-07 14:00, 2026-10-07 16:00)');
INSERT INTO bookings VALUES (101, '[2026-10-07 15:00, 2026-10-07 17:00)');  -- ✗ conflicting key value violates exclusion constraint
INSERT INTO bookings VALUES (101, '[2026-10-07 16:00, 2026-10-07 18:00)');  -- ✓ [) は半開区間なので、16:00 でちょうどつながる

-- 最近傍（KNN）：台北駅に最も近い 5 店舗
CREATE INDEX idx_stores_loc ON stores USING gist (loc);
SELECT id FROM stores ORDER BY loc <-> point(121.5, 25.03) LIMIT 5;
```

| 最も近い 5 店舗（20 万店舗） | 実行計画 | 時間 |
|---|---|---|
| インデックスなし | 20 万件の Seq Scan + top-N の並べ替え | 27 ms |
| GiST | Index Scan で、距離順に先頭 5 件を直接読み出す | 0.15 ms |

- ★ 排他制約（EXCLUDE）は GiST で最もよく聞かれる用途です：会議室やホテルの予約が重ならない、同じ従業員のシフトが重ならない、価格帯が重ならない
- KNN 検索：`ORDER BY 列 <-> 目標 LIMIT n` なら、インデックスから「近い順」に直接読み出せ、すべての距離を計算してから並べ替える必要がありません
- 全文検索にも GiST を使えます：GIN より小さく更新も速いですが、検索は遅くなります（誤判定があり再チェックが必要）。検索が中心なら GIN を選びます
- PostGIS の空間インデックスは GiST です。pgvector のベクトルインデックスは HNSW / IVFFlat（別のインデックス種別）です

### ★ BRIN（Block Range Index）

1 行ずつ記録せず、「ディスクブロックの範囲（既定 128 ページ）ごとの最小値と最大値」だけを記録します。検索時は範囲が合わないブロックを飛ばし、残りのブロックを読んで 1 行ずつチェックします。

```sql
CREATE INDEX idx_events_time_brin ON order_events USING brin (event_time);
SELECT count(*) FROM order_events
WHERE event_time >= '2025-06-01' AND event_time < '2025-06-02';
```

| order_events.event_time（時刻順に書き込み） | 時間 | インデックスのサイズ |
|---|---|---|
| インデックスなし（Seq Scan） | 108 ms | — |
| BRIN | 1.5 ms（Heap Blocks: lossy=128、余分に 8,527 件を読んでから絞り込み） | **24 kB** |
| B-tree | 0.12 ms（Index Only Scan：count はテーブルを読む必要がない） | 43 MB |

- BRIN は B-tree より少し遅い（ブロック範囲全体を読んでから絞り込む）ですが、インデックスは 1,800 分の 1 です。データ量が多く、メモリが厳しいほど、このトレードオフは割に合います
- ★ 前提：ディスク上のデータの順序が列の値と一致している（相関が高い）こと。時刻順に追記されるログ、IoT、取引記録が最適です。ランダムに書き込まれる列に BRIN を作ってもまったく役に立ちません（実験室の orders_big：オプティマイザはそもそも使いません）
- 大量の UPDATE / DELETE の後は順序が乱れて、効果が落ちます
- 「lossy」：BRIN は「このブロック範囲にあるかもしれない」としか言えないので、必ず再チェックが必要です（Rows Removed by Index Recheck）
- 向いているもの：数億件の時系列、追記のみのデータ。極小のインデックスで「おおよその位置特定」を得ます。TimescaleDB もこの性質を大いに活用しています

### インデックスの選び方

| クエリの形 | 使うもの |
|---|---|
| `=`、`<`、`>`、`BETWEEN`、`ORDER BY`、`LIKE 'abc%'` | B-tree（既定） |
| データのごく一部だけを検索し、条件が固定（`WHERE status = 'pending'`） | Partial Index |
| 条件で列に演算をしている（`lower(email)`、タイムゾーン換算） | Expression Index |
| JSONB の `@>` / `?`、配列の `@>` `&&`、全文検索、`LIKE '%…%'`（pg_trgm） | GIN |
| 範囲の重なり、排他制約、幾何、最近傍（`<->`） | GiST |
| 時刻順に追記される巨大なテーブルを、時間範囲で検索 | BRIN |
| `=` だけで、値がとても長い | Hash（実務ではほとんど使わない） |
| ベクトル類似度（AI のセマンティック検索） | pgvector の HNSW / IVFFlat |

## 16. psql のよく使うコマンド

| コマンド | 効果 |
|---|---|
| `\l` | データベースの一覧 |
| `\dt` | テーブルの一覧 |
| `\d テーブル名` | テーブルの構造、インデックス、外部キーを見る |
| `\di` | インデックスの一覧 |
| `\x` | 縦表示に切り替え（列が多いときに便利） |
| `\timing` | 各 SQL の所要時間を表示 |
| `\e` | エディタで長い SQL を書く |
| `\q` | 終了 |

---

# Redis

> 例のキーはすべて練習環境（`redis-lab` コンテナ、port 6380）のもので、ショーケースの「コマンドコンソール」にそのまま貼り付けて実行できます。データは PostgreSQL から変換したもので、壊してしまったら「データをリセット」を押します。

## 1. ★ 基本の考え方：Redis はなぜこんなに速いのか

| 考え方 | 説明 |
|---|---|
| データはメモリにある | 読み書きはマイクロ秒単位。ディスクは永続化のバックアップにだけ使う |
| コマンドはシングルスレッドで実行 | 一度に 1 つのコマンドだけを実行するので、ロック不要で切り替えのコストもない。**各コマンドは生まれつきアトミック** |
| I/O 多重化 | epoll で数万の接続を同時に処理。Redis 6 からはネットワークの読み書きをマルチスレッドにできる（`io-threads`）が、コマンドの実行はシングルスレッドのまま |
| 効率的なデータ構造 | データの大きさに応じて内部エンコーディングが自動で切り替わる（listpack、skiplist、intset…） |

★ シングルスレッドの代償：**1 つの遅いコマンドが全員を止めてしまいます**。`KEYS *`、大きなキーへの `HGETALL` / `SMEMBERS` / `DEL`、長時間動く Lua スクリプトは、どれも Redis 全体を止めてしまいます。

キーの命名規則：コロンで階層化します `オブジェクト種別:id:フィールド`。例：`product:540`、`customer:1:recent_orders`、`cache:category-report:2`。

## 2. 汎用コマンド（すべての型で使える）

| コマンド | 説明 |
|---|---|
| `EXISTS k`、`TYPE k` | 存在するか、何型か |
| `DEL k`、`UNLINK k` | 削除。UNLINK はバックグラウンドでメモリを解放するので、大きなキーを削除しても止まらない |
| `EXPIRE k 60`、`PEXPIRE k 500` | 有効期限を設定（秒 / ミリ秒） |
| `TTL k`、`PTTL k` | 残り秒数。**-1 = 有効期限なし、-2 = キーが存在しない** |
| `PERSIST k` | 有効期限を外す |
| `RENAME k k2`、`COPY k k2` | 名前の変更、コピー |
| `SCAN 0 MATCH product:* COUNT 100` | ★ キーを少しずつ探す（返されたカーソルで 0 になるまでスキャンし続ける） |
| `OBJECT ENCODING k`、`MEMORY USAGE k` | 内部エンコーディング、メモリ使用量 |
| `DBSIZE`、`INFO memory`、`SLOWLOG GET 10` | キーの数、メモリ、遅いコマンドの記録 |

★ 本番環境では `KEYS *` は禁止：すべてのキーを一度にスキャンし、その間他のリクエストはすべて待たされます。`SCAN` を使いましょう。

## 3. String

```redis
SET k v                       -- 値全体を上書きし、元の有効期限も消える
SET k v EX 1800               -- 書き込みと同時に 30 分の有効期限を設定
SET k v NX                    -- 存在しないときだけ書き込む（分散ロック）
SET k v XX                    -- 存在するときだけ書き込む
SET k v KEEPTTL               -- 元の有効期限を保持（6.0+）
GET k        MGET k1 k2       MSET k1 v1 k2 v2
INCR k       INCRBY k 10      DECR k      INCRBYFLOAT k 1.5     -- アトミックなカウンタ
GETDEL k     GETEX k EX 60    APPEND k v  STRLEN k
```

用途：キャッシュ（JSON 文字列）、カウンタ、セッション、分散ロック、レート制限。1 つの値は最大 512 MB ですが、10 KB を超えたら「大きなキー」です。

## 4. Hash

```redis
HSET product:540 name 手機 price 29949 stock 11   -- 追加したフィールド数を返す
HGET product:540 price
HMGET customer:1 name city                       -- 存在しないフィールドの位置は nil
HGETALL product:540                               -- 大きな Hash には使わず、HSCAN を使う
HINCRBY product:540 stock -2                      -- アトミックな増減
HDEL k f    HEXISTS k f    HLEN k    HKEYS k    HVALS k
HEXPIRE k 60 FIELDS 1 f                           -- フィールド単位の有効期限（7.4+）
```

★ オブジェクトのキャッシュは Hash か JSON の String か？Hash は一部のフィールドだけを読んだり変更したりできます。JSON の String は丸ごと読み書きするデータや、入れ子構造のあるデータに向いています。

## 5. List

```redis
LPUSH k a b c     RPUSH k x          -- 左 / 右から入れる
LPOP k            RPOP k 2           -- 左 / 右から取り出す
LRANGE k 0 9                          -- ★ 終端を含む：0 9 は 10 個、0 -1 はすべて
LTRIM k 0 9                           -- 先頭 10 個だけを残す
LINDEX k 0     LLEN k     LREM k 0 v
BLPOP k 5                             -- データがなければ最大 5 秒待つ（簡易キュー）
LMOVE src dst LEFT RIGHT              -- アトミックな移動（信頼性のあるキュー）
```

用途：最新 N 件（`LPUSH` + `LTRIM`）、簡易メッセージキュー。確認の仕組みや複数のコンシューマーでの分担が必要なら Stream を使います。

## 6. Set

```redis
SADD tag:特價 540 541       SREM k m      SCARD k       SISMEMBER k m
SMEMBERS k                                  -- 大きな Set には使わず、SSCAN を使う
SINTER a b     SUNION a b     SDIFF a b     -- 積集合、和集合、差集合（A にあって B にない）
SINTERCARD 2 a b                            -- 積集合の件数だけ（7.0+）
SINTERSTORE dst a b                         -- 結果を保存して件数を返す
SRANDMEMBER k 3     SPOP k                  -- ランダムに取る（抽選）
```

用途：タグ、共通の友達（積集合）、いいね済みか、ブラックリスト、抽選。要素には順序がなく、重複しません。

## 7. ★ Sorted Set（ランキング）

```redis
ZADD board 100 alice 90 bob             -- スコア メンバー
ZINCRBY board 10 alice                  -- アトミックに加点し、新しいスコアを返す
ZSCORE board alice
ZRANGE board 0 9 REV WITHSCORES         -- 上位 10 件（古い書き方 ZREVRANGE board 0 9 WITHSCORES）
ZREVRANK board alice                    -- ★ 順位は 0 から始まる
ZRANK board alice                       -- 低い順の順位
ZCOUNT board 50 100                     -- スコアの範囲内にいくつあるか。(50 は 50 を含まない
ZRANGE board 50 100 BYSCORE             -- スコアの範囲で取得
ZREMRANGEBYSCORE board -inf 10          -- スコアの範囲を削除
ZUNIONSTORE week 7 day1 day2 ...        -- 複数のランキングをまとめる（日次 → 週次）
```

★ 同点のときはメンバー名の辞書順に並び、REV を付けると全体が逆になります。「同点なら先に到達した人を上位に」したいなら、時刻をスコアに組み込みます。

内部構造：小さいときは listpack、大きくなると**スキップリスト（skiplist）+ ハッシュテーブル**になるので、スコアの範囲での検索は O(log N)、メンバーからスコアを引くのは O(1) です。

用途：ランキング、遅延キュー（スコア = 実行時刻）、スライディングウィンドウのレート制限（スコア = リクエスト時刻）、Geo。

## 8. 特殊な型：Stream、HyperLogLog、Bitmap、Geo

**Stream**（5.0+）：追記専用のログで、軽量版の Kafka のようなもの

```redis
XADD orders:stream * order_id 80000 status paid     -- * = ID を自動生成（ミリ秒-連番）
XLEN orders:stream
XRANGE orders:stream - + COUNT 10                   -- 古い順
XREVRANGE orders:stream + - COUNT 3                 -- 最新 3 件
XGROUP CREATE orders:stream g1 $                    -- コンシューマーグループを作成
XREADGROUP GROUP g1 worker-1 COUNT 10 STREAMS orders:stream >
XACK orders:stream g1 <ID>                          -- 処理が終わったら確認する。確認されないものは Pending に残る
```

**HyperLogLog**：重複のない数を推定。最大 12 KB 固定で、誤差は約 0.81%

```redis
PFADD uv:2026-09-01 user1 user2
PFCOUNT uv:2026-09-01                 -- 推定値
PFCOUNT uv:day1 uv:day2               -- 複数日を合わせた重複のない数
PFMERGE uv:2026-09 uv:day1 uv:day2    -- 合わせて新しいキーに保存
```

**Bitmap**：1 人 1 ビットで正確。サイズは最大の id に比例

```redis
SETBIT active:2026-09-01 500 1        -- 会員 500 はこの日活動した
GETBIT active:2026-09-01 500
BITCOUNT active:2026-09-01            -- 何人か
BITOP AND both active:day1 active:day2   -- 両日とも活動（OR = いずれかの日）
```

**Geo**：中身は Sorted Set

```redis
GEOADD store:locations 121.5654 25.0330 台北市     -- 経度 緯度 メンバー
GEODIST store:locations 台北市 高雄市 km
GEOSEARCH store:locations FROMMEMBER 台北市 BYRADIUS 60 km ASC WITHDIST
GEOSEARCH store:locations FROMLONLAT 121.5 25.0 BYBOX 20 20 km
```

★ UV の集計方法の選び方：少量なら Set（名簿を列挙できる）、id が連続した整数なら Bitmap（正確で省メモリ）、大量で誤差を許容できるなら HyperLogLog。

## 9. ★ トランザクションと Lua スクリプト

```redis
MULTI                        -- 開始。以降のコマンドは QUEUED を返す
INCR stats:orders:total
LPUSH customer:1:recent_orders 90001
EXEC                         -- まとめて実行。DISCARD で破棄

WATCH stock                  -- 楽観的ロック：EXEC 前に stock が他の人に変更されていたら、トランザクション全体が実行されない（nil を返す）
```

★ Redis のトランザクションには **ROLLBACK がありません**：
- キューに入れる時点で構文エラーが見つかる → `EXEC` は EXECABORT を返し、何も実行されない
- 実行時にエラーになる（例えば型の誤り）→ **その文だけが失敗し、他はそのまま反映される**

「判断してから変更する」アトミックな操作が必要なら Lua スクリプトを使います：スクリプトの実行中は他のコマンドが割り込みません。

```redis
EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0" 1 lock:order:1 my-token
```

注意：スクリプトは短くすること。長く動くと Redis 全体が止まります（既定では 5 秒経たないと SCRIPT KILL できない）。Redis 7 からは EVAL の代わりに `FUNCTION` が推奨されています。

## 10. Pipeline

コマンドごとにネットワークの往復（RTT）を 1 回待つ必要があります。Pipeline は多くのコマンドを一度に送り、最後にまとめて結果を受け取ります。

| 1000 個のキーを書き込む（練習環境での実測） | 所要時間 |
|---|---:|
| 1 つずつ SET | 約 200〜500 ms |
| Pipeline | 約 2 ms |
| MSET | 約 1〜2 ms |

Pipeline は**トランザクションではありません**：途中に他のクライアントのコマンドが入ることがあります。一度に数十万個も詰め込まず、分けて送りましょう。

## 11. ★ 実践パターン

**Cache-Aside（キャッシュアサイド）**

```redis
読み取り：GET cache:x → あれば返す。なければ → データベースを検索 → SET cache:x <値> EX 60 → 返す
書き込み：まずデータベースを更新 → 次に DEL cache:x（キャッシュは更新ではなく削除）
```

| 問題 | 状況 | 解決策 |
|---|---|---|
| ★ キャッシュ貫通 | 「そもそも存在しない」データを検索し、毎回データベースに当たる | 空の結果をキャッシュする（短い TTL）、ブルームフィルタ |
| ★ キャッシュ破壊 | 人気のキーが期限切れになった瞬間、大量のリクエストが同時にデータベースを検索する | 排他ロックで 1 つのリクエストだけに埋め戻させる、人気データは期限切れにせずバックグラウンドで更新 |
| ★ キャッシュ雪崩 | 大量のキーが同時に期限切れになる、または Redis 全体が落ちる | 有効期限にランダムな値を足す、多層キャッシュ、レート制限と縮退、高可用構成 |

キャッシュとデータベースの一貫性：「先にデータベースを更新してからキャッシュを削除」が最も一般的です。より厳しい要件には遅延二重削除や、データベースの変更を監視して（Canal / Debezium）キャッシュを削除する方法を使います。

**分散ロック**

```redis
ロック：SET lock:order:1 <uuid> NX PX 30000      -- 1 つのコマンドで「存在しなければ書く」+「有効期限」
解除：Lua で値が自分の uuid か比較してから DEL       -- 他人のロックを消さないため
```

- ★ `SETNX` + `EXPIRE` の 2 つのコマンドを使わないこと：間で落ちると期限切れにならないロックが残り、SETNX が失敗したときに続く EXPIRE が他人のロックを変えてしまいます
- 処理時間がロックの有効期限を超えることがある → Redisson のウォッチドッグが自動で延長します
- Java の実務では Redisson の `RLock` をそのまま使います

**レート制限**

| アルゴリズム | やり方 |
|---|---|
| 固定ウィンドウ | `INCR` で数え、最初のときに `EXPIRE`（Lua で包む）。欠点はウィンドウの境目で 2 倍のトラフィックになり得ること |
| スライディングウィンドウ | Sorted Set に各リクエストの時刻を記録し、`ZREMRANGEBYSCORE` でウィンドウ外を削除し、`ZCARD` で数える |
| トークンバケット | Lua で補充されるトークン数を計算。Spring Cloud Gateway の RequestRateLimiter がこれ |

**在庫の引き当て（売り越し防止）**：`GET` → 判断 → `SET` では売り越します。`DECR`（0 未満なら戻す）か、Lua で「判断してから減らす」を使います。

**その他のよくある使い方**：カウンタ（`INCR`）、セッション共有（`SET … EX`）、冪等性（`SET request:<id> 1 NX EX 86400` で重複リクエストは失敗する）、ランキング（Sorted Set）、最新のアクティビティ（`LPUSH` + `LTRIM`）、遅延キュー（Sorted Set、スコア = 実行時刻）。

## 12. ★ 永続化

| | RDB（スナップショット） | AOF（コマンドログ） |
|---|---|---|
| やり方 | 定期的にデータベース全体をバイナリファイルに保存（`BGSAVE`、子プロセスを fork） | 書き込みコマンドを 1 つずつファイルに追記 |
| 失われるデータ | 最後のスナップショット以降のすべて | `appendfsync` 次第：`always` は失わない、`everysec` は最大 1 秒、`no` は OS 任せ |
| ファイルサイズ / 再起動の速さ | 小さく、速い | 大きく、遅い（定期的に `BGREWRITEAOF` で圧縮する） |

Redis 4.0 からは両方を組み合わせられ（AOF ファイルの先頭が RDB のスナップショット）、再起動の速さとデータの安全性を両立できます。練習環境ではデータを PostgreSQL から作り直せるので、両方とも無効にしています。

## 13. ★ 期限切れの削除とメモリの退避

**期限切れのキーはどう削除されるか**：遅延削除（アクセスされたときにチェック）＋ 定期削除（毎秒一部をサンプリングしてチェック）。そのため期限切れのキーがすぐにメモリを解放するとは限りません。

**メモリがいっぱいになったら**（`maxmemory-policy`）：

| ポリシー | 説明 |
|---|---|
| `noeviction` | 既定。書き込みを拒否する（練習環境はこれ） |
| `allkeys-lru` | ★ すべてのキーから最も長く使われていないものを退避。純粋なキャッシュで最もよく使う |
| `volatile-lru` | 有効期限が設定されたキーだけを退避 |
| `allkeys-lfu` / `volatile-lfu` | 使用頻度が最も低いものを退避（4.0+） |
| `allkeys-random` / `volatile-random` | ランダム |
| `volatile-ttl` | 最も早く期限切れになるものを退避 |

LRU は近似アルゴリズムです：毎回いくつかのキー（`maxmemory-samples`）をランダムに選んで最も古いものを選ぶので、正確な LRU ではありません。

## 14. ★ 高可用性：レプリケーション、Sentinel、Cluster

| 構成 | 説明 |
|---|---|
| プライマリ・レプリカのレプリケーション | プライマリが書き込み、レプリカが読み取り。非同期レプリケーションなので、プライマリが落ちると最後の少しのデータを失うことがある |
| Sentinel（センチネル） | プライマリを監視し、落ちたらレプリカを自動でプライマリに昇格させる（自動フェイルオーバー） |
| Cluster（クラスタ） | データを複数のプライマリに分散：**16384 個の slot** で、`CRC16(key) % 16384` でどのノードに置くかが決まる |

★ Cluster の制限：1 つのコマンドで使う複数のキーは同じ slot になければならず、そうでないとエラーになります（CROSSSLOT）。hash tag で同じ slot に入れます：`{order:1}:items`、`{order:1}:status` は `{}` の中身だけで計算されます。

## 15. 内部エンコーディング（面接の加点ポイント）

| 型 | データが少ないとき | データが多いとき |
|---|---|---|
| String | int（整数）、embstr（≤ 44 bytes） | raw |
| List | listpack | quicklist（複数の listpack をつないだもの） |
| Hash | listpack | hashtable |
| Set | intset（すべて整数）、listpack | hashtable |
| Sorted Set | listpack | skiplist + hashtable |

`OBJECT ENCODING key` で確認できます。しきい値は設定で決まり、例えば `hash-max-listpack-entries 128` です。少量のデータはコンパクトな listpack でメモリを節約するので、「多数の小さな Hash に分ける」方が、1 つの巨大な Hash より省スペースになることがよくあります。

## 16. Java / Spring Boot

| ツール | 説明 |
|---|---|
| Lettuce | Spring Boot の既定のクライアント。Netty ベースでスレッドセーフ、1 つの接続を共有できる |
| Jedis | 伝統的な同期クライアントで、コネクションプールと組み合わせる（ショーケースは Jedis を使用） |
| Redisson | 分散ロック、ウォッチドッグ、レートリミッタ、遅延キューなどの高度な機能 |
| `RedisTemplate` | Spring Data Redis の操作の入口：`opsForValue()`、`opsForHash()`、`opsForZSet()`… |
| `@Cacheable` / `@CacheEvict` | Spring Cache のアノテーション。`RedisCacheManager` と組み合わせて自動で Cache-Aside を行う |

★ よくある落とし穴：
- `RedisTemplate` は既定で JDK のシリアライズを使うので、キーが `\xac\xed\x00\x05t\x00…` のような文字化けになります。`StringRedisSerializer` と JSON のシリアライザを設定するか、`StringRedisTemplate` をそのまま使います
- `@Cacheable` には既定で有効期限がないので、`RedisCacheConfiguration.entryTtl(...)` で設定します
- 同じクラスの中から自分の `@Cacheable` メソッドを呼ぶとプロキシを経由しないので、キャッシュが効きません

## 17. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| Redis はなぜ速いのか | メモリ、シングルスレッドでのコマンド実行（ロックなし）、I/O 多重化、効率的なデータ構造 |
| Redis はシングルスレッドか | コマンドの実行はシングルスレッド。6.0 からネットワーク I/O はマルチスレッドにできる。永続化や UNLINK はバックグラウンドスレッド |
| 5 つの基本型と用途 | String はキャッシュ / カウンタ、Hash はオブジェクト、List はキュー / 最新リスト、Set はタグ / 重複除去、Sorted Set はランキング |
| 貫通、破壊、雪崩 | 存在しないデータ、人気キーの期限切れ、大量のキーが同時に期限切れ（第 11 節を参照） |
| キャッシュの一貫性をどう保つか | 先にデータベースを更新してからキャッシュを削除、遅延二重削除、binlog を購読してキャッシュを削除 |
| 分散ロックをどう作るか | SET NX PX + 一意な token + Lua で解放。Redisson のウォッチドッグ。RedLock |
| トランザクションに ROLLBACK はあるか | ない。実行時のエラーは他のコマンドに影響しない。アトミック性が必要なら Lua |
| RDB と AOF | スナップショット vs コマンドログ。ハイブリッド永続化 |
| メモリがいっぱいになるとどうなるか | maxmemory-policy 次第。キャッシュなら allkeys-lru |
| 期限切れのキーはどう削除されるか | 遅延削除 + 定期削除 |
| 大きなキーとは何か、どう扱うか | 1 つのキーが大きすぎる（String > 10 KB、コレクション > 数千要素）。分割する、UNLINK で削除する、SCAN 系のコマンドで読む |
| ホットキーをどう扱うか | ローカルキャッシュ（Caffeine）を 1 層追加する、キーに接尾辞を付けて複数のノードに分散する |
| Cluster はどうシャーディングするか | 16384 個の slot、CRC16。slot をまたぐなら hash tag |
| KEYS と SCAN | KEYS は一度にすべてをスキャンしてブロックする。SCAN は分割して行い、重複することがあり、カーソルが 0 になるまで続ける必要がある |

## 18. redis-cli のよく使うコマンド

| コマンド | 効果 |
|---|---|
| `redis-cli -p 6380 --user learner --pass learner-lab` | 練習環境に接続 |
| `docker exec -it redis-lab redis-cli --user default --pass admin-lab` | コンテナ内から管理者として接続 |
| `--scan --pattern 'product:*'` | 安全にキーを一覧にする |
| `--bigkeys`、`--memkeys` | 最も大きなキーを探す |
| `--latency` | レイテンシを計測 |
| `MONITOR` | すべてのコマンドをリアルタイムで見る（非常に重いので開発環境でだけ使う） |
| `INFO`、`INFO memory`、`INFO stats` | サーバーの状態 |
| `CLIENT LIST` | 現在の接続 |

---

# MongoDB

> 例はすべて練習環境（`mongo-lab` コンテナ、port 27018、`shop` データベース）のもので、ショーケースの「コマンドコンソール」にそのまま貼り付けて実行できます。データは PostgreSQL から変換したもので、壊してしまったら「データをリセット」を押します。

## 1. ★ 基本の考え方と SQL との対応

| SQL | MongoDB |
|---|---|
| database | database |
| table | collection（コレクション） |
| row | document（ドキュメント、BSON 形式） |
| column | field（フィールド、ドキュメントごとに違ってよい） |
| primary key | `_id`（すべてのドキュメントにあり、既定は ObjectId） |
| JOIN | 埋め込みドキュメント、または `$lookup` |
| GROUP BY | 集約パイプラインの `$group` |
| index | index（概念はほぼ同じ） |

- **BSON**：バイナリの JSON で、Date、ObjectId、Decimal128、Int32 / Int64 などの型が加わっている
- **ObjectId**：12 バイトで、先頭 4 バイトが作成時刻なので、おおよそ時刻順に増えていく。`ObjectId(…).getTimestamp()` で取り出せる
- **1 つのドキュメントの上限は 16 MB**
- **柔軟なスキーマ**：同じコレクションのドキュメントでもフィールドが違ってよい。必要なら JSON Schema で検証できる（`$jsonSchema`）
- お金に double は使わないこと：`Decimal128`（`NumberDecimal("19.99")`）を使うか、「銭」単位の整数で保存する

## 2. 検索：find

```mongo
db.orders.find({ status: "paid" })                          // 条件
db.orders.find({ status: "paid" }, { total: 1, _id: 0 })    // 射影：1 は含める、0 は除外
db.orders.find({}).sort({ orderDate: -1 }).skip(20).limit(10)   // 並べ替え、ページング
db.orders.findOne({ _id: 77621 })
db.orders.countDocuments({ status: "paid" })                // 条件で数える
db.orders.estimatedDocumentCount()                          // 統計値を読むので速いが、条件は付けられない
db.orders.distinct("shipping.city")                         // 重複のない値
```

- ★ find の `sort`、`skip`、`limit` はどうつなげても、常に「sort → skip → limit」の順で実行されます
- 射影では、`_id` 以外で 1 と 0 を混在できません
- 大量のページングで大きな `skip` を使わないこと（前のデータをすべてスキャンする必要がある）。代わりに「前のページの最後の値」を条件にします：`{ orderDate: { $lt: 前のページの最後の時刻 } }`

## 3. クエリ演算子

| 種類 | 演算子 |
|---|---|
| 比較 | `$eq` `$ne` `$gt` `$gte` `$lt` `$lte` `$in` `$nin` |
| 論理 | `$and` `$or` `$nor` `$not`（同じオブジェクト内の複数の条件はもともと AND） |
| フィールド | `$exists`（フィールドが存在するか）、`$type`（型） |
| 配列 | `$all` `$elemMatch` `$size` |
| その他 | `$regex`（または `/^Apple/` と直接書く）、`$expr`（条件の中で集約式を使う。例えば 2 つのフィールドを比較） |

```mongo
db.products.find({ price: { $gte: 1000, $lte: 2000 } })
db.orders.find({ status: { $in: ["cancelled", "returned"] } })
db.customers.find({ $or: [{ city: "花蓮縣" }, { vipLevel: "gold" }] })
db.customers.find({ birthDate: { $exists: false } })
db.products.find({ $expr: { $gt: ["$price", { $multiply: ["$cost", 2] }] } })   // 売価が原価の 2 倍を超える
```

★ 型が一致する必要があります：`{ _id: "540" }`（文字列）では `_id: 540`（数値）は見つからず、自動で型変換もされず、エラーにもなりません。

★ `{ city: null }` は「値が null」と「そのフィールドがない」の両方のドキュメントを見つけます。フィールドがないものだけなら `{ $exists: false }` を使います。

## 4. 配列と埋め込みドキュメント

```mongo
db.orders.find({ "shipping.city": "台北市" })                // 埋め込みフィールドはドット記法（引用符が必要）
db.orders.find({ "items.productId": 540 })                  // ドット記法は配列も通り抜ける
db.products.find({ tags: "特價" })                           // ★ 配列の「いずれかの要素」が等しい
db.products.find({ tags: ["特價"] })                         // 配列全体が ["特價"] と「完全に等しい」
db.products.find({ tags: { $all: ["熱銷", "限量"] } })        // すべてを含む、順序不問
db.products.find({ tags: { $size: 3 } })                     // 要素がちょうど 3 つ
db.orders.find({ items: { $elemMatch: { qty: { $gte: 3 }, unitPrice: { $gte: 10000 } } } })
```

★ `$elemMatch` は面接でよく聞かれます：`{ "items.qty": { $gte: 3 }, "items.unitPrice": { $gte: 10000 } }` の 2 つの条件は「別々の要素」がそれぞれ満たしてもよいことになります。同じ要素が同時に満たすことを求めるなら、必ず `$elemMatch` を使います。

## 5. ★ 集約パイプライン

ドキュメントは各ステージを順に流れ、前のステージの出力が次のステージの入力になります。

| Stage | 役割 | SQL での対応 |
|---|---|---|
| `$match` | 絞り込み（前にあるほど良く、先頭ならインデックスを使える） | WHERE |
| `$project` / `$addFields` / `$set` / `$unset` | フィールドの選択、フィールドの追加や計算 | SELECT |
| `$group` | グループごとの集計：`$sum` `$avg` `$min` `$max` `$first` `$push` `$addToSet` | GROUP BY |
| `$sort` / `$limit` / `$skip` | 並べ替え、件数 | ORDER BY / LIMIT |
| `$unwind` | 配列を複数のドキュメントに平らにする | unnest / 明細テーブルとの JOIN |
| `$lookup` | 別のコレクションと関連付け、結果は配列 | LEFT JOIN |
| `$bucket` / `$bucketAuto` | 段階別の集計 | CASE WHEN + GROUP BY |
| `$facet` | 同じ入力で複数のパイプラインを実行（例えば総数とページを一度に求める） | 複数のクエリ |
| `$count` | 件数 | count(*) |
| `$out` / `$merge` | 結果をコレクションに書き込む | INSERT INTO … SELECT |

```mongo
db.orders.aggregate([
  { $match: { status: "delivered" } },
  { $unwind: "$items" },
  { $group: { _id: "$items.productId", name: { $first: "$items.name" }, qty: { $sum: "$items.qty" } } },
  { $sort: { qty: -1 } },
  { $limit: 5 }
])

// 会員の最新 3 件の注文 + 会員名：先に $limit してから $lookup
db.orders.aggregate([
  { $match: { customerId: 1 } },
  { $sort: { orderDate: -1 } },
  { $limit: 3 },
  { $lookup: { from: "customers", localField: "customerId", foreignField: "_id", as: "customer" } },
  { $project: { total: 1, customerName: { $first: "$customer.name" } } }
])
```

- ★ パイプラインはステージの順に実行されます：`[{ $limit: 3 }, { $sort: … }]` は本当に 3 件取ってから並べ替えます
- `$lookup` の `foreignField` にはインデックスが必要です。ないと 1 件ごとにコレクション全体をスキャンします
- 各ステージのメモリ上限は 100 MB で、超えると `allowDiskUse` が必要です（6.0 からは既定で許可）
- ★ 日付は常に UTC で保存されます：絞り込みでは `ISODate("2026-09-01T00:00:00+08:00")` と書き、`$dateToString`、`$dateTrunc` には `timezone: "Asia/Taipei"` を指定します

## 6. 書き込み

```mongo
db.customers.insertOne({ name: "王小明", email: "a@b.com" })
db.customers.insertMany([{ … }, { … }])
db.products.updateOne({ _id: 540 }, { $set: { stock: 20 } })
db.products.updateMany({ "category.name": "飲料" }, { $inc: { price: 5 } })
db.customers.updateOne({ email: "a@b.com" }, { $set: { name: "新會員" } }, { upsert: true })
db.products.findOneAndUpdate({ _id: 540, stock: { $gt: 0 } }, { $inc: { stock: -1 } }, { returnDocument: "after" })
db.orders.deleteMany({ status: "cancelled" })
```

| 演算子 | 役割 |
|---|---|
| `$set` / `$unset` | フィールドの設定 / 削除 |
| `$inc` / `$mul` | 加算 / 乗算（アトミック） |
| `$min` / `$max` | 元の値より小さい / 大きいときだけ更新 |
| `$rename` | フィールド名の変更 |
| `$setOnInsert` | upsert で「追加」するときだけ設定 |
| `$push` / `$addToSet` | 配列に追加 / 重複しないときだけ追加（`$each` で一度に複数、`$slice` で長さを制限） |
| `$pull` / `$pop` | 条件で削除 / 先頭か末尾を削除 |
| `$[]` / `$[elem]` | 配列のすべての要素 / arrayFilters に合う要素を更新 |

- ★ `replaceOne` は新しいドキュメントで古いドキュメントを「丸ごと置き換え」ます。一部のフィールドだけを変えたいなら必ず `$set` を使います
- `updateOne` / `deleteOne` は最初に一致した 1 件だけを処理します。すべてを処理するなら `updateMany` / `deleteMany`
- upsert は条件のフィールドの一意インデックスと組み合わせないと、高い並行性の下で重複したドキュメントが追加されることがあります

## 7. ★ スキーマ設計：埋め込みか参照か

**核心の原則：一緒に読むデータは一緒に保存する。**

| 埋め込み（Embedding） | 参照（Referencing） |
|---|---|
| 1 回の読み取りですべて取得でき、JOIN 不要 | データが重複せず、単独で検索・更新できる |
| 単一ドキュメントの更新はアトミックで、トランザクション不要 | 読み取り時に `$lookup` するか、プログラムで 2 回検索する |
| 1 対少数で、常に一緒に読むもの（注文明細、住所、仕様）に向く | 1 対多、際限なく増える、多対多、よく単独で更新されるデータに向く |

よくある設計パターン：

| パターン | 説明 |
|---|---|
| Extended Reference | 参照に加えて、よく使うフィールドをいくつかコピーする（例えば注文に商品名を保存）。`$lookup` が不要になる |
| Subset | 最もよく使う一部だけを埋め込む（例えば商品ドキュメントには最新 10 件のレビューだけを入れ、残りは別のコレクションに） |
| Computed | 事前に計算してドキュメントに入れておく（例えば注文の `total`）。読むときに計算し直さなくてよい |
| Bucket | 時系列データを時間でバケットに分け、1 つのドキュメントに 1 時間分のデータを入れる（MongoDB 5.0 からはネイティブの Time Series コレクションがある） |
| Tree | ツリー構造は `parentId`、`ancestors` 配列、パス文字列で保存する（練習環境の categories） |

★ アンチパターン：配列が際限なく増える（例えば 1 人の会員のすべての注文を会員ドキュメントに詰め込む）と、16 MB の上限にぶつかり、更新もどんどん遅くなります。

## 8. ★ インデックス

```mongo
db.orders.createIndex({ customerId: 1, orderDate: -1 })
db.customers.createIndex({ email: 1 }, { unique: true })
db.orders.createIndex({ orderDate: 1 }, { partialFilterExpression: { status: "pending" } })
db.sessions.createIndex({ createdAt: 1 }, { expireAfterSeconds: 3600 })   // TTL：期限切れのドキュメントを自動削除
db.orders.getIndexes()
db.orders.dropIndex("customerId_1")
db.orders.find({ customerId: 4242 }).explain("executionStats")
```

| 種類 | 説明 |
|---|---|
| 単一 / 複合 | 最もよく使う。複合インデックスはフィールドの順序が重要 |
| マルチキー（multikey） | 配列フィールドのインデックスで、要素ごとに 1 項目。1 つの複合インデックスに配列フィールドは 1 つまで |
| unique / partial / sparse | 一意、一部のドキュメントだけを含む、そのフィールドがあるドキュメントだけを含む |
| TTL | 時間が来たらドキュメントを自動削除（セッション、認証コード、ログ） |
| text / Atlas Search | 全文検索 |
| 2dsphere | 位置情報の検索 |
| hashed | ハッシュ値。主にシャードキーに使う |
| wildcard | フィールドが固定でないとき、`specs.$**` のような動的フィールドにインデックスを作る |

★ **ESR ルール**（複合インデックスのフィールド順）：**E**quality（等値）→ **S**ort（ソート）→ **R**ange（範囲）。
例えば `find({ status: "delivered", total: { $gte: 50000 } }).sort({ orderDate: -1 })` には `{ status: 1, orderDate: -1, total: 1 }` を作ると、メモリ上の並べ替えが不要になり、ページングのクエリで特に効果的です。

**explain の見方**

| フィールド / ステージ | 意味 |
|---|---|
| `COLLSCAN` | コレクション全体のスキャン（インデックス不足） |
| `IXSCAN` → `FETCH` | インデックスで位置を見つけてから、ドキュメントを読む |
| `SORT` | メモリ上で並べ替え（順序を提供するインデックスがない） |
| `PROJECTION_COVERED` | カバードクエリ：インデックスだけを読み、ドキュメントを読まない（`_id: 0` を忘れずに） |
| `nReturned` / `totalKeysExamined` / `totalDocsExamined` | 返した件数 / 見たインデックス項目の数 / 読んだドキュメントの数。3 つが近いほど良い |

## 9. ★ トランザクションと一貫性

- **単一ドキュメントの操作は常にアトミック**なので、良い埋め込み設計ならトランザクションが不要なことがよくあります
- 複数ドキュメントのトランザクション（4.0 から）には**レプリカセット**かシャードクラスタが必要で、スタンドアロンでは使えません。性能の代償があり、既定で 60 秒でタイムアウトします
- **Write Concern**：書き込みが成功とみなされるまでに何ノードの確認が必要か。`w: 1`（プライマリ）、`w: "majority"`（過半数のノード、5.0 からの既定）
- **Read Concern**：どの程度のデータを読むか。`local`、`majority`（後でロールバックされ得るデータは読まない）、`linearizable`
- **Read Preference**：どのノードから読むか。`primary`（既定）、`secondaryPreferred`（読み取りを分散するが、少し古いデータを読むことがある）

```java
// Spring：@Transactional と MongoTransactionManager の組み合わせ（レプリカセットが必要）
try (ClientSession session = client.startSession()) {
    session.withTransaction(() -> {
        orders.insertOne(session, order);
        products.updateOne(session, eq("_id", 540), inc("stock", -1));
        return null;
    });
}
```

## 10. ★ レプリカセットとシャーディング

| 構成 | 説明 |
|---|---|
| レプリカセット（Replica Set） | 1 つの Primary が書き込みを担当し、複数の Secondary が oplog で複製する。Primary が落ちると自動で新しいものが選ばれる（通常は数秒以内）。最低 3 ノード（または 2 + アービター）が必要 |
| シャーディング（Sharding） | データを「シャードキー」で複数のシャードに分散する。アプリケーションは `mongos` ルーターに接続し、設定は config servers に保存される |

★ シャードキーの選び方：
- カーディナリティが高い（値の種類が多い）、分布が均等、検索条件によく含まれる
- 単調に増えるキー（時刻、ObjectId）だと書き込みがすべて最後のシャードに集中する → hashed シャーディングか、複合シャードキーを使う
- シャードキーを含まないクエリはすべてのシャードに問い合わせる必要があり（scatter-gather）、遅くなる

## 11. よくある落とし穴

| 落とし穴 | 説明 |
|---|---|
| `{ field: null }` | そのフィールドがないドキュメントも見つける |
| 型の違い | `"540"` と `540` は等しくなく、自動で型変換されない |
| 配列の等号 | `{ tags: "a" }` は包含、`{ tags: ["a"] }` は完全一致 |
| 配列への複数の条件 | 同じ要素が同時に一致する必要があるなら `$elemMatch` |
| タイムゾーン | 日付は UTC で保存されるので、検索でもグループ化でもタイムゾーンを明示する |
| `replaceOne` / `save()` | 丸ごと置き換えなので、含めなかったフィールドは消える |
| `updateOne` | 1 件だけを更新する |
| 大きな skip でのページング | 後ろになるほど遅いので、範囲条件でのページングに変える |
| JDBC 的な考え方 | すべてのテーブルを無理にコレクションにして、あちこちで `$lookup` すると、ドキュメントデータベースの利点を失う |

## 12. Java / Spring Data MongoDB

| ツール | 説明 |
|---|---|
| MongoDB Java Driver | 公式ドライバ（ショーケースは sync 版を使用） |
| `MongoTemplate` | Spring の操作の入口：`find(Query, Class)`、`updateFirst`、`aggregate` |
| `MongoRepository` | メソッド名からクエリを生成：`findByStatusOrderByOrderDateDesc(…)` |
| `@Document` / `@Id` / `@Field` / `@Indexed` | コレクション、主キー、フィールド名、インデックスとの対応 |
| `Criteria` / `Query` / `Update` | 条件を組み立てる：`Query.query(Criteria.where("status").is("paid"))` |

★ よくある落とし穴：
- `repository.save(entity)` は丸ごと置き換えです：一部のフィールドだけを読み込んだオブジェクトを保存すると、他のフィールドが消えてしまいます。部分更新には `MongoTemplate.updateFirst` + `Update.update(…)` を使います
- Spring Data は既定でドキュメントに `_class` フィールドを余分に保存します（Java のクラスを記録）。`MappingMongoConverter` で無効にできます
- `@Indexed` は既定ではインデックスを自動作成**しません**（Spring Boot 3 からは `spring.data.mongodb.auto-index-creation=true` の設定が必要。本番環境ではマイグレーションツールで作るのが推奨）
- `LocalDateTime` にはタイムゾーンがなく、MongoDB に保存すると UTC として扱われるので、読み戻すと 8 時間ずれることがあります

## 13. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| MongoDB とリレーショナルデータベースの違い | ドキュメントモデル、柔軟なスキーマ、JOIN の代わりに埋め込み、水平スケール（シャーディング）がしやすい |
| いつ MongoDB を使うべきか | データ構造が変わりやすい、読み書きが「ドキュメント丸ごと」単位、水平スケールが必要。強い関連や複雑なトランザクションが多いシステムはリレーショナルの方が良い |
| 埋め込みか参照か | 一緒に読むなら一緒に保存。1 対少数は埋め込み、1 対多や際限なく増えるものは参照 |
| `$elemMatch` はいつ使うか | 配列の「同じ要素」が複数の条件を同時に満たす必要があるとき |
| 複合インデックスのフィールド順 | ESR：等値 → ソート → 範囲 |
| クエリがインデックスを使っているかの判断 | explain("executionStats")：IXSCAN vs COLLSCAN、docsExamined vs nReturned |
| MongoDB はトランザクションに対応しているか | 単一ドキュメントは常にアトミック。複数ドキュメントのトランザクションは 4.0 から対応し、レプリカセットが必要 |
| レプリカセットはどう障害に耐えるか | oplog での複製、Primary が落ちたら自動選出。write concern majority でロールバックを防ぐ |
| シャードキーの選び方 | 高カーディナリティ、均等な分布、よく検索される。単調増加は避ける |
| `_id` は必ず ObjectId か | いいえ、重複しない値なら何でもよい（練習環境は PostgreSQL の整数 id を使用） |
| 16 MB の制限にはどう対処するか | 設計を変える（参照、Subset、Bucket）。大きなファイルには GridFS を使う |

## 14. mongosh のよく使うコマンド

| コマンド | 効果 |
|---|---|
| `mongosh "mongodb://learner:learner-lab@localhost:27018/shop?authSource=admin"` | 練習環境に接続 |
| `docker exec -it mongo-lab mongosh -u admin -p admin-lab` | コンテナ内から管理者として接続 |
| `show dbs`、`use shop`、`show collections` | データベースの一覧、切り替え、コレクションの一覧 |
| `db.orders.stats()`、`db.stats()` | コレクション / データベースのサイズと統計 |
| `db.currentOp()`、`db.killOp(id)` | 実行中の操作を確認 / 中止 |
| `db.setProfilingLevel(1, { slowms: 100 })` | 100 ms を超える遅いクエリを `system.profile` に記録 |
| `it` | 次の結果を表示（find は一度に 20 件しか表示しない） |


# Cassandra

> 例はすべて練習環境（`cassandra-lab` コンテナ、port 9043、keyspace `shop`）のもので、ショーケースの「cqlsh コンソール」にそのまま貼り付けて実行できます。データは PostgreSQL から変換したもので、壊してしまったら「データを再読み込み」を押します。

## 1. ★ 基本の考え方と SQL との対応

| SQL | Cassandra |
|---|---|
| database / schema | keyspace（レプリケーション戦略とレプリカ数も同時に決める） |
| table | table（旧称 column family） |
| row | row（あるパーティションに属する） |
| primary key | partition key ＋ clustering columns |
| JOIN | ない。クエリに必要なデータはあらかじめ同じテーブルに入れておく（非正規化） |
| GROUP BY / ORDER BY | 主キーの列でしか使えず、しかも多くの制限がある |
| transaction | 一般的なトランザクションはない。単一パーティションの軽量トランザクション（LWT）と BATCH だけ |

- **マスターレス構成（masterless）**：すべてのノードが対等で、単一障害点がない。どのノードも「コーディネーター」としてリクエストを受けられる
- **コンシステントハッシュのリング**：パーティションキーをハッシュ（Murmur3）して token を得て、token でデータを置くノードが決まる。各ノードは多くの token の範囲を担当する（vnodes、既定 `num_tokens: 16`）
- **書き込みの経路**：commitlog（シーケンシャルに書き込み、永続性を保証）→ memtable（メモリ）→ いっぱいになると変更不可の SSTable に flush。書き込み前に読む必要がないので非常に速い
- **読み取りの経路**：memtable ＋ 複数の SSTable をマージすることがある。bloom filter でこのパーティションを含まない SSTable を飛ばし、パーティションインデックスで位置を見つける
- **compaction**：バックグラウンドで複数の SSTable を 1 つにマージし、ついでに期限切れのデータとトゥームストーンを消す
- 向いているもの：大量の書き込み、時系列、イベント記録、ユーザーのアクティビティ、IoT、複数のデータセンターにまたがり停止できないサービス
- 向いていないもの：JOIN、自由なクエリ、トランザクション、強い一貫性が必要なカウント（在庫、口座残高）が必要なもの

## 2. ★ 主キー：パーティションキーとクラスタリングキー

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

| 部分 | 書き方 | 役割 |
|---|---|---|
| パーティションキー | `(customer_id)`、複合なら `((a, b), …)` | データを**どのノード**に置くかを決める。同じパーティションのデータはまとめて保存される |
| クラスタリングキー | `order_time, order_id` | **パーティション内の順序**を決め、範囲検索ができる |
| 主キー | パーティションキー ＋ クラスタリングキー | 1 行を一意に識別する。同じ主キーで書き込むと上書きになる |

- `PRIMARY KEY (a, b, c)`：a がパーティションキー、b、c がクラスタリングキー。`PRIMARY KEY ((a, b), c)`：a と b を一緒にパーティションキーにする
- ★ クラスタリングキーで主キーを一意にする必要がある：`order_time` だけだと同じミリ秒の 2 件の注文が互いに上書きされるので、`order_id` を加える
- ★ パーティションの大きさの目安：100 MB 以内、10 万行以内。際限なく大きくなるパーティション（例えば「すべての注文」「あるセンサーのすべてのデータ」）はバケットに分ける
- static 列：`col text STATIC`。同じパーティションのすべての行で 1 つの値を共有する（例えばパーティション単位の属性）

## 3. ★ クエリ先行のデータモデル

リレーショナルは「先にデータを設計し、それからクエリを書く」。Cassandra は「**先にどう検索するかを列挙し、クエリごとに 1 つのテーブルを設計する**」です。

| クエリ | テーブル | 主キー |
|---|---|---|
| 注文番号で注文を検索 | `orders` | `(order_id)` |
| ある会員の注文、新しい順 | `orders_by_customer` | `((customer_id), order_time DESC, order_id)` |
| ある日の注文 | `orders_by_day` | `((order_day), order_time, order_id)` |
| あるカテゴリの商品、価格の高い順 | `products_by_category` | `((category), price DESC, product_id)` |
| email でログイン | `customers_by_email` | `(email)` |

- 同じデータを複数のテーブルに書き込むのは普通のこと（書き込みは安く、パーティションをまたぐ読み取りが高い）。logged BATCH で複数のテーブルを最終的に一致させる
- **時間バケット**：`((sensor_id, day), ts)`、`((order_day), order_time)` でパーティションの大きさを一定に保つ
- **ホットスポット**：パーティションキーの値は均等に分布させる。例えば「ステータス」をパーティションキーにすると、`delivered` のパーティションが非常に大きくなる
- 1 対多の子データは、コレクションや UDT で同じ行に入れられる（`list<frozen<order_item>>`）。数が少なく、一緒に読むときに使う

## 4. 検索：SELECT のルール

```cql
SELECT * FROM orders_by_customer WHERE customer_id = 4242;                 -- 単一パーティション
SELECT * FROM orders_by_customer WHERE customer_id = 1 LIMIT 3;            -- パーティションは並んでいるので最新 3 件
SELECT * FROM orders_by_customer
WHERE customer_id = 1 AND order_time >= '2025-01-01 00:00:00+0800';       -- クラスタリングキーの範囲
SELECT * FROM orders_by_customer WHERE customer_id = 4242 ORDER BY order_time ASC;   -- 逆向きに読む
SELECT * FROM products WHERE product_id IN (540, 541, 542);                -- パーティションキーの IN
SELECT order_day, COUNT(*) FROM orders_by_day
WHERE order_day IN ('2026-09-01', '2026-09-02') GROUP BY order_day;      -- GROUP BY は主キーにしか使えない
SELECT * FROM orders_by_day WHERE order_day IN ('2026-09-01', '2026-09-02') PER PARTITION LIMIT 1;
SELECT DISTINCT category FROM products_by_category;                        -- パーティションキーにしか使えない
SELECT name, WRITETIME(name), TTL(city) FROM customers WHERE customer_id = 4242;
SELECT customer_id, token(customer_id) FROM customers LIMIT 5;             -- パーティションの token を見る
```

| ルール | 説明 |
|---|---|
| ★ パーティションキーは完全に指定する | `=` か `IN` を使う。指定しないと全表スキャンになり、拒否される（`ALLOW FILTERING` を付けない限り） |
| クラスタリングキーは左から限定する | 前のクラスタリングキーを飛ばせない。前の列で範囲を使ったら、後ろの列はもう限定できない |
| 範囲はクラスタリングキーにしか使えない | パーティションキーは `=` / `IN`（または `token()` の範囲）だけ |
| ORDER BY | クラスタリングキーにしか使えず、しかもテーブル作成時の順序か完全な逆順だけ |
| 主キー以外の列 | インデックス（SAI）か `ALLOW FILTERING` がない限り WHERE に書けない |
| aggregate | `COUNT`、`SUM`、`AVG`、`MIN`、`MAX`。★ `AVG(int)` は int を返す（小数は切り捨て）ので、先に `CAST(x AS double)` する |
| PER PARTITION LIMIT | 各パーティションの先頭 n 行だけを取る。Cassandra 版の「グループごとの上位 n 件」 |

★ `ALLOW FILTERING` のコスト = 結果を見つけるために読んだ行数。パーティションキーを限定した後にパーティション内で絞り込むのは安価ですが、パーティションキーがなければ全表スキャンです。

★ 時刻の文字列にタイムゾーンがないと、**サーバーのタイムゾーン**で解釈されます。常に明記しましょう：`'2026-09-01 20:00:00+0800'`。timestamp は常に UTC で保存されます（ミリ秒精度）。

## 5. 書き込み：INSERT、UPDATE、DELETE

```cql
INSERT INTO customers_by_email (email, customer_id, name) VALUES ('a@example.com', 1, '王小明');
UPDATE products SET stock = 0, tags = tags + {'缺貨'} WHERE product_id = 540;
UPDATE products SET specs['color'] = '黑' WHERE product_id = 540;            -- map の単一のキー
UPDATE customers USING TTL 86400 SET vip_level = 'gold' WHERE customer_id = 4242;
INSERT INTO kv (k, v) VALUES ('session:1', '…') USING TTL 1800;             -- 行全体が 30 分後に期限切れ
DELETE birth_date FROM customers WHERE customer_id = 4242;                   -- 列を 1 つ削除
DELETE FROM orders_by_customer
WHERE customer_id = 1 AND order_time < '2023-01-01 00:00:00+0800';          -- 範囲削除
UPDATE product_sales SET units = units + 2 WHERE product_id = 540;           -- カウンタ
```

- ★ **INSERT も UPDATE も upsert**：書き込み前に読まず、主キーが存在すれば上書き、存在しなければ追加し、エラーにはならない
- ★ **last write wins**：各列の値（cell）は書き込みタイムスタンプを持ち、読み取り時はタイムスタンプの大きい方が勝つ。実行順序とは関係ない（`USING TIMESTAMP` で指定できる）。アプリケーションサーバーの時計は同期させておく
- ★ **TTL は cell ごとに設定される**：UPDATE は今回書いた列にだけ TTL を設定する。行全体を期限切れにするなら INSERT … USING TTL か、テーブル作成時に `default_time_to_live` を設定する
- ★ **null を書く = 削除 = トゥームストーン**：値のない列は書かないこと（driver 4 の prepared statement ではパラメータを unset のままにできる）
- カウンタ：`UPDATE … SET c = c + n` しかできず、INSERT も TTL も不可で、テーブルには主キー以外に counter 列しか置けない。再試行で重複加算されることがあり、冪等ではない

## 6. データ型

| 種類 | 型 |
|---|---|
| テキスト | `text`（= `varchar`）、`ascii` |
| 整数 | `tinyint` `smallint` `int` `bigint` `varint`（任意の長さ） |
| 小数 | `float` `double` `decimal`（金額には decimal） |
| 時刻 | `timestamp`（ミリ秒）、`date`、`time`、`duration` |
| 識別子 | `uuid`、`timeuuid`（時刻を含み、時刻順に並べられる。`now()` で生成） |
| その他 | `boolean` `blob` `inet` `counter` `vector<float, n>`（5.0、ベクトル検索） |
| コレクション | `list<T>`（順序あり、重複可）、`set<T>`（重複なし、並べ替え済み）、`map<K, V>` |
| カスタム | UDT（`CREATE TYPE`）、`tuple<…>`、`frozen<…>`（全体を 1 つの値として扱い、丸ごと置き換えるしかない） |

- コレクションは少量のデータ（数十個以内）に向いていて、コレクション全体が一緒に読み出されます
- ★ `tags = {'a'}` は丸ごと置き換え（先にトゥームストーンを 1 つ書く）、`tags = tags + {'a'}` は要素を追加するだけです
- list の `+` は重複して追加されますが、set ではされません

## 7. ★ トゥームストーンと compaction

- SSTable は書き込み後に変更されないので、**削除はトゥームストーン（tombstone）を書き込むこと**です。DELETE、null の書き込み、TTL の期限切れ、コレクションの丸ごと置き換えはすべてトゥームストーンを生成します
- 読み取り時はトゥームストーンを読んで初めてデータが削除されたと分かります：`tombstone_warn_threshold`（1,000）を超えると警告、`tombstone_failure_threshold`（100,000）を超えるとクエリが失敗します
- トゥームストーンは `gc_grace_seconds`（既定 10 日）が過ぎた後の compaction で初めて消されます。この期間は、オフラインだったレプリカが戻ってきたときに削除を同期するためのもので、そうしないと削除したデータが「復活」します（zombie）。だから **repair は必ず gc_grace_seconds 以内に一巡させます**
- ★ 範囲削除は範囲トゥームストーンを 1 つ書くだけですが、1 行ずつ削除すると行ごとにトゥームストーンが 1 つできます
- ★ アンチパターン：Cassandra をキューとして使う（書き込んでは削除し続ける）、頻繁に更新してから削除する

| compaction の戦略 | 向いているもの |
|---|---|
| STCS（SizeTiered、既定） | 書き込み中心 |
| LCS（Leveled） | 読み取り中心、頻繁な更新。読み取りで触れる SSTable が少ないが、compaction の I/O が多い |
| TWCS（TimeWindow） | 時系列＋TTL：同じ時間窓のデータをまとめ、すべて期限切れになったらファイルごと捨てる |
| UCS（Unified、5.0） | 上記のどれにも近づけるよう調整でき、新しいバージョンで推奨 |

## 8. ★ レプリケーションと一貫性レベル

```cql
CREATE KEYSPACE shop WITH replication = {'class': 'NetworkTopologyStrategy', 'dc1': 3, 'dc2': 3};
CONSISTENCY QUORUM;     -- cqlsh のコマンド：以後のリクエストは QUORUM を使う
```

- **RF（レプリカ数）**：各データを何部保存するか。本番環境ではよく 3 を使い、`NetworkTopologyStrategy` ならデータセンターごとに設定できる
- **CL（一致性レベル）**：読み書きのたびに「何個のレプリカが応答すれば成功か」をそれぞれ指定する

| CL | 応答が必要なレプリカの数 |
|---|---|
| `ONE` / `TWO` / `THREE` | 1 / 2 / 3 個 |
| `QUORUM` | ⌊RF / 2⌋ + 1（RF = 3 なら 2）、すべてのデータセンターをまたいで数える |
| `LOCAL_QUORUM` | ローカルのデータセンターの quorum。データセンター間の遅延を待たなくてよい（最もよく使う） |
| `EACH_QUORUM` | 各データセンターがそれぞれ quorum に達する（書き込み用） |
| `ALL` | すべてのレプリカ。どれか 1 台でも落ちると失敗 |
| `ANY` | 書き込み専用：hint だけでも成功とみなす |

- ★ **R + W > RF なら強い一貫性**：読み書きともに QUORUM（2 + 2 > 3）なら、読んだレプリカの中に必ず最新のものがある
- 書き込み ONE、読み取り ONE：最も速く、結果整合性で、古いデータを読むことがある
- レプリカが足りないとそのまま失敗：`UnavailableException: … QUORUM (2 required but only 1 alive)`
- 修復の仕組み：**hinted handoff**（レプリカが一時的に落ちたら、コーディネーターがまず hint を記録し、戻ったら書き込む。既定で 3 時間保持）、**read repair**（読み取り時にレプリカの不一致を見つけたら修正）、**anti-entropy repair**（`nodetool repair`、定期的な全体比較）
- CAP：Cassandra は AP システムですが、一貫性はリクエストごとに調整できます（tunable consistency）

## 9. ★ 軽量トランザクション（LWT）と BATCH

```cql
INSERT INTO customers_by_email (email, customer_id, name) VALUES ('a@example.com', 1, '王小明') IF NOT EXISTS;
UPDATE products SET price = 27999 WHERE product_id = 540 IF price = 30000;   -- compare-and-set
UPDATE products SET stock = 9 WHERE product_id = 540 IF EXISTS;

BEGIN BATCH
  INSERT INTO customers (customer_id, name, email) VALUES (99999, '測試', 't@example.com');
  INSERT INTO customers_by_email (email, customer_id, name) VALUES ('t@example.com', 99999, '測試');
APPLY BATCH;
```

- **LWT**：Paxos で「確認してから書き込む」を実現し、`[applied]` を返す（失敗時は現在の値も返す）。単一パーティションのみで、遅延は通常の書き込みの数倍。競合が多いと再試行を繰り返す
- LWT で書き込んだデータは `SERIAL` / `LOCAL_SERIAL` で読む
- ★ 同じデータに LWT と通常の書き込みを混ぜないこと（通常の書き込みは Paxos を通らないので、LWT の保証を壊す）
- **logged BATCH**（既定）：まず batchlog に書き込み、すべてが「最終的に適用される」ことを保証する。非正規化した複数のテーブルの一貫性を保つために使う。**トランザクションではない**：分離性がなく、ROLLBACK もできない
- **unlogged BATCH**：同じパーティション内のときだけ意味がある（1 回の書き込み）
- ★ BATCH は高速化のためのものではない：多数のパーティションにまたがるバッチはコーディネーターを圧迫する（`batch_size_warn_threshold` 5 KiB、`batch_size_fail_threshold` 50 KiB）
- 在庫や残高のような「条件付きで減らす」データは、通常はリレーショナルデータベースか Redis に置く

## 10. インデックス：SAI、セカンダリインデックス、マテリアライズドビュー

```cql
CREATE INDEX orders_customer_idx ON orders (customer_id) USING 'sai';
CREATE INDEX products_tags_idx ON products (tags) USING 'sai';     -- コレクション：CONTAINS
SELECT * FROM products WHERE tags CONTAINS '熱銷' AND price < 1000;  -- 複数の SAI 条件は積集合を取る（price にもインデックスが必要）
DROP INDEX orders_customer_idx;
```

- **SAI**（Storage-Attached Index、5.0）：各 SSTable とともにインデックスを作り、等号、範囲、コレクション、ベクトル検索（ANN）に対応し、複数の条件を組み合わせられる
- ★ インデックスは各ノードの**ローカルインデックス**：パーティションキーがないとすべてのノードに問い合わせる必要があり（scatter-gather）、ノードが多いほど高くつく。頻度の低いクエリ、パーティションキーとの組み合わせ、データ量の少ないテーブルに向いている
- 旧来のセカンダリインデックス（2i）や SASI には多くの制限がある。5.0 以降は SAI が推奨
- マテリアライズドビュー（MV）：別の主キーのテーブルを自動で維持するが、ずっと実験的な機能で既定では無効。実務ではたいてい自分で BATCH を使って複数のテーブルに書き込む

## 11. よくある落とし穴

| 落とし穴 | 説明 |
|---|---|
| INSERT は主キーの重複を報告しない | upsert なのでそのまま上書き。`IF NOT EXISTS` を使う |
| 存在しない行を UPDATE すると行が生える | これも upsert。`IF EXISTS` を使う |
| パーティションキーがないと検索できない | 専用のテーブルや SAI を作るか、`ALLOW FILTERING` の全表スキャンを受け入れる |
| 任意の列で ORDER BY | クラスタリングキーにしか使えない |
| テーブル全体の `COUNT(*)` | 本当に全行を読んで数えるのでタイムアウトする。カウンタテーブルか `nodetool tablestats` の推定値を使う |
| `AVG(int)` | int を返し、小数は切り捨て |
| null の書き込み | 削除と同じで、トゥームストーンができる |
| 時計のずれ | last write wins なので、後の書き込みが先の書き込みに負けることがある |
| Cassandra をキューとして使う | トゥームストーンがたまり、読み取りがどんどん遅くなり、最後は失敗する |
| パーティションが際限なく大きくなる | 時系列はバケットに分ける |
| パーティションキーの `IN` に数千個の値 | コーディネーターの負荷が大きい。単一パーティションのクエリを並行して複数送る方法に変える |
| カウンタの再試行 | 冪等ではないので、2 回加算されることがある |

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

- ★ CqlSession は重い（コネクションプール、metadata）ので、アプリケーション全体で 1 つを共有します。必ず **prepared statement** を使いましょう（解析は 1 回だけで、パーティションキーが分かるので担当ノードに直接送れる：token-aware）
- driver 4 の Maven の座標は `org.apache.cassandra:java-driver-core` に変わっています
- 大きな結果は自動でページングされます（既定 1 ページ 5,000 行）。API のページングでは `getPagingState()` を次のリクエストに渡し、OFFSET は使いません（Cassandra にはない）
- Spring Data の派生クエリは主キーの列しか使えません。他の列を使うなら `@AllowFiltering` か、CQL を自分で書きます
- 書き込みの再試行には注意：普通の INSERT / UPDATE は冪等なので安全に再試行できますが、カウンタ、`list` の追加、LWT はできません

## 13. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| Cassandra の書き込みはなぜ速いのか？ | 書き込み前に読まない。commitlog へのシーケンシャルな書き込み＋memtable。SSTable は変更せず、マージはバックグラウンドの compaction に任せる（LSM tree） |
| パーティションキーとクラスタリングキーの違いは？ | パーティションキーはデータをどのノードに置くかを決め、クラスタリングキーはパーティション内の順序を決めて範囲検索ができる |
| データモデルをどう設計するか？ | クエリ先行：すべてのクエリを列挙 → クエリごとに 1 つのテーブル。非正規化。パーティションの大きさを制御（バケット）。ホットスポットを避ける |
| 一貫性レベルをどう選ぶか？ | 普通は読み書きとも LOCAL_QUORUM。R + W > RF なら強い一貫性。ONE は最も速いが古いデータを読むことがある |
| ノードが 1 台落ちたらどうなるか？ | RF = 3、QUORUM なら通常どおり動く。hinted handoff が記録しておき、戻ったら書き込む。その後 repair |
| トゥームストーンとは？なぜ gc_grace_seconds が必要か？ | 削除はマークを書き込むこと。すべてのレプリカが削除を同期してからでないと消せず、そうしないとデータが復活する |
| LWT とは？代償は？ | Paxos による compare-and-set。単一パーティションのみで遅延が大きい。一意性チェックなど頻度の低い操作に使う |
| BATCH をトランザクションとして使えるか？ | 使えない。logged batch はすべてが最終的に適用されることを保証するだけで、分離性も rollback もない |
| セカンダリインデックスにはなぜ注意が必要か？ | ローカルインデックスなので、パーティションキーがないとすべてのノードに問い合わせる必要がある。頻度の高いクエリには専用のテーブルを作るべき |
| Cassandra vs MongoDB？ | Cassandra：マスターレス、非常に大量の書き込み、複数データセンター、固定のクエリパターン。MongoDB：ドキュメントモデル、柔軟なクエリ、トランザクションあり、プライマリ・セカンダリのレプリケーション |
| Cassandra を使うべきでないのはいつか？ | JOIN、自由なクエリ、トランザクション、強い一貫性のカウントが必要なとき、あるいはデータ量が少ないとき（PostgreSQL 1 台で十分） |

## 14. cqlsh と nodetool のよく使うコマンド

```text
DESCRIBE KEYSPACES;              -- keyspace の一覧（4.0 からはサーバー側で実行）
DESCRIBE TABLE shop.orders;      -- テーブル作成文を見る
CONSISTENCY LOCAL_QUORUM;        -- 以後のリクエストはこの一貫性レベルを使う
TRACING ON;                      -- 以後のクエリにクエリトレースを付ける
EXPAND ON;                       -- 列ごとに 1 行で表示（列が多いときに読みやすい）
COPY shop.products TO 'p.csv' WITH HEADER = true;   -- CSV のエクスポート / インポート

nodetool status                  -- ノードの状態（UN = Up / Normal）、負荷、token 数
nodetool tablestats shop.orders  -- 推定件数、パーティションの大きさ、SSTable の数
nodetool flush / compact / repair
nodetool getendpoints shop orders_by_customer 4242   -- このパーティションがどのノードにあるか
```

# Neo4j

> 例はすべて練習環境（`neo4j-lab` コンテナ、Bolt port 7688、Neo4j Browser http://localhost:7475）のもので、ショーケースの「Cypher コンソール」にそのまま貼り付けて実行できます（常に ROLLBACK するので、安心して書き込みを試せます）。データは PostgreSQL から変換したもので、フォロー関係（FOLLOWS）は模擬データです。

## 1. ★ 基本の考え方と SQL との対応

| リレーショナル | Neo4j（プロパティグラフ） |
|---|---|
| table | label（ラベル）。1 つのノードに複数のラベルを付けられる：`(:Customer:Vip)` |
| row | node（ノード） |
| column | property（プロパティ）。ノードごとに違ってよい |
| 外部キー / 中間テーブル | relationship（リレーションシップ）：必ず**型**と**方向**があり、プロパティも持てる |
| JOIN | リレーションシップをたどる（traversal） |
| SQL | Cypher：探したいパターンを ASCII アートで「描く」 |

```text
(:Customer)-[:PLACED]->(:Order)-[:CONTAINS {qty, unitPrice}]->(:Product)-[:IN_CATEGORY]->(:Category)
(:Customer)-[:FOLLOWS]->(:Customer)      (:Customer)-[:LIVES_IN]->(:City)
```

- ★ **index-free adjacency**：各ノードが自分のリレーションシップを直接記録しているので、1 歩進むコストはデータベース全体の大きさと関係ない。リレーショナルデータベースは JOIN のたびにインデックスを 1 回引く必要がある
- 向いているもの：ソーシャルな関係、レコメンド、不正検知（循環送金）、権限の継承、ナレッジグラフ、サプライチェーン、ネットワークトポロジー——「関係そのものが要点」で、何階層もたどる必要がある問題
- 向いていないもの：テーブル全体の集計レポート、多数の列での絞り込み、単純な CRUD
- インデックスは「起点」を探すためだけに使い、起点が見つかったらリレーションシップをたどる

## 2. パターンの構文

```cypher
(c)                          // 任意のノード、変数 c
(c:Customer)                 // ラベル
(c:Customer {id: 4242})      // プロパティの条件
(a)-[:FOLLOWS]->(b)          // a が b をフォロー（方向あり）
(a)<-[:FOLLOWS]-(b)          // b が a をフォロー
(a)-[:FOLLOWS]-(b)           // 方向を問わない（両方向とも一致）
(a)-[r:FOLLOWS]->(b)         // リレーションシップにも変数を付けられ、r.since でプロパティを取る
(a)-[:FOLLOWS|LIKES]->(b)    // 複数の型
(a)-[:FOLLOWS*1..3]->(b)     // 可変長：1〜3 歩（★ 必ず上限を指定する）
p = (a)-[:FOLLOWS*..5]->(b)  // パス全体を変数 p に保存
```

## 3. 検索：MATCH、WHERE、RETURN、WITH

```cypher
MATCH (c:Customer {id: 4242})-[:PLACED]->(o:Order)
WHERE o.total >= 1000 AND o.orderDate >= datetime('2025-01-01T00:00:00+08:00')
RETURN o.id, o.total
ORDER BY o.orderDate DESC
SKIP 0 LIMIT 10;

MATCH (c:Customer) WHERE NOT (c)-[:PLACED]->() RETURN count(c);   // 一度も注文していない（パターンを条件に）
MATCH (c:Customer) WHERE EXISTS { (c)-[:PLACED]->(:Order {status: 'returned'}) } RETURN count(c);

MATCH (c:Customer) WHERE c.id IN [1, 2, 4242]
OPTIONAL MATCH (c)-[:PLACED]->(o:Order) WHERE o.total >= 50000    // LEFT JOIN … ON のようなもの
RETURN c.id, count(o);

MATCH (:Customer {id: 4242})-[:FOLLOWS]->(f)
WITH f ORDER BY f.id                       // WITH = 途中の RETURN：並べ替え、絞り込み、集約をしてから次に渡す
RETURN collect(f.name);

UNWIND [4242, 1, 2] AS id                  // リストを複数行に展開
MATCH (c:Customer {id: id}) RETURN c.name;
```

| 句 / 関数 | 用途 |
|---|---|
| `WITH` | 結果を次の部分に渡す（WHERE、ORDER BY、LIMIT、集約と組み合わせられる） |
| `OPTIONAL MATCH` | 見つからないときは変数が null になり、行は残る（LEFT JOIN） |
| `UNWIND` | リストを複数行に展開（バッチ書き込みでよく使う） |
| `collect()` | 複数行をリストにまとめる |
| `[x IN list WHERE 条件 \| 式]` | リスト内包表記 |
| `EXISTS { パターン }`、`COUNT { パターン }` | サブクエリの条件 |
| `CASE WHEN … THEN … END` | SQL と同じ |
| `coalesce()`、`toInteger()`、`toFloat()`、`size()`、`keys()`、`labels()`、`type()` | よく使う関数 |

★ パラメータは `$name` を使います：`MATCH (c:Customer {id: $id})`。値を文字列に連結しないこと（Cypher インジェクションの危険があり、実行計画も再利用できない）。

## 4. 集約

```cypher
MATCH (c:Customer)<-[:FOLLOWS]-(fan)
RETURN c.id, c.name, count(fan) AS followers      // GROUP BY はない：集約でない列がグループ化の基準
ORDER BY followers DESC LIMIT 5;
```

- 集約関数：`count()`、`count(DISTINCT x)`、`sum()`、`avg()`、`min()`、`max()`、`collect()`、`percentileCont()`
- ★ `count(*)` は行数を数え、`count(x)` は null を数えない
- ★ 整数同士の割り算は整数のまま：`7 / 2 = 3`。`avg()` は浮動小数点数になる

## 5. ★ パスと走査

```cypher
// 友達の友達（知り合いかも）
MATCH (me:Customer {id: 224})-[:FOLLOWS]->()-[:FOLLOWS]->(fof)
WHERE fof <> me AND NOT (me)-[:FOLLOWS]->(fof)
RETURN count(DISTINCT fof);

// 最短経路（双方向の幅優先探索で、見つかったら止まる）
MATCH p = shortestPath((a:Customer {id: 4242})-[:FOLLOWS*..10]->(b:Customer {id: 19999}))
RETURN length(p), [n IN nodes(p) | n.name];

MATCH p = allShortestPaths((a)-[:FOLLOWS*..10]->(b)) RETURN p;   // 同じ長さの最短パスすべて

// ツリー：カテゴリ配下のすべての子カテゴリ（SQL なら WITH RECURSIVE が必要）
MATCH (c:Category)-[:SUBCATEGORY_OF*1..]->(:Category {name: '3C電子'}) RETURN c.name;
```

- `nodes(p)`、`relationships(p)`、`length(p)`（リレーションシップの数）
- ★ **リレーションシップの一意性**：同じ MATCH パターンの中では、同じリレーションシップを 2 回たどらない（無限ループを防ぐ）。しかし同じノードは何度でも現れ得るので、「友達の友達」に自分自身が含まれることがある（相互フォローのとき）
- ★ 1 本の長いパターン `(p)<-[:CONTAINS]-(:Order)<-[:PLACED]-(c)-[:PLACED]->(:Order)-[:CONTAINS]->(x)` の 2 つの PLACED は別のリレーションシップでなければならない → 「同じ注文」のケースが漏れる。必要なら 2 つの MATCH に分ける
- ★ MATCH が返すのは「マッチのしかたすべて」：同じ人に 2 本のパスでたどり着くと 2 回現れるので、人数を数えるなら DISTINCT
- 高度なアルゴリズム（PageRank、コミュニティ検出、類似度）には GDS（Graph Data Science）ライブラリを使う

## 6. 書き込み：CREATE、MERGE、SET、DELETE

```cypher
MATCH (city:City {name: '台北市'})
CREATE (c:Customer {id: 99999, name: '測試'})-[:LIVES_IN]->(city);   // 「既存」のノードにつなぐ

MATCH (a:Customer {id: 4242}), (b:Customer {id: 1})
MERGE (a)-[:FOLLOWS]->(b);                                       // あれば使い、なければ作る

MERGE (c:Customer {id: 4242})
ON CREATE SET c.name = '新會員', c.createdAt = datetime()
ON MATCH SET c.lastLogin = datetime();

MATCH (p:Product {id: 540})
SET p.stock = 0, p.tags = p.tags + '缺貨', p += {isActive: false}
REMOVE p.discount;                                               // プロパティの削除（SET p.discount = null と同じ）

MATCH (:Customer {id: 4242})-[r:FOLLOWS]->(:Customer {id: 15084}) DELETE r;   // リレーションシップの削除
MATCH (c:Customer {id: 99999}) DETACH DELETE c;                  // ノードとそのすべてのリレーションシップを削除

UNWIND $rows AS row                                              // ★ バッチ書き込み：1 回に 1 バッチずつ送る
MERGE (c:Customer {id: row.id}) SET c.name = row.name;
```

- ★ **MERGE はパターン全体をまとめて照合する**：`MERGE (a:Person {name:'A'})-[:KNOWS]->(b:Person {name:'B'})` で完全なパターンが見つからないと、2 つのノードも一緒に作り直す（重複ノードができる）。正しくは：両端を先に MATCH / MERGE してから、リレーションシップを MERGE する
- MERGE は一意制約と組み合わせる：制約がないと、2 つのトランザクションが同時に MERGE して 2 つのノードができることがある
- `SET p = {…}` はすべてのプロパティを置き換え、`SET p += {…}` は指定したプロパティだけを更新する
- プロパティを null にする = そのプロパティを削除する
- リレーションシップを持つノードは直接 DELETE できない：トランザクションの commit 時にエラーになる（トランザクション内では成功したように見える）。DETACH DELETE を使う
- ★ MATCH で見つからないと、後ろの CREATE / SET は 0 回実行され、**エラーにならない**。書き込まれたかを確認するには、返された行か書き込みの統計を見る
- 大量の削除 / 更新は分割する：`CALL { … } IN TRANSACTIONS OF 10000 ROWS`

## 7. ★ データモデルの設計

| 問題 | アドバイス |
|---|---|
| プロパティかノードか？ | 「つなぐ」か「たどる」ものはノードに（都市、ブランド、タグ）。説明するだけのものはプロパティに |
| リレーションシップのプロパティ | 「2 つの間」の情報はリレーションシップに置く（数量、時刻、重み） |
| 多くの情報を持つ多対多 | 中間ノード（例えば Customer と Product をつなぐ Order）の方が、すべてをリレーションシップに詰め込むより柔軟 |
| ラベル | 分類や絞り込みの高速化に使う（`:Customer:Vip`）。変化する状態を大量のラベルにしないこと |
| リレーションシップの型は具体的に | `:PLACED`、`:FOLLOWS` の方が汎用的な `:RELATED_TO` より良い：クエリで必要な型だけをたどれる |
| ★ スーパーノード | 数十万のリレーションシップを持つノード（有名人、人気のタグ）は、そこを通るクエリを遅くする：方向と型を制限する、時期でリレーションシップの型を分ける、統計値を事前計算する |
| 方向 | 意味に沿って 1 方向で保存すれば十分（両方向に 1 本ずつ保存する必要はない）。クエリでは方向を書かなくてもよい |

## 8. インデックス、制約、PROFILE

```cypher
CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (c:Customer) REQUIRE c.id IS UNIQUE;  // 一意制約（インデックス付き）
CREATE INDEX customer_email IF NOT EXISTS FOR (c:Customer) ON (c.email);              // RANGE インデックス
CREATE INDEX order_comp FOR (o:Order) ON (o.status, o.orderDate);                     // 複合インデックス
CREATE TEXT INDEX product_name FOR (p:Product) ON (p.name);                           // CONTAINS / ENDS WITH
CREATE FULLTEXT INDEX product_ft FOR (p:Product) ON EACH [p.name];                    // 全文検索
CREATE INDEX follows_since FOR ()-[r:FOLLOWS]-() ON (r.since);                        // リレーションシップのプロパティにもインデックスを作れる
SHOW INDEXES;  SHOW CONSTRAINTS;  DROP INDEX customer_email;

PROFILE MATCH (c:Customer) WHERE c.email = 'user04242@example.com' RETURN c;          // 実行して db hits を表示
EXPLAIN MATCH …;                                                                      // 実行せずに計画だけを見る
```

| 演算子 | 意味 |
|---|---|
| `AllNodesScan` | すべてのノードをスキャン（ラベルを書いていない）—— 最悪 |
| `NodeByLabelScan` | あるラベルのすべてのノードをスキャン（使えるインデックスがない） |
| `NodeIndexSeek` / `NodeUniqueIndexSeek` | インデックスで起点を見つける ✓ |
| `NodeIndexSeekByRange` / `NodeIndexContainsScan` | 範囲 / TEXT インデックス |
| `Expand(All)` / `Expand(Into)` | リレーションシップをたどる |
| `Filter` | 1 行ずつ絞り込む |
| `CartesianProduct` | つながっていない 2 つのパターン ⚠ |
| `Eager` | すべて読み終えてから書き込む（読み書きの衝突を避ける）。データが多いとメモリを使う |

- ★ **db hits** = ストレージ層へのアクセス回数で、ミリ秒より安定したコストの指標（実験室：インデックスなし 40,003 → インデックスあり 4）
- プロパティに演算をする（`c.id + 0 = 4242`、`toString(c.id) = '4242'`）とインデックスを使えない
- RANGE インデックス：=、範囲、STARTS WITH、IS NOT NULL。TEXT インデックス：CONTAINS、ENDS WITH
- コミュニティ版には一意制約がある。プロパティの存在制約、Node Key、プロパティの型制約はエンタープライズ版の機能

## 9. トランザクションとクラスタ

- ACID トランザクションで、既定の分離レベルは read committed。書き込み時にノード / リレーションシップをロックするので、デッドロックが起こり得る（TransientException。driver の managed transaction は自動で再試行する）
- ★ 1 つのトランザクション内の文は、すべて commit されるかすべて rollback されるか。一部のチェック（例えばリレーションシップを持つノードの削除）は commit 時に初めて行われる
- クラスタ（エンタープライズ版）：primary サーバーが Raft で過半数合意の書き込みを行い、secondary サーバーが読み取りのスケールを担う
- **bookmark**：書き込み後に bookmark を受け取り、次の読み取りでそれを渡すと、自分が書いたばかりのデータが必ず読める（causal consistency）。driver の session が自動で処理する
- コミュニティ版：単一サーバー、ユーザーデータベースは 1 つだけ（neo4j）、ロールによる権限はない

## 10. よくある落とし穴

| 落とし穴 | 説明 |
|---|---|
| パターン全体を MERGE | 完全なパターンが見つからないとすべて作り直し、重複ノードができる |
| 方向を書かない | 両方向とも一致する（フォロー 6 + フォロワー 4 = 10） |
| `= null` | 決して見つからない。`IS NULL` を使う |
| OPTIONAL MATCH の後ろの WHERE | OPTIONAL MATCH の中に書けばマッチ条件（行は残る）。後ろの WITH … WHERE に書くと null が除外される |
| 関係のない 2 つのパターンをカンマでつなぐ | デカルト積（891 × 83 = 73,953 行）になり、サーバーが警告する |
| DISTINCT を忘れる | パスごとに 1 行になる |
| 友達の友達に自分が含まれる | リレーションシップは一意だがノードは重複し得る。`fof <> me` を忘れずに |
| 1 本の長いパターンでデータが漏れる | 同じリレーションシップは 2 回使われない → 2 つの MATCH に分ける |
| 型の不一致 | `{id: '4242'}`（文字列）では `id: 4242` は見つからず、エラーにもならない |
| 整数同士の割り算 | `sum(x) / count(x)` は小数を切り捨てる |
| MATCH で見つからないと何も起きない | 後ろの CREATE は 0 回実行され、エラーにならない |
| 上限のない `*` | 大きなグラフでは数百万本のパスをたどることがある |

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
    @Id private Long id;                       // 業務上の id。または @Id @GeneratedValue で内部 id を使う
    private String name;
    @Relationship(type = "FOLLOWS", direction = Relationship.Direction.OUTGOING)
    private Set<Customer> follows;
}

public interface CustomerRepository extends Neo4jRepository<Customer, Long> {
    @Query("MATCH (:Customer {id: $id})-[:FOLLOWS]->(f) RETURN f")
    List<Customer> following(Long id);
}
```

- ★ Driver は重いので、アプリケーション全体で 1 つを共有します。Session は軽いので、使い終わったら閉じます
- `executeRead` / `executeWrite`（managed transaction）は一時的なエラーで自動的に再試行するので、トランザクション関数は繰り返し実行できるものにします（中でメールを送らないこと）
- 常にパラメータ `$id` を使い、文字列の連結はしないこと
- 大量の書き込みは `UNWIND $rows` で 1 バッチずつ送ります（このプロジェクトのデータ読み込み：1 バッチ 5,000 件）
- Spring Data Neo4j はリレーションシップを持つエンティティを読み込むとき、グラフの大きな部分を一度に引き戻すことがあります。大きなクエリにはカスタム Cypher か射影（projection）を使います
- `elementId()` / 内部 id を業務上の id にしないこと：削除後に再利用されることがあります

## 12. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| グラフデータベースとリレーショナルデータベースの違いは？ | リレーションシップが保存されている（index-free adjacency）ので、1 歩のコストはデータ総量と関係ない。JOIN はクエリ時にインデックスで照合する |
| いつグラフデータベースを使うか？ | 関係そのものが要点で、何階層もたどり、階層数が決まっていないとき：ソーシャル、レコメンド、不正検知、権限、ナレッジグラフ |
| グラフデータベースは必ず速いか？ | そうとは限らない。実験室：8 万本のフォロー関係では、「何歩以内に何人に到達できるか」は PostgreSQL でも同じくらい速い。しかし最短経路（8 歩）では SQL が数十倍遅い |
| Cypher の MERGE で注意することは？ | パターン全体をまとめて照合する。両端を MATCH してからリレーションシップを MERGE する。一意制約と組み合わせる |
| スーパーノードとは？どう扱うか？ | リレーションシップが非常に多いノード。方向と型を制限する、リレーションシップの型を分ける、事前計算する |
| クエリの性能をどう見るか？ | PROFILE で演算子と db hits を見る。起点が LabelScan ではなく IndexSeek であることを確認する |
| プロパティかノードか？ | つないだりたどったりするものはノードに、説明するだけのものはプロパティに |
| レコメンドをどう作るか？ | 協調フィルタリング：商品 ← 買った人 → その人たちが買った他の商品を、共通の購入者数で並べる |
| Neo4j はどうスケールするか？ | エンタープライズ版のクラスタ：primary（Raft で書き込み）＋ secondary（読み取りのスケール）。データ量が非常に多いときは Fabric / 複合データベースでシャーディング |

## 13. ツールとコマンド

```text
cypher-shell -a bolt://localhost:7688 -u neo4j -p neo4j-lab     # コマンドライン
docker exec -it neo4j-lab cypher-shell -u neo4j -p neo4j-lab
http://localhost:7475                                            # Neo4j Browser（結果をグラフで描画）

SHOW INDEXES;  SHOW CONSTRAINTS;  SHOW TRANSACTIONS;
CALL db.schema.visualization();                                  # ラベルとリレーションシップの型の構造を見る
CALL db.labels();  CALL db.relationshipTypes();  CALL db.propertyKeys();
neo4j-admin database import full …                               # オフラインで CSV を大量インポート（最速）
```

- APOC：よく使うユーティリティのプロシージャ（インポート / エクスポート、バッチ、日付処理）。GDS：グラフアルゴリズム（PageRank、最短経路、コミュニティ検出）

# TimescaleDB

> 例はすべて練習環境（`timescale-lab` コンテナ、port 5435、データベース `metrics`）のもので、ショーケースの「SQL コンソール」にそのまま貼り付けて実行できます。page_views は 2026-04〜09 の商品閲覧記録（191 万件）、sensor_readings は 2026-09 の倉庫センサーの測定値（1 分に 1 件）です。

## 1. ★ 基本の考え方

- **TimescaleDB = PostgreSQL の拡張**：これまでどおり SQL、JOIN、トランザクション、インデックスを使え、PostgreSQL のツール（psql、JDBC、Spring Data JPA）もそのまま使える
- **hypertable**：1 つのテーブルに見えるが、内部では時間で自動的に多数の **chunk**（普通の PostgreSQL のテーブル）に分けられ、書き込み時に新しい chunk が自動で作られる
- 時系列データの特徴：大量の書き込み、ほぼ「末尾への追加」だけ、古いデータはめったに変更しない、クエリはほとんどが「ある期間」「時間ごとの集計」、古いデータは精度を落とすか削除する
- TimescaleDB はこれらの特徴に合わせて次を加えている：**chunk exclusion**、**time_bucket** などの時間関数、**連続集約**、**圧縮（columnstore）**、**データ保持ポリシー**

| | PostgreSQL のネイティブパーティション | TimescaleDB の hypertable |
|---|---|---|
| パーティションの作成 | 自分で（または pg_partman で）各パーティションを事前に作る | 書き込み時に chunk が自動で作られる |
| パーティションの大きさ | 自分で設計 | `chunk_time_interval`（既定 7 日） |
| 時間関数 | `date_trunc` | `time_bucket`（任意の長さ）、gapfill、first / last |
| 事前集計 | MATERIALIZED VIEW（毎回全体を再計算） | 連続集約（増分更新、最新データとリアルタイムに合わせられる） |
| 圧縮 | なし（TOAST だけ） | 列指向の圧縮で、90% 以上がよくある |
| 古いデータの削除 | パーティションを DROP | `drop_chunks`、保持ポリシーで自動実行 |

## 2. Hypertable と chunk

```sql
CREATE TABLE page_views (
  view_time   timestamptz NOT NULL,
  product_id  int         NOT NULL,
  customer_id int,
  device      text        NOT NULL
);
SELECT create_hypertable('page_views', by_range('view_time', INTERVAL '7 days'));   -- 2.13 からの書き方
-- 古い書き方：SELECT create_hypertable('page_views', 'view_time', chunk_time_interval => INTERVAL '7 days');
SELECT set_chunk_time_interval('page_views', INTERVAL '1 day');                    -- 以後に作られる chunk に適用

SELECT show_chunks('page_views');
SELECT * FROM timescaledb_information.chunks WHERE hypertable_name = 'page_views';
SELECT hypertable_size('page_views'), approximate_row_count('page_views');
```

- ★ **chunk exclusion**：クエリに時間範囲があると、関係する chunk だけを読む（実験室：9/1 の 1 日は 27 個中 1 個の chunk だけを読んで 0.6 ms。すべてだと 30 ms）
- ★ 時刻の列に演算をする（`view_time::date = …`、`date_trunc('day', view_time) = …`）と chunk を除外できず、27 個すべてを読む（100 倍遅い）。常に `time >= 開始 AND time < 終了` と書く
- `now() - interval '7 days'` でも chunk を除外できる：TimescaleDB は計画時にこれを定数として先に計算する（実行計画の Index Cond に計算済みの時刻が追加される）
- hypertable に作ったインデックスは自動で各 chunk に作られる。`create_hypertable` は既定で `(time DESC)` のインデックスを作る
- ★ **一意インデックス / 主キーには必ず時刻の列を含める**（各 chunk がそれぞれ一意性をチェックするため）
- chunk の大きさの目安：最新の chunk（とそのインデックス）がメモリの 25% 程度に収まること。小さすぎる → chunk が多すぎて、時間条件のないクエリがインデックスを何度も引く。大きすぎる → 除外の効果が落ちる
- chunk の境界は UTC で揃う（7 日の chunk は木曜日 00:00 UTC から始まる）
- 2 つ目の次元（空間パーティション）を追加できる：`add_dimension('t', by_hash('device_id', 4))`。ほとんどの場合は不要

## 3. ★ time_bucket と時系列関数

```sql
SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day, count(*)       -- ★ 1 日以上にはタイムゾーンが必要
FROM page_views
WHERE view_time >= '2026-09-01 00:00+08' AND view_time < '2026-10-01 00:00+08'
GROUP BY day ORDER BY day;

SELECT time_bucket('15 minutes', time) AS t, avg(temperature) FROM sensor_readings … GROUP BY t;
SELECT time_bucket('1 month', view_time, 'Asia/Taipei') AS month, count(*) …;   -- 暦の月ごと
SELECT time_bucket('1 week', time, 'Asia/Taipei', origin => '2000-01-02') …;     -- 日曜日から始める

SELECT sensor_id, first(temperature, time), last(temperature, time)            -- 時刻順の最初 / 最後の値
FROM sensor_readings WHERE time >= … GROUP BY sensor_id;

SELECT time_bucket_gapfill('1 hour', time) AS hour,                            -- データのない区間も表示する
       avg(temperature),
       locf(avg(temperature)),                                                  -- 前の値で埋める
       interpolate(avg(temperature))                                            -- 線形補間
FROM sensor_readings
WHERE sensor_id = 7 AND time >= '2026-09-10 08:00+08' AND time < '2026-09-10 16:00+08'
GROUP BY hour ORDER BY hour;
```

- ★ timestamptz に対する time_bucket は**既定で UTC で区切る**：台湾の 1 日が午前 8 時から始まってしまうので、`'1 day'`、`'1 week'`、`'1 month'` にはどれもタイムゾーンの引数を付ける（データベースの timezone 設定は time_bucket に影響しない）
- `'30 days'` ≠ 1 か月：固定長の区間は origin（2000-01-03）から区切られ、月とは揃わない
- `'1 week'` は既定で月曜日から始まる
- ★ `time_bucket_gapfill` は WHERE から開始と終了を推定できないとエラーになる
- 普通の time_bucket は「データのある」区間しか返さないので、グラフにすると途中の空白が消える。監視データでは gapfill をよく使う
- 移動平均や前期との比較：time_bucket にウィンドウ関数を組み合わせる（`avg() OVER (ORDER BY day ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)`、`lag()`）
- 高度な関数（approx_percentile、time_weight、counter_agg、HyperLogLog）は timescaledb-toolkit 拡張にある

## 4. ★ 連続集約（Continuous Aggregate）

```sql
CREATE MATERIALIZED VIEW sensor_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', time) AS hour, sensor_id,
       avg(temperature) AS avg_temp, max(temperature) AS max_temp, count(*) AS readings
FROM sensor_readings
GROUP BY hour, sensor_id
WITH NO DATA;

CALL refresh_continuous_aggregate('sensor_hourly', '2026-09-01', '2026-10-01');   -- ある期間を手動で refresh
SELECT add_continuous_aggregate_policy('sensor_hourly',
  start_offset => INTERVAL '3 days', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '30 minutes');
ALTER MATERIALIZED VIEW sensor_hourly SET (timescaledb.materialized_only = false);  -- リアルタイム集約
```

- 増分更新：「データが変わった時間範囲」だけを再計算する（PostgreSQL の MATERIALIZED VIEW は毎回全体を再計算する）
- ★ **2.13 からは既定で `materialized_only = true`**：まだ refresh されていない最新データは見えない（落とし穴問題：9/24 以降は 0）
- リアルタイム集約（`materialized_only = false`）：マテリアライズ済みの部分 + watermark 以降の元データをその場で計算する。データは最新だが、クエリは遅くなる
- 実験室：同じレポートが元データでは 40 ms、連続集約からなら 7 ms
- ★ 集約後の数値をさらに集計する：count や sum は再度 sum できるが、**平均値をさらに平均することはできない**（sum と count を保存して最後に割り算する）。集約テーブルの `count(*)` はグループ数で、元の件数ではない
- 制限：集約は区間ごとに計算してからマージできる必要があるので、`count(DISTINCT …)` は使えない。連続集約の上にさらに連続集約を作れる（階層：分 → 時 → 日）
- 元データの削除（保持ポリシー）でマテリアライズ済みの集約は消えない：よくあるのは「元データは 30 日、時間ごとの集約は 2 年保持」

## 5. ★ 圧縮（columnstore）

```sql
ALTER TABLE page_views SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'device',
  timescaledb.compress_orderby   = 'view_time DESC'
);
SELECT compress_chunk(c) FROM show_chunks('page_views', older_than => INTERVAL '7 days') c;
SELECT add_compression_policy('page_views', INTERVAL '7 days');   -- 7 日より前の chunk を自動で圧縮
SELECT * FROM chunk_compression_stats('page_views');              -- 圧縮前後のサイズ
-- 2.18 からは columnstore とも呼ぶ：ALTER TABLE … SET (timescaledb.enable_columnstore, timescaledb.segmentby = …)、add_columnstore_policy
```

| segmentby（実験室での実測、9 月の 33 万件） | 圧縮率 | 説明 |
|---|---:|---|
| グループ化なし | 7.6 倍 | |
| `device`（3 種類の値） | 9.6 倍 | ★ デバイスでのクエリが 45 ms → 4 ms |
| `product_id`（1,500 種類の値） | 1.9 倍 | 各グループが数行しかなく、1 つのバッチを満たせないので圧縮率が悪い |

- 圧縮した chunk は列指向で保存される：1,000 行ごとに 1 つにまとめ、各列に適したアルゴリズム（delta-of-delta、辞書、Gorilla…）を使う
- ★ **segmentby** には「絞り込みによく使い、値の種類が多くない」列（デバイス、センサー id、地域）を選ぶ。**orderby** には通常時刻を置く
- 「もうあまり変わらない古いデータ」の圧縮に向いている。圧縮後も INSERT / UPDATE / DELETE はできる（2.11 から）が、コストが高い
- 少数の列だけを読む分析クエリは、圧縮後の方が通常は速い（読むデータが少ない）。1〜2 行だけの点検索は少し遅くなることがある

## 6. データ保持

```sql
SELECT drop_chunks('page_views', older_than => '2026-05-01'::timestamptz);     -- chunk を丸ごと捨てる
SELECT add_retention_policy('page_views', INTERVAL '6 months');                -- 自動で実行
SELECT * FROM timescaledb_information.jobs;                                    -- すべてのバックグラウンドジョブ（圧縮、refresh、保持）
```

- ★ DELETE は 1 行ずつ削除し、WAL を書き、さらに VACUUM も必要：実測で 7 万件の削除に 5 秒かかる。`drop_chunks` なら 5 つの chunk を捨てるのに数ミリ秒
- 捨てるのは「範囲全体が条件より古い」chunk だけで、境界の chunk は残る
- よくある階層化：元データは 30 日 → 圧縮 → 保持ポリシーで削除。連続集約はもっと長く保持する

## 7. 書き込み

- 書き込みは普通のテーブルと同じ（INSERT、COPY、バッチ）。時系列はほとんどが末尾への追加で、書き込み中の chunk は小さくインデックスもメモリにあるので、書き込みは速い
- バッチ書き込み：複数行の VALUES、`COPY`、JDBC バッチ（`reWriteBatchedInserts=true`）
- 遅れて届いたデータ（late data）は古い chunk に書き込まれる。マテリアライズ済みの連続集約は次の refresh まで更新されない。圧縮済みの chunk への書き込みはコストが高い
- UPSERT：`INSERT … ON CONFLICT (sensor_id, time) DO UPDATE`（一意インデックスに時刻の列を含める必要がある）

## 8. よくある落とし穴

| 落とし穴 | 説明 |
|---|---|
| time_bucket にタイムゾーンを指定しない | UTC で区切られ、台湾の 1 日が午前 8 時から始まり、毎日の数値がずれる |
| `'30 days'` を 1 か月とみなす | 月と揃わないので、`'1 month'` を使う |
| 時刻の列を型変換する | `time::date = …` は chunk exclusion を無効にする |
| gapfill に範囲を指定しない | エラー：could not infer start from WHERE clause |
| 連続集約に最新のデータがない | materialized_only は既定で true。refresh ポリシーを設定するか、リアルタイム集約を有効にする |
| 集約テーブルの count(*) | 数えるのはグループ数なので、sum(views) を使う |
| 平均値をさらに平均する | グループごとの件数が違うと結果が間違う |
| 時刻の列のない一意インデックス | 作成できない |
| segmentby に値の多い列を選ぶ | 圧縮率が悪い |
| DELETE で古いデータを削除する | 遅く、不要データが残る。drop_chunks / 保持ポリシーを使う |
| chunk を細かくしすぎる | chunk が多すぎて、計画も時間条件のないクエリも遅くなる |

## 9. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| TimescaleDB とは？ | PostgreSQL の時系列拡張：hypertable が時間で自動的に chunk に分かれ、さらに時間関数、連続集約、圧縮、保持ポリシーがある |
| データが増え続けても、最近のデータの検索がなぜ速いのか？ | chunk exclusion：関係する時間範囲の chunk だけを読む。最新の chunk とインデックスはメモリにある |
| chunk の大きさはどう決めるか？ | 最新の chunk がメモリの 25% に収まること。書き込み量に応じて chunk_time_interval を調整する |
| 連続集約とマテリアライズドビューの違いは？ | 増分更新（変わった部分だけを計算）、スケジュールできる、最新データとリアルタイムに合わせられる |
| 圧縮はどう設定するか？ | segmentby にはよく絞り込む値の少ない列、orderby には時刻。古い chunk だけを圧縮する |
| 古いデータはどう扱うか？ | 圧縮 → ダウンサンプリング（連続集約）→ drop_chunks / 保持ポリシー |
| TimescaleDB vs InfluxDB？ | TimescaleDB は SQL で、リレーショナルなデータと JOIN でき、PostgreSQL のエコシステムがある。InfluxDB はメトリクス専用に設計され、書き込みと tag のモデルがより単純 |
| TimescaleDB が不要なのはいつか？ | データ量が少なく（数百万件以内）、時間での大量の集計も不要なら、PostgreSQL に BRIN やネイティブパーティションで十分 |

## 10. Java / Spring

- 中身は PostgreSQL なので、JDBC URL、JPA、JdbcTemplate、Flyway はそのまま使える。hypertable は Flyway のマイグレーションで `SELECT create_hypertable(…)` を使って作る
- JPA エンティティの主キーには時刻の列を含める（複合主キー `@IdClass` / `@EmbeddedId`）か、hypertable では JPA を使わず JdbcTemplate を使う
- 大量の書き込み：`reWriteBatchedInserts=true` + JDBC バッチ、または PostgreSQL の `CopyManager`（COPY）
- time_bucket のような関数は JPQL では直接使えない：ネイティブ SQL（`@Query(nativeQuery = true)`）か JdbcTemplate を使う

# pgvector

> 例はすべて練習環境（`pg-lab` コンテナの `vectors` データベース、port 5434）のもので、ショーケースの「SQL コンソール」にそのまま貼り付けて実行できます。products は 1,500 件の商品（64 次元）で、`embed(テキスト)` は語彙で作ったミニ埋め込みモデルです。passages は 10 万件の 128 次元の模擬文書チャンクです（インデックス実験用）。

## 1. ★ 基本の考え方

- **埋め込み（embedding）**：モデルがテキストや画像を数値の列（ベクトル）に変換し、意味の近いものはベクトルも近くなる。384〜3072 次元がよくある
- **ベクトル検索**：クエリのベクトルで「最も近い k 件」（k-nearest neighbors）を探す。セマンティック検索、レコメンド、重複除去、分類、RAG に使う
- **pgvector = PostgreSQL の拡張**：`vector` 型、距離演算子、ベクトルインデックス（HNSW、IVFFlat）を追加する。ベクトルは普通の列と同じテーブルに置けるので、WHERE、JOIN、トランザクションが使える
- **RAG（Retrieval-Augmented Generation）**：文書をチャンクに分ける → 各チャンクを埋め込んでデータベースに保存 → 質問も埋め込む → 最も近いチャンクをいくつか探す → 質問と一緒に LLM に渡して答えさせる

| | pgvector | 専用のベクトルデータベース（Pinecone、Milvus、Qdrant、Weaviate） |
|---|---|---|
| 絞り込み、JOIN | 普通の SQL で、リレーショナルなデータと一緒に検索 | 別途保存した metadata でしか絞り込めない |
| トランザクション、バックアップ、権限 | PostgreSQL の既存の仕組み | それぞれ独自の仕組み |
| 規模 | 1 台で数千万件規模なら問題なし。それ以上はパーティションや読み取りレプリカ | 数十億件、分散を前提に設計 |
| 運用 | 別のシステムを増やさなくてよい | サービスが 1 つ増える（または有料の SaaS） |

★ 面接での結論：既に PostgreSQL を使っていて、データ量が数千万件以内なら、まず pgvector を使います。超大規模、非常に低いレイテンシ、マルチモーダルの高度な機能が必要になったら、専用のデータベースを検討します。

## 2. 型、演算子、関数

```sql
CREATE EXTENSION vector;
CREATE TABLE products (id int PRIMARY KEY, name text, embedding vector(64));   -- 次元数を宣言し、違う次元数は書き込めない
INSERT INTO products VALUES (1, 'x', '[0.1, 0.2, …]');

SELECT id FROM products ORDER BY embedding <=> '[…]' LIMIT 5;                  -- 最も近い 5 件
SELECT 1 - (a.embedding <=> b.embedding) AS cosine_similarity FROM …;
SELECT avg(embedding), sum(embedding) FROM products;                            -- ベクトルも集約できる
SELECT vector_dims(embedding), vector_norm(embedding), l2_normalize(embedding);
SELECT subvector(embedding, 1, 16), embedding::halfvec, binary_quantize(embedding);
```

| 演算子 | 距離 | インデックスの operator class | 説明 |
|---|---|---|---|
| `<->` | L2（ユークリッド） | `vector_l2_ops` | 長さと向きを見る |
| `<=>` | cosine 距離 = 1 - cosine 類似度 | `vector_cosine_ops` | 向きだけを見る。範囲は 0〜2 |
| `<#>` | ★ **負の**内積 | `vector_ip_ops` | 小さいほど似ている。ベクトルの長さがすべて 1 なら最速 |
| `<+>` | L1（マンハッタン） | `vector_l1_ops` | 0.7 から |
| `<~>`、`<%>` | Hamming、Jaccard | `bit_hamming_ops`、`bit_jaccard_ops` | bit 型 |

| 型 | 1 次元あたり | インデックスの上限 | 用途 |
|---|---|---|---|
| `vector` | 4 バイト（float4） | 2,000 次元 | 既定 |
| `halfvec` | 2 バイト（float2） | 4,000 次元 | 容量を半分にし、再現率はほぼ変わらない |
| `bit` | 1 ビット | 64,000 次元 | binary quantization |
| `sparsevec` | 0 でない値だけを保存 | 0 でない値 1,000 個 | 疎ベクトル（SPLADE、BM25 系） |

- ★ ベクトルをすべて長さ 1 に正規化すると、cosine、L2、内積の順位は同じになる（OpenAI などのモデルの出力は既に長さ 1）。内積（最速）を選べる
- ★ どの演算子も「小さいほど近い」：インデックスは小さい順の並べ替えにしか対応していないので、内積は符号を反転している
- 1 つの列には同じモデルが生成したベクトルだけを入れる。異なるモデルのベクトル同士は比較できない

## 3. ★ セマンティック検索

```sql
-- テキスト → ベクトル：実際のシステムではアプリケーションが埋め込みモデルの API を呼ぶ。ここでは embed() を使う
SELECT id, name FROM products ORDER BY embedding <=> embed('通勤 安靜') LIMIT 5;   -- アクティブノイズキャンセリングのイヤホンが見つかる

-- 通常の条件と一緒に使う
SELECT id, name, price FROM products
WHERE price <= 1000 AND category IN ('男裝', '女裝')
ORDER BY embedding <=> embed('冬天 保暖') LIMIT 5;

-- 類似商品（自分自身を除くのを忘れずに）
SELECT id, name FROM products WHERE id <> 806
ORDER BY embedding <=> (SELECT embedding FROM products WHERE id = 806) LIMIT 5;

-- 各行について k 個の近傍を探す：LATERAL
SELECT s.id, n.id FROM products s
CROSS JOIN LATERAL (SELECT p.id FROM products p WHERE p.id <> s.id ORDER BY p.embedding <=> s.embedding LIMIT 2) n;

-- レコメンド：ユーザーのベクトル = 購入した商品の平均
WITH taste AS (SELECT avg(p.embedding) AS v FROM purchases u JOIN products p ON p.id = u.product_id WHERE u.customer_id = 224)
SELECT p.id FROM products p, taste WHERE p.id NOT IN (…購入済み…) ORDER BY p.embedding <=> taste.v LIMIT 5;

-- k-NN 分類：最も近い 15 件で投票
SELECT category, count(*) FROM (SELECT category FROM products ORDER BY embedding <=> embed('上班 通勤') LIMIT 15) s
GROUP BY category ORDER BY count(*) DESC;
```

- セマンティック検索は「意味が近い」こと（同義語、言い換え）に強く、固有名詞に弱い：ブランド、型番、品番はモデルが知らなかったり間違えたりしがち → キーワードかハイブリッド検索を使う
- 距離のしきい値はデータを見て決める：高次元空間では無関係なものの cosine 類似度は 0 付近で、関係のあるものも 0.8 に届くとは限らない
- RAG の品質はほとんど「チャンク分割（chunking）」で決まる：長すぎると無関係な内容が混ざり、短すぎると文脈を失う。200〜800 トークンで、隣り合うチャンクを少し重ねるのが一般的

## 4. ★ ベクトルインデックス：HNSW vs IVFFlat

```sql
CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops);                          -- m = 16, ef_construction = 64
CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops) WITH (m = 32, ef_construction = 128);
SET hnsw.ef_search = 100;                                                                    -- 既定 40

CREATE INDEX ON passages USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);     -- データを読み込んだ「後」に作る
SET ivfflat.probes = 10;                                                                     -- 既定 1

SET maintenance_work_mem = '2GB';                -- ★ グラフがメモリに収まって初めて HNSW の作成が速くなる
SET max_parallel_maintenance_workers = 7;        -- インデックスを並列で作成（Docker の既定の /dev/shm は 64 MB しかないので、shm_size を増やす）
```

実測（10 万件 × 128 次元、100 件のクエリの平均、再現率 = 正確な検索の上位 10 件との一致）：

| やり方 | 再現率 | 1 回のクエリ | インデックス作成 | インデックスのサイズ |
|---|---|---|---|---|
| 正確な検索（インデックスなし） | 100% | 19 ms | — | — |
| HNSW、ef_search = 10 | 94% | 1.2 ms | 38 秒 | 79 MB |
| HNSW、ef_search = 40（既定） | 99% | 1.3 ms | | |
| HNSW、ef_search = 200 | 100% | 2.9 ms | | |
| IVFFlat lists = 100、probes = 1 | 45% | 0.9 ms | 0.8 秒 | 53 MB |
| IVFFlat lists = 100、probes = 10 | 81% | 2.7 ms | | |
| IVFFlat lists = 100、probes = 30 | 95% | 6.6 ms | | |
| IVFFlat lists = 1000、probes = 1 | 88% | 0.9 ms | 9 秒 | 56 MB |

| | HNSW | IVFFlat |
|---|---|---|
| 仕組み | 多層の近傍グラフで、上の層からより近い近傍へとたどる | k-means で lists 個のクラスタに分け、最も近い probes 個のクラスタだけを見る |
| 検索のパラメータ | `hnsw.ef_search`（返す最大件数でもある） | `ivfflat.probes` |
| 作成のパラメータ | `m`、`ef_construction` | `lists`（推奨は 件数 / 1000、100 万件以上なら √件数） |
| 再現率 / 速度 | 良い | 同じ再現率なら遅い |
| インデックス作成 | 遅く、メモリを使う | 速い |
| 空のテーブルで作成 | できる（書き込みながらグラフを作る） | できない：作成時のデータでクラスタを分けるので、データが増えたら作り直す |

- ★ どちらも**近似**最近傍（ANN）です。本番投入前に自分のデータで再現率を計測してから、ef_search / probes を調整しましょう
- ★ インデックスを使える 3 つの条件：`ORDER BY 列 演算子 値`（小さい順）＋ `LIMIT` ＋ 演算子が operator class と一致すること
- 小さなテーブル（数万件以内）なら正確な検索で十分速く、ベクトルインデックスは必須ではありません。正確な検索の再現率は常に 100% です
- 検索のパラメータを `SET LOCAL` で設定すると現在のトランザクションだけに効くので、「この文だけは特に正確に」という場合に向いています

## 5. ★ 絞り込みとマルチテナント

```sql
SELECT id FROM passages WHERE tenant_id = 7 ORDER BY embedding <=> $1 LIMIT 10;   -- ★ 10 件に満たないことがある

SET hnsw.iterative_scan = relaxed_order;     -- 0.8 から：候補が足りなければさらに探し続ける（strict_order は距離順を保証）
SET hnsw.max_scan_tuples = 20000;            -- 最大でスキャンする件数（既定 2 万）
SET ivfflat.iterative_scan = relaxed_order;  -- IVFFlat にもある（ivfflat.max_probes）

CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops) WHERE tenant_id = 7;   -- 大きなテナント用の部分インデックス
```

- ★ **post-filtering**：HNSW はまず ef_search（40）個の候補を探し、その後で WHERE を適用する。テナント 7 は 2% しかないので、実測では平均 0.7 件しか返らず、しかもエラーにならない
- 実測（テナント 7）：iterative_scan = relaxed_order は必ず 10 件そろい、再現率 77%、8 ms。ef_search = 1000 は再現率 89%、12 ms。正確な検索は 100%、10 ms
- 絞り込み後に残るデータが少なければ、正確な検索の方が正確で速い（`WITH t AS MATERIALIZED (SELECT … WHERE tenant_id = 7) SELECT … FROM t ORDER BY … LIMIT 10`）
- テナントが多く、どれも大きい：tenant_id でパーティション分割し（`PARTITION BY LIST`）、パーティションごとに HNSW インデックスを持たせる

## 6. 量子化と次元削減

```sql
CREATE INDEX ON passages USING hnsw ((embedding::halfvec(128)) halfvec_cosine_ops);
SELECT id FROM passages ORDER BY embedding::halfvec(128) <=> $1::halfvec(128) LIMIT 10;   -- 同じ式で書く必要がある

CREATE INDEX ON passages USING hnsw ((binary_quantize(embedding)::bit(128)) bit_hamming_ops);
SELECT id FROM (                                                  -- bit で候補を集める → 元のベクトルでリランク
  SELECT id, embedding FROM passages ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize($1) LIMIT 100
) c ORDER BY embedding <=> $1 LIMIT 10;
```

| インデックス（10 万件 × 128 次元） | サイズ | 再現率 |
|---|---|---|
| vector | 79 MB | 99% |
| halfvec | 54 MB | 99.9% |
| bit | 30 MB | 42% |
| bit で 100 件集める → リランク | 30 MB | 95% |

- 1536 次元 × 1000 万件 = 60 GB のベクトルで、HNSW インデックスにもう 1 部必要。量子化は精度と容量の交換
- ★ halfvec はほぼ損失がない。3072 次元のモデル（vector インデックスの上限 2,000 次元を超える）は必ず halfvec でインデックスを作る
- binary quantization は 1,000 次元以上のモデルに向いていて、必ずリランクと組み合わせる
- 次元削減：Matryoshka 系のモデル（OpenAI text-embedding-3）は先頭の 256 / 512 次元をそのまま使える（`subvector`。正規化し直すのを忘れずに）

## 7. ハイブリッド検索（hybrid search）

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

- セマンティック検索は「意味が近い」ことを、キーワード（全文検索 / BM25）は固有名詞、型番、完全一致の文字列を担当する。両方行ってからマージする
- ★ **RRF（Reciprocal Rank Fusion）**：各リストで r 位なら 1 / (60 + r) 点として合計して並べる。順位だけを見るので、両側のスコアの単位の違いを気にしなくてよい
- 中国語の全文検索には分かち書きの処理が必要（pg_bigm、zhparser）。またはキーワード側を Elasticsearch に任せる
- さらに進めるなら：上位 50 件を reranker（cross-encoder）モデルに渡して並べ直す

## 8. 書き込みとメンテナンス

- ベクトルはテキストから計算した派生データ：★ **テキストが変わったらベクトルを再計算する**。モデルを変えたらすべて再計算するので、各ベクトルのモデルとバージョンを記録しておく
- 埋め込みは遅く、お金もかかる：まとめて呼ぶ、非同期にする（テキストを書き込んだ後にバックグラウンドジョブでベクトルを埋める）、変わったデータだけを再計算する
- HNSW は INSERT 時に更新されるので、大量インポートでは「先にインポートしてからインデックスを作る」方が速い。IVFFlat は必ずデータを読み込んだ後に作る
- UPDATE / DELETE はインデックスに不要データを残し、VACUUM で消える。大量の変更の後は `REINDEX INDEX CONCURRENTLY`
- 1536 次元の 1 件は 6 KB あり、2 KB を超えると TOAST される。`SELECT *` はベクトルも一緒に返すので、必要な列だけを選ぶ

## 9. よくある落とし穴

| 落とし穴 | 説明 |
|---|---|
| `<#>` を大きい順に並べる | `<#>` は負の内積なので、DESC だと最も似ていないものが見つかる |
| `ORDER BY 1 - 距離 DESC` | 結果は同じだが、インデックスを使えない |
| 演算子がインデックスと一致しない | cosine インデックスに `<->`：テーブル全体のスキャン |
| LIMIT がない | インデックスを使えない |
| 距離のしきい値だけ `WHERE dist < 0.3` | インデックスを使えない。ORDER BY … LIMIT してから絞り込む |
| 絞り込み条件が非常に厳しい | HNSW が返すのが k 件に満たず、エラーにもならない |
| 類似商品で自分自身を除いていない | 1 位が自分自身になる（距離 0） |
| モデルが知らないテキスト | embed() が NULL / 不正確なベクトルを返し、結果は答えがあるように見えて間違っている |
| 平均ベクトル | 長さが 1 未満なので、固定のしきい値と比べる前に l2_normalize する |
| 空のテーブルで IVFFlat を作る | クラスタに意味がなく、再現率が悪い。データを読み込んでから作る |
| HNSW の ef_search が LIMIT より小さい | 最大でも ef_search 件しか返らない |
| HNSW の作成がとても遅い | maintenance_work_mem が小さすぎる（グラフがメモリに収まらない） |
| テキストを変えたのにベクトルを再計算していない | 検索結果が内容と合わない |

## 10. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| ベクトル検索とは？ | データをベクトルに埋め込み、最も近い k 件を探す。セマンティック検索、レコメンド、重複除去、RAG に使う |
| cosine、L2、内積はどう選ぶか？ | モデルの推奨に従う。ベクトルがすべて正規化されていれば 3 つの順位は同じで、内積が最速 |
| HNSW vs IVFFlat？ | HNSW は再現率と速度が良く、書き込みながら作れるが、作成が遅くメモリを使う。IVFFlat は作成が速く小さいが、データを読み込んでから作る必要がある |
| 再現率はどう調整するか？ | HNSW は ef_search（作成時は m、ef_construction）、IVFFlat は probes、lists。自分のデータで計測する |
| WHERE を加えたら結果が減った？ | post-filtering。iterative scan、ef_search を上げる、部分インデックス、パーティション、あるいは絞り込み後に正確な検索 |
| ベクトルが容量を取りすぎる？ | halfvec（半分、ほぼ損失なし）、binary quantization + リランク、次元削減 |
| セマンティック検索で型番が見つからない？ | ハイブリッド検索：全文検索 + ベクトルを RRF でマージ |
| pgvector か専用のベクトルデータベースか？ | 数千万件以内で、リレーショナルなデータと一緒に検索する → pgvector。数十億件、分散 → 専用のデータベース |
| RAG の流れは？ | チャンク分割 → 埋め込み → ベクトルを保存 → 質問を埋め込む → 最も近いチャンクを探す（+ 絞り込み、リランク）→ LLM に渡す |
| 埋め込みモデルを変えるときは何をするか？ | すべてを埋め込み直し、インデックスを作り直す。新旧のモデルのベクトルを混ぜない（先に新しい列に書き込み、切り替えた後で古い列を削除する） |

## 11. Java / Spring

- JDBC：`com.pgvector:pgvector` パッケージで、`PGvector.addVectorType(conn)` の後に `new PGvector(float[])` をパラメータにする。または文字列を `?::vector` でそのまま渡す
- Hibernate 6.4+：`hibernate-vector` モジュールで、`@JdbcTypeCode(SqlTypes.VECTOR) @Array(length = 1536) float[] embedding`
- ★ Spring AI：`PgVectorStore`（starter：`spring-ai-starter-vector-store-pgvector`）。設定の接頭辞は `spring.ai.vectorstore.pgvector`：`index-type=HNSW`、`distance-type=COSINE_DISTANCE`、`dimensions`
- `vectorStore.add(documents)`：EmbeddingModel で埋め込んでから書き込む。検索は `vectorStore.similaritySearch(request)` で、request は `SearchRequest.builder()` で `query("…")`、`topK(5)`、`filterExpression("tenant == 7")` を設定する
- 埋め込みの呼び出しはまとめて行い、再試行とレート制限を入れる。一覧 API でベクトル列を SELECT しないこと（大きい）

# Elasticsearch

> 例はすべて練習環境（`es-lab` コンテナ、http://localhost:9201、Elasticsearch 8.17 シングルノード）のもので、ショーケースの「Dev Tools コンソール」か Kibana Dev Tools にそのまま貼り付けて実行できます。products（1,500 件の商品）、reviews（2 万件以上の中国語のレビュー）、orders（8 万件の注文、明細は nested）、logs（9 月の API アクセスログ、20 万件）。

## 1. ★ 基本の考え方

- **転置インデックス（inverted index）**：各ドキュメントを「語」に分け、「語 → その語を含むドキュメント（と位置）」の対応表を作る。検索時は語から直接ドキュメントを探すので、すべてをスキャンする必要がない
- 各フィールドは型に応じて別の構造を使う：text → 転置インデックス。keyword、数値、日付 → 転置インデックス + **doc values**（フィールドごとに保存し、並べ替えや集約に使う）。数値と日付にはさらに BKD ツリー（範囲検索）がある
- **ニアリアルタイム（near real-time）**：書き込み後は refresh（既定 1 秒）を待たないと検索できない
- 内部は Lucene：1 つの**シャード（shard）**が 1 つの Lucene インデックスで、変更できない多数の **segment** からなる

| リレーショナルデータベース | Elasticsearch |
|---|---|
| テーブル | インデックス（index） |
| 行 | ドキュメント（document、JSON） |
| 列 | フィールド（field） |
| schema | mapping |
| SQL | Query DSL（JSON）。SQL API（`POST /_sql`）もある |
| B-tree インデックス | 転置インデックス、doc values、BKD ツリー（各フィールドに既定でインデックスがある） |
| JOIN | ほぼない：非正規化、nested、join フィールド（parent-child） |
| トランザクション | ない：単一ドキュメントのアトミックな書き込みと楽観的ロックだけ |

★ 面接での結論：Elasticsearch は**検索と分析のエンジン**で、主要なデータベースではありません。よくある構成：PostgreSQL / MySQL に正本を保存し、Elasticsearch にもう 1 部同期して全文検索、絞り込み、集約に使う（商品検索、サイト内検索、ログ分析）。

## 2. ★ アナライザ（analyzer）

```es
POST /_analyze
{ "analyzer": "standard", "text": "Sony 無線耳機，降噪效果很棒" }
# → sony、無、線、耳、機、降、噪、效、果、很、棒（中国語は 1 文字 1 語）

POST /_analyze
{ "analyzer": "cjk", "text": "Sony 無線耳機，降噪效果很棒" }
# → sony、無線、線耳、耳機、降噪、噪效、效果、果很、很棒（2 文字ずつ）

POST /products/_analyze
{ "field": "name", "text": "主動降噪耳機" }          # フィールドに設定されたアナライザを使う
```

- アナライザ = 文字フィルタ（char_filter。例えば HTML を取り除く）→ **トークナイザ（tokenizer）** → トークンフィルタ（filter。例えば小文字化、同義語、語幹化、ストップワード）
- ★ 書き込み時と検索時には互換性のあるアナライザを使わないと語が一致しない。`analyzer`（書き込み）と `search_analyzer`（検索）は別々に設定できる
- 中国語：standard は 1 文字 1 語（「音質」= 音 OR 質 でノイズが多い）。**cjk** は 2 文字ずつ（プラグイン不要で再現率が高い）。**IK、smartcn、jieba** は本当の分かち書き（プラグインが必要）
- 実測（レビュー）：match「音質」は cjk で 695 件、standard で 4,509 件見つかる（「品質」「肉質」が混ざる）
- 同義語は search_analyzer（`synonym_graph`）に置く：同義語辞書を変えてもインデックスを作り直す必要がない
- アナライザはインデックス作成時に決まり、後から変えるには新しいインデックスを作って reindex する

## 3. Mapping と型

```es
PUT /products
{
  "mappings": {
    "dynamic": "strict",                                       # 定義されていないフィールドはそのまま拒否
    "properties": {
      "name":     { "type": "text", "analyzer": "cjk",
                    "fields": { "keyword": { "type": "keyword" } } },   # multi-field：同じ値に 2 種類のインデックス
      "brand":    { "type": "keyword" },
      "price":    { "type": "integer" },
      "created_at": { "type": "date" },
      "items":    { "type": "nested", "properties": { … } }
    }
  }
}
GET /products/_mapping
```

| 型 | 用途 |
|---|---|
| `text` | 全文検索（分析される）。並べ替えや集約はできない |
| `keyword` | 完全一致、並べ替え、集約（ブランド、ステータス、タグ、ID） |
| `integer` / `long` / `float` / `scaled_float` | 数値、範囲検索。金額には scaled_float を使える |
| `date` | UTC のミリ秒で保存。検索時はタイムゾーンに注意 |
| `boolean`、`ip`、`geo_point` | ブール値、IP（ネットワーク範囲で検索できる）、緯度経度（距離検索） |
| `object` / `nested` | オブジェクト。配列内のオブジェクトを「同じオブジェクト」として比較するなら nested |
| `dense_vector` | ベクトル（kNN 検索。pgvector と同種の機能） |

- ★ **動的 mapping**：定義されていないフィールドは最初に現れたときに型が推定され（数値 → long、文字列 → text + keyword）、以後は固定される。1 つ目が数値で 2 つ目が "10A" → 2 つ目は拒否される
- ★ 既存のフィールドの**型は変えられない**：新しいインデックスを作る → `_reindex` → **エイリアス（alias）**で切り替える。プログラムは常に alias 経由でアクセスする
- 本番環境では事前に mapping か **index template** を定義する。フィールドが多すぎる（mapping explosion）とクラスタが落ちる

## 4. ★ Query DSL

```es
GET /products/_search
{
  "query": {
    "bool": {
      "must":     [{ "match": { "description": "輕薄" } }],             # 一致が必要、スコアあり
      "filter":   [{ "term": { "category": "筆電" } },                  # 一致が必要、スコアなし、キャッシュ可
                   { "range": { "price": { "gte": 20000, "lte": 40000 } } }],
      "should":   [{ "term": { "tags": "熱銷" } }],                     # 加点
      "must_not": [{ "term": { "brand": "Apple" } }]                   # 除外
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

| クエリ | 説明 |
|---|---|
| `match` | 全文：まず分析し、それらの語を含むドキュメントを探す。既定は OR（`operator: and`、`minimum_should_match`） |
| `match_phrase` | 語が順に隣り合っている必要がある（位置で判断）。`slop` で間隔を許容 |
| `multi_match` | 複数のフィールド。`"fields": ["name^3", "description"]` で重み付け。best_fields / most_fields / cross_fields |
| `term` / `terms` | 完全一致（分析しない）。keyword や数値に使う |
| `range` | 数値、日付の範囲（`gte`、`lt`。日付は `now-7d/d` と書ける） |
| `exists` | フィールドに値がある |
| `fuzzy` / `fuzziness: "AUTO"` | 打ち間違いを許容（編集距離） |
| `prefix` / `wildcard` / `regexp` | 前方一致など。先頭がワイルドカードのものはとても遅い |
| `nested` | nested フィールドへの検索 |
| `function_score` | フィールドの値、距離、スクリプトでスコアを調整 |

- ★ **query context vs filter context**：must / should はスコアを計算し、filter / must_not はスコアを計算せず結果をキャッシュできる → 関連度が不要な条件は常に filter に入れる
- ★ text フィールドには match、keyword、数値、日付には term / range を使う。text に term を使うと見つからないことが多い（保存されているのは小文字化・分割された語）
- 実測：match「通勤戴很舒服」は 2,756 件見つかる（いずれかの bigram があれば一致）が、match_phrase は 266 件だけ
- `hits.total` は既定で 10,000 件までしか正確に数えない（`relation: gte`）。正確に知るには：`track_total_hits: true` か `_count`

## 5. ★ 関連度：BM25

- スコア ≈ **IDF**（その語を含むドキュメントが少ないほど高い）× **TF**（出現回数。飽和する。パラメータ k1 = 1.2）× **フィールド長による調整**（短いほど高い。パラメータ b = 0.75）
- 実測：「降噪」（404 件にある）の 1 位は 4.3 点、「很好」（8 千件以上にある）の 1 位はわずか 1.5 点
- `"explain": true` でスコアの計算方法が見える。スコアは同じクエリ内で順位を比べるだけで、しきい値には使えない
- 順位の調整：フィールドの重み（`^3`）、should での加点、`function_score`（評価、販売数、新しさ、距離）、`rescore`
- 古いバージョン（5.0 より前）の既定は TF-IDF。BM25 は高頻度の語や長いドキュメントの扱いがより合理的

## 6. ★ 集約（aggregations）

```es
GET /orders/_search
{
  "size": 0,                                                      # 集約だけでドキュメントは不要
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

| 種類 | 集約 | SQL での対応 |
|---|---|---|
| bucket（グループ化） | `terms`、`date_histogram`、`histogram`、`range`、`filters`、`composite` | GROUP BY |
| metric（計算） | `avg`、`sum`、`min`、`max`、`stats`、`percentiles`、`cardinality`、`top_hits` | 集約関数 |
| pipeline（結果に対してさらに計算） | `bucket_sort`、`derivative`、`cumulative_sum`、`moving_fn` | ウィンドウ関数 |

- 集約は doc values を使う：text フィールドは集約できない（fielddata は既定で無効）。keyword か `.keyword` サブフィールドを使う
- ★ 近似値：`terms` はシャードが複数あると数え漏れることがある（`doc_count_error_upper_bound`）、`cardinality` は HyperLogLog++、`percentiles` は TDigest
- ★ date_histogram に `time_zone` を指定しないと UTC で区切られる（台湾の 1 日が午前 8 時から始まる）
- すべてのグループをめくっていく（エクスポートする）には `composite` + `after` を使う

## 7. 関連データ：nested、object、parent-child

```es
GET /orders/_count
{
  "query": { "nested": { "path": "items", "query": { "bool": { "filter": [
    { "term": { "items.category": "手機" } },
    { "range": { "items.quantity": { "gte": 2 } } }
  ] } } } }
}
```

- ★ **object の配列は平らにされる**：items.category = [手機, 男裝]、items.quantity = [1, 3] となり、条件が別々の明細から来ても一致する（実測：object では 6,884 件、nested の正解は 3,465 件）
- **nested**：各オブジェクトが隠れた Lucene ドキュメントになる（8 万件の注文と 20 万件の明細 → `_cat/indices` には 28 万件と表示）。検索や集約には nested / reverse_nested を使う。明細を 1 つ更新するとドキュメント全体を書き直す
- **join フィールド（parent-child）**：親と子が別々のドキュメントで個別に更新できるが、検索は遅く、同じシャードに置く必要がある（routing）
- ★ まずは**非正規化**：検索に必要なフィールドをドキュメントに直接入れ（注文に商品名やカテゴリを入れる）、データが変わったら作り直す

## 8. 書き込みとニアリアルタイム

```es
PUT  /scratch/_doc/1            { … }             # _id を指定：追加または丸ごと上書き
POST /scratch/_doc              { … }             # _id を自動生成
PUT  /scratch/_create/1         { … }             # 既に存在すれば失敗
POST /scratch/_update/1         { "doc": { "price": 2990 } }
POST /scratch/_update_by_query  { "query": …, "script": { "source": "ctx._source.tags.add(params.t)", "params": { "t": "週年慶" } } }
POST /scratch/_delete_by_query  { "query": … }
POST /_bulk                     （NDJSON：1 行がアクション、1 行が内容）
PUT  /scratch/_doc/1?if_seq_no=5&if_primary_term=1   { … }   # 楽観的ロック：変更されていたら 409
```

- 書き込みの流れ：メモリの buffer + **translog**（喪失防止）→ **refresh**（検索可能な segment になる。既定は 1 秒ごと）→ **flush**（ディスクに fsync し、translog を消す）→ **merge**（segment をマージし、削除済みのドキュメントを消す）
- ★ 検索は refresh を待つ必要があり、`GET /_doc/id` はリアルタイム。書いたらすぐに検索したいなら：`?refresh=wait_for`（毎回 `refresh=true` にはしない）
- ★ segment は変更できない：**更新 = 削除マーク + 書き直し**、削除はマークを付けるだけ → 同じドキュメントを頻繁に更新する（カウンタ）用途には向かない
- `_bulk` はトランザクションではない：各アクションがそれぞれ成功・失敗するので、レスポンスの `errors` と各 `items` を確認する
- 大量のインポート：`refresh_interval: -1`、`number_of_replicas: 0` にしておき、インポート後に戻す
- 古いデータの削除に delete_by_query を使わないこと：時間でインデックスを分け、インデックスごと削除する

## 9. ★ シャードとクラスタ

- インデックスは複数の **primary shard** に分かれる（作成後は変更できず、`_split` / `_shrink` か reindex しかない）。各 primary には **replica** がある（いつでも変更できる）
- ドキュメントをどのシャードに置くか：`hash(_routing または _id) % シャード数` → だからシャード数を変えられない
- 検索は **query then fetch**：各シャードがそれぞれ上位 from + size 件を見つける → コーディネーターがマージして並べ替える → ドキュメントの内容を取りに行く
- クラスタの健全性：**green**（すべて割り当て済み）/ **yellow**（置き場所のない replica がある。例えばシングルノード）/ **red**（primary が欠けていて、データが不完全）
- ノードの役割：master（クラスタの状態を管理。split brain を避けるため奇数台）、data（hot / warm / cold）、ingest、coordinating
- シャードの大きさの目安は 10〜50 GB。小さなシャードが多すぎる（oversharding）とメモリを無駄にし、master を遅くする
- replica は可用性と読み取りスループットを高めるが、書き込みはすべてのコピーに行う必要がある

## 10. ページングと大量の読み取り

```es
GET /logs/_search
{ "size": 100, "sort": [{ "@timestamp": "asc" }, { "trace_id": "asc" }],
  "search_after": [1788192208672, "722ada4508737385"] }        # 前のページの最後の 1 件の sort 値

POST /logs/_pit?keep_alive=1m                                  # point in time：スナップショットを固定
```

- ★ `from + size` は最大 10,000（`index.max_result_window`）：各シャードが上位 from + size 件を並べる必要があり、後ろのページほど高くつく
- ディープページングには **search_after** を使う（+ PIT でページをめくる間の結果を固定）。並べ替えの最後には必ず一意なフィールドを入れる
- scroll は大量エクスポート用（現在は非推奨で、PIT + search_after を使う）

## 11. ログと時系列：data stream、ILM

- ログは時間でインデックスを分ける（logs-2026.09.18）か、**data stream**（自動で rollover する隠しインデックスの集まりで、追加のみ）を使う
- **ILM**（Index Lifecycle Management）：hot（新しいデータ、SSD）→ warm（読み取り専用、forcemerge）→ cold / frozen（安価なストレージ）→ delete
- ELK / Elastic Stack：Beats / Logstash で収集 → Elasticsearch に保存 → Kibana で検索・ダッシュボード。OpenSearch は AWS がフォークしたオープンソース版

## 12. よくある落とし穴

| 落とし穴 | 説明 |
|---|---|
| text フィールドに term を使う | 保存されているのは分析後の語（小文字化、分割）なので見つからない |
| 文全体を match する | 既定で OR なので、どれか 1 語でも一致すれば該当し、結果が多くなる |
| 中国語に standard アナライザ | 1 文字 1 語なので、「音質」で「品質」が見つかる |
| object の配列 | 条件がオブジェクトをまたいで交差一致するので、nested を使う |
| hits.total が 10000 | 既定では 1 万件までしか正確に数えず、`relation: gte` |
| date_histogram にタイムゾーンを指定しない | UTC で区切られ、毎日の数値がずれる |
| text フィールドで集約・並べ替え | エラー：fielddata is disabled。keyword を使う |
| from 10000 | Result window is too large。search_after を使う |
| 動的 mapping | 最初のドキュメントが型を決め、以後書き込めなくなる |
| フィールドの型を変える | 変えられない。新しいインデックス + reindex + alias |
| 書いてすぐ検索する | ニアリアルタイムなので、refresh を待つ必要がある |
| シャード数が多すぎる / 少なすぎる | 作成後は変えられない。多すぎるとリソースの無駄、少なすぎると水平スケールできない |
| Elasticsearch を主データベースにする | トランザクションがなく、mapping を変えられず、最近の書き込みを失うことがある（設定による） |

## 13. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| なぜ検索が速いのか？ | 転置インデックス：語から直接ドキュメントを探す。doc values で並べ替えと集約。シャードで並列処理 |
| text と keyword の違いは？ | text は分析され、全文検索に使う。keyword は分析されず、完全一致、並べ替え、集約に使う |
| 関連度はどう計算するか？ | BM25：IDF、TF（飽和する）、フィールド長。boost や function_score で調整できる |
| query と filter の違いは？ | query はスコアを計算する。filter はスコアを計算せずキャッシュできるので速い |
| なぜ書き込んだのに検索できないのか？ | ニアリアルタイム：refresh（既定 1 秒）の後で検索可能な segment になる |
| シャード数はどう決めるか？ | データ量（1 シャード 10〜50 GB）とノード数で決める。primary の数は作成後に変えられない |
| クラスタが yellow / red になった？ | yellow：replica が未割り当て（シングルノード）。red：primary が欠けていて、データが不完全 |
| データベースとどう同期するか？ | アプリケーションでの二重書き込み（不整合の恐れ）、CDC（Debezium → Kafka → Elasticsearch）、定期的な全件再構築。データベースを正とする |
| ディープページングはどうするか？ | search_after + PIT。from + size は 10,000 までの制限がある |
| nested と object は？ | object の配列は平らにされ、オブジェクトをまたいで交差一致する。nested はオブジェクトの境界を保つが、コストが高い |
| Elasticsearch とデータベースの LIKE の比較は？ | LIKE '%語%' はテーブル全体をスキャンし、関連度での並べ替えもなく、分かち書きも分からない。ES は転置インデックス、BM25、アナライザを使う |
| Elasticsearch と pgvector は？ | ES はキーワード（BM25）と集約が得意で、dense_vector で kNN もできる。ハイブリッド検索では両方を併用することが多い |

## 14. Java / Spring

- 公式の **Elasticsearch Java API Client**（`co.elastic.clients:elasticsearch-java`）：型安全な builder で、`client.search(s -> s.index("products").query(q -> q.match(m -> m.field("name").query("耳機"))), Product.class)`
- **Spring Data Elasticsearch**：`@Document(indexName = "products")`、`@Field(type = FieldType.Text, analyzer = "cjk")`、`ElasticsearchRepository<Product, String>`（派生クエリ `findByBrand`）、複雑なクエリには `NativeQuery`
- ★ 同期の戦略：トランザクションの commit 後に送る（`@TransactionalEventListener(phase = AFTER_COMMIT)`）、失敗は再試行できるようにする。大量のデータには CDC を使う
- バッチ書き込みには `BulkIngester` を使う。クエリにはタイムアウトを設定する。Elasticsearch のコネクションプールを大きくしすぎない

# InfluxDB

> 例はすべて練習環境（`influx-lab` コンテナ、http://localhost:8087、InfluxDB 2.7、組織 shop、bucket metrics）のもので、ショーケースの「コンソール」にそのまま貼り付けて実行できます。cpu、mem（ホスト 5 台、1 分ごと）、http（API リクエストの累積カウンタ）、sensors（倉庫の温湿度、5 分ごと）はどれも 2026 年 9 月のデータで、orders は PostgreSQL からコピーしています。

## 1. ★ 基本の考え方

- **point（点）**= measurement + tag set + field set + タイムスタンプ：`cpu,host=db-01,role=db usage_user=35.8,usage_system=10.2 1790783940`
- **measurement** ≈ テーブル名。**tag** はインデックスがあり、値は常に文字列（絞り込みやグループ化に使う）。**field** はインデックスがなく、測定値を保存する（浮動小数点数、整数、文字列、ブール値）
- **series**：measurement + tag set（+ field）で 1 本の時系列が決まり、それぞれ時刻順に保存・圧縮される。cpu はホストが 5 台 → 5 本の series
- 2.x の構成：**organization** → **bucket**（データベース + 保持期間）。権限は **token** に紐づく（ロールはなく、各 token が読み書きできる bucket を列挙する）
- クエリ言語：**InfluxQL**（SQL 風、1.x からある。2.x では先に DBRP マッピングを作る必要がある）、**Flux**（2.x のパイプライン言語で、新機能の開発は止まっている）。3.x では **SQL** + InfluxQL に変わった

| リレーショナルデータベース / TimescaleDB | InfluxDB |
|---|---|
| テーブル | measurement |
| インデックスのある列（ホスト、地域） | tag（常に文字列） |
| 普通の列（数値） | field |
| 行 | point |
| データベース + 保持ポリシー | bucket（2.x）、database + retention policy（1.x） |
| JOIN、サブクエリ、ウィンドウ関数 | InfluxQL には JOIN がない。Flux は join できる |
| UPDATE | ない：同じ series・同じタイムスタンプで書き直すと上書きされる |

## 2. 書き込み：line protocol

```text
# measurement,tag1=値,tag2=値 field1=値,field2=値 タイムスタンプ
cpu,host=db-01,region=tpe,role=db usage_user=35.84,usage_system=10.2 1790783940
orders,city=台北市,status=paid order_id=1001i,total=1990i,coupon="WELCOME",gift=true 1790726400
```

- measurement と tag の間はカンマ、tag と field の間は空白 1 つ、field と時刻の間も空白 1 つ。空白、カンマ、イコールはバックスラッシュでエスケープする
- field の型：浮動小数点数（既定）、**整数には i を付ける**（`1990i`）、文字列はダブルクォート、ブール値は `true` / `false`
- タイムスタンプの単位は `precision` パラメータで決まる（既定は ns、us、ms、s）。時刻を指定しないと、サーバーが受け取った時刻を使う
- ★ 同じ series + 同じタイムスタンプ = 同じ点：後から書いたものが前のものを上書きする（異なる field はマージされる）。同じ秒・同じ tag の 2 件の注文 → 1 件目が消え、しかもエラーにならない
- ★ 同じ shard の中では、1 つの field は 1 つの型しか持てない：先に `total=100i`、次に `total=1.5` と書くと → field type conflict で、バッチ全体が拒否されることがある
- バッチで書き込む（1 バッチ約 5,000 行）。tag を名前順に並べてから書き込むと速い。削除は delete API（時間範囲 + tag の条件）で行う

## 3. ★ データモデル：tag か field か

| | tag | field |
|---|---|---|
| インデックス | ある（TSI） | ない |
| 型 | 文字列のみ | 浮動小数点数、整数、文字列、ブール値 |
| WHERE での絞り込み | インデックスを引いて series を直接見つける | series 全体を読み出して 1 件ずつ比較 |
| GROUP BY | できる | できない（すべて 1 つのグループにまとまり、エラーにならない） |
| 計算（mean、sum） | できない | できる |
| 値の種類が多いとき | ★ series 数が急増する（high cardinality） | 影響なし |

- 実測（実験室）：1,000 人のユーザー × 20 件のイベントで、user_id を tag にすると **1,000 本の series**、field にすると **1 本**
- ★ ルール：絞り込みやグループ化に使い、値の種類が限られるもの（ホスト、地域、サービス、ステータス、センサー番号）→ tag。測定値、ID、高カーディナリティの値（ユーザー ID、注文番号、trace id、IP、完全な URL）→ field
- high cardinality は 1.x / 2.x の性能問題の最大の原因：インデックスがメモリを食い、書き込みが遅くなり、クエリは大量の series をマージする必要がある。3.x が列指向ストレージ（Arrow / Parquet）に変わったのはこれを解決するため
- `SHOW SERIES EXACT CARDINALITY`（InfluxQL）、`influxdb.cardinality()`（Flux）で series 数を調べられる

## 4. ★ InfluxQL

```sql
SHOW MEASUREMENTS;
SHOW TAG KEYS FROM cpu;
SHOW TAG VALUES FROM cpu WITH KEY = "host";
SHOW FIELD KEYS FROM orders;                       -- フィールドと型

SELECT mean(usage_user) FROM cpu
WHERE host = 'db-01'                               -- ★ 文字列の値はシングルクォート
  AND time >= '2026-09-18T12:00:00+08:00' AND time < '2026-09-18T17:00:00+08:00'
GROUP BY time(1h), host fill(null) tz('Asia/Taipei');

SELECT last(usage_user) FROM cpu GROUP BY host;    -- 各ホストの最後の報告
SELECT top(total, 5), order_id, city FROM orders WHERE time >= '2026-09-01T00:00:00+08:00';
SELECT max(mean) FROM (SELECT mean(usage_user) FROM cpu WHERE … GROUP BY time(1h), host) GROUP BY host;   -- サブクエリ
SELECT non_negative_derivative(last(requests), 1m) FROM http WHERE service = 'api' AND … GROUP BY time(1m);
```

| 種類 | 関数 | time 列 |
|---|---|---|
| aggregate | `count`、`mean`、`sum`、`median`、`spread`、`stddev` | 区間の開始（GROUP BY time がないときは 0） |
| selector | `first`、`last`、`max`、`min`、`top`、`bottom`、`percentile` | その 1 件の時刻（GROUP BY time があるときは区間の開始） |
| transformation | `derivative`、`non_negative_derivative`、`difference`、`moving_average`、`cumulative_sum` | 各行 |

- ★ シングルクォート = 文字列の値、ダブルクォート = 識別子。`host = "db-01"` では何も見つからず、エラーにもならない
- ★ 1 日以上の GROUP BY time() には必ず `tz('Asia/Taipei')` を付ける。ないと UTC で区切られる（1 行多くなり、毎日の値が間違う）
- `fill(null | none | 0 | previous | linear)` でデータのない区間の表示方法を決める
- `ORDER BY time` しかできない。値で順位を付けるには `top()` / `bottom()` を使う
- GROUP BY tag があると、`LIMIT` は「series ごとに」制限する。series の数を制限するには `SLIMIT` を使う
- tag だけを SELECT する（field がない）→ 何も返らない。JOIN はない

## 5. ★ Flux

```flux
import "timezone"
option location = timezone.location(name: "Asia/Taipei")

from(bucket: "metrics")
  |> range(start: 2026-09-18T00:00:00+08:00, stop: 2026-09-20T00:00:00+08:00)   // ★ range は必須
  |> filter(fn: (r) => r._measurement == "cpu" and r._field == "usage_user")
  |> aggregateWindow(every: 1d, fn: max, createEmpty: false)                     // _time は区間の「終了」
  |> group()                                                                     // 1 つのテーブルにまとめないと一緒に並べ替えられない
  |> sort(columns: ["_value"], desc: true)
  |> limit(n: 3)
  |> keep(columns: ["_time", "host", "_value"])

// 2 つの field を足す：まず pivot で同じ行にまとめる
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> map(fn: (r) => ({ r with total: r.usage_user + r.usage_system }))

// カウンタ → 1 分あたりの量（リセットによる負の値は飛ばす）
  |> derivative(unit: 1m, nonNegative: true)
```

- データモデル：**1 行に 1 つの値**（`_value`）で、フィールド名は `_field` にある。各 series が 1 つの「テーブル」で、関数はテーブルごとに実行される
- グループ = テーブル：`group(columns: ["role"])` でテーブルを分け直し、`group()` ですべてを 1 つにまとめる。集約、並べ替え、limit はどれもテーブルごとに行われる
- InfluxQL との違い：`aggregateWindow` の `_time` は既定で区間の**終了**（`timeSrc: "_start"` で開始に変わる）。タイムゾーンは `option location` で指定する
- Flux にできて InfluxQL にできないこと：異なる measurement / bucket の join、`map` での任意の計算、`to()` で bucket に書き戻す（ダウンサンプリング）、Task のスケジュール実行
- セキュリティ：Flux には外部システムに接続できる `sql`、`http` などのパッケージがあり、token の権限では制御できない

## 6. カウンタと変化率

- 監視システム（Telegraf、Prometheus exporter）が収集するのはほとんどが**累積カウンタ**：「これまでの合計回数」だけを報告し、クエリ時に差分を計算する
- ★ プログラムが再起動するとカウンタが 0 に戻り、`derivative` は巨大な負の数を計算してしまう（実測 -360 万）→ 常に `non_negative_derivative` / `non_negative_difference` を使う（Flux：`nonNegative: true`、`increase()`）
- `difference` = 後ろの値 − 前の値。`derivative(…, 1m)` = さらに時間単位で割って速度にする
- エラー率：`100 * non_negative_difference(last(errors)) / non_negative_difference(last(requests))`

## 7. ★ 保持期間とダウンサンプリング（downsampling）

- bucket の **retention period**：期限を過ぎたデータは自動で削除される（shard group 単位で丸ごと削除するので安価）
- ダウンサンプリング：**Task**（スケジュール実行される Flux）で古いデータを別の bucket に集計する。例えば：
  `option task = {name: "downsample_cpu", every: 1h}` + `aggregateWindow(every: 1h, fn: mean) |> to(bucket: "cpu_1h")`
- 1.x では Continuous Query + Retention Policy、TimescaleDB では連続集約 + 保持ポリシーを使うが、考え方は同じ
- 実測：9 月の cpu は 200,880 点から 3,348 点（1/60）になり、同じクエリが数倍速くなる。しかし 1 時間平均では db-01 が満杯になったピークが見えない → mean、max、min、count を一緒に保存することが多い
- 遅れて届いたデータは実行済みの Task を逃すことがある：「1 時間前」の区間を処理するようにスケジュールし、少し余裕を持たせる

## 8. ストレージエンジンとバージョン

- 1.x / 2.x：**TSM**（Time-Structured Merge Tree）エンジン：書き込みはまず WAL とメモリキャッシュに入り、それから TSM ファイルに圧縮される。時間で **shard group** に分ける（保持期間が無限なら 7 日ごとに 1 グループ）。**TSI** インデックスが tag → series を対応付ける
- 列は型に応じて圧縮される（時刻は delta-of-delta、浮動小数点数は Gorilla XOR）ので、時系列データの圧縮率は非常に高い
- 3.x（IOx）：Apache **Arrow**（メモリ）+ **Parquet**（オブジェクトストレージ）+ **DataFusion**（SQL クエリエンジン）。列指向で高カーディナリティに強く、クエリは SQL / InfluxQL を使い、**Flux には対応していない**

| | 1.x | 2.x | 3.x |
|---|---|---|---|
| クエリ | InfluxQL | Flux、InfluxQL（互換 API） | SQL、InfluxQL |
| 構成 | database + retention policy | organization + bucket + token | database（table = measurement） |
| スケジュール集計 | Continuous Query | Task（Flux） | 外部スケジューラ / 処理エンジン |
| ストレージ | TSM + TSI | TSM + TSI | Arrow + Parquet |
| 高カーディナリティ | 弱い | 弱い | ずっと良い |

## 9. よくある落とし穴

| 落とし穴 | 説明 |
|---|---|
| 文字列をダブルクォートで `host = "db-01"` | ダブルクォートは識別子なので、データが見つからずエラーにもならない |
| tag の値を数値として `sensor_id = 2` | tag は常に文字列なので、`'2'` と書く |
| tz() のない GROUP BY time(1d) | UTC で区切られ、毎日の値が間違う |
| カウンタに derivative | 再起動時に巨大な負の数が出る |
| field で GROUP BY | すべて 1 つのグループにまとまり、エラーにならない |
| tag だけを SELECT | 何も返らない |
| GROUP BY tag + LIMIT | series ごとに LIMIT がかかる（series の数を制限するなら SLIMIT） |
| range のない Flux | エラー：cannot submit unbounded read |
| Flux と InfluxQL の時刻 | aggregateWindow は区間の終了、GROUP BY time は開始に表示 |
| 同じ秒・同じ tag で 2 件書き込む | 後のものが前のものを上書きする |
| 同じ field に異なる型を書き込む | field type conflict で、バッチ全体が拒否されることがある |
| 高カーディナリティの値を tag に | series 数が爆発し、メモリと性能に問題が出る |

## 10. ★ 面接問題への即答

| 問題 | ポイント |
|---|---|
| InfluxDB のデータモデルは？ | measurement + tags（インデックスのある文字列）+ fields（測定値）+ 時刻。measurement + tag set = series |
| tag と field はどう選ぶか？ | 絞り込み、グループ化、値の種類が限られる → tag。測定値、ID、高カーディナリティ → field |
| high cardinality とは？ | series の数が多すぎる（例えばユーザー ID を tag にする）こと。インデックスがメモリを食い、書き込みもクエリも遅くなる |
| 古いデータはどう扱うか？ | retention period で自動削除 + Task で別の bucket にダウンサンプリング |
| カウンタから速度をどう求めるか？ | non_negative_derivative / increase で、リセットによる負の値を避ける |
| InfluxDB vs TimescaleDB？ | InfluxDB：専用の時系列エンジンで、書き込みと圧縮が速く、監視のエコシステム（Telegraf、Grafana）がある。TimescaleDB：完全な SQL、JOIN、トランザクションがあり、リレーショナルなデータと一緒に置け、高カーディナリティも問題ない |
| InfluxDB vs Prometheus？ | Prometheus はプル型でメトリクスを収集し、PromQL を使い、Kubernetes の監視やアラートに向いているが、ローカルストレージは長期保存には向かない。InfluxDB はプッシュ型で書き込む汎用の時系列データベースで、任意のイベントや長期のデータを保存できる |
| なぜ 3.x は SQL に戻ったのか？ | Flux は学習コストが高く、エコシステムも小さかった。列指向ストレージ（Arrow / Parquet / DataFusion）で SQL も高カーディナリティもうまく扱えるようになった |
| 書き間違えたデータはどう直すか？ | 同じ series・同じタイムスタンプで書き直す。または delete API で削除してから書き直す |

## 11. Java / Spring

- 2.x の公式クライアント：`com.influxdb:influxdb-client-java`
  - `Point.measurement("cpu").addTag("host", "db-01").addField("usage_user", 35.8).time(Instant.now(), WritePrecision.MS)`
  - 書き込みには `WriteApi`（非同期、自動バッチと再試行）か `WriteApiBlocking` を使う。クエリは `QueryApi.query(flux)` で `FluxTable` が返る
- 3.x：`influxdb3-java`（Arrow Flight でのクエリ、SQL / InfluxQL）
- アプリケーションのメトリクス：Spring Boot Actuator + Micrometer の `micrometer-registry-influx`（`management.influx.metrics.export.*` を設定）で、JVM や HTTP リクエストなどのメトリクスを自動で送る
- ★ 書き込みは必ずバッチで非同期に。数値の型は明示的に決め（整数には `addField(name, long)`）、field type conflict を避ける。高カーディナリティの値を tag にしない
