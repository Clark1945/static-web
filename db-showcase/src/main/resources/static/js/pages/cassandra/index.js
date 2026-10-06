// Cassandra 頁面外框：資料表總覽（可收合，每張表回答哪個查詢、主鍵怎麼設計）＋ 四個分頁
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";

const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫 CQL，自動批改", load: () => import("./practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("./traps.js") },
  { id: "lab", label: "實驗室", hint: "表設計、墓碑、一致性與 LWT", load: () => import("./lab.js") },
  { id: "console", label: "cqlsh 主控台", hint: "直接下 CQL，可開查詢追蹤", load: () => import("./console.js") },
];
const TAB_KEY = "cassandra-tab";
const OPEN_KEY = "cassandra-tables-open";
export const CONSOLE_DRAFT_KEY = "cassandra-console-draft";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:80ch">
          資料來自 PostgreSQL 的 shop 資料庫。Cassandra 的設計是「先想好怎麼查，再為每一種查詢建一張表」：
          同一筆訂單寫進 orders、orders_by_customer、orders_by_day 三張表。你輸入的 CQL 一律用權限受限的角色執行。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 Cassandra 中</span></div></div>
      </div>

      <details class="schema" data-ref="tables">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>資料表 <small>9 張表、每張表回答一種查詢</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div class="coll-grid" data-ref="grid"></div>
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
  let pollTimer = null;

  const tables = $("tables");
  tables.open = store.get(OPEN_KEY, true);
  const syncHint = () => { $("hint").textContent = tables.open ? "點擊收合" : "點擊展開"; };
  syncHint();
  tables.addEventListener("toggle", () => { syncHint(); store.set(OPEN_KEY, tables.open); });

  const keyBadge = (role) => !role ? "" : role.startsWith("分區") ? '<span class="key-badge pk">分區鍵</span>'
    : `<span class="key-badge ck">${esc(role.replace("叢集鍵 ", "叢集 "))}</span>`;

  async function loadOverview() {
    try {
      const o = await api("/api/cassandra/overview");
      if (!alive) return;
      const load = o.load;
      const total = o.tables.reduce((s, t) => s + (t.rows ?? 0), 0);
      $("stats").innerHTML = `
        <div class="stat"><b>${o.tables.length}</b><span>張表（keyspace shop）</span></div>
        <div class="stat"><b>${load.running ? "載入中" : fmt(total)}</b><span>${load.running ? `${fmt(load.written)} / 約 ${fmt(load.expected)} 次寫入` : "列（各表加總）"}</span></div>
        <div class="stat"><b>${load.last ? load.last.seconds.toFixed(1) + " 秒" : "已就緒"}</b><span>${load.last ? `載入了 ${fmt(load.last.writes)} 次寫入` : "資料存在容器的 volume，重啟不必重新載入"}</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset" ${load.running ? "disabled" : ""}>${load.running ? "載入中…" : "重新載入資料"}</button>
          <span>清空 shop 的 9 張表，重新從 PostgreSQL 轉入（Cassandra ${esc(o.version)}）</span>
        </div>
        ${load.error ? `<div class="error" style="flex-basis:100%">上次載入失敗：${esc(load.error)}</div>` : ""}`;
      $("reset").addEventListener("click", reset);
      $("grid").innerHTML = o.tables.map((t) => `
        <article class="coll-card">
          <header><b class="mono">${esc(t.name)}</b><span>${t.rows == null ? "載入中" : fmt(t.rows) + " 列"}</span></header>
          <p class="answers">回答：<b>${esc(t.query)}</b></p>
          <p>${esc(t.design)}</p>
          <table class="cols">${t.columns.map((c) => `<tr><td class="mono">${esc(c.name)}</td><td class="mono muted">${esc(c.type)}</td><td>${keyBadge(c.role)}</td></tr>`).join("")}</table>
          <details><summary>CREATE TABLE</summary><pre class="code">${esc(t.createStatement)}</pre></details>
          <button type="button" class="try mono" data-try="${esc(t.sample)}">${esc(t.sample.length > 54 ? t.sample.slice(0, 52) + "…" : t.sample)}</button>
        </article>`).join("");
      clearTimeout(pollTimer);
      if (load.running) pollTimer = setTimeout(loadOverview, 1500);
    } catch (e) {
      if (alive) $("stats").innerHTML = `<div class="error">讀不到 Cassandra：${esc(e.message)}</div>`;
    }
  }

  async function reset() {
    $("reset").disabled = true;
    try { await postJson("/api/cassandra/reset", {}); await loadOverview(); }
    catch (e) { $("reset").textContent = "失敗：" + e.message; }
  }

  $("grid").addEventListener("click", (e) => {
    const b = e.target.closest("[data-try]");
    if (!b) return;
    store.set(CONSOLE_DRAFT_KEY, b.dataset.try + ";");
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
  return () => { alive = false; clearTimeout(pollTimer); if (unmountTab) unmountTab(); };
}
