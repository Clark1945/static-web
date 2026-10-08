-- TimescaleDB 練習資料的結構（展示台啟動時執行，全部都可以重複執行）。
-- 句子之間用連續兩個分號分隔（函式內容裡會有單一分號）。

CREATE EXTENSION IF NOT EXISTS timescaledb;;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'learner') THEN
    CREATE ROLE learner NOLOGIN;
  END IF;
END $$;;

-- 訂單：從 PostgreSQL 複製，以下單時間當作時間軸
CREATE TABLE IF NOT EXISTS orders (
  order_time  timestamptz NOT NULL,
  order_id    int         NOT NULL,
  customer_id int         NOT NULL,
  status      text        NOT NULL,
  city        text,
  total       int         NOT NULL,
  item_count  int         NOT NULL
);;
SELECT create_hypertable('orders', by_range('order_time', INTERVAL '30 days'), if_not_exists => TRUE);;
CREATE INDEX IF NOT EXISTS orders_customer_time ON orders (customer_id, order_time DESC);;

-- 商品瀏覽紀錄（點擊流）：用雜湊值產生，每次重建都一樣
CREATE TABLE IF NOT EXISTS page_views (
  view_time   timestamptz NOT NULL,
  product_id  int         NOT NULL,
  customer_id int,                     -- NULL = 沒登入的訪客
  device      text        NOT NULL,
  referrer    text        NOT NULL
);;
SELECT create_hypertable('page_views', by_range('view_time', INTERVAL '7 days'), if_not_exists => TRUE);;
CREATE INDEX IF NOT EXISTS page_views_product_time ON page_views (product_id, view_time DESC);;

-- 倉庫的溫濕度感測器
CREATE TABLE IF NOT EXISTS sensors (
  sensor_id int PRIMARY KEY,
  warehouse text NOT NULL,
  zone      text NOT NULL,
  kind      text NOT NULL            -- 冷藏 / 常溫
);;
CREATE TABLE IF NOT EXISTS sensor_readings (
  time        timestamptz      NOT NULL,
  sensor_id   int              NOT NULL,
  temperature double precision,
  humidity    double precision
);;
SELECT create_hypertable('sensor_readings', by_range('time', INTERVAL '1 day'), if_not_exists => TRUE);;
CREATE INDEX IF NOT EXISTS sensor_readings_sensor_time ON sensor_readings (sensor_id, time DESC);;

GRANT USAGE ON SCHEMA public TO learner;;
GRANT SELECT, INSERT, UPDATE, DELETE ON orders, page_views, sensors, sensor_readings TO learner;;
