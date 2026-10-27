-- pgvector 練習資料的結構（展示台啟動時在 pg-lab 容器的 vectors 資料庫執行，全部都可以重複執行）。
-- 句子之間用連續兩個分號分隔（函式內容裡會有單一分號）。

CREATE EXTENSION IF NOT EXISTS vector;;

-- 詞庫：每個詞一個 64 維的向量，意思相近的詞向量也相近（由幾十個「主題」加權組成，recipe 記錄配方）。
-- 這是用固定規則做的迷你嵌入模型，取代真正的語言模型，結果每次重建都一樣。
CREATE TABLE IF NOT EXISTS vocab (
  word      text PRIMARY KEY,
  recipe    text NOT NULL,
  embedding vector(64) NOT NULL
);;

-- 把一段文字轉成向量：找出文字裡出現的詞，向量相加後正規化成長度 1。
-- 詞庫裡一個詞都沒有時回傳 NULL（模型不認識這段文字）。
CREATE OR REPLACE FUNCTION embed(q text) RETURNS vector(64)
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT l2_normalize(sum(embedding)) FROM vocab WHERE strpos(q, word) > 0
$$;;

-- 看 embed() 認得文字裡的哪些詞
CREATE OR REPLACE FUNCTION tokens(q text) RETURNS text[]
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT coalesce(array_agg(word ORDER BY strpos(q, word), word), '{}') FROM vocab WHERE strpos(q, word) > 0
$$;;

-- 商品：從 PostgreSQL 複製，description 依規格產生，embedding = embed(description) 再加一點點雜訊
CREATE TABLE IF NOT EXISTS products (
  id          int PRIMARY KEY,
  name        text    NOT NULL,
  category    text    NOT NULL,
  price       int     NOT NULL,
  tags        text[]  NOT NULL DEFAULT '{}',
  specs       jsonb,
  description text,
  embedding   vector(64)
);;

-- 購買紀錄：從 PostgreSQL 的訂單明細複製（不含取消、退貨）
CREATE TABLE IF NOT EXISTS purchases (
  customer_id  int NOT NULL,
  product_id   int NOT NULL,
  quantity     int NOT NULL,
  purchased_at timestamptz NOT NULL
);;
CREATE INDEX IF NOT EXISTS purchases_customer ON purchases (customer_id);;

-- 模擬 RAG 的文件片段向量庫：10 萬筆 128 維，分屬 50 個租戶（tenant），用來量測索引
CREATE TABLE IF NOT EXISTS passages (
  id        int PRIMARY KEY,
  tenant_id int NOT NULL,
  embedding vector(128) NOT NULL
);;

-- 100 個測試用的查詢向量（不在 passages 裡），量測召回率用
CREATE TABLE IF NOT EXISTS questions (
  id        int PRIMARY KEY,
  embedding vector(128) NOT NULL
);;

-- 索引建立花了幾秒（展示台重啟後實驗室還看得到）；learner 看不到
CREATE TABLE IF NOT EXISTS lab_timings (
  name    text PRIMARY KEY,
  seconds double precision NOT NULL
);;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'learner') THEN
    CREATE ROLE learner NOLOGIN;
  END IF;
END $$;;

GRANT USAGE ON SCHEMA public TO learner;;
GRANT SELECT ON vocab, passages, questions TO learner;;
GRANT SELECT, INSERT, UPDATE, DELETE ON products, purchases TO learner;;
