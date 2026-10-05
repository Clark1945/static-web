-- =========================================================
-- 產生練習資料（固定亂數種子 → 每次重建結果都一樣）
-- =========================================================
SELECT setseed(0.42);

-- 小工具：隨機姓名、從陣列隨機挑一個（skew > 1 會偏向前面的元素）
CREATE FUNCTION pg_temp.rname() RETURNS text LANGUAGE sql VOLATILE AS $$
  SELECT (ARRAY['陳','林','黃','張','李','王','吳','劉','蔡','楊','許','鄭','謝','郭','洪',
                '曾','邱','廖','賴','周','徐','蘇','葉','莊','呂','江','何','蕭','羅','高'])[1 + floor(random() * 30)::int]
      || (ARRAY['家','宜','怡','冠','俊','志','雅','承','柏','欣','佳','建','美','子','思','品','宇','書','詩','明'])[1 + floor(random() * 20)::int]
      || (ARRAY['豪','君','婷','宏','廷','翰','涵','恩','安','瑋','華','玲','傑','萱','妤','誠','樺','慧','文','穎'])[1 + floor(random() * 20)::int]
$$;

CREATE FUNCTION pg_temp.pick(a anyarray, skew float8 DEFAULT 1) RETURNS anyelement LANGUAGE sql VOLATILE AS $$
  SELECT a[1 + floor(power(random(), skew) * array_length(a, 1))::int]
$$;

-- ---------------------------------------------------------
-- 會員 20,000 筆（id 越小越早註冊）
-- ---------------------------------------------------------
INSERT INTO customers (name, email, gender, birth_date, city, signup_date)
SELECT pg_temp.rname(),
       'user' || lpad(g::text, 5, '0') || '@example.com',
       CASE WHEN random() < 0.52 THEN 'F' ELSE 'M' END,
       CASE WHEN random() < 0.06 THEN NULL
            ELSE date '1960-01-01' + floor(random() * (date '2006-12-31' - date '1960-01-01'))::int END,
       CASE WHEN random() < 0.03 THEN NULL
            ELSE pg_temp.pick(ARRAY['台北市','新北市','台中市','高雄市','桃園市','台南市','新竹市','新竹縣',
                                    '彰化縣','基隆市','嘉義市','屏東縣','宜蘭縣','花蓮縣','台東縣'], 1.8) END,
       date '2022-01-01' + floor((g - 1) * (date '2026-06-30' - date '2022-01-01') / 20000.0)::int
FROM generate_series(1, 20000) g;

-- ---------------------------------------------------------
-- 分類 27 筆（三層樹）
-- ---------------------------------------------------------
CREATE TEMP TABLE cat_seed (id int, name text, parent_id int, base_price int, brands text[]);
INSERT INTO cat_seed VALUES
  ( 1, '3C電子',   NULL, NULL,  NULL),
  ( 2, '手機',        1, 15000, '{Apple,Samsung,ASUS,Sony,小米}'),
  ( 3, '筆電',        1, 30000, '{Apple,ASUS,Acer,Lenovo,MSI}'),
  ( 4, '耳機',        1, NULL,  NULL),
  ( 5, '無線耳機',    4, 3500,  '{Sony,Apple,JBL,Bose,小米}'),
  ( 6, '有線耳機',    4, 1200,  '{Sony,Audio-Technica,JBL,Sennheiser}'),
  ( 7, '手機配件',    1, 500,   '{Anker,Belkin,ESR,小米}'),
  ( 8, '居家生活', NULL, NULL,  NULL),
  ( 9, '廚房用品',    8, 800,   '{膳魔師,象印,虎牌,Tefal}'),
  (10, '寢具',        8, 1500,  '{IKEA,無印良品,宜得利}'),
  (11, '收納',        8, 400,   '{IKEA,無印良品,宜得利,天馬}'),
  (12, '服飾',     NULL, NULL,  NULL),
  (13, '男裝',       12, 800,   '{UNIQLO,GU,NET,Lativ}'),
  (14, '女裝',       12, 900,   '{UNIQLO,GU,NET,Lativ,ZARA}'),
  (15, '鞋子',       12, NULL,  NULL),
  (16, '運動鞋',     15, 2800,  '{Nike,Adidas,New Balance,ASICS}'),
  (17, '休閒鞋',     15, 1800,  '{Converse,Vans,Skechers}'),
  (18, '食品',     NULL, NULL,  NULL),
  (19, '零食',       18, 80,    '{義美,乖乖,華元,卡迪那}'),
  (20, '飲料',       18, 40,    '{統一,黑松,金車,維他露}'),
  (21, '生鮮',       18, 350,   '{台畜,大成,卜蜂}'),
  (22, '美妝保養', NULL, NULL,  NULL),
  (23, '保養',       22, 900,   '{理膚寶水,雅漾,Kiehls,SK-II}'),
  (24, '彩妝',       22, 600,   '{Maybelline,KATE,1028,MAC}'),
  (25, '運動戶外', NULL, NULL,  NULL),
  (26, '健身器材',   25, 1500,  '{Decathlon,Reebok,Adidas}'),
  (27, '露營用品',   25, 2500,  '{Coleman,Snow Peak,Decathlon}');

