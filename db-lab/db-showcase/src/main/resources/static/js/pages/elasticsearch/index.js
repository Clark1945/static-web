// Elasticsearch 頁面外框：索引總覽（可收合）＋ 四個分頁
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";

const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫 Query DSL，自動批改", load: () => import("./practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("./traps.js") },
  { id: "lab", label: "實驗室", hint: "分析器、相關性、寫入行為", load: () => import("./lab.js") },
  { id: "console", label: "Dev Tools 主控台", hint: "範例與自由請求", load: () => import("./console.js") },
];
const TAB_KEY = "es-tab";
const OPEN_KEY = "es-schema-open";
export const CONSOLE_DRAFT_KEY = "es-console-draft";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:80ch">
          Elasticsearch 把文件的每個詞建成倒排索引（詞 → 哪些文件有它），全文檢索、相關性排序（BM25）、即時聚合都很快，
          常用在商品搜尋、站內搜尋、日誌分析（ELK）。所有操作都是 REST API + JSON；這裡用 Kibana Dev Tools 的寫法。
          資料：商品與訂單從 PostgreSQL 複製；評論與 API 存取紀錄用固定規則產生。批改用唯讀的 reader 帳號，主控台用 learner（只能寫 scratch* 索引）。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 Elasticsearch 中</span></div></div>
      </div>

      <details class="schema" data-ref="schema">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>索引與 mapping <small>欄位型別、分析器、文件數</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div data-ref="schema-body" class="es-schema"></div>
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

  const badge = (type) => type === "text" ? '<span class="key-badge pk">全文</span>' : type === "nested" ? '<span class="key-badge fk">nested</span>' : "";

  async function loadOverview() {
    try {
      const o = await api("/api/elastic/overview");
      if (!alive) return;
      const load = o.load;
      const docs = o.indices.reduce((s, i) => s + i.docs, 0);
      $("stats").innerHTML = `
        <div class="stat"><b>${load.running ? "載入中" : fmt(docs)}</b><span>${load.running ? esc(load.step ?? "") : `份文件（${o.indices.length} 個索引）`}</span></div>
        <div class="stat"><b>${esc(o.version ?? "—")}</b><span>版本（單節點）</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset" ${load.running ? "disabled" : ""}>${load.running ? "載入中…" : "重新載入資料"}</button>
          <span>刪除練習用的索引後重新建立（約 25 秒）</span>
        </div>
        ${load.error ? `<div class="error" style="flex-basis:100%">上次載入失敗：${esc(load.error)}</div>` : ""}`;
      $("reset").addEventListener("click", reset);
      $("schema-body").innerHTML = `<div class="coll-grid">${o.indices.map((i) => `
          <article class="coll-card">
            <header><b class="mono">${esc(i.name)}</b><span>${fmt(i.docs)} 份 · ${esc(i.size)}</span></header>
            <p>${esc(i.design)}</p>
            <table class="cols">${i.fields.map((f) => `<tr><td class="mono">${esc(f.name)}</td><td class="mono muted">${esc(f.type)}</td>
              <td>${badge(f.type)}${f.detail ? `<div class="muted es-detail">${esc(f.detail)}</div>` : ""}</td></tr>`).join("")}</table>
            <button type="button" class="try mono" data-try="${esc(i.sample)}">${esc(i.sample.split("\n")[0])}</button>
          </article>`).join("")}</div>`;
      clearTimeout(pollTimer);
      if (load.running) pollTimer = setTimeout(loadOverview, 2000);
    } catch (e) {
      if (alive) $("stats").innerHTML = `<div class="error">讀不到 Elasticsearch：${esc(e.message)}\n請確認 es-lab 容器有在執行（docker compose up -d elasticsearch）。</div>`;
    }
  }

  async function reset() {
    $("reset").disabled = true;
    try { await postJson("/api/elastic/reset", {}); await loadOverview(); }
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
