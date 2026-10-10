# Database CheatSheet

An interview-oriented database cheat sheet; every example runs as-is in the practice environment (the `shop` e-commerce data). ★ = frequently asked in interviews.
Switch databases with the tabs at the top and jump to sections with the contents on the left; search covers every database, and each tab shows how many sections matched.

---

# PostgreSQL

> Examples that modify data (sections 10 and 11) can be practiced in the showcase's "write sandbox" (automatically rolled back after running), or in psql or DBeaver. If you break something, rebuild the database: `docker compose down -v && docker compose up -d`

## 0. ★ SQL's "execution order"

The order you write a query in differs from the order the database runs it, and many questions hinge on this:

```
Written order:   SELECT → FROM → WHERE → GROUP BY → HAVING → ORDER BY → LIMIT
Execution order: FROM/JOIN → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT
```

- **WHERE can't use aliases defined in SELECT** (they don't exist yet when WHERE runs); ORDER BY can
- **WHERE can't contain aggregate functions** (`WHERE count(*) > 5` ❌); use HAVING
- WHERE filters "rows" first, then HAVING filters "groups"

---

## 1. The basics (quick reference)

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

Pagination: `LIMIT 20 OFFSET 40` (page 3, 20 per page)

---

## 2. The JOIN family

| Syntax | Result |
|---|---|
| `INNER JOIN` / `JOIN` | Keeps only rows that match on both sides |
| `LEFT JOIN` | Keeps every left row; unmatched right side is NULL |
| `RIGHT JOIN` | Keeps every right row (usually rewritten as a LEFT JOIN in practice) |
| `FULL JOIN` | Keeps both sides; unmatched columns are NULL |
| `CROSS JOIN` | Cartesian product: N left rows × M right rows |
| SELF JOIN | A table joined to itself, with different aliases |

```sql
-- Find rows with "no" match: LEFT JOIN + IS NULL
SELECT c.id, c.name
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL;

-- SELF JOIN: each category with its parent category
SELECT c.name AS category, p.name AS parent
FROM categories c
LEFT JOIN categories p ON p.id = c.parent_id;
```

★ **The LEFT JOIN trap: condition in ON or in WHERE?**

```sql
-- Condition in ON: every customer is kept; only "cancelled orders" are matched
SELECT c.id, count(o.id)
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id AND o.status = 'cancelled'
GROUP BY c.id;

-- Condition in WHERE: customers who never cancelled have o.status NULL and are filtered out → it became an INNER JOIN
SELECT c.id, count(o.id)
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.status = 'cancelled'
GROUP BY c.id;
```

---

## 3. ★ Handling NULL

| Syntax | Meaning |
|---|---|
| `col IS NULL` / `IS NOT NULL` | Tests for NULL. **`= NULL` is never true** |
| `COALESCE(a, b, c)` | Returns the first non-NULL value |
| `NULLIF(a, b)` | Returns NULL when a = b (commonly used to avoid dividing by 0) |
| `count(*)` vs `count(col)` | `count(*)` counts rows; `count(col)` skips NULL |
| `a IS DISTINCT FROM b` | A "not equal" that treats NULL as an ordinary value |

```sql
SELECT name, COALESCE(city, '未填寫') AS city FROM customers;
SELECT count(*) AS all_rows, count(birth_date) AS has_birthday FROM customers;
SELECT revenue / NULLIF(orders, 0) FROM ...;   -- NULL instead of an error when orders is 0
```

★ **`NOT IN` breaks on NULL**: if the subquery returns even one NULL, `NOT IN` finds nothing. Use `NOT EXISTS` to find what "doesn't exist".

```sql
-- Categories with "no subcategories". categories.parent_id has NULL (top-level categories) → this returns 0 rows
SELECT * FROM categories WHERE id NOT IN (SELECT parent_id FROM categories);

-- Correct
SELECT * FROM categories c
WHERE NOT EXISTS (SELECT 1 FROM categories ch WHERE ch.parent_id = c.id);
```

---

## 4. Subqueries

```sql
-- Scalar subquery: returns a single value
SELECT name, price FROM products
WHERE price > (SELECT avg(price) FROM products);

-- IN: compare against a list of values
SELECT * FROM customers
WHERE id IN (SELECT customer_id FROM orders WHERE status = 'returned');

-- EXISTS: only asks "is there any", stops at the first match
SELECT * FROM customers c
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.status = 'cancelled');

-- Derived table: a subquery in FROM must have an alias
SELECT avg(cnt) FROM (
  SELECT customer_id, count(*) AS cnt FROM orders GROUP BY customer_id
) t;

-- Correlated subquery: references outer columns, recomputed for every row
SELECT p.name, p.price
FROM products p
WHERE p.price > (SELECT avg(price) FROM products WHERE category_id = p.category_id);
```

---

## 5. CASE WHEN and conditional aggregation

```sql
SELECT name, price,
       CASE WHEN price >= 10000 THEN '高價'
            WHEN price >= 1000  THEN '中價'
            ELSE '平價' END AS level
FROM products;

-- Several conditions at once (rows to columns / pivot)
SELECT shipping_city,
       count(*) FILTER (WHERE status = 'delivered')              AS delivered,   -- PostgreSQL syntax
       sum(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END)   AS cancelled    -- portable syntax
FROM orders
GROUP BY shipping_city;
```

---

## 6. Set operations

| Syntax | Meaning |
|---|---|
| `UNION` | Combines and **removes duplicates** (needs a sort to de-duplicate, slower) |
| `UNION ALL` | Combines and keeps duplicates (★ use this when you don't need de-duplication) |
| `INTERSECT` | Intersection |
| `EXCEPT` | Difference (in A but not in B) |

Both sides must have the same number and types of columns.

```sql
SELECT customer_id FROM orders
EXCEPT
SELECT customer_id FROM orders WHERE status = 'returned';   -- customers who ordered but never returned anything
```

---

## 7. CTE (WITH)

Splits a query into named steps; easier to read than nested subqueries.

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

**Recursive CTE**: for tree structures (org charts, category trees)

```sql
WITH RECURSIVE tree AS (
  SELECT id, name, 1 AS depth FROM categories WHERE parent_id IS NULL   -- anchor
  UNION ALL
  SELECT c.id, c.name, t.depth + 1                                      -- one level down each time
  FROM categories c JOIN tree t ON c.parent_id = t.id
)
SELECT * FROM tree;
```

---

## 8. ★ Window functions

The biggest difference from GROUP BY: **rows aren't collapsed into one**; every row is kept, and you can still see statistics for the whole group.

```
function() OVER (PARTITION BY group_column ORDER BY sort_column [frame])
```

| Function | Use |
|---|---|
| `row_number()` | 1, 2, 3, 4 (different numbers even for equal values) |
| `rank()` | 1, 2, 2, 4 (equal values share a rank, **with gaps**) |
| `dense_rank()` | 1, 2, 2, 3 (equal values share a rank, no gaps) |
| `ntile(n)` | Splits into n equal buckets |
| `lag(col, n)` / `lead(col, n)` | The value n rows before / after |
| `first_value()` / `last_value()` | The first / last value in the frame |
| `sum() / avg() / count() OVER` | Group statistics while keeping every row |

```sql
-- ★ Top N per group: the 3 most expensive products in each category
SELECT * FROM (
  SELECT name, category_id, price,
         dense_rank() OVER (PARTITION BY category_id ORDER BY price DESC) AS rk
  FROM products
) t
WHERE rk <= 3;

-- Running total
SELECT order_date, amount,
       sum(amount) OVER (ORDER BY order_date) AS running_total
FROM ...;

-- 7-day moving average
SELECT day, revenue,
       avg(revenue) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS ma7
FROM ...;

-- Compare with the previous row
SELECT month, revenue, revenue - lag(revenue) OVER (ORDER BY month) AS diff
FROM ...;

-- Share: each product's percentage of its category's revenue
SELECT name, category_id, price,
       round(100.0 * price / sum(price) OVER (PARTITION BY category_id), 2) AS pct
FROM products;
```


★ Window functions **can't go directly in WHERE** (they run after WHERE); wrap them in a subquery or CTE.

---

## 9. Common functions (PostgreSQL)

**Strings**

```sql
'a' || 'b'                     -- concatenation (NULL || 'b' gives NULL)
concat('a', NULL, 'b')         -- concatenation that ignores NULL
length(s)   lower(s)   upper(s)   trim(s)
substring(s, 1, 3)   left(s, 3)   right(s, 3)
replace(s, '舊', '新')
split_part('a@b.com', '@', 2)  -- 'b.com'
s LIKE '王%'                    -- % any length, _ one character
s ILIKE '%apple%'              -- case-insensitive (PostgreSQL only)
string_agg(name, ', ')         -- aggregate into one string
```

**Dates and times**

```sql
now()   current_date
date_trunc('month', order_date)            -- truncate to the start of the month; essential for monthly reports
extract(year FROM order_date)              -- extract year / month / dow (day of week) / hour
order_date::date                           -- timestamp to date
current_date - interval '30 days'
age(current_date, birth_date)              -- compute an age
to_char(order_date, 'YYYY-MM')             -- formatting
order_date >= '2026-01-01' AND order_date < '2026-02-01'   -- ★ a range query uses indexes better than extract
```

**Numbers**

```sql
round(x, 2)   ceil(x)   floor(x)   abs(x)   x % 3
```

★ **The integer division trap**: `5 / 2 = 2`. For decimals write `5.0 / 2` or `5::numeric / 2`.

**Casting**: `'123'::int`, `CAST('123' AS int)`, `price::text`

---

## 10. Insert, update, delete (DML)

```sql
INSERT INTO customers (name, email, signup_date)
VALUES ('測試帳號', 'test@example.com', current_date)
RETURNING id;                                   -- RETURNING gives back the new id

-- ★ UPSERT: update if it exists, insert if not (decided by the primary key or a UNIQUE column)
INSERT INTO categories (id, name, parent_id)
VALUES (28, '寵物用品', NULL)
ON CONFLICT (id)
DO UPDATE SET name = EXCLUDED.name;             -- EXCLUDED = the row we tried to insert

UPDATE products SET price = price * 0.9 WHERE category_id = 19;

-- Update using data from another table
UPDATE products p SET is_active = false
FROM categories c
WHERE c.id = p.category_id AND c.name = '生鮮';

DELETE FROM orders WHERE status = 'cancelled' RETURNING id;   -- order_items has ON DELETE CASCADE, so items are deleted too
```

★ **DELETE vs TRUNCATE vs DROP**

| | DELETE | TRUNCATE | DROP |
|---|---|---|---|
| Deletes | Matching rows | All rows | The whole table (including its structure) |
| WHERE allowed | ✅ | ❌ | ❌ |
| Speed | Slow (row by row) | Fast | Fast |
| Fires triggers | ✅ | ❌ | ❌ |
| Can ROLLBACK | ✅ | ✅ (in PostgreSQL) | ✅ (in PostgreSQL) |

---

## 11. Tables and constraints (DDL)

```sql
CREATE TABLE coupons (
  id         serial PRIMARY KEY,                        -- auto-increment primary key
  code       text NOT NULL UNIQUE,
  discount   numeric(3,2) CHECK (discount BETWEEN 0 AND 1),
  customer_id int REFERENCES customers(id) ON DELETE CASCADE,  -- foreign key
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE coupons ADD COLUMN used boolean DEFAULT false;
ALTER TABLE coupons DROP COLUMN used;
DROP TABLE coupons;
```

| Constraint | Meaning |
|---|---|
| `PRIMARY KEY` | Unique + not NULL; only one per table |
| `UNIQUE` | No duplicates (multiple NULLs are allowed) |
| `NOT NULL` | No empty values |
| `CHECK` | A custom condition |
| `FOREIGN KEY` / `REFERENCES` | Must match an existing value in another table |

**Views**

```sql
CREATE VIEW v_order_total AS
SELECT order_id, sum(quantity * unit_price * (1 - discount)) AS total
FROM order_items GROUP BY order_id;            -- stores only the query; recomputed on every query

CREATE MATERIALIZED VIEW mv_order_total AS ...;  -- stores the result; fast to query
REFRESH MATERIALIZED VIEW mv_order_total;        -- data must be refreshed manually
```

---

## 12. ★ Indexes and EXPLAIN

```sql
CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_cust_date ON orders (customer_id, order_date);  -- composite index
CREATE UNIQUE INDEX ... ;
DROP INDEX idx_orders_customer;

EXPLAIN SELECT ...;          -- the estimated plan
EXPLAIN ANALYZE SELECT ...;  -- actually runs it and shows real timings
```

| Plan term | Meaning |
|---|---|
| `Seq Scan` | Scans the whole table from start to end |
| `Index Scan` | Finds positions via the index, then fetches rows from the table |
| `Index Only Scan` | The index has every needed column; no trip to the table |
| `Bitmap Heap Scan` | Collects positions via the index first, then reads the table in batches |
| `Nested Loop` / `Hash Join` / `Merge Join` | The three JOIN algorithms |

**Common cases where an index can't be used**

- Computing on the column or wrapping it in a function: `WHERE extract(year FROM order_date) = 2026` ❌
- A leading wildcard: `LIKE '%abc'` ❌ (`LIKE 'abc%'` is fine)
- A composite index `(a, b)` queried only on `b` (the leftmost prefix rule)
- The result is a large fraction of the table, so the database decides a full scan is faster

**The cost of indexes**: they take space, and every INSERT / UPDATE / DELETE has to update them, which slows writes.

---

## 13. ★ Transactions

```sql
BEGIN;
UPDATE products SET stock = stock - 1 WHERE id = 10;
INSERT INTO orders (...) VALUES (...);
COMMIT;      -- confirm; on error, ROLLBACK cancels everything
```

**ACID**

| | Meaning |
|---|---|
| Atomicity | All succeed or all fail |
| Consistency | Constraints hold before and after the transaction |
| Isolation | Concurrent transactions don't interfere with each other |
| Durability | After COMMIT nothing is lost, even in a crash |

**Isolation levels and the anomalies they allow** (PostgreSQL defaults to Read Committed)

| Level | Dirty read | Non-repeatable read | Phantom read |
|---|---|---|---|
| Read Uncommitted | Never in PG | Yes | Yes |
| Read Committed | ❌ | Yes | Yes |
| Repeatable Read | ❌ | ❌ | Never in PG |
| Serializable | ❌ | ❌ | ❌ |

Lock the rows you'll modify: `SELECT ... FOR UPDATE` (e.g. lock before deducting stock, to avoid overselling)

---

## 14. ★ Quick answers to classic interview questions

| Question | Key point |
|---|---|
| WHERE vs HAVING | WHERE filters rows before grouping; HAVING filters grouped results and can use aggregates |
| UNION vs UNION ALL | UNION removes duplicates (slower); UNION ALL doesn't |
| The Nth highest price (salary) | `SELECT DISTINCT price FROM products ORDER BY price DESC LIMIT 1 OFFSET N-1`, or `dense_rank()` |
| Find duplicates | `GROUP BY column HAVING count(*) > 1` |
| Delete duplicates, keeping one | Use `row_number() OVER (PARTITION BY duplicated columns)` and delete rows with rn > 1 |
| Top N per group | `row_number()` / `dense_rank()` + an outer `WHERE rk <= N` |
| Logged in N days in a row | Subtract `row_number()` days from the date; equal results belong to the same streak |
| IN vs EXISTS | Use EXISTS for large subquery results; `NOT IN` breaks on NULL |
| Normalization | 1NF atomic columns, 2NF no partial dependencies, 3NF no transitive dependencies |
| Why not index everything | Space, slower writes, and the database may not use them anyway |
| char vs varchar vs text | In PostgreSQL the three perform almost identically; just use text |
| serial vs identity | `GENERATED ALWAYS AS IDENTITY` is the SQL standard; recommended for new projects |

---

## 15. ★ Advanced syntax: JSONB, arrays, UPSERT, advanced indexes

### JSONB

`products.specs` is JSONB, e.g. `{"color": "黑", "storage_gb": 256, "warranty": {"years": 2}}`

| Syntax | Returns | Meaning |
|---|---|---|
| `specs->'warranty'` | jsonb | Extract JSON (you can keep going down) |
| `specs->>'color'` | text | Extract text (use this at the last level) |
| `specs#>>'{warranty,years}'` | text | Extract by path |
| `specs @> '{"storage_gb": 256}'` | bool | ★ Containment; can use a GIN index |
| `specs ? 'battery_hours'` | bool | Whether the key exists (`?|` any, `?&` all) |
| `specs \|\| '{"on_sale": true}'` | jsonb | Merge; the same keys are overwritten |
| `specs - 'on_sale'` | jsonb | Remove a key |
| `jsonb_set(specs, '{warranty,years}', '3')` | jsonb | Change the value at a path |
| `jsonb_array_elements_text(specs->'sizes')` | rows | Expand a JSON array |
| `jsonb_build_object('a', 1)`, `jsonb_agg(…)` | jsonb | Build JSON |

★ Traps:
- `->>` extracts **text**; cast before comparing sizes: `(specs->>'storage_gb')::int > 64`. Writing `> '64'` compares character by character, and `'128' > '64'` is false
- `NULL || '{…}'` gives NULL; write `COALESCE(specs, '{}') || '{…}'`
- In JDBC `?` is a parameter placeholder, so `specs ? 'key'` must become `??` or `jsonb_exists(specs, 'key')`
- json vs jsonb: jsonb is stored as binary, drops duplicate keys and whitespace, and supports indexes; **almost always use jsonb**

### Array

`products.tags` is `text[]`, e.g. `{熱銷,特價}`

| Syntax | Meaning |
|---|---|
| `'特價' = ANY(tags)` | Any element equals |
| `tags @> ARRAY['熱銷','限量']` | Must have all of them (can use GIN) |
| `tags && ARRAY['環保','獨家']` | Shares at least one (can use GIN) |
| `unnest(tags)` | Expand into rows |
| `array_agg(name)` | Aggregate rows into an array |
| `cardinality(tags)` | Number of elements (0 for an empty array) |
| `array_append(tags, '新品')`, `array_remove(tags, '特價')` | Add / remove |

★ `array_length(tags, 1)` returns **NULL** for an empty array, not 0.

Java: `WHERE id = ANY(?)` with `ps.setArray(1, conn.createArrayOf("int", ids))` replaces building an `IN (…)` string yourself.

### generate_series: generating consecutive values

```sql
SELECT generate_series(1, 5);                                        -- 1–5
SELECT generate_series('2026-09-01'::date, '2026-09-30', '1 day');   -- every day

-- ★ Filling zeros in reports: generate the full timeline first, then LEFT JOIN (condition in ON)
SELECT d::date, count(o.id)
FROM generate_series('2026-09-01'::date, '2026-09-30', '1 day') d
LEFT JOIN orders o ON o.order_date >= d AND o.order_date < d + interval '1 day'
GROUP BY d ORDER BY d;
```

It's also commonly used to generate large amounts of test data (that's how this practice database was built).

### RETURNING and UPSERT

```sql
INSERT INTO customers (name, email, signup_date)
VALUES ('王小明', 'a@b.com', current_date)
RETURNING id;                                         -- get the new id back directly

UPDATE products SET price = price * 1.1 WHERE id = 1 RETURNING id, price;   -- the updated values
DELETE FROM orders WHERE status = 'pending' RETURNING id;

-- ★ UPSERT: atomic, with no "check then write" race condition
INSERT INTO categories (id, name) VALUES (2, '智慧型手機')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;  -- EXCLUDED = the row we tried to insert

INSERT … ON CONFLICT (email) DO NOTHING;              -- skip duplicates (idempotent)

-- Moving data: delete and archive in one statement, in one transaction
WITH moved AS (DELETE FROM orders WHERE … RETURNING *)
INSERT INTO orders_archive SELECT * FROM moved;
```

★ Sequences aren't transactional: ids used before a ROLLBACK aren't given back, so ids have gaps.

