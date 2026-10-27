// TimescaleDB 頁面外框：hypertable 與連續聚合總覽（可收合）＋ 五個分頁
// TimescaleDB 就是 PostgreSQL，練習題、陷阱題、寫入沙盒直接共用 PostgreSQL 頁面的模組，只換 API 路徑
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";

const OPTS = { api: "/api/timescale", prefix: "ts" };
const SANDBOX_EXAMPLES = [
  { label: "寫入一筆讀數", sql: `INSERT INTO sensor_readings (time, sensor_id, temperature, humidity)
VALUES ('2026-10-01 00:00+08', 2, 4.2, 85.0)
RETURNING *;

-- 時間超出現有範圍，TimescaleDB 自動建立新的 chunk（只在這個交易裡）
SELECT count(*) AS chunks FROM show_chunks('sensor_readings');` },
  { label: "刪除一天的資料", sql: `WITH removed AS (
  DELETE FROM sensor_readings
  WHERE time >= '2026-09-01 00:00+08' AND time < '2026-09-02 00:00+08'
  RETURNING 1
)
SELECT count(*) AS removed FROM removed;` },
  { label: "UPDATE（帶上時間條件）", sql: `-- 帶上時間條件，TimescaleDB 只需要找一個 chunk
UPDATE orders SET status = 'returned'
WHERE order_id = 77621
  AND order_time >= '2026-09-01 00:00+08' AND order_time < '2026-10-01 00:00+08'
RETURNING order_id, order_time, status;` },
];
const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫 SQL，自動批改", load: () => import("../postgres/practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("../postgres/traps.js") },
  { id: "sandbox", label: "寫入沙盒", hint: "INSERT / UPDATE / DELETE，自動還原", load: () => import("../postgres/sandbox.js"), opts: { examples: SANDBOX_EXAMPLES } },
  { id: "lab", label: "實驗室", hint: "chunk、壓縮、連續聚合", load: () => import("./lab.js") },
  { id: "console", label: "SQL 主控台", hint: "範例與自由查詢", load: () => import("./console.js") },
];
const TAB_KEY = "ts-tab";
const OPEN_KEY = "ts-schema-open";
export const CONSOLE_DRAFT_KEY = "ts-console-draft";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:80ch">
          TimescaleDB 是 PostgreSQL 的擴充：照樣寫 SQL，但表會依時間自動切成很多個 chunk（hypertable），另外有 time_bucket、
          連續聚合、壓縮、資料保留政策。資料：訂單從 PostgreSQL 複製；商品瀏覽紀錄與倉庫感測器是用固定規則產生的。
          你輸入的 SQL 一律用權限受限的 learner 角色執行，寫入一律 ROLLBACK。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 TimescaleDB 中</span></div></div>
      </div>

      <details class="schema" data-ref="schema">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>Hypertable 與連續聚合 <small>每張表切成幾個 chunk、時間範圍、大小</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div data-ref="schema-body" class="ts-schema"></div>
      </details>

      <section class="mode">
        <div class="tabs tabs-5" role="tablist" data-ref="tabs">
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
      const o = await api("/api/timescale/overview");
      if (!alive) return;
      const load = o.load;
      const rows = o.hypertables.reduce((s, h) => s + h.approxRows, 0);
      const chunks = o.hypertables.reduce((s, h) => s + h.chunks, 0);
      $("stats").innerHTML = `
        <div class="stat"><b>${load.running ? "載入中" : fmt(rows)}</b><span>${load.running ? esc(load.step ?? "") : `筆（${o.hypertables.length} 個 hypertable）`}</span></div>
        <div class="stat"><b>${fmt(chunks)}</b><span>個 chunk</span></div>
        <div class="stat"><b>${o.aggregates.length}</b><span>個連續聚合</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset" ${load.running ? "disabled" : ""}>${load.running ? "載入中…" : "重新載入資料"}</button>
          <span>清空後重新產生（約 30 秒，TimescaleDB ${esc(o.version)}）</span>
        </div>
        ${load.error ? `<div class="error" style="flex-basis:100%">上次載入失敗：${esc(load.error)}</div>` : ""}`;
      $("reset").addEventListener("click", reset);
      $("schema-body").innerHTML = `
        <div class="coll-grid">${o.hypertables.map((h) => `
          <article class="coll-card">
            <header><b class="mono">${esc(h.name)}</b><span>${fmt(h.approxRows)} 筆 · ${esc(h.size)}</span></header>
            <p>${esc(h.design)}</p>
            <div class="ts-chunks"><span>時間欄位 <b class="mono">${esc(h.timeColumn)}</b></span>
              <span>每 <b>${esc(h.chunkInterval)}</b> 一個 chunk，共 <b>${fmt(h.chunks)}</b> 個</span>
              <span>${esc(h.from)} ～ ${esc(h.to)}</span></div>
            <table class="cols">${h.columns.map((c) => `<tr><td class="mono">${esc(c.name)}</td><td class="mono muted">${esc(c.type)}</td>
              <td>${c.name === h.timeColumn ? '<span class="key-badge pk">時間軸</span>' : ""}</td></tr>`).join("")}</table>
            <button type="button" class="try mono" data-try="${esc(h.sample)}">${esc(h.sample.length > 54 ? h.sample.slice(0, 52) + "…" : h.sample)}</button>
          </article>`).join("")}
        ${o.aggregates.map((a) => `
          <article class="coll-card">
            <header><b class="mono">${esc(a.name)}</b><span>連續聚合 · ${esc(a.size)}</span></header>
            <p>已物化到 <b>${esc(a.watermark ?? "—")}</b>；${a.materializedOnly ? "只回傳已物化的資料（materialized_only）" : "即時聚合：未物化的部分從原始資料即時計算"}</p>
            <details><summary>定義</summary><pre class="code">${esc(a.definition)}</pre></details>
            <button type="button" class="try mono" data-try="${esc(a.sample)}">${esc(a.sample)}</button>
          </article>`).join("")}</div>`;
      clearTimeout(pollTimer);
      if (load.running) pollTimer = setTimeout(loadOverview, 2000);
    } catch (e) {
      if (alive) $("stats").innerHTML = `<div class="error">讀不到 TimescaleDB：${esc(e.message)}\n請確認 timescale-lab 容器有在執行（docker compose up -d timescale）。</div>`;
    }
  }

  async function reset() {
    $("reset").disabled = true;
    try { await postJson("/api/timescale/reset", {}); await loadOverview(); }
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
    unmountTab = mod.mount($("tab-body"), { ...OPTS, ...(tab.opts ?? {}) }) ?? null;
  }
  $("tabs").addEventListener("click", (e) => {
    const b = e.target.closest("[data-tab]");
    if (b) openTab(b.dataset.tab);
  });

  loadOverview();
  openTab(store.get(TAB_KEY, "practice"));
  return () => { alive = false; clearTimeout(pollTimer); if (unmountTab) unmountTab(); };
}
