# RabbitMQ 實戰講義

給 2～4 年經驗軟體工程師的 RabbitMQ 講義，單一 HTML 檔，只有中文版。範例以 Java + Spring Boot（Spring AMQP）為主。

## 本機預覽

直接用瀏覽器開啟 `index.html` 即可，不需要 server、不需要建置。講義本身不能連 RabbitMQ；想動手跑，第 1 章附有 `docker-compose.yml`，起一個 `rabbitmq:4-management` 就能對照管理介面（http://localhost:15672）操作。

## 內容（共 11 章）

| 篇 | 章 | 主題 |
|---|---|---|
| 基礎篇 | 0 | 先看全貌：為什麼要 MQ、核心模型、與 Kafka / Redis 的比較 |
| | 1 | 快速上手：Docker Compose、管理介面、Spring Boot 第一個範例 |
| | 2 | Exchange 與路由：四種類型、topic 萬用字元、拓撲宣告 |
| | 3 | Queue 類型與屬性：Classic / Quorum / Stream、TTL、Policy |
| 實作篇 | 4 | 訊息可靠性：Publisher confirm、persistent、ack、prefetch、至少一次語意 |
| | 5 | Spring AMQP 實戰：連線管理、JSON、消費端設定、冪等、順序性 |
| | 6 | 死信（DLX）、重試策略、延遲訊息、毒藥訊息 |
| | 7 | 常見模式：Work Queue、Pub/Sub、RPC、Outbox、訊息設計 |
| 營運篇 | 8 | 叢集與高可用：Quorum 多數決、網路分區、Federation / Shovel |
| | 9 | 營運、監控與排錯：指標、流量控制、症狀排錯表、安全、調校 |
| | 10 | 面試題（12 題）與小抄 |

版本基準：RabbitMQ 4.x（AMQP 0-9-1）、Spring Boot 3.x、Java 17+。

## 功能

- 每章有小測驗與「這章讀完了」勾選，進度記在這個瀏覽器的 localStorage。
- 目錄可收合（桌面）或滑出（手機）；目錄每章左邊的 ☆ 可收藏，按「複習收藏」只顯示已收藏的章節。
- 程式碼區塊有複製按鈕；第 4～6 章有練習題與可展開的參考答案。
- 深色模式、手機版、列印時自動展開所有答案。
- localStorage 的鍵：`rabbitmq-handout:done`（完成進度）、`:fav`（收藏）、`:toc`（目錄收合）。

## 修改時注意

- 程式碼區塊在 `<pre class="code"><code>` 裡，`<`、`>`、`&` 要寫成 `&lt;`、`&gt;`、`&amp;`。
- 新增章節要同時改：目錄的 `<li data-ch>`、章節的 `<section id data-ch>`、進度條的 `aria-valuemax`。