### Advanced indexes

| Index | Syntax | When to use |
|---|---|---|
| B-tree (default) | `CREATE INDEX … (col)` | =, <, >, BETWEEN, ORDER BY, prefix LIKE 'abc%' |
| Composite | `(customer_id, order_date)` | Equality conditions first, sort columns after (leftmost prefix) |
| ★ Partial index | `(order_date) WHERE status = 'pending'` | Queries touch only a small part of the data; a much smaller index |
| ★ Expression index | `(lower(email))` | The query computes on the column; the function must be IMMUTABLE |
| Covering index | `(a, b) INCLUDE (c)` | Turns the query into an Index Only Scan |
| GIN | `USING gin (specs)` | JSONB @>, ?; array @>, &&; full-text search |
| BRIN | `USING brin (event_time)` | Huge tables appended in time order (logs, IoT); only tens of KB |
| Hash | `USING hash (col)` | Only = queries; rarely used in practice |

BRIN's precondition: the data's order on disk must match the column values (high correlation). BRIN on a randomly written column is useless.

### ★ Partial indexes

Index only "the rows that match a condition". Good when "most of the data is never queried with this condition".

```sql
-- Customer support only queries pending orders: pending is only 3%
CREATE INDEX idx_orders_pending ON orders (order_date) WHERE status = 'pending';

SELECT id, customer_id FROM orders
WHERE status = 'pending'            -- the condition must "imply" the index's WHERE to use it
ORDER BY order_date DESC LIMIT 20;  -- the index is ordered by order_date: Index Scan Backward, no sort
```

Measured (perf.orders_big in the index lab, 2 million rows, about 60,000 pending):

| Index | Size | "The newest 20 pending" |
|---|---:|---|
| `(status)` full index | 13 MB | Finds 60,000 rows, then sorts |
| `(order_date) WHERE status = 'pending'` | 1.3 MB | Index Scan Backward, 0.1 ms |

| Common use | Syntax |
|---|---|
| Soft delete: index only rows not yet deleted | `CREATE INDEX … (email) WHERE deleted_at IS NULL` |
| Job queue: index only unfinished jobs | `CREATE INDEX … (created_at) WHERE done = false` |
| ★ Conditional uniqueness: e.g. one default address per customer | `CREATE UNIQUE INDEX … (customer_id) WHERE is_default` |
| Exclude lots of NULLs | `CREATE INDEX … (birth_date) WHERE birth_date IS NOT NULL` |

- Benefits: a small index (easier to keep entirely in memory), and writes of non-matching rows don't maintain the index
- ★ The query's WHERE must let the optimizer "prove" it matches the index condition: `status = 'pending'` works; `status IN ('pending', 'paid')` and `status = $1` (a parameter) don't
- ★ A Java / JDBC pitfall: with a PreparedStatement parameter `WHERE status = ?`, after a few executions PostgreSQL may switch to a "generic plan", which doesn't know the parameter value and so **can't use** the partial index (measured: it switches to the full status index + a sort). Write fixed conditions directly in the SQL (`WHERE status = 'pending'`), not as parameters
- Conditional uniqueness: a table's `UNIQUE` constraint can't have a WHERE; use `CREATE UNIQUE INDEX … WHERE …` (or an EXCLUDE constraint)
- In an interview: "When only a small part of the data is queried this way and the condition is fixed, use a partial index: it's small and cheap to write."

> The measurements below are all on the large perf tables in the index lab (2 million rows each), creating indexes in a transaction and rolling back after measuring.

### ★ Expression indexes

The index stores "the value after the computation". When a query computes on a column (functions, casts, time zone conversions), a regular index can't be used, so create an expression index.

```sql
-- Query orders by "date in Taiwan time": order_date is timestamptz
CREATE INDEX idx_orders_tw_day ON orders (((order_date AT TIME ZONE 'Asia/Taipei')::date));
SELECT count(*) FROM orders
WHERE (order_date AT TIME ZONE 'Asia/Taipei')::date = '2025-06-01';   -- the expression must be "exactly the same"

CREATE INDEX idx_customers_email_lower ON customers (lower(email));     -- case-insensitive email
SELECT * FROM customers WHERE lower(email) = lower('User04242@Example.com');
```

| | No index | Expression index |
|---|---|---|
| Plan | Seq Scan, filtering out 1.998 million rows | Bitmap Index Scan |
| Time | 488 ms | 5 ms |

- ★ The query's expression must be **exactly the same** as the index's: the index is on `(order_date AT TIME ZONE 'Asia/Taipei')::date`, so a query written as `order_date::date` can't use it (measured: Seq Scan)
- ★ Only **IMMUTABLE** functions are allowed (same input, always the same output): not `now()`; and `timestamptz::date` depends on the session's time zone setting, so it isn't allowed either, which is why `AT TIME ZONE` is spelled out
- Also common for a single JSONB key: `CREATE INDEX … ((meta->>'coupon'))`, with `WHERE meta->>'coupon' = 'FALL10'`
- Cost: the expression is computed on every write; the index is about the size of a regular B-tree (14 MB here)
- An equivalent approach: instead of computing on the column, move the computation to the "value" side, e.g. `WHERE order_date >= '2025-06-01 00:00+08' AND order_date < '2025-06-02 00:00+08'`, and a regular order_date index works

### ★ GIN (Generalized Inverted Index)

Indexes a value by "taking it apart": every key/value of a JSONB, every element of an array, every word of a document, recording which rows they appear in. Good for "a column holding many values, where you query rows containing a certain value".

```sql
CREATE INDEX idx_events_meta ON order_events USING gin (meta);                   -- supports @> ? ?| ?&
CREATE INDEX idx_events_meta_path ON order_events USING gin (meta jsonb_path_ops); -- supports only @>, but half the size
SELECT count(*) FROM order_events WHERE meta @> '{"coupon": "VIP2026"}';           -- ★ must use @>

CREATE INDEX idx_products_tags ON products USING gin (tags);                     -- text[]: @> && <@
SELECT * FROM products WHERE tags @> ARRAY['熱銷'];

CREATE EXTENSION pg_trgm;                                                        -- trigrams: LIKE '%…%'
CREATE INDEX idx_customers_email_trgm ON customers USING gin (email gin_trgm_ops);
SELECT * FROM customers WHERE email LIKE '%04242%';

CREATE INDEX idx_docs_fts ON docs USING gin (to_tsvector('simple', body));       -- full-text search
```

| JSONB `meta @> '{"coupon": "VIP2026"}'` | Time | Index size |
|---|---|---|
| No index (Seq Scan) | 524 ms | — |
| `gin (meta)` | — | 8.7 MB |
| `gin (meta jsonb_path_ops)` (chosen by the optimizer) | 6.8 ms | 4.5 MB |

- ★ `meta->>'coupon' = 'VIP2026'` **can't use** GIN (`->>` with `=` isn't a GIN operator); write `meta @> '{"coupon": "VIP2026"}'`, or create a separate B-tree expression index
- `jsonb_ops` (the default): supports `@>`, `?` (key exists), `?|`, `?&`; `jsonb_path_ops`: supports only `@>`, but smaller and faster
- pg_trgm + GIN lets `LIKE '%middle%'` and `ILIKE` use an index too (measured 2.7 ms → 0.04 ms on 20,000 rows); a B-tree only handles `LIKE 'abc%'` (prefixes)
- Cost: slower writes (one row updates many index entries); PostgreSQL mitigates this with a pending list merged later (`fastupdate`)
- Like the core of Elasticsearch, it's an inverted index; Elasticsearch adds tokenization, relevance scoring and distribution

### GiST (Generalized Search Tree)

A balanced tree with "customizable comparisons", for indexing data that **overlaps or has distance**: ranges (tstzrange), geometry (point, polygon, PostGIS), full-text search. A B-tree only understands "ordering"; GiST understands "overlaps `&&`", "contains `@>`" and "distance `<->`".

```sql
-- ★ Exclusion constraint: bookings for the same room can't overlap (UNIQUE can't check "overlap")
CREATE EXTENSION btree_gist;                          -- lets GiST also handle room's =
CREATE TABLE bookings (
  room   int,
  during tstzrange,
  EXCLUDE USING gist (room WITH =, during WITH &&)
);
INSERT INTO bookings VALUES (101, '[2026-10-07 14:00, 2026-10-07 16:00)');
INSERT INTO bookings VALUES (101, '[2026-10-07 15:00, 2026-10-07 17:00)');  -- ✗ conflicting key value violates exclusion constraint
INSERT INTO bookings VALUES (101, '[2026-10-07 16:00, 2026-10-07 18:00)');  -- ✓ [) is half-open, so 16:00 connects exactly

-- Nearest neighbor (KNN): the 5 stores nearest to Taipei Main Station
CREATE INDEX idx_stores_loc ON stores USING gist (loc);
SELECT id FROM stores ORDER BY loc <-> point(121.5, 25.03) LIMIT 5;
```

| The nearest 5 stores (200,000 stores) | Plan | Time |
|---|---|---|
| No index | Seq Scan of 200,000 rows + top-N sort | 27 ms |
| GiST | Index Scan, reading the first 5 directly by distance | 0.15 ms |

- ★ Exclusion constraints (EXCLUDE) are GiST's most-asked use: non-overlapping meeting room / hotel bookings, non-overlapping shifts for one employee, non-overlapping price ranges
- KNN search: `ORDER BY column <-> target LIMIT n`; the index reads "nearest first" directly, without computing every distance and sorting
- Full-text search can use GiST too: smaller and faster to update than GIN, but slower to query (false positives must be rechecked); choose GIN for query-heavy workloads
- PostGIS spatial indexes are GiST; pgvector's vector indexes are HNSW / IVFFlat (separate index types)

### ★ BRIN (Block Range Index)

Doesn't record every row, only "the min and max in each range of disk blocks (128 pages by default)". Queries skip block ranges that can't match, then read the remaining blocks and check row by row.

```sql
CREATE INDEX idx_events_time_brin ON order_events USING brin (event_time);
SELECT count(*) FROM order_events
WHERE event_time >= '2025-06-01' AND event_time < '2025-06-02';
```

| order_events.event_time (written in time order) | Time | Index size |
|---|---|---|
| No index (Seq Scan) | 108 ms | — |
| BRIN | 1.5 ms (Heap Blocks: lossy=128, read 8,527 extra rows then filtered) | **24 kB** |
| B-tree | 0.12 ms (Index Only Scan: count needn't read the table) | 43 MB |

- BRIN is a bit slower than a B-tree (it reads whole block ranges and filters), but the index is 1,800 times smaller; the bigger the data and the tighter the memory, the better the trade
- ★ Precondition: the data's order on disk must match the column values (high correlation). Logs, IoT and transaction records appended in time order are ideal; BRIN on a randomly written column is useless (the lab's orders_big: the optimizer simply ignores it)
- After lots of UPDATEs / DELETEs the order gets scrambled and it works worse
- "lossy": BRIN can only say "this block range might have it", so rows must always be rechecked (Rows Removed by Index Recheck)
- Good for: time series with hundreds of millions of rows, append-only data; a tiny index buys "rough positioning". TimescaleDB relies heavily on this property too

### How to choose an index

| The query looks like | Use |
|---|---|
| `=`, `<`, `>`, `BETWEEN`, `ORDER BY`, `LIKE 'abc%'` | B-tree (default) |
| Only a small part of the data, with a fixed condition (`WHERE status = 'pending'`) | Partial index |
| The condition computes on the column (`lower(email)`, time zone conversion) | Expression index |
| JSONB `@>` / `?`, array `@>` `&&`, full-text search, `LIKE '%…%'` (pg_trgm) | GIN |
| Range overlaps, exclusion constraints, geometry, nearest neighbor (`<->`) | GiST |
| Huge tables appended in time order, queried by time range | BRIN |
| Only `=`, with very long values | Hash (rarely used in practice) |
| Vector similarity (AI semantic search) | pgvector's HNSW / IVFFlat |

## 16. Common psql commands

| Command | Effect |
|---|---|
| `\l` | List databases |
| `\dt` | List tables |
| `\d table` | Show a table's structure, indexes and foreign keys |
| `\di` | List indexes |
| `\x` | Toggle expanded display (handy with many columns) |
| `\timing` | Show how long each SQL statement takes |
| `\e` | Write a long SQL statement in an editor |
| `\q` | Quit |

---

# Redis

> The keys in the examples all come from the practice environment (the `redis-lab` container, port 6380) and can be pasted straight into the showcase's "command console". The data is converted from PostgreSQL; if you break something, press "reset data".

## 1. ★ Basics: why Redis is so fast

| Concept | Explanation |
|---|---|
| Data lives in memory | Reads and writes take microseconds; the disk is only used for persistence backups |
| Commands run on a single thread | One command at a time, with no locks and no context switching; **every command is atomic by nature** |
| I/O multiplexing | epoll handles tens of thousands of connections at once; since Redis 6 network I/O can be multi-threaded (`io-threads`), but commands still run on one thread |
| Efficient data structures | The underlying encoding switches automatically with data size (listpack, skiplist, intset…) |

★ The cost of a single thread: **one slow command blocks everyone**. `KEYS *`, `HGETALL` / `SMEMBERS` / `DEL` on a big key, and long-running Lua scripts all stall the whole Redis.

Key naming convention: layer with colons, `object-type:id:field`, e.g. `product:540`, `customer:1:recent_orders`, `cache:category-report:2`.

## 2. Generic commands (work on every type)

