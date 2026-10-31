# static-web

幾個互相獨立的小專案放在同一個 repo。每個專案一個資料夾，各自有自己的說明、各自能單獨執行或部署，彼此不共用程式碼。

| 資料夾 | 是什麼 | 技術 | 怎麼開 |
|---|---|---|---|
| [`history-db/`](history-db/) | 全球歷史資料庫：橫軸是國家、縱軸是年份的時間軸，目前收錄台灣 1603～2024 | 純 HTML / CSS / JS（靜態） | 直接雙擊 `index.html` |
| [`html-css-js-handout/`](html-css-js-handout/) | HTML / CSS / JavaScript 互動式講義（基礎篇 + 進階篇，共 13 章），中英雙語，右上角切換；有可編輯的即時範例、預測題與小測驗，目錄可收合、可收藏章節集中複習，手機也好讀 | 單一 HTML 檔 | 直接雙擊 `index.html` |
| [`db-lab/`](db-lab/) | 資料庫面試練習庫：用模擬電商資料練習 PostgreSQL、Redis、MongoDB 等九種資料庫，含 Spring Boot 展示台與中英日三語靜態網站 | Docker、Spring Boot、靜態網站 | 見 [`db-lab/README.md`](db-lab/README.md) |
| [`vue3-handout/`](vue3-handout/) | Vue 3 實戰講義（給 3～4 年經驗工程師）：Vite、Composition API、響應式原理、Pinia、Nuxt 3（SSR、Nitro、useFetch），附小測驗、練習題與面試題 | 單一 HTML 檔 | 直接雙擊 `index.html` |
| [`rabbitmq-handout/`](rabbitmq-handout/) | RabbitMQ 實戰講義（給 2～4 年經驗工程師）：Exchange 路由、Queue 類型、訊息可靠性、Spring AMQP、DLX 與重試、叢集與營運，附小測驗、練習題與面試題 | 單一 HTML 檔 | 直接雙擊 `index.html` |

## 部署

| 專案 | 平台 | 設定 |
|---|---|---|
| `db-lab/docs/` | Render | 根目錄的 [`render.yaml`](render.yaml)（Render 只讀 repo 根目錄，所以這個檔案不能搬進子資料夾） |
| `history-db/` | Cloudflare Pages | Framework preset 選 None、Build command 留空、Build output directory 填 `history-db` |
| `html-css-js-handout/`、`vue3-handout/`、`rabbitmq-handout/` | 任何靜態空間 | 不需要建置，整個資料夾上傳即可 |

## 新增專案

1. 在根目錄建一個新資料夾，資料夾名稱用小寫英文與連字號。
2. 資料夾裡放自己的 `README.md`，寫清楚怎麼執行。
3. 回到這份 README 的表格加一列。
4. 如果專案需要特別的忽略規則，優先寫在該資料夾自己的 `.gitignore`，不要擠進根目錄。
