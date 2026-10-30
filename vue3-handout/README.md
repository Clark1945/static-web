# Vue 3 / Vite / Pinia / Nuxt 3 實戰講義

給 3～4 年經驗工程師的 Vue 3 講義，單一 HTML 檔，只有中文版。

## 本機預覽

直接用瀏覽器開啟 `index.html` 即可，不需要 server、不需要建置。講義本身不能執行 Vue 程式碼；想動手跑，請在本機 `npm create vue@latest`，或貼到 StackBlitz / Vue SFC Playground。

## 內容（共 10 章）

| 章 | 主題 |
|---|---|
| 0 | Vue、Vite、Pinia、Nuxt 各管什麼，CSR / SSR / SSG 比較 |
| 1 | Vite：開發與建置機制、proxy、環境變數、子路徑部署 |
| 2 | 單檔元件與模板語法、`key` 的作用、Vue 2 到 3 的差異 |
| 3 | 響應式系統：`ref` / `reactive` / `computed` / `watch` / `nextTick` |
| 4 | 元件設計：props、emit、`defineModel`、插槽、provide / inject、生命週期 |
| 5 | Composable 與 Vue Router |
| 6 | Pinia：兩種寫法、`storeToRefs`、持久化、測試 |
| 7 | Nuxt 3：`useFetch`、`server/api`、`runtimeConfig`、`routeRules`、hydration 問題 |
| 8 | 測試、效能優化、TypeScript、常見陷阱 |
| 9 | 面試題、Vue 2 到 3 對照表、指令小抄 |

版本基準：Vue 3.5、Vite 5 以上、Pinia 2 以上、Nuxt 3（附 Nuxt 4 差異說明）。

## 功能

- 每章有小測驗與「這章讀完了」勾選，進度記在這個瀏覽器的 localStorage（鍵：`vue3-handout:done`）。
- 程式碼區塊有複製按鈕；章節有練習題與可展開的參考答案。
- 深色模式、手機版、列印時自動展開所有答案。

## 還沒有的功能

與 `html-css-js-handout/` 相比，這份講義還沒有：中英雙語切換、可收合的目錄、章節收藏與集中複習、手機抽屜式目錄。
