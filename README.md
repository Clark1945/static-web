# 資料庫面試練習庫（shop）

以一間台灣電商「shop」的模擬資料（約 30 萬筆，彼此有關聯）練習各種資料庫。目前有 PostgreSQL、Redis、MongoDB、Cassandra、Neo4j、TimescaleDB、pgvector、Elasticsearch、InfluxDB。

## 連線資訊

| | PostgreSQL | Redis | MongoDB | Cassandra | Neo4j | TimescaleDB | pgvector | Elasticsearch | InfluxDB |
|---|---|---|---|---|---|---|---|---|---|
| 容器 | `pg-lab` | `redis-lab` | `mongo-lab` | `cassandra-lab` | `neo4j-lab` | `timescale-lab` | `pg-lab`（同一個容器） | `es-lab` | `influx-lab` |
| Host / Port | localhost:5434 | localhost:6380 | localhost:27018 | localhost:9043 | Bolt localhost:7688、Browser http://localhost:7475 | localhost:5435 | localhost:5434 | http://localhost:9201 | http://localhost:8087 |
| 資料庫 | shop | db 0（練習資料）、db 1（批改用的隔離區） | shop（練習資料）、scratch（批改用的隔離區）、lab（實驗室） | keyspace shop（練習資料）、scratch（批改用的隔離區）、lab / lab_rf3（實驗室） | neo4j（社群版只有一個使用者資料庫） | metrics（hypertable：page_views、sensor_readings、orders） | vectors（展示台第一次啟動時自動建立） | 索引 products、reviews、orders、orders_object、logs（練習資料）；scratch*（批改與主控台用） | 組織 shop：bucket metrics（練習資料）、scratch（寫入練習與批改用） |
| 帳號 | lab / lab | `default` / admin-lab（管理）、`learner` / learner-lab（受限） | `admin` / admin-lab（管理）、`reader` / reader-lab（唯讀）、`learner` / learner-lab（讀寫） | `cassandra` / cassandra（管理）、`reader` / reader-lab（唯讀）、`learner` / learner-lab（讀寫 shop）、`sandbox` / sandbox-lab（只能碰 scratch） | `neo4j` / neo4j-lab（社群版沒有角色權限：展示台的 Cypher 一律 ROLLBACK） | `tsadmin` / timescale-lab（管理；使用者的 SQL 一律 `SET LOCAL ROLE learner`） | lab / lab（同 PostgreSQL；使用者的 SQL 一律 `SET LOCAL ROLE learner`） | `elastic` / elastic-lab（管理）、`reader` / reader-lab（唯讀）、`learner` / learner-lab（練習資料唯讀、scratch* 可寫） | `admin` / influx-lab（管理，token influx-lab-admin-token）；展示台另外建立 reader（只讀 metrics）、learner（讀 metrics、讀寫 scratch）兩個 token |

```bash
docker exec -it pg-lab psql -U lab -d shop
```

```bash
docker exec -it redis-lab redis-cli --user learner --pass learner-lab
```

```bash
docker exec -it mongo-lab mongosh "mongodb://learner:learner-lab@localhost/shop?authSource=admin"
```

```bash
docker exec -it cassandra-lab cqlsh -u learner -p learner-lab -k shop
```

```bash
docker exec -it neo4j-lab cypher-shell -u neo4j -p neo4j-lab
```

```bash
docker exec -it timescale-lab psql -U tsadmin -d metrics
```

```bash
docker exec -it pg-lab psql -U lab -d vectors
```

```bash
curl -u reader:reader-lab "http://localhost:9201/_cat/indices?v"
```

圖形介面：PostgreSQL 用 DBeaver 或 pgAdmin；Redis 用 RedisInsight；MongoDB 用 Compass；Cassandra 用 DBeaver；Neo4j 用內建的 Neo4j Browser（http://localhost:7475，結果畫成圖）；TimescaleDB、pgvector 跟 PostgreSQL 一樣用 DBeaver 或 pgAdmin；Elasticsearch 用展示台的 Dev Tools 主控台（或另外架 Kibana）；InfluxDB 用內建的網頁介面（http://localhost:8087，帳號 admin / influx-lab）。

## 常用指令

```bash
docker compose up -d          # 啟動全部 6 個資料庫（Cassandra 要等將近一分鐘）
docker compose stop           # 停止（資料保留；手動停止的容器，Docker 重啟後不會自動啟動）
docker compose down -v        # 刪掉資料庫，下次 up 會重新產生一模一樣的資料
```

