-- =========================================================
-- 索引實驗室：perf.orders_big（200 萬筆，只有主鍵、沒有其他索引）
-- 放在獨立的 perf schema，不影響 public 的 5 張練習表
--
-- 安全設計：APP 建立 / 刪除索引時會 SET ROLE perf_lab，
-- 這個角色只擁有 perf schema，就算送出 DROP INDEX public.xxx 也會被資料庫拒絕
-- =========================================================
SELECT setseed(0.7);

CREATE ROLE perf_lab NOLOGIN;
GRANT perf_lab TO lab;

CREATE SCHEMA perf AUTHORIZATION perf_lab;

CREATE TABLE perf.orders_big (
    id          bigserial     PRIMARY KEY,
    customer_id int           NOT NULL,          -- 1 ~ 200,000，平均每人 10 筆
    order_date  timestamptz   NOT NULL,          -- 2022-01-01 ~ 2026-09-30
    status      text          NOT NULL,          -- 八成以上是 delivered（選擇性很低）
    amount      numeric(10,2) NOT NULL
);
ALTER TABLE perf.orders_big OWNER TO perf_lab;
COMMENT ON TABLE perf.orders_big IS '索引實驗用的大表';

INSERT INTO perf.orders_big (customer_id, order_date, status, amount)
SELECT 1 + floor(random() * 200000)::int,
       timestamptz '2022-01-01' + random() * (timestamptz '2026-09-30' - timestamptz '2022-01-01'),
       CASE WHEN r < 0.82 THEN 'delivered' WHEN r < 0.92 THEN 'cancelled'
            WHEN r < 0.97 THEN 'returned' ELSE 'pending' END,
       round((50 + random() * 30000)::numeric, 2)
FROM (SELECT random() AS r FROM generate_series(1, 2000000)) g;

VACUUM ANALYZE perf.orders_big;
