-- shop 與 scratch 共用的資料表定義（{ks} 會換成 keyspace 名稱）。
-- Cassandra 的設計原則是「先想好要怎麼查，再為每一種查詢建一張表」：
-- 同一份資料會依查詢需求寫進好幾張表（反正規化），每張表的 PRIMARY KEY 決定了它能回答哪些問題。

CREATE TYPE IF NOT EXISTS {ks}.order_item (
  product_id int,
  name       text,
  qty        int,
  unit_price int,
  discount   decimal
);

-- 用會員編號查會員
CREATE TABLE IF NOT EXISTS {ks}.customers (
  customer_id int PRIMARY KEY,
  name        text,
  email       text,
  gender      text,
  birth_date  date,
  city        text,
  signup_date date,
  vip_level   text
);

-- 用 email 查會員（登入）：同一份資料，換一個分區鍵
CREATE TABLE IF NOT EXISTS {ks}.customers_by_email (
  email       text PRIMARY KEY,
  customer_id int,
  name        text
);

-- 用商品編號查商品；tags 是 set、specs 是 map（巢狀的規格攤平成 "warranty.years" 這種 key）
CREATE TABLE IF NOT EXISTS {ks}.products (
  product_id    int PRIMARY KEY,
  name          text,
  category      text,
  category_path list<text>,
  price         int,
  cost          int,
  stock         int,
  is_active     boolean,
  created_at    date,
  tags          set<text>,
  specs         map<text, text>
);

-- 某個分類的商品，依價格由高到低：分區鍵 category，叢集鍵 price DESC（product_id 讓同價商品不會互相覆蓋）
CREATE TABLE IF NOT EXISTS {ks}.products_by_category (
  category   text,
  price      int,
  product_id int,
  name       text,
  stock      int,
  PRIMARY KEY ((category), price, product_id)
) WITH CLUSTERING ORDER BY (price DESC, product_id ASC);

-- 用訂單編號查整張訂單：明細用 UDT 清單直接放在訂單裡
CREATE TABLE IF NOT EXISTS {ks}.orders (
  order_id      int PRIMARY KEY,
  customer_id   int,
  order_time    timestamp,
  status        text,
  shipping_city text,
  shipping_fee  int,
  total         int,
  items         list<frozen<order_item>>
);

-- 某位會員的訂單，新的在前（會員中心「我的訂單」）
CREATE TABLE IF NOT EXISTS {ks}.orders_by_customer (
  customer_id int,
  order_time  timestamp,
  order_id    int,
  status      text,
  total       int,
  item_count  int,
  PRIMARY KEY ((customer_id), order_time, order_id)
) WITH CLUSTERING ORDER BY (order_time DESC, order_id ASC);

-- 某一天的訂單（時間分桶）：order_day 是台灣時間的日期，一天一個分區，避免分區無限長大
CREATE TABLE IF NOT EXISTS {ks}.orders_by_day (
  order_day     date,
  order_time    timestamp,
  order_id      int,
  customer_id   int,
  status        text,
  total         int,
  shipping_city text,
  PRIMARY KEY ((order_day), order_time, order_id)
);

-- 某件商品最近被誰買走
CREATE TABLE IF NOT EXISTS {ks}.items_by_product (
  product_id  int,
  order_time  timestamp,
  order_id    int,
  customer_id int,
  qty         int,
  unit_price  int,
  PRIMARY KEY ((product_id), order_time, order_id)
) WITH CLUSTERING ORDER BY (order_time DESC, order_id ASC);

-- 計數器表：除了主鍵以外只能有 counter 欄位，只能用 UPDATE 加減
CREATE TABLE IF NOT EXISTS {ks}.product_sales (
  product_id int PRIMARY KEY,
  units      counter,
  orders     counter
);