六個容器都設定了 `restart: unless-stopped`：Docker Desktop 啟動時會自動跟著啟動。Redis 的資料是展示台啟動時才載入，所以 Docker 比展示台晚起來時，要重啟展示台。

Redis、MongoDB、Cassandra 的練習資料都是從 PostgreSQL 轉進去的：Redis 每次啟動都重新轉入（約 1 秒、4 萬個 key）；MongoDB 在 shop 是空的時候才轉入（約 3 秒、10 萬份文件）；Cassandra 在 shop 是空的時候才在背景轉入（9 張表、約 48 萬次寫入、10 秒左右），第一次啟動時展示台會先建立 keyspace、資料表與角色（約 30 秒）。Neo4j 在沒有訂單節點時才在背景轉入（10 萬個節點、46 萬條關係，約 12 秒）；追蹤關係（FOLLOWS）是用固定亂數種子產生的模擬社群資料，同一份也會寫進 PostgreSQL 的 `graphlab.follows`，給實驗室比較遞迴 CTE。TimescaleDB 在 orders 是空的時候才載入（約 30 秒）：訂單從 PostgreSQL 複製，商品瀏覽紀錄（191 萬筆）與倉庫感測器讀數（52 萬筆）在 TimescaleDB 裡用雜湊值產生，每次都一樣。pgvector 在 pg-lab 容器裡另開 vectors 資料庫（pg-lab 的映像檔本來就內建 pgvector 0.8），沒有資料時才載入（約 45 秒，大部分是建 HNSW 索引）：商品與購買紀錄從 PostgreSQL 複製，詞庫（迷你嵌入模型 `embed()`）、商品向量、10 萬筆 128 維的索引實驗資料都用固定亂數種子產生。Elasticsearch 在練習用的索引不齊全時才載入（約 25 秒，58 萬份文件）：商品與訂單從 PostgreSQL 複製；評論依購買紀錄用模板產生；API 存取紀錄是 9 月一整個月，埋了兩次事故（9/18 金流逾時、9/25 購物車連不上 Redis）與一次網址掃描。每次啟動都會建立 reader / learner 角色。InfluxDB 在 metrics 少了任何一個 measurement 時才載入（約 15 秒、62 萬個點）：主機 CPU / 記憶體、API 請求計數器、倉庫感測器都是 2026 年 9 月、用固定亂數種子產生（埋了 db-01 滿載、web-02 記憶體洩漏與重啟、web-03 下線、感測器斷線等事件），訂單從 PostgreSQL 複製；每次啟動都會重建 scratch bucket 的 DBRP 對應與 reader / learner token。都可以在頁面上按「重置資料」或「重新載入資料」。

Cassandra 的映像檔預設不需要密碼；`docker-compose.yml` 在啟動前改了設定檔，開啟 `PasswordAuthenticator` 與 `CassandraAuthorizer`，並關閉 TRUNCATE 時的自動快照。

## 資料表關聯

```mermaid
erDiagram
    customers   ||--o{ orders      : "customer_id"
    orders      ||--|{ order_items : "order_id"
    products    ||--o{ order_items : "product_id"
    categories  ||--o{ products    : "category_id"
    categories  ||--o{ categories  : "parent_id（上層分類）"
```

| 資料表 | 筆數 | 說明 |
|---|---:|---|
| customers | 20,000 | 會員 |
| orders | 80,000 | 訂單，2022-01 ~ 2026-09 |
| order_items | 約 200,000 | 訂單明細；小計 = `quantity * unit_price * (1 - discount)` |
| products | 1,500 | 商品；`specs` 是 JSONB 規格、`tags` 是 text[] 標籤 |
| categories | 27 | 三層分類樹，`parent_id` 指向自己這張表 |

## 刻意埋的「陷阱」（練習時會遇到）

- 1,629 位會員從沒下過單 → `LEFT JOIN`、`NOT EXISTS`
- 100 件新商品沒人買過；8 個上層分類底下沒有直接掛商品
- 部分欄位是 NULL：會員的生日 / 城市、最上層分類的 `parent_id`
- `categories.parent_id` 有 NULL → `NOT IN` 會查不到任何資料
- 商品有同價 → `RANK` / `DENSE_RANK` / `ROW_NUMBER` 差異
- 外鍵欄位沒建索引 → 之後用 `EXPLAIN ANALYZE` 比較加索引前後

