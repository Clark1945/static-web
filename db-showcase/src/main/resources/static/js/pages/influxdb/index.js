// InfluxDB 頁面外框：measurement 總覽（可收合）＋ 四個分頁
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";

const TABS = [
  { id: "practice", label: "練習題", hint: "InfluxQL、Flux、line protocol，自動批改", load: () => import("./practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("./traps.js") },
  { id: "lab", label: "實驗室", hint: "cardinality、兩種查詢語言、降低精度", load: () => import("./lab.js") },
  { id: "console", label: "主控台", hint: "範例與自由查詢、寫入", load: () => import("./console.js") },
];
const TAB_KEY = "ifx-tab";
const OPEN_KEY = "ifx-schema-open";
export const CONSOLE_DRAFT_KEY = "ifx-console-draft";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:80ch">
          InfluxDB 是專為時間序列設計的資料庫：每一筆資料（point）= measurement + tags（有索引的描述）+ fields（量測值）+ 時間戳記，
          同一組 tags 的資料是一條 series，依時間存放、壓縮。常用在主機監控、IoT 感測器、應用程式指標。
          這裡是 2.7 版：查詢可以用 InfluxQL（類似 SQL，1.x 起就有）或 Flux（管線式語言），寫入用 line protocol。
          資料都在 2026 年 9 月（訂單從 PostgreSQL 複製）；批改用唯讀 token，主控台的 token 只能寫入 scratch bucket。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 InfluxDB 中</span></div></div>
      </div>

      <details class="schema" data-ref="schema">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>Measurement 與 series <small>tag、field、series 數量</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div data-ref="schema-body" class="ifx-schema"></div>
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
      const o = await api("/api/influx/overview");
      if (!alive) return;
      const load = o.load;
      const series = o.measurements.reduce((s, m) => s + m.series, 0);
      $("stats").innerHTML = `
        <div class="stat"><b>${load.running ? "載入中" : o.measurements.length}</b><span>${load.running ? esc(load.step ?? "") : "個 measurement"}</span></div>
        <div class="stat"><b>${fmt(series)}</b><span>條 series</span></div>
        <div class="stat"><b>${esc(o.version ?? "—")}</b><span>版本</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset" ${load.running ? "disabled" : ""}>${load.running ? "載入中…" : "重新載入資料"}</button>
          <span>刪除 metrics bucket 後重新產生（約 15 秒${load.points ? `，上次寫入 ${fmt(load.points)} 個點` : ""}）</span>
        </div>
        ${load.error ? `<div class="error" style="flex-basis:100%">上次載入失敗：${esc(load.error)}</div>` : ""}`;
      $("reset").addEventListener("click", reset);
      $("schema-body").innerHTML = `<div class="coll-grid">${o.measurements.map((m) => `
          <article class="coll-card">
            <header><b class="mono">${esc(m.name)}</b><span>${fmt(m.series)} 條 series</span></header>
            <p>${esc(m.design)}</p>
            <table class="cols">
              ${m.tags.map((t) => `<tr><td class="mono">${esc(t)}</td><td class="muted">字串</td><td><span class="key-badge pk">tag</span></td></tr>`).join("")}
              ${m.fields.map((f) => { const [n, type] = f.split("（"); return `<tr><td class="mono">${esc(n)}</td><td class="muted mono">${esc((type ?? "").replace("）", ""))}</td><td><span class="key-badge fk">field</span></td></tr>`; }).join("")}
            </table>
            <button type="button" class="try mono" data-try="${esc(m.sample)}">${esc(m.sample.length > 54 ? m.sample.slice(0, 52) + "…" : m.sample)}</button>
          </article>`).join("")}</div>`;
      clearTimeout(pollTimer);
      if (load.running) pollTimer = setTimeout(loadOverview, 2000);
    } catch (e) {
      if (alive) $("stats").innerHTML = `<div class="error">讀不到 InfluxDB：${esc(e.message)}\n請確認 influx-lab 容器有在執行（docker compose up -d influxdb）。</div>`;
    }
  }

  async function reset() {
    $("reset").disabled = true;
    try { await postJson("/api/influx/reset", {}); await loadOverview(); }
    catch (e) { $("reset").textContent = "失敗：" + e.message; }
  }

  $("schema-body").addEventListener("click", (e) => {
    const b = e.target.closest("[data-try]");
    if (!b) return;
    store.set(CONSOLE_DRAFT_KEY, b.dataset.try);
    store.set("ifx-console-lang", "influxql");
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
