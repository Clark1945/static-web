-- =========================================================
-- 電商資料庫「shop」結構：5 張表
--   customers ─< orders ─< order_items >─ products >─ categories（自我參照）
-- 注意：外鍵欄位刻意「沒有」建索引，之後學 EXPLAIN / 索引時用來比較效能
-- =========================================================

CREATE TABLE customers (
    id          serial PRIMARY KEY,
    name        text   NOT NULL,
    email       text   NOT NULL UNIQUE,
    gender      char(1) CHECK (gender IN ('M', 'F')),
    birth_date  date,                                   -- 可能是 NULL
    city        text,                                   -- 可能是 NULL
    signup_date date   NOT NULL,
    vip_level   text   NOT NULL DEFAULT 'normal'
                CHECK (vip_level IN ('normal', 'silver', 'gold'))
);
COMMENT ON TABLE customers IS '會員';

CREATE TABLE categories (
    id        int  PRIMARY KEY,
    name      text NOT NULL,
    parent_id int  REFERENCES categories(id)            -- 自我參照：上層分類（最多三層），最上層是 NULL
);
COMMENT ON TABLE categories IS '商品分類（樹狀結構）';

CREATE TABLE products (
    id          serial        PRIMARY KEY,
    name        text          NOT NULL,
    category_id int           NOT NULL REFERENCES categories(id),
    price       numeric(10,2) NOT NULL CHECK (price >= 0),   -- 售價
    cost        numeric(10,2) NOT NULL CHECK (cost >= 0),    -- 進貨成本
    stock       int           NOT NULL DEFAULT 0 CHECK (stock >= 0),
    is_active   boolean       NOT NULL DEFAULT true,         -- 是否上架
    created_at  date          NOT NULL
);
COMMENT ON TABLE products IS '商品';

CREATE TABLE orders (
    id            serial      PRIMARY KEY,
    customer_id   int         NOT NULL REFERENCES customers(id),
    order_date    timestamptz NOT NULL,
    status        text        NOT NULL
                  CHECK (status IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled', 'returned')),
    shipping_city text,
    shipping_fee  int         NOT NULL DEFAULT 0
);
COMMENT ON TABLE orders IS '訂單（金額要從 order_items 算）';

CREATE TABLE order_items (
    order_id   int           REFERENCES orders(id) ON DELETE CASCADE,
    product_id int           REFERENCES products(id),
    quantity   int           NOT NULL CHECK (quantity > 0),
    unit_price numeric(10,2) NOT NULL,                   -- 下單當下的單價
    discount   numeric(3,2)  NOT NULL DEFAULT 0 CHECK (discount BETWEEN 0 AND 1),  -- 0.10 = 打九折
    PRIMARY KEY (order_id, product_id)                   -- 複合主鍵
);
COMMENT ON TABLE order_items IS '訂單明細；小計 = quantity * unit_price * (1 - discount)';
