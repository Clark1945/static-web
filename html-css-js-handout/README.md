# HTML / CSS / JavaScript 互動式講義

給後端工程師的前端講義：從「看得懂」到「改得動」。單一 HTML 檔，中英雙語，右上角切換。

## 本機預覽

直接用瀏覽器開啟 `index.html` 即可，不需要 server、不需要建置。

`examples/modules/` 是 ES Modules 的多檔案範例，瀏覽器不允許用 `file://` 載入模組，這個資料夾要透過本機伺服器開：

```bash
python3 -m http.server 8000   # 在 html-css-js-handout/ 底下執行，再開 http://localhost:8000/examples/modules/
```

## 內容

| 篇 | 章節 |
|---|---|
| 基礎篇 | 0 先看全貌、1 HTML、2 CSS、3 JavaScript 語言、4 DOM 與事件、5 非同步與瀏覽器 API |
| 進階篇 | 6 HTML 進階、7 CSS 進階、8 JS 核心機制、9 非同步深入、10 事件與模組 |
| 實戰與小抄 | 11 實戰：拆解歷史資料庫（搭配 `history-db/`）、12 小抄與延伸 |

## 功能

- 可直接編輯的「試試看」範例，右邊即時顯示結果；練習題有自動檢查，預測題先選答案再執行驗證。
- 每章有小測驗與「這章讀完了」勾選，進度記在這個瀏覽器的 localStorage。
- 目錄可收合（桌面）或滑出（手機）；目錄每章左邊的 ☆ 可收藏，按「複習收藏」只顯示已收藏的章節。
- 手機版有頂端細列、較大的字級與觸控目標。
- 列印時自動展開所有答案。

## 專案結構

```
index.html          整份講義（樣式、中英兩份內容、腳本都在裡面）
examples/modules/   ES Modules 多檔案範例（含循環依賴 cycle-a.js / cycle-b.js）
```

## 修改時注意

- 講義內容分成 `lang="zh-Hant"` 與 `lang="en"` 兩份，改內容要兩邊一起改。
- 兩份共用同一組章節代號（`data-ch`），進度與收藏以它為準，不要讓兩邊不一致。
- localStorage 的鍵：`html-css-js-handout:lang`（語言）、`:done`（完成進度）、`:toc`（目錄收合）、`:fav`（收藏）。
