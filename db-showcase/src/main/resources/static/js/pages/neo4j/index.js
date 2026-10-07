// Neo4j 頁面外框：圖的結構（可收合：標籤、關係型別、schema 圖）＋ 四個分頁
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";
import { graphView, labelColor } from "./format.js";

const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫 Cypher，自動批改", load: () => import("./practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("./traps.js") },
  { id: "lab", label: "實驗室", hint: "PROFILE 與索引、圖 vs SQL", load: () => import("./lab.js") },
  { id: "console", label: "Cypher 主控台", hint: "直接下 Cypher，結果畫成圖", load: () => import("./console.js") },
];
const TAB_KEY = "neo4j-tab";
const OPEN_KEY = "neo4j-schema-open";
export const CONSOLE_DRAFT_KEY = "neo4j-console-draft";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:80ch">
          資料來自 PostgreSQL 的 shop 資料庫，轉成節點與關係；追蹤關係（FOLLOWS）是用固定亂數種子產生的模擬社群資料。
          Neo4j 社群版沒有角色權限，所以你輸入的 Cypher 一律在交易裡執行，最後 ROLLBACK，寫入不會留下來。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 Neo4j 中</span></div></div>
      </div>

      <details class="schema" data-ref="schema">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>圖的結構 <small>節點標籤、關係型別、索引</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div class="neo-schema" data-ref="schema-body"></div>
      </details>

      <section class="mode">
        <div class="tabs tabs-4" role="tablist" data-ref="tabs">
          ${TABS.map((t) => `<button type="button" role="tab" data-tab="${t.id}"><b>${esc(t.label)}</b><span>${esc(t.hint)}</span></button>`).join("")}
        </div>
        <div data-ref="tab-body" role="tabpanel"></div>
      </section>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true, unmountTab = null, tabToken = 0, pollTimer = null;

  const schema = $("schema");
  schema.open = store.get(OPEN_KEY, true);
  const syncHint = () => { $("hint").textContent = schema.open ? "點擊收合" : "點擊展開"; };
  syncHint();
  schema.addEventListener("toggle", () => { syncHint(); store.set(OPEN_KEY, schema.open); });

  async function loadOverview() {
    try {
      const o = await api("/api/neo4j/overview");
      if (!alive) return;
      const load = o.load;
      const nodes = o.labels.reduce((s, l) => s + l.count, 0);
      const rels = o.relationships.reduce((s, r) => s + r.count, 0);
      $("stats").innerHTML = `
        <div class="stat"><b>${load.running ? "載入中" : fmt(nodes)}</b><span>${load.running ? `${esc(load.step ?? "")}：${fmt(load.done)} / 約 ${fmt(load.expected)}` : `個節點（${o.labels.length} 種標籤）`}</span></div>
        <div class="stat"><b>${fmt(rels)}</b><span>條關係（${o.relationships.length} 種型別）</span></div>
        <div class="stat"><b>${load.last ? load.last.seconds.toFixed(1) + " 秒" : "已就緒"}</b><span>${load.last ? "這次啟動重新載入的時間" : "資料存在容器的 volume，重啟不必重新載入"}</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset" ${load.running ? "disabled" : ""}>${load.running ? "載入中…" : "重新載入資料"}</button>
          <span>刪掉所有節點與關係，重新從 PostgreSQL 轉入（Neo4j ${esc(o.version)}）</span>
        </div>
        ${load.error ? `<div class="error" style="flex-basis:100%">上次載入失敗：${esc(load.error)}</div>` : ""}`;
      $("reset").addEventListener("click", reset);
      $("schema-body").innerHTML = `
        <div class="neo-schema-graph">
          <h3>Schema 圖 <small class="muted">CALL db.schema.visualization()</small></h3>
          ${graphView(o.schema, { height: 300 })}
        </div>
        <div class="neo-schema-lists">
          <h3>節點標籤</h3>
          <div class="neo-cards">${o.labels.map((l) => `
            <article class="coll-card">
              <header><b class="mono" style="color:${labelColor(l.label)}">:${esc(l.label)}</b><span>${fmt(l.count)} 個</span></header>
              <p>${esc(l.design)}</p>
              <div class="coll-meta">${l.properties.map((p) => `<span class="tag mono">${esc(p)}</span>`).join(" ")}</div>
              <button type="button" class="try mono" data-try="${esc(l.sample)}">${esc(l.sample.length > 54 ? l.sample.slice(0, 52) + "…" : l.sample)}</button>
            </article>`).join("")}</div>
          <h3>關係型別</h3>
          <div class="result"><table><thead><tr><th>型別</th><th>圖樣</th><th class="num">數量</th><th>說明</th></tr></thead><tbody>
            ${o.relationships.map((r) => `<tr><td class="mono"><b>:${esc(r.type)}</b></td><td class="mono">${esc(r.pattern)}</td>
              <td class="num">${fmt(r.count)}</td><td>${esc(r.design)}</td></tr>`).join("")}</tbody></table></div>
          <h3>索引與約束</h3>
          <div class="coll-meta">${o.indexes.map((i) => `<span class="tag mono">${esc(i.name)}（${esc(i.type)}${i.owningConstraint ? "，唯一約束" : ""}）</span>`).join(" ")}</div>
        </div>`;
      clearTimeout(pollTimer);
      if (load.running) pollTimer = setTimeout(loadOverview, 1500);
    } catch (e) {
      if (alive) $("stats").innerHTML = `<div class="error">讀不到 Neo4j：${esc(e.message)}</div>`;
    }
  }

  async function reset() {
    $("reset").disabled = true;
    try { await postJson("/api/neo4j/reset", {}); await loadOverview(); }
    catch (e) { $("reset").textContent = "失敗：" + e.message; }
  }

  $("schema-body").addEventListener("click", (e) => {
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
  return () => { alive = false; clearTimeout(pollTimer); if (unmountTab) unmountTab(); };
}