## 索引實驗室的大表

`perf` schema 有兩張 200 萬筆的大表，不在上面 5 張表裡，專門用來比較各種索引：

- `perf.orders_big`：隨機順序寫入的訂單
- `perf.order_events`：依時間順序寫入的事件紀錄，`meta` 是 JSONB（示範 BRIN、GIN）

## 資料庫角色（最小權限）

| 角色 | 用途 | 權限 |
|---|---|---|
| `lab` | 連線帳號 | 超級使用者，只用來讀結構、管理連線 |
| `learner` | 執行使用者寫的 SQL | public 5 張表可讀寫（寫入沙盒一律 ROLLBACK）、perf 只能讀 |
| `perf_lab` | 索引實驗室建 / 刪索引 | 只擁有 perf schema |

## 展示台 APP

[db-showcase/](db-showcase/) 是 Spring Boot 寫的多資料庫展示台（http://localhost:8081），目前已串接 PostgreSQL、Redis、MongoDB、Cassandra、Neo4j、TimescaleDB、pgvector、Elasticsearch、InfluxDB。

**PostgreSQL**

| 分頁 | 內容 |
|---|---|
| 練習題 | 43 題，只給題目，自己寫 SQL；後端同時執行你的 SQL 與標準答案並比對結果。含 JSONB、Array、generate_series、RETURNING / UPSERT 寫入題 |
| 陷阱題 | 10 題，先猜結果再執行兩段 SQL 對照（LEFT JOIN 的 ON / WHERE、NOT IN 與 NULL、JSONB 文字比較…） |
| 寫入沙盒 | INSERT / UPDATE / DELETE，可以多句一起執行，最後一律 ROLLBACK |
| 索引實驗室 | 11 個步驟：B-tree、複合索引、Partial、Expression、BRIN、GIN，看 EXPLAIN ANALYZE 的變化 |
| 範例與自由查詢 | 示範查詢，或寫任何 SELECT |

**Redis**（資料從 PostgreSQL 轉成 String、Hash、List、Set、Sorted Set、Stream、HyperLogLog、Bitmap、Geo）

| 分頁 | 內容 |
|---|---|
| 練習題 | 26 題，自己寫指令；在隔離的 db 1 分別執行你的指令與標準答案，比對回傳值或執行後的資料狀態 |
| 陷阱題 | 8 題：交易沒有 ROLLBACK、SET 會清掉 TTL、LRANGE 包含結尾、HyperLogLog 是估計值、KEYS 與 SCAN… |
| 實戰實驗室 | Cache-Aside（含快取穿透）、限流、分散式鎖（含錯誤的釋放方式）、庫存超賣、排行榜、Pipeline |
| 指令主控台 | 以 ACL 受限的 learner 帳號執行任何指令 |

**MongoDB**（orders 內嵌明細、products 反正規化分類、customers 省略空欄位、categories 存祖先陣列；指令用 mongosh 寫法）

| 分頁 | 內容 |
|---|---|
| 練習題 | 27 題：查詢、運算子、陣列與內嵌、聚合管線、寫入；查詢題用唯讀帳號批改，寫入題在 scratch 副本比對資料狀態 |
| 陷阱題 | 8 題：陣列等號、$elemMatch、null 會找到沒有欄位的文件、型別不轉換、replaceOne 整份取代、時區… |
| 實驗室 | 內嵌 vs 參照（$lookup 有無索引）、索引與 explain（6 步，含 ESR 規則）、聚合管線逐步看 |
| 指令主控台 | mongosh 寫法：find、aggregate、update…、explain |

**Cassandra**（同一份資料依查詢寫成 9 張表：orders、orders_by_customer、orders_by_day、products_by_category、計數器表…；指令就是 CQL，主控台另外支援 cqlsh 的 `CONSISTENCY` 與 `TRACING`）

| 分頁 | 內容 |
|---|---|
| 練習題 | 28 題：主鍵查詢、叢集鍵與時間分桶、集合與函式、寫入（BATCH、計數器、LWT、TTL、範圍刪除）；查詢題用唯讀角色批改，寫入題在 scratch keyspace 比對資料狀態 |
| 陷阱題 | 11 題：INSERT 是 upsert、ORDER BY 只能用叢集鍵、AVG(int)、舊時間戳記的寫入被忽略、TTL 是設在欄位上、寫 null 會產生墓碑… |
| 實驗室 | 查詢與表設計（查詢追蹤：分區鍵 vs ALLOW FILTERING、SAI 索引）、墓碑（佇列反模式）、一致性等級與 LWT（庫存超賣） |
| cqlsh 主控台 | 用 learner 角色執行 CQL，可以開查詢追蹤 |

