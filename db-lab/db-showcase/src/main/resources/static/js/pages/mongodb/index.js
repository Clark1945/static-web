// MongoDB 頁面外框：集合總覽（可收合，含設計說明與範例文件）＋ 四個分頁
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";
import { formatValue } from "./format.js";

const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫查詢，自動批改", load: () => import("./practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("./traps.js") },
  { id: "lab", label: "實驗室", hint: "內嵌 vs 參照、索引、聚合管線", load: () => import("./lab.js") },
  { id: "console", label: "指令主控台", hint: "mongosh 寫法，直接下指令", load: () => import("./console.js") },
];
const TAB_KEY = "mongo-tab";
const OPEN_KEY = "mongo-colls-open";
export const CONSOLE_DRAFT_KEY = "mongo-console-draft";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:80ch">
          資料來自 PostgreSQL 的 shop 資料庫，轉成文件時刻意做了不同的設計取捨（內嵌、反正規化、省略欄位）。
          指令用 mongosh 的寫法；你輸入的指令一律用權限受限的帳號執行，伺服器端 JavaScript（$where）已關閉。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 MongoDB 中</span></div></div>
      </div>

      <details class="schema" data-ref="colls">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>資料結構 <small>4 個集合的文件長什麼樣子、為什麼這樣設計</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div class="coll-grid" data-ref="coll-grid"></div>
      </details>

      <section class="mode">
        <div class="tabs tabs-4" role="tablist" data-ref="tabs">
          ${TABS.map((t) => `<button type="button" role="tab" data-tab="${t.id}"><b>${esc(t.label)}</b><span>${esc(t.hint)}</span></button>`).join("")}
        </div>
        <div data-ref="tab-body" role="tabpanel"></div>
      </section>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true;
  let unmountTab = null;
  let tabToken = 0;

  const colls = $("colls");
  colls.open = store.get(OPEN_KEY, true);
  const syncHint = () => { $("hint").textContent = colls.open ? "點擊收合" : "點擊展開"; };
  syncHint();
  colls.addEventListener("toggle", () => { syncHint(); store.set(OPEN_KEY, colls.open); });

  const kb = (b) => (b >= 1048576 ? (b / 1048576).toFixed(1) + " MB" : Math.round(b / 1024) + " KB");

  async function loadOverview() {
    try {
      const o = await api("/api/mongo/overview");
      if (!alive) return;
      const docs = o.collections.reduce((s, c) => s + c.count, 0);
      $("stats").innerHTML = `
        <div class="stat"><b>${o.collections.length}</b><span>個集合</span></div>
        <div class="stat"><b>${fmt(docs)}</b><span>份文件</span></div>
        <div class="stat"><b>${kb(o.collections.reduce((s, c) => s + c.size, 0))}</b><span>資料大小（未壓縮）</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset">重置資料</button>
          <span>刪掉 shop 資料庫，重新從 PostgreSQL 轉入（MongoDB ${esc(o.version)}）</span>
        </div>`;
      $("reset").addEventListener("click", reset);
      $("coll-grid").innerHTML = o.collections.map((c) => `
        <article class="coll-card">
          <header><b class="mono">${esc(c.name)}</b><span>${fmt(c.count)} 份 · 平均 ${fmt(c.avgObjSize)} bytes</span></header>
          <p>${esc(c.design)}</p>
          <div class="coll-meta">索引：${c.indexes.map((i) => `<span class="tag mono">${esc(i)}</span>`).join(" ")}</div>
          <details><summary>範例文件</summary><pre class="cli mongo">${formatValue(c.sample)}</pre></details>
          <button type="button" class="try mono" data-try="db.${esc(c.name)}.findOne({ _id: ${esc(String(c.sample?._id ?? 1))} })">db.${esc(c.name)}.findOne(…)</button>
        </article>`).join("");
    } catch (e) {
      if (alive) $("stats").innerHTML = `<div class="error">讀不到 MongoDB：${esc(e.message)}\n請確認 mongo-lab 容器有在執行（docker compose up -d mongo）。</div>`;
    }
  }

  async function reset() {
    const btn = $("reset");
    btn.disabled = true;
    btn.textContent = "重新載入中（約 3 秒）…";
    try { await postJson("/api/mongo/reset", {}); await loadOverview(); }
    catch (e) { btn.textContent = "失敗：" + e.message; }
  }

  $("coll-grid").addEventListener("click", (e) => {
    const b = e.target.closest("[data-try]");
    if (!b) return;
    store.set(CONSOLE_DRAFT_KEY, b.dataset.try);
    openTab("console");
    el.querySelector(".mode").scrollIntoView({ behavior: "smooth" });
  });

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

  loadOverview();
  openTab(store.get(TAB_KEY, "practice"));
  return () => { alive = false; if (unmountTab) unmountTab(); };
}
