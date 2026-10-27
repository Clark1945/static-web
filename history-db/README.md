# 全球歷史資料庫

純 HTML / CSS / JS 的靜態網站。橫軸是國家，縱軸是年份，往下捲動就是時間往後走。
目前收錄台灣 1603 年至今的關鍵事件，程式碼已為其他國家預留擴充空間。

## 本機預覽

直接用瀏覽器開啟 `index.html` 即可（資料用 `<script>` 載入，不需要 server）。
想模擬正式環境也可以：

```bash
python3 -m http.server 8000   # 然後開 http://localhost:8000
```

## 專案結構

```
index.html          頁面骨架與 script 載入順序
css/style.css       樣式（含深色模式、手機版）
js/registry.js      HistoryDB 註冊表
js/app.js           時間軸渲染、搜尋、篩選、年代跳轉
data/taiwan.js      台灣資料（每個國家一個檔案）
scripts/validate.mjs  資料格式驗證
```

## 資料格式

每個國家一個 `data/<id>.js`，呼叫 `HistoryDB.register({...})`。

**國家**

| 欄位 | 說明 |
|---|---|
| `id` | 小寫英數與連字號，全站唯一 |
| `name` / `nameEn` | 欄位標題（`nameEn` 可省略） |
| `color` | `#rrggbb`，用於該國的時間軸線、圓點與時代標籤 |
| `eras` | 時代分期陣列，每個國家自訂（日本可用繩文、彌生，中國可用朝代） |
| `events` | 事件陣列 |

**時代** `{ id, name, start, end }`：`end` 為 `null` 代表延續到現在。

**事件**

| 欄位 | 必填 | 說明 |
|---|---|---|
| `id` | 是 | 全站唯一，建議加國家前綴，例如 `tw-1895-cession`。也是網址 hash，可分享單一事件 |
| `year` | 是 | 整數西元年，**西元前用負數**（沒有 0 年）。決定排序與所在列 |
| `yearEnd` | | 事件橫跨多年時使用，卡片上會顯示「1786～1788」 |
| `yearLabel` | | 覆寫顯示文字，例如 `"1720 年代"` |
| `precision` | | `exact`（預設）、`approx`（約略）、`disputed`（說法不一） |
| `era` | 建議 | 對應 `eras[].id`；該時代的第一個事件上方會出現時代標籤 |
| `major` | | `true` 為重大節點：卡片加粗，年份刻度變成黃銅色 |
| `title` / `summary` | 是 | 標題與一兩句話的簡述 |
| `wiki` | | 維基百科條目名稱，連到維基搜尋；名稱完全符合時直接跳到條目 |
| `links` | | 額外連結 `[{ label, url }]`，`url` 必須是 https |

同一年有多個事件時，會依資料檔順序排在同一列。不同國家的事件依年份對齊在同一橫列。

## 新增一個國家

1. 複製 `data/taiwan.js` 為 `data/japan.js`，改 `id`、`name`、`color`、`eras`、`events`。
2. 在 `index.html` 的 `data/taiwan.js` 下方加一行：
   ```html
   <script defer src="data/japan.js"></script>
   ```
3. 執行 `node scripts/validate.mjs` 檢查格式。

有兩個以上國家時，頂部會自動出現國家篩選按鈕，表頭會多一欄。欄位太多時，時間軸會左右捲動，年份欄固定在左側。

## 部署到 Cloudflare Pages

這是純靜態網站，沒有 build 步驟。

- **Git 整合**：Framework preset 選 `None`，Build command 留空，Build output directory 填 `/`（專案在 repo 子資料夾時填該資料夾名稱）。
- **直接上傳**：`npx wrangler pages deploy . --project-name history-db`

## 關於維基百科連結

條目名稱是人工填寫的。連結走維基的搜尋入口，所以名稱不精確時會顯示搜尋結果，
而不是 404。之後想換成精確網址，可以逐條改用 `links` 欄位。