**Neo4j**（(:Customer)-[:PLACED]->(:Order)-[:CONTAINS]->(:Product)、分類樹、城市、品牌、會員之間的 FOLLOWS；查詢結果裡的節點與關係會畫成圖）

| 分頁 | 內容 |
|---|---|
| 練習題 | 24 題：基本 MATCH、方向與聚合、路徑與走訪（朋友的朋友、最短路徑、分類樹）、推薦、寫入（CREATE、MERGE、SET、DELETE）；寫入題在交易裡用檢查查詢比對，最後 ROLLBACK |
| 陷阱題 | 12 題：MERGE 整個圖樣、不寫方向、`= null`、OPTIONAL MATCH 的 WHERE、笛卡兒積、關係唯一性漏掉同一張訂單、DELETE 在 commit 時才報錯… |
| 實驗室 | PROFILE 與索引（LabelScan vs IndexSeek、db hits、TEXT / RANGE 索引、超級節點）、圖 vs SQL（推薦、幾步內可到達幾人、最短路徑，與 PostgreSQL 實測比較） |
| Cypher 主控台 | 所有句子在同一個交易裡執行後 ROLLBACK；擋掉 LOAD CSV、CALL dbms.*、使用者與資料庫管理 |

**TimescaleDB**（PostgreSQL 的時序擴充；練習題、陷阱題、寫入沙盒直接共用 PostgreSQL 頁面的程式，只換 API 路徑）

| 分頁 | 內容 |
|---|---|
| 練習題 | 18 題：time_bucket（含時區）、first / last、gapfill / locf、移動平均、連續聚合、chunk、寫入與刪除舊資料 |
| 陷阱題 | 6 題：time_bucket 預設依 UTC、'30 days' 不是一個月、轉型讓 chunk exclusion 失效、gapfill 沒給範圍、連續聚合沒有最新資料、聚合表的 count(*) |
| 寫入沙盒 | 多句 INSERT / UPDATE / DELETE，最後一律 ROLLBACK |
| 實驗室 | chunk exclusion（EXPLAIN ANALYZE 看讀了幾個 chunk）、壓縮（不同 segmentby 的壓縮率與查詢速度）、連續聚合（即時聚合與 refresh） |
| SQL 主控台 | 範例與自由查詢 |

**pgvector**（PostgreSQL 的向量擴充；同樣共用 PostgreSQL 頁面的程式。真正的嵌入模型用 `embed(文字)` 代替：詞庫裡每個詞一個 64 維向量，意思相近的詞向量相近）

| 分頁 | 內容 |
|---|---|
| 練習題 | 20 題：四種距離運算子、相似商品、語意搜尋加一般條件、距離門檻、每類別最符合、LATERAL 找鄰居、k-NN 分類、依購買紀錄推薦、近似重複、多租戶精確搜尋、量化的空間、寫入時產生 / 重算向量 |
| 陷阱題 | 8 題：沒排除自己、`<#>` 是負內積、運算子跟索引不一致、`1 - 距離 DESC` 用不到索引、沒有 LIMIT、過濾後不足 k 筆、模型不認得的文字、平均向量的長度 |
| 寫入沙盒 | 多句 INSERT / UPDATE / DELETE，最後一律 ROLLBACK |
| 實驗室 | 語意 / 關鍵字 / 混合搜尋（RRF）並排比較；精確、HNSW（ef_search）、IVFFlat（lists、probes）的召回率與速度；過濾 + 向量索引（iterative scan）；量化（halfvec、bit + 重排）的索引大小與召回率 |
| SQL 主控台 | 範例與自由查詢 |

**Elasticsearch**（Kibana Dev Tools 寫法：`GET /products/_search` 加上 JSON；批改用 reader，主控台用 learner）

