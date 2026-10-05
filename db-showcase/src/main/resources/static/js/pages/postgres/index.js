// PostgreSQL 頁面外框：標題、統計、可收合的 ER 圖，下方用分頁切換四種模式
import { api, fmt, esc, pageHead, store } from "../../lib.js";
import { renderER } from "./er.js";

const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫 SQL，自動批改", load: () => import("./practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("./traps.js") },
  { id: "sandbox", label: "寫入沙盒", hint: "INSERT / UPDATE / DELETE，自動還原", load: () => import("./sandbox.js") },
  { id: "lab", label: "索引實驗室", hint: "兩張 200 萬筆的表，比較各種索引", load: () => import("./lab.js") },
  { id: "playground", label: "範例與自由查詢", hint: "看範例，或寫任何 SELECT", load: () => import("./playground.js") },
];
const TAB_KEY = "pg-tab";
const SCHEMA_KEY = "pg-schema-open";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取資料庫中</span></div></div>
      </div>

      <details class="schema" data-ref="schema">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>資料結構 <small>ER 圖：每張表的欄位、筆數與關聯</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div class="er-wrap"><svg data-ref="er" role="img" aria-label="資料庫 ER 圖"></svg></div>
        <div class="legend">
          <span><b class="pk">PK</b> 主鍵</span>
          <span><b class="fk">FK</b> 外鍵</span>
          <span><b>?</b> 可為 NULL</span>
          <span>連線：<b>‖</b> 那端是「一」，<b>⋔</b> 那端是「多」</span>
        </div>
      </details>

      <section class="mode">
        <div class="tabs" role="tablist" data-ref="tabs">
          ${TABS.map((t) => `
            <button type="button" role="tab" data-tab="${t.id}">
              <b>${esc(t.label)}</b><span>${esc(t.hint)}</span>
            </button>`).join("")}
        </div>
        <div data-ref="tab-body" role="tabpanel"></div>
      </section>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true;
  let unmountTab = null;
  let tabToken = 0;

  // ---------- 可收合的資料結構 ----------
  const schema = $("schema");
  schema.open = store.get(SCHEMA_KEY, true);
  const syncHint = () => { $("hint").textContent = schema.open ? "點擊收合" : "點擊展開"; };
  syncHint();
  schema.addEventListener("toggle", () => { syncHint(); store.set(SCHEMA_KEY, schema.open); });

  api("/api/postgres/schema").then((tables) => {
    if (!alive) return;
    const total = tables.reduce((s, t) => s + t.rowCount, 0);
    const biggest = tables.reduce((a, b) => (b.rowCount > a.rowCount ? b : a));
    const fkCount = tables.reduce((s, t) => s + t.columns.filter((c) => c.references).length, 0);
    $("stats").innerHTML = `
      <div class="stat"><b>${tables.length}</b><span>資料表</span></div>
      <div class="stat"><b>${fmt(total)}</b><span>總筆數</span></div>
      <div class="stat"><b>${fkCount}</b><span>外鍵關聯</span></div>
      <div class="stat"><b class="mono" style="font-size:1.1rem">${esc(biggest.name)}</b><span>最大的表（${fmt(biggest.rowCount)} 筆）</span></div>`;
    $("er").innerHTML = renderER(tables, $("er"));
  }).catch((e) => {
    if (!alive) return;
    $("stats").innerHTML = `<div class="error">讀不到資料庫：${esc(e.message)}\n請確認 pg-lab 容器有在執行（docker compose up -d）。</div>`;
  });

  // ---------- 分頁 ----------
  async function openTab(id) {
    const tab = TABS.find((t) => t.id === id) ?? TABS[0];
    store.set(TAB_KEY, tab.id);
    el.querySelectorAll("[data-tab]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === tab.id)));
    if (unmountTab) { unmountTab(); unmountTab = null; }
    const token = ++tabToken;
    $("tab-body").innerHTML = '<div class="empty">載入中…</div>';
    const mod = await tab.load();
    if (!alive || token !== tabToken) return;
    unmountTab = mod.mount($("tab-body")) ?? null;
  }

  $("tabs").addEventListener("click", (e) => {
    const b = e.target.closest("[data-tab]");
    if (b) openTab(b.dataset.tab);
  });
  openTab(store.get(TAB_KEY, "practice"));

  return () => {
    alive = false;
    if (unmountTab) unmountTab();
  };
}