INSERT INTO categories (id, name, parent_id)
SELECT id, name, parent_id FROM cat_seed ORDER BY id;

-- ---------------------------------------------------------
-- 商品 1,500 筆（1401~1500 號是 2026 年新品，還沒人買過）
-- ---------------------------------------------------------
INSERT INTO products (name, category_id, price, cost, stock, is_active, created_at)
SELECT c.brands[1 + floor(random() * array_length(c.brands, 1))::int] || ' ' || c.name || ' '
         || chr(65 + floor(random() * 26)::int) || (100 + floor(random() * 900)::int),
       c.id,
       x.price,
       round(x.price * (0.45 + random() * 0.30)),
       CASE WHEN random() < 0.04 THEN 0 ELSE floor(random() * 500)::int END,
       random() >= 0.05,
       CASE WHEN x.g <= 1400 THEN date '2021-01-01' + floor(random() * 365)::int
            ELSE date '2026-07-01' + floor(random() * 90)::int END
FROM (
  SELECT g, cid,
         greatest(9, round(cs.base_price * (0.4 + random() * 1.6) / 10) * 10 - 1) AS price
  FROM (
    SELECT g, pg_temp.pick((SELECT array_agg(id) FROM cat_seed WHERE base_price IS NOT NULL)) AS cid
    FROM generate_series(1, 1500) g
  ) a
  JOIN cat_seed cs ON cs.id = a.cid
) x
JOIN cat_seed c ON c.id = x.cid
ORDER BY x.g;

-- ---------------------------------------------------------
-- 訂單 80,000 筆（19001~20000 號會員從沒下過單；老會員下單較多）
-- ---------------------------------------------------------
INSERT INTO orders (customer_id, order_date, status, shipping_city, shipping_fee)
SELECT cid,
       od,
       CASE WHEN od >= timestamp '2026-09-20' THEN
              CASE WHEN r_st < 0.3 THEN 'pending' WHEN r_st < 0.6 THEN 'paid'
                   WHEN r_st < 0.9 THEN 'shipped' ELSE 'cancelled' END
            ELSE
              CASE WHEN r_st < 0.82 THEN 'delivered' WHEN r_st < 0.92 THEN 'cancelled' ELSE 'returned' END
       END,
       CASE WHEN city IS NULL OR r_city < 0.1
            THEN pg_temp.pick(ARRAY['台北市','新北市','台中市','高雄市','桃園市','台南市'])
            ELSE city END,
       CASE WHEN r_fee < 0.5 THEN 0 WHEN r_fee < 0.85 THEN 60 ELSE 100 END
FROM (
  SELECT x.cid, x.r_st, x.r_city, x.r_fee, c.city,
         c.signup_date + x.r_t * (timestamp '2026-09-30 23:59:59' - c.signup_date) AS od
  FROM (
    SELECT 1 + floor(power(random(), 1.6) * 19000)::int AS cid,
           random() AS r_t, random() AS r_st, random() AS r_city, random() AS r_fee
    FROM generate_series(1, 80000)
  ) x
  JOIN customers c ON c.id = x.cid
) y
ORDER BY od;                                   -- 讓訂單編號跟時間順序一致

-- ---------------------------------------------------------
-- 訂單明細：每張訂單 1~4 種商品（同訂單重複商品就略過）
-- ---------------------------------------------------------
INSERT INTO order_items (order_id, product_id, quantity, unit_price, discount)
SELECT y.oid, p.id,
       CASE WHEN rq < 0.70 THEN 1 WHEN rq < 0.90 THEN 2 WHEN rq < 0.97 THEN 3 ELSE 5 END,
       p.price,
       CASE WHEN rd < 0.85 THEN 0 WHEN rd < 0.95 THEN 0.10 ELSE 0.20 END
FROM (
  SELECT o.id AS oid,
         1 + floor(power(random(), 1.4) * 1400)::int AS pid,
         random() AS rq, random() AS rd
  FROM orders o
  CROSS JOIN LATERAL generate_series(1, 1 + floor(random() * 4)::int + 0 * o.id)
) y
JOIN products p ON p.id = y.pid
ORDER BY y.oid
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------
-- VIP 等級：依已送達訂單的累計消費，前 5% gold、接下來 15% silver
-- ---------------------------------------------------------
UPDATE customers c
SET vip_level = CASE WHEN s.pr >= 0.95 THEN 'gold' WHEN s.pr >= 0.80 THEN 'silver' ELSE 'normal' END
FROM (
  SELECT o.customer_id, percent_rank() OVER (ORDER BY sum(oi.quantity * oi.unit_price * (1 - oi.discount))) AS pr
  FROM orders o JOIN order_items oi ON oi.order_id = o.id
  WHERE o.status = 'delivered'
  GROUP BY o.customer_id
) s
WHERE s.customer_id = c.id;

ANALYZE;