| Command | Meaning |
|---|---|
| `EXISTS k`, `TYPE k` | Does it exist, what type is it |
| `DEL k`, `UNLINK k` | Delete; UNLINK frees memory in the background, so deleting a big key doesn't block |
| `EXPIRE k 60`, `PEXPIRE k 500` | Set an expiry (seconds / milliseconds) |
| `TTL k`, `PTTL k` | Seconds remaining; **-1 = no expiry, -2 = key doesn't exist** |
| `PERSIST k` | Remove the expiry |
| `RENAME k k2`, `COPY k k2` | Rename, copy |
| `SCAN 0 MATCH product:* COUNT 100` | ★ Find keys in batches (keep scanning with the returned cursor until it's 0) |
| `OBJECT ENCODING k`, `MEMORY USAGE k` | Underlying encoding, memory used |
| `DBSIZE`, `INFO memory`, `SLOWLOG GET 10` | Number of keys, memory, slow command log |

★ Never use `KEYS *` in production: it scans every key at once while every other request waits. Use `SCAN`.

## 3. String

```redis
SET k v                       -- overwrites the whole value and also clears the existing expiry
SET k v EX 1800               -- write and set a 30-minute expiry at once
SET k v NX                    -- write only if it doesn't exist (distributed lock)
SET k v XX                    -- write only if it exists
SET k v KEEPTTL               -- keep the existing expiry (6.0+)
GET k        MGET k1 k2       MSET k1 v1 k2 v2
INCR k       INCRBY k 10      DECR k      INCRBYFLOAT k 1.5     -- atomic counters
GETDEL k     GETEX k EX 60    APPEND k v  STRLEN k
```

Uses: caching (JSON strings), counters, sessions, distributed locks, rate limiting. A single value can be up to 512 MB, but anything over 10 KB already counts as a "big key".

## 4. Hash

```redis
HSET product:540 name 手機 price 29949 stock 11   -- returns the number of new fields
HGET product:540 price
HMGET customer:1 name city                       -- nil where a field doesn't exist
HGETALL product:540                               -- avoid on big Hashes; use HSCAN
HINCRBY product:540 stock -2                      -- atomic increment / decrement
HDEL k f    HEXISTS k f    HLEN k    HKEYS k    HVALS k
HEXPIRE k 60 FIELDS 1 f                           -- per-field expiry (7.4+)
```

★ Cache objects as a Hash or a JSON String? A Hash lets you read or change just some fields; a JSON String suits reading and writing everything at once and nested structures.

## 5. List

```redis
LPUSH k a b c     RPUSH k x          -- push from the left / right
LPOP k            RPOP k 2           -- pop from the left / right
LRANGE k 0 9                          -- ★ the end is inclusive: 0 9 is 10 items; 0 -1 is everything
LTRIM k 0 9                           -- keep only the first 10
LINDEX k 0     LLEN k     LREM k 0 v
BLPOP k 5                             -- wait up to 5 seconds if empty (a simple queue)
LMOVE src dst LEFT RIGHT              -- atomic move (a reliable queue)
```

Uses: the latest N items (`LPUSH` + `LTRIM`), simple message queues. When you need acknowledgements and several consumers sharing work, use Stream.

## 6. Set

```redis
SADD tag:特價 540 541       SREM k m      SCARD k       SISMEMBER k m
SMEMBERS k                                  -- avoid on big Sets; use SSCAN
SINTER a b     SUNION a b     SDIFF a b     -- intersection, union, difference (in A, not in B)
SINTERCARD 2 a b                            -- only the size of the intersection (7.0+)
SINTERSTORE dst a b                         -- store the result, return the count
SRANDMEMBER k 3     SPOP k                  -- random picks (lotteries)
```

Uses: tags, mutual friends (intersection), whether someone already liked something, blocklists, lotteries. Elements are unordered and unique.

## 7. ★ Sorted Set (leaderboards)

```redis
ZADD board 100 alice 90 bob             -- score member
ZINCRBY board 10 alice                  -- atomically add points, returns the new score
ZSCORE board alice
ZRANGE board 0 9 REV WITHSCORES         -- top 10 (old syntax: ZREVRANGE board 0 9 WITHSCORES)
ZREVRANK board alice                    -- ★ ranks start at 0
ZRANK board alice                       -- rank from lowest to highest
ZCOUNT board 50 100                     -- how many in a score range; (50 means excluding 50
ZRANGE board 50 100 BYSCORE             -- fetch by score range
ZREMRANGEBYSCORE board -inf 10          -- delete a score range
ZUNIONSTORE week 7 day1 day2 ...        -- merge several boards (daily → weekly)
```

★ Equal scores are ordered lexicographically by member; REV reverses everything. To rank "whoever got there first higher on a tie", encode time into the score.

Underneath: small sets are a listpack; large ones become a **skiplist + hash table**, so queries by score range are O(log N) and looking up a member's score is O(1).

Uses: leaderboards, delay queues (score = run time), sliding-window rate limiting (score = request time), Geo.

## 8. Special types: Stream, HyperLogLog, Bitmap, Geo

**Stream** (5.0+): an append-only log, like a lightweight Kafka

```redis
XADD orders:stream * order_id 80000 status paid     -- * = auto-generated ID (milliseconds-sequence)
XLEN orders:stream
XRANGE orders:stream - + COUNT 10                   -- oldest to newest
XREVRANGE orders:stream + - COUNT 3                 -- the newest 3
XGROUP CREATE orders:stream g1 $                    -- create a consumer group
XREADGROUP GROUP g1 worker-1 COUNT 10 STREAMS orders:stream >
XACK orders:stream g1 <ID>                          -- acknowledge when done; unacknowledged entries stay Pending
```

**HyperLogLog**: estimates distinct counts, fixed at most 12 KB, error about 0.81%

```redis
PFADD uv:2026-09-01 user1 user2
PFCOUNT uv:2026-09-01                 -- the estimate
PFCOUNT uv:day1 uv:day2               -- distinct count across several days combined
PFMERGE uv:2026-09 uv:day1 uv:day2    -- merge into a new key
```

**Bitmap**: one bit per person, exact, size proportional to the largest id

```redis
SETBIT active:2026-09-01 500 1        -- customer 500 was active that day
GETBIT active:2026-09-01 500
BITCOUNT active:2026-09-01            -- how many people
BITOP AND both active:day1 active:day2   -- active on both days (OR = either day)
```

**Geo**: a Sorted Set underneath

```redis
GEOADD store:locations 121.5654 25.0330 台北市     -- longitude latitude member
GEODIST store:locations 台北市 高雄市 km
GEOSEARCH store:locations FROMMEMBER 台北市 BYRADIUS 60 km ASC WITHDIST
GEOSEARCH store:locations FROMLONLAT 121.5 25.0 BYBOX 20 20 km
```

★ Choosing how to count UV: a Set for small volumes (you can list the people), a Bitmap for sequential integer ids (exact and compact), HyperLogLog for huge volumes where some error is acceptable.

## 9. ★ Transactions and Lua scripts

```redis
MULTI                        -- start; later commands return QUEUED
INCR stats:orders:total
LPUSH customer:1:recent_orders 90001
EXEC                         -- run them together; DISCARD abandons

WATCH stock                  -- optimistic lock: if stock was changed by someone else before EXEC, the transaction doesn't run (returns nil)
```

★ Redis transactions **have no ROLLBACK**:
- A syntax error detected while queuing → `EXEC` returns EXECABORT and nothing runs
- An error only at run time (e.g. wrong type) → **only that command fails; the others still take effect**

For atomic "check, then modify" operations, use a Lua script: no other command runs while the script executes.

```redis
EVAL "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0" 1 lock:order:1 my-token
```

Note: keep scripts short; running too long blocks the whole Redis (SCRIPT KILL is only available after 5 seconds by default). Since Redis 7, `FUNCTION` is recommended over EVAL.

## 10. Pipeline

Every command waits for a network round trip (RTT). A pipeline sends many commands at once and collects the results at the end.

| Writing 1000 keys (measured in the practice environment) | Time |
|---|---:|
| One SET at a time | about 200–500 ms |
| Pipeline | about 2 ms |
| MSET | about 1–2 ms |

A pipeline **isn't a transaction**: other clients' commands may run in between. Don't stuff hundreds of thousands in at once; send them in batches.

## 11. ★ Patterns in practice

**Cache-Aside**

```redis
Read: GET cache:x → return it if present; if not → query the database → SET cache:x <value> EX 60 → return
Write: update the database first → then DEL cache:x (delete, don't update the cache)
```

| Problem | Scenario | Solutions |
|---|---|---|
| ★ Cache penetration | Querying data that "doesn't exist at all" hits the database every time | Cache empty results (short TTL), Bloom filters |
| ★ Cache breakdown | The instant a hot key expires, a flood of requests hits the database at once | A mutex so only one request refills it; hot data that never expires + background refresh |
| ★ Cache avalanche | Lots of keys expire at the same time, or the whole Redis goes down | Random jitter on expiry times, multi-level caches, rate limiting and degradation, high-availability setups |

Cache/database consistency: "update the database first, then delete the cache" is the most common; for stricter needs use delayed double deletion, or listen to database changes (Canal / Debezium) and delete the cache.

**Distributed locks**

```redis
Lock: SET lock:order:1 <uuid> NX PX 30000      -- one command does "write if absent" + "expiry"
Unlock: Lua compares the value with your own uuid before DEL       -- avoids deleting someone else's lock
```

- ★ Don't use the two commands `SETNX` + `EXPIRE`: a crash in between leaves a lock that never expires, and when SETNX fails, the following EXPIRE changes someone else's lock
- The work may take longer than the lock's expiry → Redisson's watchdog renews it automatically
- In Java, just use Redisson's `RLock`

**Rate limiting**

| Algorithm | How |
|---|---|
| Fixed window | Count with `INCR`, `EXPIRE` on the first hit (inside Lua); the downside is up to double traffic at window boundaries |
| Sliding window | A Sorted Set records each request time; `ZREMRANGEBYSCORE` removes those outside the window, `ZCARD` counts |
| Token bucket | Lua computes the refilled tokens; Spring Cloud Gateway's RequestRateLimiter works this way |

**Stock deduction (preventing overselling)**: `GET` → check → `SET` oversells; use `DECR` (add it back if below 0) or Lua "check, then deduct".

**Other common uses**: counters (`INCR`), shared sessions (`SET … EX`), idempotency (`SET request:<id> 1 NX EX 86400`, so duplicate requests fail), leaderboards (Sorted Set), activity feeds (`LPUSH` + `LTRIM`), delay queues (Sorted Set, score = run time).

## 12. ★ Persistence

| | RDB (snapshots) | AOF (command log) |
|---|---|---|
| How | Periodically saves the whole database to a binary file (`BGSAVE`, forking a child process) | Appends every write command to a file |
| Data lost | Everything since the last snapshot | Depends on `appendfsync`: `always` loses nothing, `everysec` at most 1 second, `no` is up to the OS |
| File size / restart speed | Small, fast | Large, slow (periodically compacted by `BGREWRITEAOF`) |

Since Redis 4.0 they can be combined (the AOF file starts with an RDB snapshot), balancing restart speed and data safety. The practice environment disables both, since all the data can be rebuilt from PostgreSQL.

## 13. ★ Expiration and memory eviction

**How expired keys are deleted**: lazy deletion (checked on access) + periodic deletion (a sample checked every second). So expired keys don't necessarily free memory immediately.

**What happens when memory is full** (`maxmemory-policy`):

| Policy | Meaning |
|---|---|
| `noeviction` | The default; rejects writes (used in the practice environment) |
| `allkeys-lru` | ★ Evicts the least recently used of all keys; the most common for pure caches |
| `volatile-lru` | Evicts only keys that have an expiry |
| `allkeys-lfu` / `volatile-lfu` | Evicts the least frequently used (4.0+) |
| `allkeys-random` / `volatile-random` | Random |
| `volatile-ttl` | Evicts the keys closest to expiring |

LRU is approximate: each time it samples a few keys (`maxmemory-samples`) and picks the oldest; it's not exact LRU.

## 14. ★ High availability: replication, Sentinel, Cluster

| Architecture | Explanation |
|---|---|
| Primary–replica replication | The primary writes, replicas serve reads; replication is asynchronous, so a primary failure may lose the last bit of data |
| Sentinel | Monitors the primary and promotes a replica automatically when it fails (automatic failover) |
| Cluster | Data is spread across several primaries: **16384 slots**, and `CRC16(key) % 16384` decides which node |

★ Cluster's limitation: the keys used by one command must be in the same slot, or you get an error (CROSSSLOT). Use hash tags to put them in one slot: `{order:1}:items` and `{order:1}:status` are hashed only on the part inside `{}`.

## 15. Underlying encodings (bonus interview points)

| Type | With little data | With lots of data |
|---|---|---|
| String | int (integers), embstr (≤ 44 bytes) | raw |
| List | listpack | quicklist (several listpacks linked) |
| Hash | listpack | hashtable |
| Set | intset (all integers), listpack | hashtable |
| Sorted Set | listpack | skiplist + hashtable |

Check with `OBJECT ENCODING key`. The thresholds come from configuration, e.g. `hash-max-listpack-entries 128`. Small data uses the compact listpack to save memory, so "splitting into many small Hashes" often saves space over one giant Hash.

## 16. Java / Spring Boot

| Tool | Explanation |
|---|---|
| Lettuce | Spring Boot's default client, built on Netty and thread-safe; one connection can be shared |
| Jedis | The traditional synchronous client, used with a connection pool (the showcase uses Jedis) |
| Redisson | Advanced features: distributed locks, watchdog, rate limiters, delay queues |
| `RedisTemplate` | Spring Data Redis's entry point: `opsForValue()`, `opsForHash()`, `opsForZSet()`… |
| `@Cacheable` / `@CacheEvict` | Spring Cache annotations; with `RedisCacheManager` they do Cache-Aside automatically |

★ Common pitfalls:
- `RedisTemplate` uses JDK serialization by default, so keys become gibberish like `\xac\xed\x00\x05t\x00…`. Configure `StringRedisSerializer` and a JSON serializer, or just use `StringRedisTemplate`
- `@Cacheable` has no expiry by default; set it in `RedisCacheConfiguration.entryTtl(...)`
- Calling your own `@Cacheable` method from within the same class doesn't go through the proxy, so the cache doesn't apply

## 17. ★ Quick interview answers

| Question | Key points |
|---|---|
| Why is Redis fast | Memory, single-threaded command execution (no locks), I/O multiplexing, efficient data structures |
| Is Redis single-threaded | Commands run on one thread; since 6.0 network I/O can be multi-threaded; persistence and UNLINK use background threads |
| The five basic types and their uses | String caching / counters, Hash objects, List queues / latest lists, Set tags / de-duplication, Sorted Set leaderboards |
| Penetration, breakdown, avalanche | Non-existent data, a hot key expiring, many keys expiring at once (see section 11) |
| How to keep the cache consistent | Update the database, then delete the cache; delayed double deletion; subscribe to the binlog and delete the cache |
| How to build a distributed lock | SET NX PX + a unique token + release with Lua; Redisson's watchdog; RedLock |
| Do transactions ROLLBACK | No; a run-time error doesn't affect other commands. Use Lua for atomicity |
| RDB vs AOF | Snapshots vs a command log; hybrid persistence |
| What happens when memory is full | Depends on maxmemory-policy; use allkeys-lru for caches |
| How expired keys are deleted | Lazy deletion + periodic deletion |
| What a big key is and how to handle it | A single key that's too big (String > 10 KB, collections > thousands of elements); split it, delete with UNLINK, read with SCAN-style commands |
| How to handle a hot key | An extra local cache layer (Caffeine); add suffixes to the key to spread it across nodes |
| How Cluster shards | 16384 slots, CRC16; hash tags for cross-slot keys |
| KEYS vs SCAN | KEYS scans everything at once and blocks; SCAN works in batches, may repeat, and must run until the cursor is 0 |

## 18. Common redis-cli commands

| Command | Effect |
|---|---|
| `redis-cli -p 6380 --user learner --pass learner-lab` | Connect to the practice environment |
| `docker exec -it redis-lab redis-cli --user default --pass admin-lab` | Connect as admin from inside the container |
| `--scan --pattern 'product:*'` | List keys safely |
| `--bigkeys`, `--memkeys` | Find the biggest keys |
| `--latency` | Measure latency |
| `MONITOR` | Watch every command live (very costly; development only) |
| `INFO`, `INFO memory`, `INFO stats` | Server status |
| `CLIENT LIST` | Current connections |

---

# MongoDB

> The examples all come from the practice environment (the `mongo-lab` container, port 27018, the `shop` database) and can be pasted straight into the showcase's "command console". The data is converted from PostgreSQL; if you break something, press "reset data".

## 1. ★ Basics, compared with SQL

| SQL | MongoDB |
|---|---|
| database | database |
| table | collection |
| row | document (BSON format) |
| column | field (each document can differ) |
| primary key | `_id` (every document has one; ObjectId by default) |
| JOIN | Embedded documents, or `$lookup` |
| GROUP BY | The aggregation pipeline's `$group` |
| index | index (almost the same concept) |

- **BSON**: binary JSON, adding types like Date, ObjectId, Decimal128, Int32 / Int64
- **ObjectId**: 12 bytes; the first 4 bytes are the creation time, so it roughly increases with time, and `ObjectId(…).getTimestamp()` extracts it
- **A single document is limited to 16 MB**
- **Flexible schema**: documents in one collection can have different fields; JSON Schema validation (`$jsonSchema`) is available when needed
- Don't use double for money: use `Decimal128` (`NumberDecimal("19.99")`) or store integer "cents"

## 2. Queries: find

```mongo
db.orders.find({ status: "paid" })                          // filter
db.orders.find({ status: "paid" }, { total: 1, _id: 0 })    // projection: 1 include, 0 exclude
db.orders.find({}).sort({ orderDate: -1 }).skip(20).limit(10)   // sort, paginate
db.orders.findOne({ _id: 77621 })
db.orders.countDocuments({ status: "paid" })                // count by filter
db.orders.estimatedDocumentCount()                          // reads metadata; fast, no filter allowed
db.orders.distinct("shipping.city")                         // distinct values
```

- ★ However find's `sort`, `skip` and `limit` are chained, they always run as "sort → skip → limit"
- Apart from `_id`, a projection can't mix 1 and 0
- Don't paginate deep with a large `skip` (it scans everything before it); use "the last value of the previous page" as a condition instead: `{ orderDate: { $lt: last time on the previous page } }`

## 3. Query operators

| Category | Operators |
|---|---|
| Comparison | `$eq` `$ne` `$gt` `$gte` `$lt` `$lte` `$in` `$nin` |
| Logical | `$and` `$or` `$nor` `$not` (several conditions in one object are already ANDed) |
| Field | `$exists` (whether a field exists), `$type` (type) |
| Array | `$all` `$elemMatch` `$size` |
| Other | `$regex` (or write `/^Apple/` directly), `$expr` (aggregation expressions in a filter, e.g. comparing two fields) |

```mongo
db.products.find({ price: { $gte: 1000, $lte: 2000 } })
db.orders.find({ status: { $in: ["cancelled", "returned"] } })
db.customers.find({ $or: [{ city: "花蓮縣" }, { vipLevel: "gold" }] })
db.customers.find({ birthDate: { $exists: false } })
db.products.find({ $expr: { $gt: ["$price", { $multiply: ["$cost", 2] }] } })   // price more than twice the cost
```

★ Types must match: `{ _id: "540" }` (a string) won't find `_id: 540` (a number); there's no automatic conversion and no error.

★ `{ city: null }` finds both documents whose value is null and documents without the field. For only missing fields use `{ $exists: false }`.

## 4. Arrays and embedded documents

```mongo
db.orders.find({ "shipping.city": "台北市" })                // dot notation for embedded fields (quoted)
db.orders.find({ "items.productId": 540 })                  // dot notation also reaches into arrays
db.products.find({ tags: "特價" })                           // ★ "any element" of the array equals
db.products.find({ tags: ["特價"] })                         // the whole array "exactly equals" ["特價"]
db.products.find({ tags: { $all: ["熱銷", "限量"] } })        // contains all, in any order
db.products.find({ tags: { $size: 3 } })                     // exactly 3 elements
db.orders.find({ items: { $elemMatch: { qty: { $gte: 3 }, unitPrice: { $gte: 10000 } } } })
```

★ `$elemMatch` is a classic interview question: the two conditions in `{ "items.qty": { $gte: 3 }, "items.unitPrice": { $gte: 10000 } }` can be satisfied by "different elements"; to require the same element to satisfy both, you must use `$elemMatch`.

## 5. ★ The aggregation pipeline

Documents flow through each stage in order; each stage's output is the next stage's input.

| Stage | Purpose | SQL equivalent |
|---|---|---|
| `$match` | Filter (the earlier the better; a leading one can use indexes) | WHERE |
| `$project` / `$addFields` / `$set` / `$unset` | Choose fields, add or compute fields | SELECT |
| `$group` | Grouped aggregation: `$sum` `$avg` `$min` `$max` `$first` `$push` `$addToSet` | GROUP BY |
| `$sort` / `$limit` / `$skip` | Sorting, counts | ORDER BY / LIMIT |
| `$unwind` | Flatten an array into several documents | unnest / JOIN a detail table |
| `$lookup` | Join another collection; the result is an array | LEFT JOIN |
| `$bucket` / `$bucketAuto` | Statistics by ranges | CASE WHEN + GROUP BY |
| `$facet` | Run several pipelines on the same input (e.g. total count and a page at once) | Several queries |
| `$count` | Count | count(*) |
| `$out` / `$merge` | Write the result into a collection | INSERT INTO … SELECT |

```mongo
db.orders.aggregate([
  { $match: { status: "delivered" } },
  { $unwind: "$items" },
  { $group: { _id: "$items.productId", name: { $first: "$items.name" }, qty: { $sum: "$items.qty" } } },
  { $sort: { qty: -1 } },
  { $limit: 5 }
])

// A customer's newest 3 orders + the customer's name: $limit before $lookup
db.orders.aggregate([
  { $match: { customerId: 1 } },
  { $sort: { orderDate: -1 } },
  { $limit: 3 },
  { $lookup: { from: "customers", localField: "customerId", foreignField: "_id", as: "customer" } },
  { $project: { total: 1, customerName: { $first: "$customer.name" } } }
])
```

- ★ A pipeline runs its stages in order: `[{ $limit: 3 }, { $sort: … }]` really takes 3 documents first and then sorts
- `$lookup`'s `foreignField` needs an index, or every document scans the whole collection
- Each stage has a 100 MB memory limit; beyond that it needs `allowDiskUse` (allowed by default since 6.0)
- ★ Dates are always stored in UTC: filter with `ISODate("2026-09-01T00:00:00+08:00")`, and give `$dateToString` / `$dateTrunc` `timezone: "Asia/Taipei"`

## 6. Writes

```mongo
db.customers.insertOne({ name: "王小明", email: "a@b.com" })
db.customers.insertMany([{ … }, { … }])
db.products.updateOne({ _id: 540 }, { $set: { stock: 20 } })
db.products.updateMany({ "category.name": "飲料" }, { $inc: { price: 5 } })
db.customers.updateOne({ email: "a@b.com" }, { $set: { name: "新會員" } }, { upsert: true })
db.products.findOneAndUpdate({ _id: 540, stock: { $gt: 0 } }, { $inc: { stock: -1 } }, { returnDocument: "after" })
db.orders.deleteMany({ status: "cancelled" })
```

| Operator | Purpose |
|---|---|
| `$set` / `$unset` | Set / remove a field |
| `$inc` / `$mul` | Add / multiply (atomic) |
| `$min` / `$max` | Update only if smaller / larger than the current value |
| `$rename` | Rename a field |
| `$setOnInsert` | Set only when an upsert "inserts" |
| `$push` / `$addToSet` | Add to an array / add only if absent (`$each` for several at once, `$slice` to limit the length) |
| `$pull` / `$pop` | Remove by condition / remove the first or last |
| `$[]` / `$[elem]` | Update every element of an array / the elements matching arrayFilters |

- ★ `replaceOne` "replaces the whole" old document with the new one; to change only some fields, always use `$set`
- `updateOne` / `deleteOne` handle only the first matching document; use `updateMany` / `deleteMany` for all of them
- upsert needs a unique index on the filter field, or concurrent upserts may insert duplicate documents

## 7. ★ Schema design: embed or reference

**The core principle: data that's read together is stored together.**

| Embedding | Referencing |
|---|---|
| Everything in one read, no JOIN | No duplicated data; can be queried and updated on its own |
| Updates to a single document are atomic, with no transaction needed | Reads need `$lookup`, or two queries in the application |
| Good for one-to-few that's always read together (order items, addresses, specs) | Good for one-to-many, unbounded growth, many-to-many, and data often updated on its own |

Common design patterns:

| Pattern | Explanation |
|---|---|
| Extended Reference | Besides the reference, copy a few frequently used fields (e.g. the product name in an order), saving a `$lookup` |
| Subset | Embed only the most used part (e.g. a product document holds only the latest 10 reviews; the rest go in another collection) |
| Computed | Compute it ahead of time and store it in the document (e.g. an order's `total`), so reads don't recompute it |
| Bucket | Bucket time series data by time, one document per hour of data (MongoDB 5.0+ has native Time Series collections) |
| Tree | Store trees with `parentId`, an `ancestors` array or a path string (the practice environment's categories) |

★ Anti-pattern: arrays that grow without limit (e.g. stuffing all of a customer's orders into the customer document) hit the 16 MB limit, and updates get slower and slower.

## 8. ★ Indexes

```mongo
db.orders.createIndex({ customerId: 1, orderDate: -1 })
db.customers.createIndex({ email: 1 }, { unique: true })
db.orders.createIndex({ orderDate: 1 }, { partialFilterExpression: { status: "pending" } })
db.sessions.createIndex({ createdAt: 1 }, { expireAfterSeconds: 3600 })   // TTL: deletes expired documents automatically
db.orders.getIndexes()
db.orders.dropIndex("customerId_1")
db.orders.find({ customerId: 4242 }).explain("executionStats")
```

| Type | Explanation |
|---|---|
| Single / compound | The most common; field order matters in compound indexes |
| Multikey | An index on an array field, one entry per element; at most one array field per compound index |
| unique / partial / sparse | Unique, only some documents, only documents that have the field |
| TTL | Deletes documents automatically when their time is up (sessions, verification codes, logs) |
| text / Atlas Search | Full-text search |
| 2dsphere | Geospatial queries |
| hashed | Hash values, mainly for shard keys |
| wildcard | When fields vary, index dynamic fields like `specs.$**` |

★ **The ESR rule** (field order in compound indexes): **E**quality → **S**ort → **R**ange.
For example, for `find({ status: "delivered", total: { $gte: 50000 } }).sort({ orderDate: -1 })` create `{ status: 1, orderDate: -1, total: 1 }`, which avoids an in-memory sort and is especially effective for pagination.

**Reading explain**

| Field / stage | Meaning |
|---|---|
| `COLLSCAN` | A full collection scan (missing index) |
| `IXSCAN` → `FETCH` | Find positions via the index, then read the documents |
| `SORT` | Sorting in memory (no index provides the order) |
| `PROJECTION_COVERED` | A covered query: reads only the index, not the documents (remember `_id: 0`) |
| `nReturned` / `totalKeysExamined` / `totalDocsExamined` | Documents returned / index entries examined / documents read; the closer the three are, the better |

## 9. ★ Transactions and consistency

- **Operations on a single document are always atomic**, so a good embedded design often needs no transactions
- Multi-document transactions (4.0+) require a **replica set** or sharded cluster and don't work on a standalone server; they have a performance cost and a default 60-second timeout
- **Write Concern**: how many nodes must acknowledge a write. `w: 1` (the primary), `w: "majority"` (a majority, the default since 5.0)
- **Read Concern**: what level of data to read. `local`, `majority` (never reads data that might later be rolled back), `linearizable`
- **Read Preference**: which node to read from. `primary` (the default), `secondaryPreferred` (spreads reads, but may read slightly stale data)

```java
// Spring: @Transactional with MongoTransactionManager (requires a replica set)
try (ClientSession session = client.startSession()) {
    session.withTransaction(() -> {
        orders.insertOne(session, order);
        products.updateOne(session, eq("_id", 540), inc("stock", -1));
        return null;
    });
}
```

## 10. ★ Replica sets and sharding

| Architecture | Explanation |
|---|---|
| Replica set | One Primary takes writes and several Secondaries replicate via the oplog; when the Primary fails a new one is elected automatically (usually within seconds); at least 3 nodes (or 2 + an arbiter) |
| Sharding | Data is spread across shards by the "shard key"; applications connect to the `mongos` router, and configuration lives in config servers |

★ Choosing a shard key:
- High cardinality (many distinct values), an even distribution, and often present in query conditions
- A monotonically increasing key (time, ObjectId) concentrates every write on the last shard → use hashed sharding, or a compound shard key
- Queries without the shard key must ask every shard (scatter-gather), which is slower

## 11. Common traps

| Trap | Explanation |
|---|---|
| `{ field: null }` | Also finds documents without the field |
| Different types | `"540"` and `540` aren't equal; no automatic conversion |
| Equality on arrays | `{ tags: "a" }` means contains; `{ tags: ["a"] }` means exactly equal |
| Several conditions on an array | To require the same element to match all of them, use `$elemMatch` |
| Time zones | Dates are stored in UTC; give the time zone explicitly when querying and grouping |
| `replaceOne` / `save()` | Replaces the whole document; fields not included disappear |
| `updateOne` | Updates only one document |
| Pagination with large skip | Slower the further you go; paginate with range conditions instead |
| JDBC-style thinking | Turning every table into a collection and `$lookup` everywhere throws away the document database's advantages |

## 12. Java / Spring Data MongoDB

| Tool | Explanation |
|---|---|
| MongoDB Java Driver | The official driver (the showcase uses the sync version) |
| `MongoTemplate` | Spring's entry point: `find(Query, Class)`, `updateFirst`, `aggregate` |
| `MongoRepository` | Queries generated from method names: `findByStatusOrderByOrderDateDesc(…)` |
| `@Document` / `@Id` / `@Field` / `@Indexed` | Map to a collection, the primary key, a field name, an index |
| `Criteria` / `Query` / `Update` | Build conditions: `Query.query(Criteria.where("status").is("paid"))` |

★ Common pitfalls:
- `repository.save(entity)` replaces the whole document: save an object loaded with only some fields and the other fields disappear. For partial updates use `MongoTemplate.updateFirst` + `Update.update(…)`
- Spring Data stores an extra `_class` field in each document by default (recording the Java class); it can be turned off via `MappingMongoConverter`
- `@Indexed` does **not** create indexes automatically by default (since Spring Boot 3 you set `spring.data.mongodb.auto-index-creation=true`; in production, create them with a migration tool)
- `LocalDateTime` has no time zone and is treated as UTC when stored in MongoDB, so it may come back 8 hours off

## 13. ★ Quick interview answers

| Question | Key points |
|---|---|
| How does MongoDB differ from relational databases | Document model, flexible schema, embedding instead of JOINs, easier horizontal scaling (sharding) |
| When to use MongoDB | Changing data structures, reads and writes by "whole document", horizontal scaling needs; systems with strong relationships and complex transactions are better off relational |
| Embed or reference | Store together what's read together; embed one-to-few, reference one-to-many or unbounded growth |
| When to use `$elemMatch` | When "the same element" of an array must satisfy several conditions at once |
| Compound index field order | ESR: equality → sort → range |
| How to tell whether a query uses an index | explain("executionStats"): IXSCAN vs COLLSCAN, docsExamined vs nReturned |
| Does MongoDB support transactions | Single documents are always atomic; multi-document transactions since 4.0, requiring a replica set |
| How a replica set tolerates failures | oplog replication, automatic election when the Primary fails; write concern majority avoids rollbacks |
| How to choose a shard key | High cardinality, even distribution, frequently queried; avoid monotonic increase |
| Must `_id` be an ObjectId | No, any unique value works (the practice environment uses PostgreSQL's integer ids) |
| What about the 16 MB limit | Change the design (referencing, Subset, Bucket); use GridFS for large files |

## 14. Common mongosh commands

| Command | Effect |
|---|---|
| `mongosh "mongodb://learner:learner-lab@localhost:27018/shop?authSource=admin"` | Connect to the practice environment |
| `docker exec -it mongo-lab mongosh -u admin -p admin-lab` | Connect as admin from inside the container |
| `show dbs`, `use shop`, `show collections` | List databases, switch, list collections |
| `db.orders.stats()`, `db.stats()` | Collection / database size and statistics |
| `db.currentOp()`, `db.killOp(id)` | View / kill running operations |
| `db.setProfilingLevel(1, { slowms: 100 })` | Log queries slower than 100 ms to `system.profile` |
| `it` | Show the next batch of results (find shows only 20 at a time) |


# Cassandra

> The examples all come from the practice environment (the `cassandra-lab` container, port 9043, keyspace `shop`) and can be pasted straight into the showcase's "cqlsh console". The data is converted from PostgreSQL; if you break something, press "reload data".

## 1. ★ Basics, compared with SQL

| SQL | Cassandra |
|---|---|
| database / schema | keyspace (which also sets the replication strategy and replica count) |
| table | table (formerly column family) |
| row | row (belongs to a partition) |
| primary key | partition key + clustering columns |
| JOIN | None. The data a query needs must be put in the same table ahead of time (denormalization) |
| GROUP BY / ORDER BY | Only on primary key columns, with many restrictions |
| transaction | No general transactions; only single-partition lightweight transactions (LWT) and BATCH |

- **Masterless architecture**: every node is equal, with no single point of failure; any node can act as the "coordinator" for a request
- **Consistent hashing ring**: the partition key is hashed (Murmur3) into a token, and the token decides which nodes hold the data; each node owns many token ranges (vnodes, `num_tokens: 16` by default)
- **Write path**: commitlog (sequential writes, for durability) → memtable (memory) → when full, flushed to an immutable SSTable. Writes never read first, so they're very fast
- **Read path**: memtable + possibly several SSTables merged; bloom filters skip SSTables that don't contain the partition, then the partition index finds the position
- **compaction**: merges several SSTables into one in the background, clearing expired data and tombstones along the way
- Good for: heavy writes, time series, event logs, user activity, IoT, multi-datacenter services that can't go down
- Not good for: JOINs, ad hoc queries, transactions, strongly consistent counts (stock, account balances)

## 2. ★ Primary keys: partition keys and clustering keys

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

| Part | Syntax | Role |
|---|---|---|
| Partition key | `(customer_id)`; composite ones are written `((a, b), …)` | Decides **which node** holds the data; a partition's data is stored together |
| Clustering key | `order_time, order_id` | Decides **the order within the partition**; supports range queries |
| Primary key | Partition key + clustering key | Uniquely identifies a row; writing the same primary key overwrites |

- `PRIMARY KEY (a, b, c)`: a is the partition key, b and c are clustering keys; `PRIMARY KEY ((a, b), c)`: a and b together form the partition key
- ★ The clustering key must make the primary key unique: with only `order_time`, two orders in the same millisecond would overwrite each other, so `order_id` is added
- ★ Recommended partition size: under 100 MB and under 100,000 rows. Partitions that grow without limit ("all orders", "all data of one sensor") must be bucketed
- static columns: `col text STATIC`, one value shared by every row in a partition (e.g. partition-level attributes)

## 3. ★ Query-first data modeling

Relational design is "design the data first, then write queries"; Cassandra is "**list how you'll query first, then design one table per query**".

| Query | Table | Primary key |
|---|---|---|
| Look up an order by its id | `orders` | `(order_id)` |
| A customer's orders, newest first | `orders_by_customer` | `((customer_id), order_time DESC, order_id)` |
| The orders of one day | `orders_by_day` | `((order_day), order_time, order_id)` |
| A category's products, highest price first | `products_by_category` | `((category), price DESC, product_id)` |
| Log in by email | `customers_by_email` | `(email)` |

- Writing the same data into several tables is normal (writes are cheap; cross-partition reads are expensive); a logged BATCH keeps the tables eventually consistent
- **Time bucketing**: `((sensor_id, day), ts)`, `((order_day), order_time)` keep partition sizes bounded
- **Hot spots**: partition key values must be evenly distributed. With "status" as the partition key, for example, the `delivered` partition would be enormous
- One-to-many child data can live in the same row as a collection or UDT (`list<frozen<order_item>>`), when it's small and read together

## 4. Queries: the rules of SELECT

```cql
SELECT * FROM orders_by_customer WHERE customer_id = 4242;                 -- single partition
SELECT * FROM orders_by_customer WHERE customer_id = 1 LIMIT 3;            -- partition already sorted; the newest 3
SELECT * FROM orders_by_customer
WHERE customer_id = 1 AND order_time >= '2025-01-01 00:00:00+0800';       -- clustering key range
SELECT * FROM orders_by_customer WHERE customer_id = 4242 ORDER BY order_time ASC;   -- reverse read
SELECT * FROM products WHERE product_id IN (540, 541, 542);                -- IN on the partition key
SELECT order_day, COUNT(*) FROM orders_by_day
WHERE order_day IN ('2026-09-01', '2026-09-02') GROUP BY order_day;      -- GROUP BY only on primary key columns
SELECT * FROM orders_by_day WHERE order_day IN ('2026-09-01', '2026-09-02') PER PARTITION LIMIT 1;
SELECT DISTINCT category FROM products_by_category;                        -- only on the partition key
SELECT name, WRITETIME(name), TTL(city) FROM customers WHERE customer_id = 4242;
SELECT customer_id, token(customer_id) FROM customers LIMIT 5;             -- see the partitions' tokens
```

| Rule | Explanation |
|---|---|
| ★ The full partition key is required | Use `=` or `IN`; without it the query is a full table scan and is rejected (unless you add `ALLOW FILTERING`) |
| Restrict clustering keys from the left | You can't skip earlier clustering keys; once one uses a range, later ones can't be restricted |
| Ranges only on clustering keys | The partition key takes only `=` / `IN` (or a `token()` range) |
| ORDER BY | Only clustering keys, and only in the table's order or exactly reversed |
| Non-key columns | Can't go in WHERE unless indexed (SAI) or with `ALLOW FILTERING` |
| aggregate | `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`; ★ `AVG(int)` returns an int (fraction dropped), so `CAST(x AS double)` first |
| PER PARTITION LIMIT | Only the first n rows of each partition; Cassandra's "top n per group" |

★ The cost of `ALLOW FILTERING` = the number of rows read to find the results. Filtering within a partition after restricting the partition key is cheap; without the partition key it's a full table scan.

★ Time strings without a time zone are interpreted in **the server's time zone**. Always spell it out: `'2026-09-01 20:00:00+0800'`. Timestamps are always stored in UTC (millisecond precision).

## 5. Writes: INSERT, UPDATE, DELETE

```cql
INSERT INTO customers_by_email (email, customer_id, name) VALUES ('a@example.com', 1, '王小明');
UPDATE products SET stock = 0, tags = tags + {'缺貨'} WHERE product_id = 540;
UPDATE products SET specs['color'] = '黑' WHERE product_id = 540;            -- a single map key
UPDATE customers USING TTL 86400 SET vip_level = 'gold' WHERE customer_id = 4242;
INSERT INTO kv (k, v) VALUES ('session:1', '…') USING TTL 1800;             -- the whole row expires in 30 minutes
DELETE birth_date FROM customers WHERE customer_id = 4242;                   -- delete one column
DELETE FROM orders_by_customer
WHERE customer_id = 1 AND order_time < '2023-01-01 00:00:00+0800';          -- range delete
UPDATE product_sales SET units = units + 2 WHERE product_id = 540;           -- counter
```

- ★ **INSERT and UPDATE are both upserts**: nothing is read before writing; an existing primary key is overwritten and a missing one is inserted, with no error
- ★ **last write wins**: every column value (cell) carries a write timestamp, and on read the larger timestamp wins, regardless of execution order (`USING TIMESTAMP` can set it). Keep your application servers' clocks in sync
- ★ **TTL is set per cell**: UPDATE sets a TTL only on the columns it writes; to expire a whole row use INSERT … USING TTL, or set `default_time_to_live` on the table
- ★ **Writing null = deleting = a tombstone**: don't write columns that have no value (driver 4's prepared statements can leave parameters unset)
- Counters: only `UPDATE … SET c = c + n`; no INSERT, no TTL, and a counter table can have only counter columns besides the primary key; retries may add twice, so they aren't idempotent

## 6. Data types

| Category | Types |
|---|---|
| Text | `text` (= `varchar`), `ascii` |
| Integers | `tinyint` `smallint` `int` `bigint` `varint` (arbitrary length) |
| Decimals | `float` `double` `decimal` (use decimal for money) |
| Time | `timestamp` (milliseconds), `date`, `time`, `duration` |
| Identifiers | `uuid`, `timeuuid` (contains time, sortable by time, generated by `now()`) |
| Other | `boolean` `blob` `inet` `counter` `vector<float, n>` (5.0, vector search) |
| Collections | `list<T>` (ordered, duplicates allowed), `set<T>` (unique, sorted), `map<K, V>` |
| Custom | UDT (`CREATE TYPE`), `tuple<…>`, `frozen<…>` (treated as one value, only replaceable as a whole) |

- Collections suit small amounts of data (a few dozen at most); the whole collection is read together
- ★ `tags = {'a'}` replaces the whole thing (writing a tombstone first); `tags = tags + {'a'}` only adds elements
- `+` on a list adds duplicates; on a set it doesn't

## 7. ★ Tombstones and compaction

- SSTables are never modified after being written, so **a delete writes a tombstone**; DELETE, writing null, TTL expiry and replacing a whole collection all create tombstones
- Reads only know data was deleted by reading the tombstone: beyond `tombstone_warn_threshold` (1,000) you get a warning, and beyond `tombstone_failure_threshold` (100,000) the query fails outright
- Tombstones are only purged by compaction after `gc_grace_seconds` (10 days by default). That window lets offline replicas sync the deletion when they come back; otherwise deleted data "comes back to life" (zombies). So **a full repair must finish within gc_grace_seconds**
- ★ A range delete writes a single range tombstone; deleting row by row writes one tombstone per row
- ★ Anti-patterns: using Cassandra as a queue (constantly writing then deleting), frequent updates followed by deletes

| Compaction strategy | Good for |
|---|---|
| STCS (SizeTiered, default) | Write-heavy workloads |
| LCS (Leveled) | Read-heavy, frequently updated; reads touch fewer SSTables, but compaction does more I/O |
| TWCS (TimeWindow) | Time series + TTL: data from the same time window is kept together, and whole files are dropped when they expire |
| UCS (Unified, 5.0) | Can be tuned to resemble any of the above; recommended in new versions |

## 8. ★ Replication and consistency levels

```cql
CREATE KEYSPACE shop WITH replication = {'class': 'NetworkTopologyStrategy', 'dc1': 3, 'dc2': 3};
CONSISTENCY QUORUM;     -- cqlsh command: later requests use QUORUM
```

- **RF (replication factor)**: how many copies of each piece of data. Production commonly uses 3; `NetworkTopologyStrategy` can set it per datacenter
- **CL (consistency level)**: each read and write specifies "how many replicas must respond for success"

| CL | Replicas that must respond |
|---|---|
| `ONE` / `TWO` / `THREE` | 1 / 2 / 3 |
| `QUORUM` | ⌊RF / 2⌋ + 1 (2 when RF = 3), counted across all datacenters |
| `LOCAL_QUORUM` | A quorum in the local datacenter, without waiting for cross-datacenter latency (the most common) |
| `EACH_QUORUM` | A quorum in every datacenter (for writes) |
| `ALL` | Every replica; fails if any one is down |
| `ANY` | Writes only: even a hint counts as success |

- ★ **R + W > RF means strong consistency**: with QUORUM for both reads and writes (2 + 2 > 3), the replicas read always include the latest copy
- Write ONE, read ONE: fastest, eventually consistent, may read stale data
- Too few replicas fails immediately: `UnavailableException: … QUORUM (2 required but only 1 alive)`
- Repair mechanisms: **hinted handoff** (when a replica is temporarily down, the coordinator keeps a hint and replays it when it's back; kept 3 hours by default), **read repair** (fixes inconsistent replicas found during reads), **anti-entropy repair** (`nodetool repair`, a periodic full comparison)
- CAP: Cassandra is an AP system, but consistency can be tuned per request (tunable consistency)

## 9. ★ Lightweight transactions (LWT) and BATCH

```cql
INSERT INTO customers_by_email (email, customer_id, name) VALUES ('a@example.com', 1, '王小明') IF NOT EXISTS;
UPDATE products SET price = 27999 WHERE product_id = 540 IF price = 30000;   -- compare-and-set
UPDATE products SET stock = 9 WHERE product_id = 540 IF EXISTS;

BEGIN BATCH
  INSERT INTO customers (customer_id, name, email) VALUES (99999, '測試', 't@example.com');
  INSERT INTO customers_by_email (email, customer_id, name) VALUES ('t@example.com', 99999, '測試');
APPLY BATCH;
```

- **LWT**: uses Paxos for "check, then write", returning `[applied]` (with the current values on failure). Single partition only; latency is several times a normal write, and heavy contention means repeated retries
- Read data written by LWT with `SERIAL` / `LOCAL_SERIAL`
- ★ Don't mix LWT and normal writes on the same data (normal writes bypass Paxos and break LWT's guarantees)
- **logged BATCH** (the default): written to the batchlog first, guaranteeing everything is "eventually applied"; it keeps denormalized tables consistent; **not a transaction**: no isolation, no ROLLBACK
- **unlogged BATCH**: only makes sense within a single partition (one write)
- ★ BATCH isn't for speed: a batch across many partitions overwhelms the coordinator (`batch_size_warn_threshold` 5 KiB, `batch_size_fail_threshold` 50 KiB)
- Data needing "conditional deduction", like stock and balances, usually lives in a relational database or Redis

## 10. Indexes: SAI, secondary indexes, materialized views

```cql
CREATE INDEX orders_customer_idx ON orders (customer_id) USING 'sai';
CREATE INDEX products_tags_idx ON products (tags) USING 'sai';     -- collections: CONTAINS
SELECT * FROM products WHERE tags CONTAINS '熱銷' AND price < 1000;  -- several SAI conditions are intersected (price needs an index too)
DROP INDEX orders_customer_idx;
```

- **SAI** (Storage-Attached Index, 5.0): built alongside each SSTable; supports equality, ranges, collections and vector search (ANN), and several conditions can be combined
- ★ Indexes are **local to each node**: without the partition key, every node must be asked (scatter-gather), which gets more expensive with more nodes. Good for infrequent queries, use with the partition key, or small tables
- The older secondary indexes (2i) and SASI have many limitations; since 5.0, SAI is recommended
- Materialized views (MV): automatically maintain a table with another primary key, but they've always been experimental and off by default; in practice people mostly write several tables with BATCH themselves

## 11. Common traps

| Trap | Explanation |
|---|---|
| INSERT never reports a duplicate primary key | It's an upsert and overwrites; use `IF NOT EXISTS` |
| UPDATE on a missing row grows a row | Also an upsert; use `IF EXISTS` |
| Can't query without the partition key | Build a dedicated table, use SAI, or accept the full scan of `ALLOW FILTERING` |
| ORDER BY any column | Only clustering keys |
| `COUNT(*)` over a whole table | Really reads every row to count, and times out; use a counter table or the estimate from `nodetool tablestats` |
| `AVG(int)` | Returns an int, with the fraction dropped |
| Writing null | Same as deleting, creating a tombstone |
| Unsynchronized clocks | Last write wins, so a later write can lose to an earlier one |
| Using Cassandra as a queue | Tombstones pile up, reads get slower and slower, and eventually fail |
| Unbounded partitions | Bucket time series |
| `IN` on the partition key with thousands of values | Heavy load on the coordinator; send many single-partition queries in parallel instead |
| Counter retries | Not idempotent, may add twice |

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

- ★ A CqlSession is heavy (connection pools, metadata), so share one across the whole application; always use **prepared statements** (parsed once, and since they know the partition key they can go straight to the responsible node: token-aware)
- Driver 4's Maven coordinates have changed to `org.apache.cassandra:java-driver-core`
- Large results are paged automatically (5,000 rows per page by default); for API pagination pass `getPagingState()` to the next request instead of OFFSET (Cassandra has none)
- Spring Data's derived queries can only use primary key columns; other columns need `@AllowFiltering` or hand-written CQL
- Be careful with write retries: plain INSERT / UPDATE are idempotent and safe to retry; counters, `list` appends and LWT aren't

## 13. ★ Quick interview answers

| Question | Key points |
|---|---|
| Why are Cassandra writes fast? | No read before write; sequential commitlog + memtable; SSTables are immutable and merging is left to background compaction (an LSM tree) |
| How do partition keys and clustering keys differ? | The partition key decides which node holds the data; the clustering key decides the order within the partition and supports range queries |
| How do you design the data model? | Query-first: list every query → one table per query; denormalize; bound partition sizes (bucketing); avoid hot spots |
| How do you choose a consistency level? | Usually LOCAL_QUORUM for reads and writes; R + W > RF means strong consistency; ONE is fastest but may read stale data |
| What happens when a node goes down? | With RF = 3 and QUORUM it keeps working; hinted handoff records writes and replays them when it's back; then repair |
| What is a tombstone? Why gc_grace_seconds? | A delete writes a marker; it can only be purged after every replica has synced the deletion, or the data comes back to life |
| What is LWT? Its cost? | Paxos compare-and-set; single partition only, high latency; for infrequent operations like uniqueness checks |
| Can BATCH be used as a transaction? | No. A logged batch only guarantees everything is eventually applied, with no isolation and no rollback |
| Why be careful with secondary indexes? | They're local indexes, so without the partition key every node must be asked; frequent queries deserve a dedicated table |
| Cassandra vs MongoDB? | Cassandra: masterless, huge write volumes, multi-datacenter, fixed query patterns; MongoDB: document model, flexible queries, transactions, primary–secondary replication |
| When shouldn't you use Cassandra? | When you need JOINs, ad hoc queries, transactions or strongly consistent counts, or the data is small (one PostgreSQL is enough) |

## 14. Common cqlsh and nodetool commands

```text
DESCRIBE KEYSPACES;              -- list keyspaces (runs server-side since 4.0)
DESCRIBE TABLE shop.orders;      -- show the CREATE TABLE statement
CONSISTENCY LOCAL_QUORUM;        -- later requests use this consistency level
TRACING ON;                      -- attach a query trace to later queries
EXPAND ON;                       -- one line per column (readable with many columns)
COPY shop.products TO 'p.csv' WITH HEADER = true;   -- export / import CSV

nodetool status                  -- node status (UN = Up / Normal), load, token count
nodetool tablestats shop.orders  -- estimated row count, partition sizes, SSTable count
nodetool flush / compact / repair
nodetool getendpoints shop orders_by_customer 4242   -- which nodes hold this partition
```

# Neo4j

> The examples all come from the practice environment (the `neo4j-lab` container, Bolt port 7688, Neo4j Browser at http://localhost:7475) and can be pasted straight into the showcase's "Cypher console" (always rolled back, so feel free to try writes). The data is converted from PostgreSQL; the follow relationships (FOLLOWS) are simulated.

## 1. ★ Basics, compared with SQL

| Relational | Neo4j (property graph) |
|---|---|
| table | label; a node can have several labels: `(:Customer:Vip)` |
| row | node |
| column | property; each node can differ |
| Foreign key / junction table | relationship: always has a **type** and a **direction**, and can have properties |
| JOIN | Following relationships (traversal) |
| SQL | Cypher: "draw" the pattern you're looking for in ASCII art |

```text
(:Customer)-[:PLACED]->(:Order)-[:CONTAINS {qty, unitPrice}]->(:Product)-[:IN_CATEGORY]->(:Category)
(:Customer)-[:FOLLOWS]->(:Customer)      (:Customer)-[:LIVES_IN]->(:City)
```

- ★ **index-free adjacency**: each node directly records its own relationships, so the cost of one step doesn't depend on the total database size; a relational database does an index lookup for every JOIN
- Good for: social relationships, recommendations, fraud detection (circular transfers), permission inheritance, knowledge graphs, supply chains, network topology — problems where "the relationships themselves are the point" and you walk several levels deep
- Not good for: whole-table aggregate reports, filtering on many columns, plain CRUD
- Indexes are only used to find "starting points"; after that you follow relationships

## 2. Pattern syntax

```cypher
(c)                          // any node, variable c
(c:Customer)                 // a label
(c:Customer {id: 4242})      // property conditions
(a)-[:FOLLOWS]->(b)          // a follows b (directed)
(a)<-[:FOLLOWS]-(b)          // b follows a
(a)-[:FOLLOWS]-(b)           // either direction (matches both)
(a)-[r:FOLLOWS]->(b)         // relationships can have variables too; r.since reads a property
(a)-[:FOLLOWS|LIKES]->(b)    // several types
(a)-[:FOLLOWS*1..3]->(b)     // variable length: 1 to 3 steps (★ always give an upper bound)
p = (a)-[:FOLLOWS*..5]->(b)  // store the whole path in variable p
```

## 3. Queries: MATCH, WHERE, RETURN, WITH

```cypher
MATCH (c:Customer {id: 4242})-[:PLACED]->(o:Order)
WHERE o.total >= 1000 AND o.orderDate >= datetime('2025-01-01T00:00:00+08:00')
RETURN o.id, o.total
ORDER BY o.orderDate DESC
SKIP 0 LIMIT 10;

MATCH (c:Customer) WHERE NOT (c)-[:PLACED]->() RETURN count(c);   // never ordered (a pattern as a condition)
MATCH (c:Customer) WHERE EXISTS { (c)-[:PLACED]->(:Order {status: 'returned'}) } RETURN count(c);

MATCH (c:Customer) WHERE c.id IN [1, 2, 4242]
OPTIONAL MATCH (c)-[:PLACED]->(o:Order) WHERE o.total >= 50000    // like LEFT JOIN … ON
RETURN c.id, count(o);

MATCH (:Customer {id: 4242})-[:FOLLOWS]->(f)
WITH f ORDER BY f.id                       // WITH = a RETURN in the middle: sort, filter, aggregate, then pass on
RETURN collect(f.name);

UNWIND [4242, 1, 2] AS id                  // expand a list into rows
MATCH (c:Customer {id: id}) RETURN c.name;
```

| Clause / function | Use |
|---|---|
| `WITH` | Pass results to the next part (works with WHERE, ORDER BY, LIMIT and aggregation) |
| `OPTIONAL MATCH` | Variables are null when nothing matches, and the row is kept (LEFT JOIN) |
| `UNWIND` | Expand a list into rows (common for batch writes) |
| `collect()` | Collect rows into a list |
| `[x IN list WHERE condition \| expression]` | List comprehension |
| `EXISTS { pattern }`, `COUNT { pattern }` | Subquery conditions |
| `CASE WHEN … THEN … END` | Same as SQL |
| `coalesce()`, `toInteger()`, `toFloat()`, `size()`, `keys()`, `labels()`, `type()` | Common functions |

★ Use parameters with `$name`: `MATCH (c:Customer {id: $id})`. Don't concatenate values into the string (Cypher injection, and the plan can't be reused).

## 4. Aggregation

```cypher
MATCH (c:Customer)<-[:FOLLOWS]-(fan)
RETURN c.id, c.name, count(fan) AS followers      // no GROUP BY: non-aggregated columns are the grouping keys
ORDER BY followers DESC LIMIT 5;
```

- Aggregate functions: `count()`, `count(DISTINCT x)`, `sum()`, `avg()`, `min()`, `max()`, `collect()`, `percentileCont()`
- ★ `count(*)` counts rows; `count(x)` skips null
- ★ Integer division stays an integer: `7 / 2 = 3`; `avg()` gives a float

## 5. ★ Paths and traversal

```cypher
// Friends of friends (people you may know)
MATCH (me:Customer {id: 224})-[:FOLLOWS]->()-[:FOLLOWS]->(fof)
WHERE fof <> me AND NOT (me)-[:FOLLOWS]->(fof)
RETURN count(DISTINCT fof);

// Shortest path (bidirectional breadth-first, stops when found)
MATCH p = shortestPath((a:Customer {id: 4242})-[:FOLLOWS*..10]->(b:Customer {id: 19999}))
RETURN length(p), [n IN nodes(p) | n.name];

MATCH p = allShortestPaths((a)-[:FOLLOWS*..10]->(b)) RETURN p;   // every equally short path

// Trees: every subcategory under a category (SQL needs WITH RECURSIVE)
MATCH (c:Category)-[:SUBCATEGORY_OF*1..]->(:Category {name: '3C電子'}) RETURN c.name;
```

- `nodes(p)`, `relationships(p)`, `length(p)` (number of relationships)
- ★ **Relationship uniqueness**: within one MATCH pattern the same relationship is never traversed twice (avoiding infinite loops), but the same node can appear several times. So "friends of friends" can include yourself (with mutual follows)
- ★ In one long pattern `(p)<-[:CONTAINS]-(:Order)<-[:PLACED]-(c)-[:PLACED]->(:Order)-[:CONTAINS]->(x)` the two PLACED must be different relationships → "the same order" cases are missed; split into two MATCHes if you need them
- ★ MATCH returns "every way of matching": a person reached by two paths appears twice, so counting people needs DISTINCT
- Advanced algorithms (PageRank, community detection, similarity) use the GDS (Graph Data Science) library

## 6. Writes: CREATE, MERGE, SET, DELETE

```cypher
MATCH (city:City {name: '台北市'})
CREATE (c:Customer {id: 99999, name: '測試'})-[:LIVES_IN]->(city);   // connect to an "existing" node

MATCH (a:Customer {id: 4242}), (b:Customer {id: 1})
MERGE (a)-[:FOLLOWS]->(b);                                       // use it if present, create it if not

MERGE (c:Customer {id: 4242})
ON CREATE SET c.name = '新會員', c.createdAt = datetime()
ON MATCH SET c.lastLogin = datetime();

MATCH (p:Product {id: 540})
SET p.stock = 0, p.tags = p.tags + '缺貨', p += {isActive: false}
REMOVE p.discount;                                               // delete a property (same as SET p.discount = null)

MATCH (:Customer {id: 4242})-[r:FOLLOWS]->(:Customer {id: 15084}) DELETE r;   // delete a relationship
MATCH (c:Customer {id: 99999}) DETACH DELETE c;                  // delete a node and all its relationships

UNWIND $rows AS row                                              // ★ batch writes: send one batch at a time
MERGE (c:Customer {id: row.id}) SET c.name = row.name;
```

- ★ **MERGE matches the whole pattern together**: when `MERGE (a:Person {name:'A'})-[:KNOWS]->(b:Person {name:'B'})` can't find the complete pattern, it recreates both nodes too (creating duplicates). Correct: MATCH / MERGE both ends first, then MERGE the relationship
- MERGE needs a uniqueness constraint: without one, two transactions MERGEing at once may create two nodes
- `SET p = {…}` replaces every property; `SET p += {…}` updates only the given ones
- Setting a property to null deletes it
- A node with relationships can't be DELETEd directly: it fails at transaction commit (it looks fine inside the transaction); use DETACH DELETE
- ★ When MATCH finds nothing, the CREATE / SET after it runs 0 times, **with no error**; check the returned rows or write statistics to confirm writes
- Bulk deletes / updates must be batched: `CALL { … } IN TRANSACTIONS OF 10000 ROWS`

## 7. ★ Data modeling

| Question | Advice |
|---|---|
| Property or node? | Things you "connect to" or "traverse through" become nodes (cities, brands, tags); things that only describe become properties |
| Relationship properties | Information about "the link between the two" goes on the relationship (quantity, time, weight) |
| Many-to-many with lots of information | An intermediate node (e.g. Order connecting Customer and Product) is more flexible than stuffing everything into a relationship |
| Labels | For classification and faster filtering (`:Customer:Vip`); don't turn changing states into lots of labels |
| Specific relationship types | `:PLACED` and `:FOLLOWS` beat a generic `:RELATED_TO`: queries can walk only the types they need |
| ★ Supernodes | Nodes with hundreds of thousands of relationships (celebrities, popular tags) slow down queries passing through them: restrict direction and type, split relationship types by time, precompute statistics |
| Direction | Store one direction based on meaning (no need to store both); queries can ignore direction |

## 8. Indexes, constraints and PROFILE

```cypher
CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (c:Customer) REQUIRE c.id IS UNIQUE;  // uniqueness constraint (with an index)
CREATE INDEX customer_email IF NOT EXISTS FOR (c:Customer) ON (c.email);              // RANGE index
CREATE INDEX order_comp FOR (o:Order) ON (o.status, o.orderDate);                     // composite index
CREATE TEXT INDEX product_name FOR (p:Product) ON (p.name);                           // CONTAINS / ENDS WITH
CREATE FULLTEXT INDEX product_ft FOR (p:Product) ON EACH [p.name];                    // full-text search
CREATE INDEX follows_since FOR ()-[r:FOLLOWS]-() ON (r.since);                        // relationship properties can be indexed too
SHOW INDEXES;  SHOW CONSTRAINTS;  DROP INDEX customer_email;

PROFILE MATCH (c:Customer) WHERE c.email = 'user04242@example.com' RETURN c;          // run it and show db hits
EXPLAIN MATCH …;                                                                      // show the plan only, without running
```

| Operator | Meaning |
|---|---|
| `AllNodesScan` | Scans every node (no label given) — the worst |
| `NodeByLabelScan` | Scans every node with a label (no usable index) |
| `NodeIndexSeek` / `NodeUniqueIndexSeek` | Finds the starting point via an index ✓ |
| `NodeIndexSeekByRange` / `NodeIndexContainsScan` | Range / TEXT index |
| `Expand(All)` / `Expand(Into)` | Follows relationships |
| `Filter` | Filters row by row |
| `CartesianProduct` | Two unconnected patterns ⚠ |
| `Eager` | Reads everything before writing (to avoid read/write conflicts); memory-hungry with large data |

- ★ **db hits** = accesses to the storage layer, a more stable cost measure than milliseconds (lab: 40,003 without an index → 4 with one)
- Computing on a property (`c.id + 0 = 4242`, `toString(c.id) = '4242'`) prevents index use
- RANGE indexes: =, ranges, STARTS WITH, IS NOT NULL; TEXT indexes: CONTAINS, ENDS WITH
- The community edition has uniqueness constraints; property existence constraints, Node Keys and property type constraints are enterprise features

## 9. Transactions and clustering

- ACID transactions, with read committed isolation by default; writes lock nodes / relationships, and deadlocks can happen (TransientException; the driver's managed transactions retry automatically)
- ★ All statements in a transaction either all commit or all roll back; some checks (e.g. deleting a node with relationships) only happen at commit
- Clustering (enterprise): primary servers use Raft for majority-agreed writes, and secondary servers scale reads
- **bookmarks**: a write returns a bookmark, and passing it to the next read guarantees you see your own write (causal consistency); the driver's session handles it automatically
- Community edition: single server, only one user database (neo4j), no role-based permissions

## 10. Common traps

| Trap | Explanation |
|---|---|
| MERGE on a whole pattern | If the complete pattern isn't found, everything is recreated, giving duplicate nodes |
| No direction | Both directions match (6 followed + 4 followers = 10) |
| `= null` | Never finds anything; use `IS NULL` |
| WHERE after OPTIONAL MATCH | Inside OPTIONAL MATCH it's a matching condition (rows are kept); in a later WITH … WHERE it filters out the nulls |
| Commas between unrelated patterns | A cartesian product (891 × 83 = 73,953 rows), with a server warning |
| Forgetting DISTINCT | Every path counts as a row |
| Friends of friends include yourself | Relationships are unique but nodes can repeat; remember `fof <> me` |
| A long pattern misses data | The same relationship is never used twice → split into two MATCHes |
| Mismatched types | `{id: '4242'}` (a string) won't find `id: 4242`, with no error |
| Integer division | `sum(x) / count(x)` drops the fraction |
| When MATCH finds nothing, nothing happens | The CREATE after it runs 0 times, with no error |
| Unbounded `*` | Can produce millions of paths on a large graph |

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
    @Id private Long id;                       // business id; or @Id @GeneratedValue for the internal id
    private String name;
    @Relationship(type = "FOLLOWS", direction = Relationship.Direction.OUTGOING)
    private Set<Customer> follows;
}

public interface CustomerRepository extends Neo4jRepository<Customer, Long> {
    @Query("MATCH (:Customer {id: $id})-[:FOLLOWS]->(f) RETURN f")
    List<Customer> following(Long id);
}
```

- ★ The Driver is heavy, so share one across the whole application; Sessions are light, so close them when done
- `executeRead` / `executeWrite` (managed transactions) retry automatically on transient errors, so transaction functions must be safe to repeat (don't send emails inside them)
- Always use parameters like `$id`, never string concatenation
- For bulk writes send batches with `UNWIND $rows` (this project loads data 5,000 rows per batch)
- Spring Data Neo4j may pull a huge chunk of the graph back when loading entities with relationships; use custom Cypher or projections for large queries
- Don't use `elementId()` / internal ids as business ids: they may be reused after deletion

## 12. ★ Quick interview answers

| Question | Key points |
|---|---|
| How do graph databases differ from relational ones? | Relationships are stored (index-free adjacency), so one step costs the same regardless of total data size; a JOIN matches through indexes at query time |
| When to use a graph database? | When relationships are the point, you walk several levels, and the depth varies: social, recommendations, fraud detection, permissions, knowledge graphs |
| Are graph databases always faster? | Not necessarily. Lab: with 80,000 follow relationships, "how many people within N steps" is just as fast in PostgreSQL; but for shortest paths (8 steps) SQL is dozens of times slower |
| What to watch for with Cypher's MERGE? | It matches the whole pattern together; MATCH both ends, then MERGE the relationship; use uniqueness constraints |
| What is a supernode, and how to handle it? | A node with very many relationships; restrict direction and type, split relationship types, precompute |
| How to check query performance? | PROFILE shows operators and db hits; make sure the starting point is an IndexSeek, not a LabelScan |
| Property or node? | Things you connect or traverse through become nodes; things that only describe become properties |
| How to build recommendations? | Collaborative filtering: product ← people who bought it → other products they bought, ranked by number of shared buyers |
| How does Neo4j scale? | Enterprise clusters: primaries (Raft writes) + secondaries (read scaling); for huge data, shard with Fabric / composite databases |

## 13. Tools and commands

```text
cypher-shell -a bolt://localhost:7688 -u neo4j -p neo4j-lab     # command line
docker exec -it neo4j-lab cypher-shell -u neo4j -p neo4j-lab
http://localhost:7475                                            # Neo4j Browser (draws results as graphs)

SHOW INDEXES;  SHOW CONSTRAINTS;  SHOW TRANSACTIONS;
CALL db.schema.visualization();                                  # see the structure of labels and relationship types
CALL db.labels();  CALL db.relationshipTypes();  CALL db.propertyKeys();
neo4j-admin database import full …                               # offline bulk CSV import (the fastest)
```

- APOC: common utility procedures (import/export, batching, date handling); GDS: graph algorithms (PageRank, shortest paths, community detection)

# TimescaleDB

> The examples all come from the practice environment (the `timescale-lab` container, port 5435, database `metrics`) and can be pasted straight into the showcase's "SQL console". page_views holds product views from 2026-04 to 09 (1.91 million rows), and sensor_readings holds warehouse sensor readings for 2026-09 (one per minute).

## 1. ★ Basics

- **TimescaleDB = a PostgreSQL extension**: you still write SQL, JOINs, transactions and indexes, and PostgreSQL tools (psql, JDBC, Spring Data JPA) all work directly
- **hypertable**: looks like one table, but underneath it's automatically split by time into many **chunks** (ordinary PostgreSQL tables), with new chunks created automatically on write
- Characteristics of time series data: heavy writes, almost always "appended at the end", old data rarely modified, queries mostly "a time range" and "aggregated by time", old data downsampled or deleted
- TimescaleDB adds for these: **chunk exclusion**, time functions like **time_bucket**, **continuous aggregates**, **compression (columnstore)** and **retention policies**

| | PostgreSQL native partitioning | TimescaleDB hypertable |
|---|---|---|
| Creating partitions | Create every partition yourself in advance (or with pg_partman) | Chunks are created automatically on write |
| Partition size | Your own design | `chunk_time_interval` (7 days by default) |
| Time functions | `date_trunc` | `time_bucket` (any length), gapfill, first / last |
| Pre-aggregation | MATERIALIZED VIEW (fully recomputed every time) | Continuous aggregates (incremental, can merge the latest data in real time) |
| Compression | None (only TOAST) | Columnar compression, commonly above 90% |
| Deleting old data | DROP a partition | `drop_chunks`, run automatically by retention policies |

## 2. Hypertables and chunks

```sql
CREATE TABLE page_views (
  view_time   timestamptz NOT NULL,
  product_id  int         NOT NULL,
  customer_id int,
  device      text        NOT NULL
);
SELECT create_hypertable('page_views', by_range('view_time', INTERVAL '7 days'));   -- syntax since 2.13
-- Old syntax: SELECT create_hypertable('page_views', 'view_time', chunk_time_interval => INTERVAL '7 days');
SELECT set_chunk_time_interval('page_views', INTERVAL '1 day');                    -- applies to chunks created later

SELECT show_chunks('page_views');
SELECT * FROM timescaledb_information.chunks WHERE hypertable_name = 'page_views';
SELECT hypertable_size('page_views'), approximate_row_count('page_views');
```

- ★ **chunk exclusion**: a query with a time range reads only the relevant chunks (lab: one day, 9/1, reads only 1 of 27 chunks in 0.6 ms; everything takes 30 ms)
- ★ Computing on the time column (`view_time::date = …`, `date_trunc('day', view_time) = …`) prevents chunk exclusion, so all 27 are read (100 times slower); always write `time >= start AND time < end`
- `now() - interval '7 days'` still excludes chunks: TimescaleDB evaluates it to a constant at planning time (the plan's Index Cond shows an extra computed time)
- Indexes created on a hypertable are created on every chunk automatically; `create_hypertable` creates a `(time DESC)` index by default
- ★ **Unique indexes / primary keys must include the time column** (each chunk checks uniqueness on its own)
- Recommended chunk size: the latest chunk (and its indexes) fits in about 25% of memory; too small → too many chunks, and queries without a time condition do many index lookups; too large → exclusion works poorly
- Chunk boundaries align to UTC (7-day chunks start on Thursdays at 00:00 UTC)
- A second dimension (space partitioning) can be added: `add_dimension('t', by_hash('device_id', 4))`, unnecessary in most cases

## 3. ★ time_bucket and time series functions

```sql
SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day, count(*)       -- ★ a day or longer needs a time zone
FROM page_views
WHERE view_time >= '2026-09-01 00:00+08' AND view_time < '2026-10-01 00:00+08'
GROUP BY day ORDER BY day;

SELECT time_bucket('15 minutes', time) AS t, avg(temperature) FROM sensor_readings … GROUP BY t;
SELECT time_bucket('1 month', view_time, 'Asia/Taipei') AS month, count(*) …;   -- by calendar month
SELECT time_bucket('1 week', time, 'Asia/Taipei', origin => '2000-01-02') …;     -- weeks starting on Sunday

SELECT sensor_id, first(temperature, time), last(temperature, time)            -- the first / last value by time
FROM sensor_readings WHERE time >= … GROUP BY sensor_id;

SELECT time_bucket_gapfill('1 hour', time) AS hour,                            -- intervals without data are listed too
       avg(temperature),
       locf(avg(temperature)),                                                  -- fill with the previous value
       interpolate(avg(temperature))                                            -- linear interpolation
FROM sensor_readings
WHERE sensor_id = 7 AND time >= '2026-09-10 08:00+08' AND time < '2026-09-10 16:00+08'
GROUP BY hour ORDER BY hour;
```

- ★ time_bucket on timestamptz **cuts in UTC by default**: a Taiwan day would start at 8 a.m.; `'1 day'`, `'1 week'` and `'1 month'` all need the time zone argument (the database's timezone setting doesn't affect time_bucket)
- `'30 days'` ≠ a month: fixed-length buckets are cut from the origin (2000-01-03) and don't line up with months
- `'1 week'` starts on Monday by default
- ★ `time_bucket_gapfill` must be able to infer the start and end from WHERE, or it errors
- A plain time_bucket returns only intervals "that have data", so gaps disappear in charts; monitoring data commonly uses gapfill
- Moving averages and period-over-period comparisons: time_bucket plus window functions (`avg() OVER (ORDER BY day ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)`, `lag()`)
- Advanced functions (approx_percentile, time_weight, counter_agg, HyperLogLog) live in the timescaledb-toolkit extension

## 4. ★ Continuous aggregates

```sql
CREATE MATERIALIZED VIEW sensor_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', time) AS hour, sensor_id,
       avg(temperature) AS avg_temp, max(temperature) AS max_temp, count(*) AS readings
FROM sensor_readings
GROUP BY hour, sensor_id
WITH NO DATA;

CALL refresh_continuous_aggregate('sensor_hourly', '2026-09-01', '2026-10-01');   -- manually refresh a time range
SELECT add_continuous_aggregate_policy('sensor_hourly',
  start_offset => INTERVAL '3 days', end_offset => INTERVAL '1 hour', schedule_interval => INTERVAL '30 minutes');
ALTER MATERIALIZED VIEW sensor_hourly SET (timescaledb.materialized_only = false);  -- real-time aggregation
```

- Incremental refresh: only "time ranges where data changed" are recomputed (PostgreSQL's MATERIALIZED VIEW recomputes everything each time)
- ★ **Since 2.13 the default is `materialized_only = true`**: the latest data not yet refreshed can't be seen (the trap: 0 after 9/24)
- Real-time aggregation (`materialized_only = false`): the materialized part + raw data after the watermark computed on the fly; the data is current, but queries are slower
- Lab: the same report takes 40 ms on raw data and 7 ms from the continuous aggregate
- ★ Rolling up aggregated numbers: counts and sums can be summed again; **averages can't be averaged again** (store sum and count and divide at the end); `count(*)` on an aggregate counts groups, not raw rows
- Limits: aggregates must be computable in pieces and merged, so `count(DISTINCT …)` isn't allowed; continuous aggregates can be built on continuous aggregates (tiers: minute → hour → day)
- Deleting raw data (retention policies) doesn't delete already-materialized aggregates: a common setup is "keep raw data 30 days, hourly aggregates 2 years"

## 5. ★ Compression (columnstore)

```sql
ALTER TABLE page_views SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'device',
  timescaledb.compress_orderby   = 'view_time DESC'
);
SELECT compress_chunk(c) FROM show_chunks('page_views', older_than => INTERVAL '7 days') c;
SELECT add_compression_policy('page_views', INTERVAL '7 days');   -- automatically compress chunks older than 7 days
SELECT * FROM chunk_compression_stats('page_views');              -- size before and after compression
-- Since 2.18 it's also called columnstore: ALTER TABLE … SET (timescaledb.enable_columnstore, timescaledb.segmentby = …), add_columnstore_policy
```

| segmentby (measured in the lab, 330,000 rows from September) | Compression ratio | Notes |
|---|---:|---|
| None | 7.6× | |
| `device` (3 values) | 9.6× | ★ queries by device go from 45 ms → 4 ms |
| `product_id` (1,500 values) | 1.9× | Each group has only a few rows, never filling a batch, so compression is poor |

- Compressed chunks are stored by column: every 1,000 rows are packed into one row, and each column uses a suitable algorithm (delta-of-delta, dictionary, Gorilla…)
- ★ For **segmentby**, choose columns "often used to filter, with few distinct values" (device, sensor id, region); **orderby** is usually the time
- Best for compressing "old data that rarely changes"; compressed chunks still accept INSERT / UPDATE / DELETE (since 2.11), but at a higher cost
- Analytical queries reading few columns are usually faster after compression (less data read); point queries for a row or two may get a bit slower

## 6. Data retention

```sql
SELECT drop_chunks('page_views', older_than => '2026-05-01'::timestamptz);     -- drop whole chunks
SELECT add_retention_policy('page_views', INTERVAL '6 months');                -- run automatically
SELECT * FROM timescaledb_information.jobs;                                    -- every background job (compression, refresh, retention)
```

- ★ DELETE removes rows one by one, writes WAL and needs VACUUM: deleting 70,000 rows takes 5 seconds in testing; `drop_chunks` drops 5 chunks in a few milliseconds
- It only drops chunks "entirely older than the condition"; the boundary chunk stays
- A common tiering: raw data for 30 days → compressed → deleted by the retention policy; continuous aggregates kept longer

## 7. Writes

- Writes work like an ordinary table (INSERT, COPY, batches); time series are mostly appended at the end, and the chunk being written is small with its indexes in memory, so writes are fast
- Batched writes: multi-row VALUES, `COPY`, JDBC batches (`reWriteBatchedInserts=true`)
- Late data is written into old chunks; already-materialized continuous aggregates update only at the next refresh; writing into compressed chunks costs more
- UPSERT: `INSERT … ON CONFLICT (sensor_id, time) DO UPDATE` (the unique index must include the time column)

## 8. Common traps

| Trap | Explanation |
|---|---|
| time_bucket without a time zone | Cut in UTC, a Taiwan day starts at 8 a.m., and every day's number is wrong |
| Treating `'30 days'` as a month | Doesn't line up with months; use `'1 month'` |
| Casting the time column | `time::date = …` defeats chunk exclusion |
| gapfill without a range | Error: could not infer start from WHERE clause |
| A continuous aggregate missing the latest data | materialized_only defaults to true; add a refresh policy or turn on real-time aggregation |
| count(*) on an aggregate | Counts groups; use sum(views) |
| Averaging averages | Wrong when groups have different counts |
| A unique index without the time column | Can't be created |
| segmentby on a column with many values | Poor compression |
| Deleting old data with DELETE | Slow, leaves dead rows; use drop_chunks / retention policies |
| Chunks that are too small | Too many chunks, so planning and queries without a time condition get slower |

## 9. ★ Quick interview answers

| Question | Key points |
|---|---|
| What is TimescaleDB? | A time series extension for PostgreSQL: hypertables split into chunks by time automatically, plus time functions, continuous aggregates, compression and retention policies |
| Why do queries on recent data stay fast as data grows? | Chunk exclusion: only chunks in the relevant time range are read; the latest chunk and its indexes are in memory |
| How is chunk size decided? | The latest chunk should fit in 25% of memory; tune chunk_time_interval to the write volume |
| How do continuous aggregates differ from materialized views? | Incremental refresh (only changed parts are recomputed), scheduling, and real-time merging of the latest data |
| How do you configure compression? | segmentby on frequently filtered columns with few values, orderby on time; compress only old chunks |
| What happens to old data? | Compression → downsampling (continuous aggregates) → drop_chunks / retention policies |
| TimescaleDB vs InfluxDB? | TimescaleDB is SQL, can JOIN relational data, and has the PostgreSQL ecosystem; InfluxDB is built specifically for metrics, with simpler writes and a tag model |
| When don't you need TimescaleDB? | Small data (a few million rows or less) without heavy time-based aggregation: PostgreSQL with BRIN or native partitioning is enough |

## 10. Java / Spring

- It's just PostgreSQL: the JDBC URL, JPA, JdbcTemplate and Flyway all work as usual; create hypertables in a Flyway migration with `SELECT create_hypertable(…)`
- A JPA entity's primary key must include the time column (a composite key with `@IdClass` / `@EmbeddedId`), or skip JPA on hypertables and use JdbcTemplate
- Bulk writes: `reWriteBatchedInserts=true` + JDBC batches, or PostgreSQL's `CopyManager` (COPY)
- Functions like time_bucket can't be used directly in JPQL: use native SQL (`@Query(nativeQuery = true)`) or JdbcTemplate

# pgvector

> The examples all come from the practice environment (the `vectors` database in the `pg-lab` container, port 5434) and can be pasted straight into the showcase's "SQL console". products has 1,500 products (64 dimensions), and `embed(text)` is a mini embedding model built from a vocabulary; passages has 100,000 simulated 128-dimension document chunks (for the index lab).

## 1. ★ Basics

- **Embedding**: a model turns text or images into a list of numbers (a vector), and things with similar meaning get similar vectors; 384–3072 dimensions are common
- **Vector search**: take a query vector and find "the k nearest" (k-nearest neighbors), for semantic search, recommendations, de-duplication, classification and RAG
- **pgvector = a PostgreSQL extension**: adds the `vector` type, distance operators and vector indexes (HNSW, IVFFlat); vectors sit in the same table as regular columns, so you can WHERE, JOIN and use transactions
- **RAG (Retrieval-Augmented Generation)**: split documents into chunks → embed each chunk into the database → embed the question too → find the nearest chunks → give them to the LLM with the question

| | pgvector | Dedicated vector databases (Pinecone, Milvus, Qdrant, Weaviate) |
|---|---|---|
| Filtering, JOINs | Plain SQL, together with relational data | Only via separately stored metadata |
| Transactions, backups, permissions | Ready-made in PostgreSQL | Each has its own mechanisms |
| Scale | Tens of millions of rows on one server are fine; beyond that, partitions and read replicas | Designed for billions, distributed |
| Operations | No extra system to run | One more service (or a paid SaaS) |

★ Interview conclusion: if you already use PostgreSQL and the data is within tens of millions of rows, start with pgvector; consider a dedicated database when you need huge scale, very low latency or advanced multimodal features.

## 2. Types, operators, functions

```sql
CREATE EXTENSION vector;
CREATE TABLE products (id int PRIMARY KEY, name text, embedding vector(64));   -- declare dimensions; the wrong dimension can't be written
INSERT INTO products VALUES (1, 'x', '[0.1, 0.2, …]');

SELECT id FROM products ORDER BY embedding <=> '[…]' LIMIT 5;                  -- the nearest 5
SELECT 1 - (a.embedding <=> b.embedding) AS cosine_similarity FROM …;
SELECT avg(embedding), sum(embedding) FROM products;                            -- vectors can be aggregated too
SELECT vector_dims(embedding), vector_norm(embedding), l2_normalize(embedding);
SELECT subvector(embedding, 1, 16), embedding::halfvec, binary_quantize(embedding);
```

| Operator | Distance | Index operator class | Notes |
|---|---|---|---|
| `<->` | L2 (Euclidean) | `vector_l2_ops` | Considers length and direction |
| `<=>` | Cosine distance = 1 - cosine similarity | `vector_cosine_ops` | Direction only, range 0–2 |
| `<#>` | ★ **Negative** inner product | `vector_ip_ops` | Smaller is more similar; fastest when every vector has length 1 |
| `<+>` | L1 (Manhattan) | `vector_l1_ops` | Since 0.7 |
| `<~>`, `<%>` | Hamming, Jaccard | `bit_hamming_ops`, `bit_jaccard_ops` | The bit type |

| Type | Per dimension | Index limit | Use |
|---|---|---|---|
| `vector` | 4 bytes (float4) | 2,000 dimensions | The default |
| `halfvec` | 2 bytes (float2) | 4,000 dimensions | Half the space with almost no recall loss |
| `bit` | 1 bit | 64,000 dimensions | Binary quantization |
| `sparsevec` | Only non-zero values stored | 1,000 non-zero values | Sparse vectors (SPLADE, BM25-style) |

- ★ When vectors are normalized to length 1, cosine, L2 and inner product give the same ranking (models like OpenAI's already output length 1), so you can choose the inner product (the fastest)
- ★ Every operator is "smaller is closer": indexes only support ascending order, so the inner product is negated
- One column should only hold vectors from one model; vectors from different models can't be compared

## 3. ★ Semantic search

```sql
-- Text → vector: real systems have the application call an embedding model's API; here we use embed()
SELECT id, name FROM products ORDER BY embedding <=> embed('通勤 安靜') LIMIT 5;   -- finds active noise-cancelling headphones

-- Together with regular conditions
SELECT id, name, price FROM products
WHERE price <= 1000 AND category IN ('男裝', '女裝')
ORDER BY embedding <=> embed('冬天 保暖') LIMIT 5;

-- Similar products (remember to exclude the product itself)
SELECT id, name FROM products WHERE id <> 806
ORDER BY embedding <=> (SELECT embedding FROM products WHERE id = 806) LIMIT 5;

-- k neighbors for each row: LATERAL
SELECT s.id, n.id FROM products s
CROSS JOIN LATERAL (SELECT p.id FROM products p WHERE p.id <> s.id ORDER BY p.embedding <=> s.embedding LIMIT 2) n;

-- Recommendations: user vector = average of purchased products
WITH taste AS (SELECT avg(p.embedding) AS v FROM purchases u JOIN products p ON p.id = u.product_id WHERE u.customer_id = 224)
SELECT p.id FROM products p, taste WHERE p.id NOT IN (…purchased…) ORDER BY p.embedding <=> taste.v LIMIT 5;

-- k-NN classification: the nearest 15 vote
SELECT category, count(*) FROM (SELECT category FROM products ORDER BY embedding <=> embed('上班 通勤') LIMIT 15) s
GROUP BY category ORDER BY count(*) DESC;
```

- Semantic search is strong at "similar meaning" (synonyms, rephrasing) and weak at proper nouns: models often don't know or confuse brands, model numbers and part numbers → use keywords or hybrid search
- Distance thresholds depend on the data: in high-dimensional space unrelated things have cosine similarity near 0, and related things don't necessarily reach 0.8
- RAG quality depends mostly on "chunking": too long mixes in unrelated content, too short loses context; 200–800 tokens with some overlap between neighboring chunks is common

## 4. ★ Vector indexes: HNSW vs IVFFlat

```sql
CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops);                          -- m = 16, ef_construction = 64
CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops) WITH (m = 32, ef_construction = 128);
SET hnsw.ef_search = 100;                                                                    -- default 40

CREATE INDEX ON passages USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);     -- build "after" loading the data
SET ivfflat.probes = 10;                                                                     -- default 1

SET maintenance_work_mem = '2GB';                -- ★ HNSW builds fast only when the graph fits in memory
SET max_parallel_maintenance_workers = 7;        -- parallel index builds (Docker's default /dev/shm is only 64 MB; raise shm_size)
```

Measured (100,000 rows × 128 dimensions, averaged over 100 queries, recall = overlap with the exact top 10):

| Approach | Recall | Per query | Build time | Index size |
|---|---|---|---|---|
| Exact search (no index) | 100% | 19 ms | — | — |
| HNSW, ef_search = 10 | 94% | 1.2 ms | 38 s | 79 MB |
| HNSW, ef_search = 40 (default) | 99% | 1.3 ms | | |
| HNSW, ef_search = 200 | 100% | 2.9 ms | | |
| IVFFlat lists = 100, probes = 1 | 45% | 0.9 ms | 0.8 s | 53 MB |
| IVFFlat lists = 100, probes = 10 | 81% | 2.7 ms | | |
| IVFFlat lists = 100, probes = 30 | 95% | 6.6 ms | | |
| IVFFlat lists = 1000, probes = 1 | 88% | 0.9 ms | 9 s | 56 MB |

| | HNSW | IVFFlat |
|---|---|---|
| How it works | A multi-layer neighbor graph, walking toward closer neighbors from the top layer down | k-means splits vectors into lists clusters; only the nearest probes clusters are examined |
| Query parameter | `hnsw.ef_search` (also the maximum rows returned) | `ivfflat.probes` |
| Build parameters | `m`, `ef_construction` | `lists` (recommended: rows / 1000, or √rows above a million) |
| Recall / speed | Better | Slower at the same recall |
| Building | Slow, memory-hungry | Fast |
| Building on an empty table | Possible (the graph is built as data is written) | No: clusters come from the data at build time, so rebuild as data grows |

- ★ Both are **approximate** nearest neighbor (ANN); before going live, measure recall on your own data, then tune ef_search / probes
- ★ Three conditions to use the index: `ORDER BY column operator value` (ascending) + `LIMIT` + an operator matching the operator class
- For small tables (tens of thousands of rows) exact search is fast enough and a vector index isn't necessarily needed; exact search always has 100% recall
- Set query parameters with `SET LOCAL` to affect only the current transaction, good for "this query must be extra accurate"

## 5. ★ Filtering and multi-tenancy

```sql
SELECT id FROM passages WHERE tenant_id = 7 ORDER BY embedding <=> $1 LIMIT 10;   -- ★ may return fewer than 10

SET hnsw.iterative_scan = relaxed_order;     -- since 0.8: keep searching when there aren't enough candidates (strict_order guarantees distance order)
SET hnsw.max_scan_tuples = 20000;            -- maximum rows to scan (default 20,000)
SET ivfflat.iterative_scan = relaxed_order;  -- IVFFlat has it too (ivfflat.max_probes)

CREATE INDEX ON passages USING hnsw (embedding vector_cosine_ops) WHERE tenant_id = 7;   -- a partial index for a large tenant
```

- ★ **post-filtering**: HNSW finds ef_search (40) candidates first and applies WHERE afterwards; tenant 7 is only 2%, so in testing it returns 0.7 rows on average, with no error
- Measured (tenant 7): iterative_scan = relaxed_order always fills all 10, with 77% recall in 8 ms; ef_search = 1000 gives 89% recall in 12 ms; exact search gives 100% in 10 ms
- When little data remains after filtering, exact search is both accurate and fast (`WITH t AS MATERIALIZED (SELECT … WHERE tenant_id = 7) SELECT … FROM t ORDER BY … LIMIT 10`)
- Many tenants, each large: partition by tenant_id (`PARTITION BY LIST`), each partition with its own HNSW index

## 6. Quantization and dimensionality reduction

```sql
CREATE INDEX ON passages USING hnsw ((embedding::halfvec(128)) halfvec_cosine_ops);
SELECT id FROM passages ORDER BY embedding::halfvec(128) <=> $1::halfvec(128) LIMIT 10;   -- must use the same expression

CREATE INDEX ON passages USING hnsw ((binary_quantize(embedding)::bit(128)) bit_hamming_ops);
SELECT id FROM (                                                  -- bit fetches candidates → rerank with the original vectors
  SELECT id, embedding FROM passages ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize($1) LIMIT 100
) c ORDER BY embedding <=> $1 LIMIT 10;
```

| Index (100,000 rows × 128 dimensions) | Size | Recall |
|---|---|---|
| vector | 79 MB | 99% |
| halfvec | 54 MB | 99.9% |
| bit | 30 MB | 42% |
| bit fetches 100 → rerank | 30 MB | 95% |

- 1536 dimensions × 10 million rows = 60 GB of vectors, plus another copy for the HNSW index; quantization trades precision for space
- ★ halfvec is nearly lossless; models with 3072 dimensions (above vector's 2,000-dimension index limit) must be indexed as halfvec
- Binary quantization suits models with 1,000+ dimensions and must be paired with reranking
- Dimensionality reduction: Matryoshka-style models (OpenAI text-embedding-3) can simply take the first 256 / 512 dimensions (`subvector`; remember to re-normalize)

## 7. Hybrid search

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

- Semantic search handles "similar meaning"; keyword search (full-text / BM25) handles proper nouns, model numbers and exact strings; run both and merge
- ★ **RRF (Reciprocal Rank Fusion)**: rank r in each list scores 1 / (60 + r), and the scores are summed; it uses only ranks, so the two sides' different score units don't matter
- Chinese full-text search needs word segmentation (pg_bigm, zhparser), or hand the keyword side to Elasticsearch
- Going further: send the top 50 to a reranker (cross-encoder) model to reorder

## 8. Writes and maintenance

- Vectors are derived data computed from text: ★ **when the text changes, recompute the vector**; switching models means recomputing everything, so record each vector's model and version
- Embedding is slow and costs money: batch the calls, do it asynchronously (a background job fills in vectors after the text is written), recompute only changed data
- HNSW updates on INSERT; for bulk imports "import first, then build the index" is faster; IVFFlat must be built after the data is loaded
- UPDATE / DELETE leave dead entries in the index, cleaned by VACUUM; after heavy changes run `REINDEX INDEX CONCURRENTLY`
- One 1536-dimension row is 6 KB, and anything over 2 KB is TOASTed; `SELECT *` sends the vectors back too, so select only the columns you need

## 9. Common traps

| Trap | Explanation |
|---|---|
| Sorting `<#>` descending | `<#>` is the negative inner product, so DESC finds the least similar |
| `ORDER BY 1 - distance DESC` | Same result, but the index can't be used |
| Operator doesn't match the index | A cosine index with `<->`: a full table scan |
| No LIMIT | The index can't be used |
| Only a distance threshold `WHERE dist < 0.3` | The index can't be used; ORDER BY … LIMIT, then filter |
| Very selective filters | HNSW returns fewer than k rows, with no error |
| Similar products don't exclude the product itself | First place is the product itself (distance 0) |
| Text the model doesn't know | embed() returns NULL / an inaccurate vector; results look like answers but are wrong |
| Average vectors | Their length is below 1; l2_normalize before comparing with a fixed threshold |
| IVFFlat built on an empty table | The clusters are meaningless and recall is poor; build after loading data |
| HNSW ef_search smaller than LIMIT | Returns at most ef_search rows |
| Slow HNSW builds | maintenance_work_mem is too small (the graph doesn't fit in memory) |
| Text changed but the vector wasn't recomputed | Search results don't match the content |

## 10. ★ Quick interview answers

| Question | Key points |
|---|---|
| What is vector search? | Embed data as vectors and find the k nearest; used for semantic search, recommendations, de-duplication and RAG |
| How to choose cosine, L2 or inner product? | Follow the model's recommendation; with normalized vectors all three rank the same, and the inner product is fastest |
| HNSW vs IVFFlat? | HNSW has better recall and speed and can be built as data is written, but builds slowly and uses lots of memory; IVFFlat builds fast and is smaller, but must be built after loading data |
| How do you tune recall? | HNSW: ef_search (m and ef_construction at build time); IVFFlat: probes and lists; measure on your own data |
| Fewer results after adding WHERE? | Post-filtering; iterative scan, a higher ef_search, partial indexes, partitioning, or exact search after filtering |
| Vectors take too much space? | halfvec (half, nearly lossless), binary quantization + reranking, dimensionality reduction |
| Semantic search can't find model numbers? | Hybrid search: full-text + vectors, merged with RRF |
| pgvector or a dedicated vector database? | Within tens of millions of rows and queried together with relational data → pgvector; billions, distributed → a dedicated database |
| What is the RAG flow? | Chunk → embed → store vectors → embed the question → find the nearest chunks (+ filtering, reranking) → give them to the LLM |
| What does switching embedding models involve? | Re-embed everything and rebuild indexes; never mix old and new models' vectors (write a new column first, switch over, then drop the old one) |

## 11. Java / Spring

- JDBC: the `com.pgvector:pgvector` package; after `PGvector.addVectorType(conn)`, pass `new PGvector(float[])` as a parameter; or just pass a string as `?::vector`
- Hibernate 6.4+: the `hibernate-vector` module, `@JdbcTypeCode(SqlTypes.VECTOR) @Array(length = 1536) float[] embedding`
- ★ Spring AI: `PgVectorStore` (starter: `spring-ai-starter-vector-store-pgvector`), configured under `spring.ai.vectorstore.pgvector`: `index-type=HNSW`, `distance-type=COSINE_DISTANCE`, `dimensions`
- `vectorStore.add(documents)`: embeds with the EmbeddingModel, then writes; query with `vectorStore.similaritySearch(request)`, building the request with `SearchRequest.builder()` and `query("…")`, `topK(5)`, `filterExpression("tenant == 7")`
- Embedding calls need batching, retries and rate limiting; don't SELECT vector columns in list APIs (they're big)

# Elasticsearch

> The examples all come from the practice environment (the `es-lab` container, http://localhost:9201, Elasticsearch 8.17 single node) and can be pasted straight into the showcase's "Dev Tools console" or Kibana Dev Tools. products (1,500 products), reviews (20,000-odd Chinese reviews), orders (80,000 orders, with nested items), logs (September's API access logs, 200,000 entries).

## 1. ★ Basics

- **Inverted index**: each document is split into "terms", building a lookup table of "term → which documents contain it (and where)"; queries find documents directly by term instead of scanning everything
- Each field uses a structure suited to its type: text → inverted index; keyword, numbers, dates → inverted index + **doc values** (stored per field, for sorting and aggregations); numbers and dates also get BKD trees (range queries)
- **Near real-time**: written data is searchable only after a refresh (1 second by default)
- Lucene underneath: one **shard** is one Lucene index, made of many immutable **segments**

| Relational database | Elasticsearch |
|---|---|
| Table | Index |
| Row | Document (JSON) |
| Column | Field |
| schema | mapping |
| SQL | Query DSL (JSON); there's also a SQL API (`POST /_sql`) |
| B-tree index | Inverted index, doc values, BKD tree (every field is indexed by default) |
| JOIN | Almost none: denormalization, nested, join fields (parent-child) |
| Transactions | None: only atomic single-document writes and optimistic locking |

★ Interview conclusion: Elasticsearch is a **search and analytics engine**, not a primary database. A common architecture: PostgreSQL / MySQL hold the source of truth, and a copy is synced to Elasticsearch for full-text search, filtering and aggregations (product search, site search, log analysis).

## 2. ★ Analyzers

```es
POST /_analyze
{ "analyzer": "standard", "text": "Sony 無線耳機，降噪效果很棒" }
# → sony, 無, 線, 耳, 機, 降, 噪, 效, 果, 很, 棒 (one Chinese character per term)

POST /_analyze
{ "analyzer": "cjk", "text": "Sony 無線耳機，降噪效果很棒" }
# → sony, 無線, 線耳, 耳機, 降噪, 噪效, 效果, 果很, 很棒 (two characters per term)

POST /products/_analyze
{ "field": "name", "text": "主動降噪耳機" }          # use the field's configured analyzer
```

- Analyzer = character filters (char_filter, e.g. stripping HTML) → **tokenizer** → token filters (filter, e.g. lowercasing, synonyms, stemming, stop words)
- ★ Writing and searching must use compatible analyzers for terms to match; `analyzer` (writing) and `search_analyzer` (searching) can be set separately
- Chinese: standard makes one term per character (「音質」 = 音 OR 質, noisy); **cjk** uses two-character bigrams (no plugin, high recall); **IK, smartcn, jieba** do real segmentation (plugins required)
- Measured (reviews): match 「音質」 (sound quality) finds 695 with cjk and 4,509 with standard (mixing in 「品質」 quality and 「肉質」 meat texture)
- Put synonyms in the search_analyzer (`synonym_graph`): changing the synonym list doesn't require reindexing
- Analyzers are fixed when the index is created; changing them later means a new index and a reindex

## 3. Mappings and types

```es
PUT /products
{
  "mappings": {
    "dynamic": "strict",                                       # reject undefined fields outright
    "properties": {
      "name":     { "type": "text", "analyzer": "cjk",
                    "fields": { "keyword": { "type": "keyword" } } },   # multi-field: two indexes for the same value
      "brand":    { "type": "keyword" },
      "price":    { "type": "integer" },
      "created_at": { "type": "date" },
      "items":    { "type": "nested", "properties": { … } }
    }
  }
}
GET /products/_mapping
```

| Type | Use |
|---|---|
| `text` | Full-text search (analyzed); can't be sorted or aggregated |
| `keyword` | Exact matching, sorting, aggregations (brands, statuses, tags, IDs) |
| `integer` / `long` / `float` / `scaled_float` | Numbers, range queries; scaled_float works for money |
| `date` | Stored as UTC milliseconds; mind time zones when querying |
| `boolean`, `ip`, `geo_point` | Booleans, IPs (subnet queries), coordinates (distance queries) |
| `object` / `nested` | Objects; use nested when objects in an array must be matched as "the same object" |
| `dense_vector` | Vectors (kNN search, the same kind of feature as pgvector) |

- ★ **Dynamic mapping**: an undefined field's type is inferred the first time it appears (number → long, string → text + keyword), and then it's fixed; if the first document has a number and the second "10A" → the second is rejected
- ★ An existing field's **type can't be changed**: create a new index → `_reindex` → switch with an **alias**; applications should always go through an alias
- In production define mappings or **index templates** up front; too many fields (mapping explosion) can bring a cluster down

## 4. ★ Query DSL

```es
GET /products/_search
{
  "query": {
    "bool": {
      "must":     [{ "match": { "description": "輕薄" } }],             # must match, scored
      "filter":   [{ "term": { "category": "筆電" } },                  # must match, not scored, cacheable
                   { "range": { "price": { "gte": 20000, "lte": 40000 } } }],
      "should":   [{ "term": { "tags": "熱銷" } }],                     # bonus score
      "must_not": [{ "term": { "brand": "Apple" } }]                   # exclude
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

| Query | Explanation |
|---|---|
| `match` | Full text: analyze first, then find documents containing those terms; OR by default (`operator: and`, `minimum_should_match`) |
| `match_phrase` | Terms must be in order and adjacent (decided by position); `slop` allows gaps |
| `multi_match` | Several fields, weighted with `"fields": ["name^3", "description"]`; best_fields / most_fields / cross_fields |
| `term` / `terms` | Exact match (not analyzed), for keyword and numbers |
| `range` | Numeric and date ranges (`gte`, `lt`; dates can be written `now-7d/d`) |
| `exists` | The field has a value |
| `fuzzy` / `fuzziness: "AUTO"` | Tolerates typos (edit distance) |
| `prefix` / `wildcard` / `regexp` | Starts with…; leading wildcards are very slow |
| `nested` | Query nested fields |
| `function_score` | Adjust scores by field values, distance or scripts |

- ★ **query context vs filter context**: must / should are scored; filter / must_not aren't scored and their results can be cached → conditions that don't need relevance always go in filter
- ★ Use match on text fields; use term / range on keyword, numbers and dates. term on text often finds nothing (it stores lowercased, split terms)
- Measured: match 「通勤戴很舒服」 finds 2,756 (any single bigram counts), while match_phrase finds only 266
- `hits.total` counts exactly only up to 10,000 by default (`relation: gte`); for exact counts: `track_total_hits: true` or `_count`

## 5. ★ Relevance: BM25

- Score ≈ **IDF** (the fewer documents have the term, the higher) × **TF** (occurrences, with saturation, parameter k1 = 1.2) × **field-length adjustment** (shorter is higher, parameter b = 0.75)
- Measured: 「降噪」 (in 404 reviews) tops out at 4.3, while 「很好」 ("very good", in 8,000-odd) tops out at only 1.5
- `"explain": true` shows how a score was computed; scores only rank within one query and can't be used as thresholds
- Tuning the ranking: field boosts (`^3`), should bonuses, `function_score` (ratings, sales, recency, distance), `rescore`
- Older versions (before 5.0) defaulted to TF-IDF; BM25 handles frequent terms and long documents more sensibly

## 6. ★ Aggregations

```es
GET /orders/_search
{
  "size": 0,                                                      # aggregations only, no documents
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

| Kind | Aggregations | SQL equivalent |
|---|---|---|
| bucket (grouping) | `terms`, `date_histogram`, `histogram`, `range`, `filters`, `composite` | GROUP BY |
| metric (computing) | `avg`, `sum`, `min`, `max`, `stats`, `percentiles`, `cardinality`, `top_hits` | Aggregate functions |
| pipeline (computing on results) | `bucket_sort`, `derivative`, `cumulative_sum`, `moving_fn` | Window functions |

- Aggregations use doc values: text fields can't be aggregated (fielddata is off by default); use keyword or a `.keyword` subfield
- ★ Approximations: `terms` may undercount with multiple shards (`doc_count_error_upper_bound`), `cardinality` is HyperLogLog++, `percentiles` is TDigest
- ★ date_histogram without `time_zone` cuts in UTC (a Taiwan day starts at 8 a.m.)
- To page through every bucket (exporting), use `composite` + `after`

## 7. Related data: nested, object, parent-child

```es
GET /orders/_count
{
  "query": { "nested": { "path": "items", "query": { "bool": { "filter": [
    { "term": { "items.category": "手機" } },
    { "range": { "items.quantity": { "gte": 2 } } }
  ] } } } }
}
```

- ★ **Object arrays are flattened**: items.category = [手機, 男裝], items.quantity = [1, 3], so conditions from different items also match (measured: 6,884 with object vs the correct 3,465 with nested)
- **nested**: each object is a hidden Lucene document (80,000 orders with 200,000 items → `_cat/indices` shows 280,000 documents); queries and aggregations need nested / reverse_nested; updating one item rewrites the whole document
- **join fields (parent-child)**: parents and children are separate documents that can be updated independently, but queries are slow and they must be on the same shard (routing)
- ★ Prefer **denormalization**: put the fields you search on directly into the document (product names and categories in orders), and rebuild when the data changes

## 8. Writes and near real-time

```es
PUT  /scratch/_doc/1            { … }             # with an _id: insert or overwrite the whole document
POST /scratch/_doc              { … }             # auto-generated _id
PUT  /scratch/_create/1         { … }             # fails if it already exists
POST /scratch/_update/1         { "doc": { "price": 2990 } }
POST /scratch/_update_by_query  { "query": …, "script": { "source": "ctx._source.tags.add(params.t)", "params": { "t": "週年慶" } } }
POST /scratch/_delete_by_query  { "query": … }
POST /_bulk                     (NDJSON: one line of action, one line of content)
PUT  /scratch/_doc/1?if_seq_no=5&if_primary_term=1   { … }   # optimistic locking: 409 if it was changed
```

- The write path: in-memory buffer + **translog** (against loss) → **refresh** (becomes a searchable segment, every second by default) → **flush** (fsync to disk, clear the translog) → **merge** (merge segments, purge deleted documents)
- ★ Searches wait for a refresh; `GET /_doc/id` is real-time. For "searchable right after writing": `?refresh=wait_for` (don't use `refresh=true` every time)
- ★ Segments are immutable: **an update = mark deleted + rewrite**, and a delete is just a mark → frequently updating the same document (counters) is a poor fit
- `_bulk` isn't a transaction: each action succeeds or fails on its own; check `errors` and each of the `items` in the response
- Bulk imports: `refresh_interval: -1` and `number_of_replicas: 0`, restored after the import
- Don't delete old data with delete_by_query: split indices by time and delete whole indices

## 9. ★ Shards and clusters

- An index is split into several **primary shards** (fixed after creation, only `_split` / `_shrink` or a reindex can change them); each primary has **replicas** (changeable at any time)
- Which shard a document goes to: `hash(_routing or _id) % number of shards` → which is why the shard count can't change
- Search is **query then fetch**: each shard finds its top from + size → the coordinator merges and sorts → then fetches the document contents
- Cluster health: **green** (everything allocated) / **yellow** (some replicas have nowhere to go, e.g. a single node) / **red** (a primary is missing, data is incomplete)
- Node roles: master (manages cluster state; use an odd number to avoid split brain), data (hot / warm / cold), ingest, coordinating
- Recommended shard size is 10–50 GB; too many small shards (oversharding) waste memory and slow the master
- Replicas improve availability and read throughput, but every write must go to each copy

## 10. Pagination and bulk reads

```es
GET /logs/_search
{ "size": 100, "sort": [{ "@timestamp": "asc" }, { "trace_id": "asc" }],
  "search_after": [1788192208672, "722ada4508737385"] }        # the sort values of the previous page's last hit

POST /logs/_pit?keep_alive=1m                                  # point in time: a fixed snapshot
```

- ★ `from + size` is limited to 10,000 (`index.max_result_window`): every shard has to rank its top from + size, so deeper pages cost more
- Deep pagination uses **search_after** (+ a PIT so results don't change while paging); the sort must end with a unique field
- scroll is for bulk exports (no longer recommended; use PIT + search_after)

## 11. Logs and time series: data streams, ILM

- Split logs into indices by time (logs-2026.09.18), or use a **data stream** (a set of hidden indices with automatic rollover, append-only)
- **ILM** (Index Lifecycle Management): hot (new data, SSDs) → warm (read-only, forcemerge) → cold / frozen (cheap storage) → delete
- ELK / Elastic Stack: Beats / Logstash collect → Elasticsearch stores → Kibana queries and dashboards; OpenSearch is the open-source fork from AWS

## 12. Common traps

| Trap | Explanation |
|---|---|
| term on a text field | It stores analyzed terms (lowercased, split), so nothing is found |
| match on a whole sentence | OR by default, so any one term matches and there are lots of results |
| standard analyzer for Chinese | One term per character, so 「音質」 finds 「品質」 |
| Object arrays | Conditions cross-match across objects; use nested |
| hits.total is 10000 | Counted exactly only up to ten thousand by default, with `relation: gte` |
| date_histogram without a time zone | Cut in UTC, so every day's number is wrong |
| Aggregating or sorting on text fields | Error: fielddata is disabled; use keyword |
| from 10000 | Result window is too large; use search_after |
| Dynamic mapping | The first document decides the type, and later writes fail |
| Changing a field's type | Impossible; new index + reindex + alias |
| Searching right after writing | Near real-time; wait for a refresh |
| Too many / too few shards | Fixed after creation; too many waste resources, too few can't scale out |
| Using Elasticsearch as the primary database | No transactions, mappings can't change, recent writes may be lost (depending on configuration) |

## 13. ★ Quick interview answers

| Question | Key points |
|---|---|
| Why is search fast? | Inverted index: find documents directly by term; doc values for sorting and aggregations; shards processed in parallel |
| How do text and keyword differ? | text is analyzed, for full-text search; keyword isn't analyzed, for exact matching, sorting and aggregations |
| How is relevance computed? | BM25: IDF, TF (with saturation), field length; adjustable with boosts and function_score |
| How do query and filter differ? | query is scored; filter isn't scored and can be cached, so it's faster |
| Why can't I find what I just wrote? | Near real-time: it becomes a searchable segment only after a refresh (1 second by default) |
| How do you decide the shard count? | By data volume (10–50 GB per shard) and node count; the number of primaries can't change after creation |
| The cluster turned yellow / red? | yellow: replicas unallocated (a single node); red: a primary is missing and data is incomplete |
| How do you sync with the database? | Dual writes from the application (may be inconsistent), CDC (Debezium → Kafka → Elasticsearch), periodic full rebuilds; the database is the source of truth |
| How do you do deep pagination? | search_after + PIT; from + size is limited to 10,000 |
| nested vs object? | Object arrays are flattened and cross-match across objects; nested keeps object boundaries but costs more |
| Elasticsearch vs the database's LIKE? | LIKE '%term%' scans the whole table, has no relevance ranking and doesn't understand word segmentation; ES uses an inverted index, BM25 and analyzers |
| Elasticsearch vs pgvector? | ES excels at keywords (BM25) and aggregations, and has dense_vector for kNN; hybrid search often uses both |

## 14. Java / Spring

- The official **Elasticsearch Java API Client** (`co.elastic.clients:elasticsearch-java`): type-safe builders, `client.search(s -> s.index("products").query(q -> q.match(m -> m.field("name").query("耳機"))), Product.class)`
- **Spring Data Elasticsearch**: `@Document(indexName = "products")`, `@Field(type = FieldType.Text, analyzer = "cjk")`, `ElasticsearchRepository<Product, String>` (derived queries like `findByBrand`), `NativeQuery` for complex queries
- ★ Sync strategy: send only after the transaction commits (`@TransactionalEventListener(phase = AFTER_COMMIT)`), and make failures retryable; use CDC for large data
- Use `BulkIngester` for batched writes; set timeouts on queries; don't make the Elasticsearch connection pool too large

# InfluxDB

> The examples all come from the practice environment (the `influx-lab` container, http://localhost:8087, InfluxDB 2.7, organization shop, bucket metrics) and can be pasted straight into the showcase's "console". cpu and mem (5 hosts, per minute), http (cumulative API request counters) and sensors (warehouse temperature and humidity, every 5 minutes) all cover September 2026; orders is copied from PostgreSQL.

## 1. ★ Basics

- **point** = measurement + tag set + field set + timestamp: `cpu,host=db-01,role=db usage_user=35.8,usage_system=10.2 1790783940`
- **measurement** ≈ a table name; **tags** are indexed and their values are always strings (for filtering and grouping); **fields** aren't indexed and hold measured values (floats, integers, strings, booleans)
- **series**: measurement + tag set (+ field) defines one time series, each stored and compressed separately in time order. cpu has 5 hosts → 5 series
- 2.x organization: **organization** → **bucket** (database + retention); permissions are attached to **tokens** (no roles; each token lists which buckets it can read / write)
- Query languages: **InfluxQL** (SQL-like, since 1.x; in 2.x you create DBRP mappings first) and **Flux** (2.x's pipeline language, no longer getting new features); 3.x switched to **SQL** + InfluxQL

| Relational database / TimescaleDB | InfluxDB |
|---|---|
| Table | measurement |
| Indexed columns (host, region) | tag (always a string) |
| Regular columns (values) | field |
| Row | point |
| Database + retention policy | bucket (2.x), database + retention policy (1.x) |
| JOINs, subqueries, window functions | InfluxQL has no JOIN; Flux can join |
| UPDATE | None: rewriting the same series at the same timestamp overwrites |

## 2. Writes: line protocol

```text
# measurement,tag1=value,tag2=value field1=value,field2=value timestamp
cpu,host=db-01,region=tpe,role=db usage_user=35.84,usage_system=10.2 1790783940
orders,city=台北市,status=paid order_id=1001i,total=1990i,coupon="WELCOME",gift=true 1790726400
```

- A comma between measurement and tags, one space between tags and fields, one space between fields and the time; spaces, commas and equals signs are escaped with a backslash
- Field types: float (the default), **integers end in i** (`1990i`), strings in double quotes, booleans `true` / `false`
- The timestamp unit is set by the `precision` parameter (ns by default, us, ms, s); without a time, the server's receive time is used
- ★ The same series + the same timestamp = the same point: the later write overwrites the earlier (different fields are merged). Two orders in the same second with the same tags → the first disappears, with no error
- ★ Within one shard a field can have only one type: writing `total=100i` and then `total=1.5` → field type conflict, and the whole batch may be rejected
- Write in batches (about 5,000 lines each); sorting tags by name before writing is faster; delete with the delete API (time range + tag conditions)

## 3. ★ Data modeling: tag or field

| | tag | field |
|---|---|---|
| Index | Yes (TSI) | No |
| Types | Strings only | Float, integer, string, boolean |
| WHERE filtering | Looks up the index and finds series directly | Reads the whole series and compares point by point |
| GROUP BY | Yes | No (everything ends up in one group, with no error) |
| Computation (mean, sum) | No | Yes |
| With many distinct values | ★ The number of series explodes (high cardinality) | No effect |

- Measured (lab): 1,000 users × 20 events, with user_id as a tag → **1,000 series**; as a field → **1**
- ★ Rule: things you filter and group by, with a limited set of values (host, region, service, status, sensor id) → tags; measured values, IDs and high-cardinality values (user IDs, order numbers, trace ids, IPs, full URLs) → fields
- High cardinality is the number one cause of performance problems in 1.x / 2.x: the index eats memory, writes slow down, and queries must merge huge numbers of series; 3.x's switch to columnar storage (Arrow / Parquet) was made to fix it
- `SHOW SERIES EXACT CARDINALITY` (InfluxQL) and `influxdb.cardinality()` (Flux) show the number of series

## 4. ★ InfluxQL

```sql
SHOW MEASUREMENTS;
SHOW TAG KEYS FROM cpu;
SHOW TAG VALUES FROM cpu WITH KEY = "host";
SHOW FIELD KEYS FROM orders;                       -- fields and their types

SELECT mean(usage_user) FROM cpu
WHERE host = 'db-01'                               -- ★ single quotes for string values
  AND time >= '2026-09-18T12:00:00+08:00' AND time < '2026-09-18T17:00:00+08:00'
GROUP BY time(1h), host fill(null) tz('Asia/Taipei');

SELECT last(usage_user) FROM cpu GROUP BY host;    -- each host's last report
SELECT top(total, 5), order_id, city FROM orders WHERE time >= '2026-09-01T00:00:00+08:00';
SELECT max(mean) FROM (SELECT mean(usage_user) FROM cpu WHERE … GROUP BY time(1h), host) GROUP BY host;   -- subquery
SELECT non_negative_derivative(last(requests), 1m) FROM http WHERE service = 'api' AND … GROUP BY time(1m);
```

| Kind | Functions | The time column |
|---|---|---|
| aggregate | `count`, `mean`, `sum`, `median`, `spread`, `stddev` | The start of the interval (0 without GROUP BY time) |
| selector | `first`, `last`, `max`, `min`, `top`, `bottom`, `percentile` | That point's time (the interval start with GROUP BY time) |
| transformation | `derivative`, `non_negative_derivative`, `difference`, `moving_average`, `cumulative_sum` | Every row |

- ★ Single quotes = string values, double quotes = identifiers. `host = "db-01"` finds nothing, with no error
- ★ GROUP BY time() of a day or more always needs `tz('Asia/Taipei')`, or it's cut in UTC (an extra row, and every day's value is wrong)
- `fill(null | none | 0 | previous | linear)` decides how intervals without data are shown
- Only `ORDER BY time`; rank by value with `top()` / `bottom()`
- With GROUP BY tag, `LIMIT` applies "per series"; limit the number of series with `SLIMIT`
- SELECTing only tags (no field) → no data returned; there's no JOIN

## 5. ★ Flux

```flux
import "timezone"
option location = timezone.location(name: "Asia/Taipei")

from(bucket: "metrics")
  |> range(start: 2026-09-18T00:00:00+08:00, stop: 2026-09-20T00:00:00+08:00)   // ★ range is required
  |> filter(fn: (r) => r._measurement == "cpu" and r._field == "usage_user")
  |> aggregateWindow(every: 1d, fn: max, createEmpty: false)                     // _time is the window's "end"
  |> group()                                                                     // merge into one table to sort together
  |> sort(columns: ["_value"], desc: true)
  |> limit(n: 3)
  |> keep(columns: ["_time", "host", "_value"])

// Adding two fields: pivot them into one row first
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> map(fn: (r) => ({ r with total: r.usage_user + r.usage_system }))

// Counter → amount per minute (skipping negative values from resets)
  |> derivative(unit: 1m, nonNegative: true)
```

- Data model: **one value per row** (`_value`), with the field name in `_field`; each series is a "table", and functions run on each table separately
- Groups = tables: `group(columns: ["role"])` regroups into tables, `group()` merges everything into one; aggregation, sorting and limit all work per table
- Differences from InfluxQL: `aggregateWindow`'s `_time` defaults to the window's **end** (`timeSrc: "_start"` makes it the start); time zones use `option location`
- What Flux can do that InfluxQL can't: join different measurements / buckets, arbitrary computation with `map`, writing back to a bucket with `to()` (downsampling), scheduled Tasks
- Security: Flux has packages like `sql` and `http` that can reach external systems, beyond the reach of token permissions

## 6. Counters and rates

- Monitoring systems (Telegraf, Prometheus exporters) mostly collect **cumulative counters**: they report only "the total so far", and queries compute differences
- ★ A restart resets the counter to zero, and `derivative` produces a huge negative number (-3.6 million in testing) → always use `non_negative_derivative` / `non_negative_difference` (Flux: `nonNegative: true`, `increase()`)
- `difference` = the next minus the previous; `derivative(…, 1m)` also divides by a time unit, giving a rate
- Error rate: `100 * non_negative_difference(last(errors)) / non_negative_difference(last(requests))`

## 7. ★ Retention and downsampling

- A bucket's **retention period**: data older than it is deleted automatically (whole shard groups at a time, which is cheap)
- Downsampling: a **Task** (Flux run on a schedule) rolls old data up into another bucket, for example:
  `option task = {name: "downsample_cpu", every: 1h}` + `aggregateWindow(every: 1h, fn: mean) |> to(bucket: "cpu_1h")`
- 1.x uses Continuous Queries + Retention Policies; TimescaleDB uses continuous aggregates + retention policies; the idea is the same
- Measured: September's cpu goes from 200,880 points to 3,348 (1/60), and the same query is several times faster; but hourly averages hide db-01's fully loaded spike → store mean, max, min and count together
- Late data may miss Tasks that have already run: schedule processing of the interval "an hour ago", leaving some buffer

## 8. Storage engines and versions

- 1.x / 2.x: the **TSM** (Time-Structured Merge Tree) engine: writes go to the WAL and an in-memory cache first, then are compacted into TSM files; data is split by time into **shard groups** (one group per 7 days with infinite retention); the **TSI** index maps tags → series
- Columns are compressed by type (delta-of-delta for timestamps, Gorilla XOR for floats), giving time series data very high compression
- 3.x (IOx): Apache **Arrow** (memory) + **Parquet** (object storage) + **DataFusion** (a SQL query engine), columnar and friendly to high cardinality; queries use SQL / InfluxQL, and **Flux isn't supported**

| | 1.x | 2.x | 3.x |
|---|---|---|---|
| Queries | InfluxQL | Flux, InfluxQL (compatibility API) | SQL, InfluxQL |
| Organization | database + retention policy | organization + bucket + token | database (table = measurement) |
| Scheduled rollups | Continuous Queries | Tasks (Flux) | External schedulers / processing engine |
| Storage | TSM + TSI | TSM + TSI | Arrow + Parquet |
| High cardinality | Weak | Weak | Much better |

## 9. Common traps

| Trap | Explanation |
|---|---|
| Double quotes around strings `host = "db-01"` | Double quotes are identifiers, so nothing is found, with no error |
| Treating tag values as numbers `sensor_id = 2` | Tags are always strings; write `'2'` |
| GROUP BY time(1d) without tz() | Cut in UTC, so every day's value is wrong |
| derivative on a counter | A huge negative number at restarts |
| GROUP BY a field | Everything ends up in one group, with no error |
| SELECTing only tags | No data returned |
| GROUP BY tag + LIMIT | LIMIT applies per series (use SLIMIT to limit the number of series) |
| Flux without range | Error: cannot submit unbounded read |
| Flux vs InfluxQL times | aggregateWindow labels the window end, GROUP BY time labels the start |
| Two writes in the same second with the same tags | The later overwrites the earlier |
| Different types written to the same field | field type conflict, and the whole batch may be rejected |
| High-cardinality values as tags | The number of series explodes, causing memory and performance problems |

## 10. ★ Quick interview answers

| Question | Key points |
|---|---|
| InfluxDB's data model? | measurement + tags (indexed strings) + fields (measured values) + time; measurement + tag set = series |
| How to choose tags vs fields? | Filtering, grouping, limited values → tags; measured values, IDs, high cardinality → fields |
| What is high cardinality? | Too many series (e.g. putting user IDs in tags), so the index eats memory and writes and queries slow down |
| What happens to old data? | The retention period deletes it automatically + Tasks downsample into another bucket |
| How do you compute rates from counters? | non_negative_derivative / increase, avoiding negative values from resets |
| InfluxDB vs TimescaleDB? | InfluxDB: a dedicated time series engine, fast writes and compression, a monitoring ecosystem (Telegraf, Grafana); TimescaleDB: full SQL, JOINs and transactions, lives alongside relational data, and handles high cardinality fine |
| InfluxDB vs Prometheus? | Prometheus pulls metrics, uses PromQL, suits Kubernetes monitoring and alerting, and its local storage isn't meant for long-term retention; InfluxDB is a push-based general time series database that can store arbitrary events and long-term data |
| Why did 3.x go back to SQL? | Flux had a steep learning curve and a small ecosystem; columnar storage (Arrow / Parquet / DataFusion) handles both SQL and high cardinality well |
| How do you fix wrongly written data? | Rewrite the same series at the same timestamp; or delete with the delete API and write again |

## 11. Java / Spring

- The official 2.x client: `com.influxdb:influxdb-client-java`
  - `Point.measurement("cpu").addTag("host", "db-01").addField("usage_user", 35.8).time(Instant.now(), WritePrecision.MS)`
  - Write with `WriteApi` (asynchronous, with automatic batching and retries) or `WriteApiBlocking`; query with `QueryApi.query(flux)`, which returns `FluxTable`s
- 3.x: `influxdb3-java` (Arrow Flight queries, SQL / InfluxQL)
- Application metrics: Spring Boot Actuator + Micrometer's `micrometer-registry-influx` (configure `management.influx.metrics.export.*`) sends JVM, HTTP request and other metrics automatically
- ★ Always write in batches, asynchronously; decide number types explicitly (use `addField(name, long)` for integers) to avoid field type conflicts; don't put high-cardinality values in tags