| 分頁 | 內容 |
|---|---|
| 練習題 | 26 題：match / term / bool / range、排序與 _source、_analyze、operator and、match_phrase、multi_match 加權、fuzziness、minimum_should_match、terms / avg / date_histogram / percentiles / cardinality 聚合、找事故與掃描來源、nested 查詢與聚合、寫入（_doc、_update、_update_by_query、_bulk、_delete_by_query，在 scratch_* 副本上比對） |
| 陷阱題 | 11 題：term 查 text 欄位、match 是 OR、standard 分析器搜中文、object 陣列交叉比對、nested 的文件數、hits.total 只算到一萬、date_histogram 沒給時區、對 text 聚合、深分頁、動態 mapping、近即時 |
| 實驗室 | 分析器（_analyze 比較、cjk vs standard 的搜尋結果）、相關性排序（IDF、欄位長度、boost、function_score、filter 不計分，可展開 BM25 的計算過程）、寫入行為（近即時、樂觀鎖、mapping 不能改、刪除只是標記、同義詞、search_after） |
| Dev Tools 主控台 | 範例與自由請求；scratch 開頭的索引可以自己建立、寫入、刪除 |

**InfluxDB**（2.7；每題指定語言：InfluxQL、Flux 或 line protocol；批改用 reader token，主控台用 learner token）

| 分頁 | 內容 |
|---|---|
| 練習題 | 24 題：SHOW TAG VALUES / FIELD KEYS、時間範圍、last / top / percentile、GROUP BY time() + tz() + fill()、子查詢、non_negative_derivative / difference、moving_average、Flux（range、aggregateWindow、pivot + map、group + sort、derivative）、line protocol 寫入（型別、覆蓋） |
| 陷阱題 | 11 題：字串用雙引號、tag 是字串、沒有 tz()、計數器重置、GROUP BY field、只 SELECT tag、LIMIT 是每條 series、Flux 沒有 range、aggregateWindow 的時間在區間結束、同一秒寫兩筆被覆蓋、field 型別衝突 |
| 實驗室 | series 數量（user_id 放 tag vs field：1,000 條 vs 1 條）、InfluxQL vs Flux 六組對照、降低精度（Flux to() 寫回 scratch，點數變 1/60） |
| 主控台 | InfluxQL / Flux / line protocol 三種模式；metrics 唯讀、scratch 可寫；Flux 不能 import 連到外部的套件 |

題目內容放在 `db-showcase/src/main/resources/` 的 `postgres/`、`redis/`、`mongo/`、`cassandra/`、`neo4j/`、`timescale/`、`pgvector/`、`elastic/`、`influx/` YAML 檔，加題目不用改程式。

```bash
cd db-showcase
mvn spring-boot:run
```

## 靜態網站（docs/）

`docs/` 是可以直接雙擊打開、也可以部署到 Render 的靜態網站，不需要資料庫：

| 檔案 | 內容 |
|---|---|
| `docs/index.html` | 首頁 |
| `docs/cheatsheet.html` | CheatSheet：目錄、搜尋、SQL 上色、一鍵複製 |
| `docs/question-bank.html` | 題庫：PostgreSQL、Redis、MongoDB、Cassandra、Neo4j、TimescaleDB、pgvector、InfluxDB、Elasticsearch 各一個分頁，每個分頁有練習題、陷阱題、實驗，附提示、答案與正確答案的實際執行結果 |

改了 `CHEATSHEET.md` 或題庫 YAML 之後，重新產生（展示台有在執行時，會順便更新執行結果）：

```bash
python tools/build_static.py
```

**部署到 Render**：New → Static Site → 選這個 repo，Publish Directory 填 `docs`，Build Command 留空或填 `echo ok`。
或用 New → Blueprint，會自動讀取 `render.yaml`。

## 學習路線

1. JOIN 進階：LEFT / RIGHT / FULL / SELF JOIN、NULL 的處理（`COALESCE`、`IS NULL`）
2. 子查詢與 `IN` / `EXISTS` / `NOT EXISTS`
3. `CASE WHEN`、條件彙總 `FILTER`
4. CTE（`WITH`）
5. 視窗函數：`ROW_NUMBER`、`RANK`、`LAG/LEAD`、累計加總、移動平均
6. 日期時間：`date_trunc`、`EXTRACT`、`interval`、月營收/留存率
7. 遞迴 CTE：組織樹、分類樹
8. 寫入：`INSERT ... ON CONFLICT`、`UPDATE ... FROM`、`DELETE`、`RETURNING`
9. 交易與鎖：`BEGIN/COMMIT/ROLLBACK`、隔離等級
10. 索引與效能：`EXPLAIN ANALYZE`、B-tree、複合索引
11. 設計：正規化、約束、View / Materialized View
