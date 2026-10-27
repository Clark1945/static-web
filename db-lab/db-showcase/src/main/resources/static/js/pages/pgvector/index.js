// pgvector 頁面外框：資料表總覽（可收合）＋ 五個分頁
// pgvector 就是 PostgreSQL，練習題、陷阱題、寫入沙盒直接共用 PostgreSQL 頁面的模組，只換 API 路徑
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";

const OPTS = { api: "/api/pgvector", prefix: "vec" };
const SANDBOX_EXAMPLES = [
  { label: "新增商品並嵌入", sql: `INSERT INTO products (id, name, category, price, description, embedding)
VALUES (9001, '測試 露營用品 X001', '露營用品', 1990, '測試 露營用品 X001 輕量 帳篷',
        embed('測試 露營用品 X001 輕量 帳篷'))
RETURNING id, name, vector_dims(embedding) AS dims;

-- 新增之後馬上就搜得到
SELECT id, name, round((embedding <=> embed('登山'))::numeric, 3) AS distance
FROM products
ORDER BY embedding <=> embed('登山')
LIMIT 5;` },
  { label: "改描述、重算向量", sql: `UPDATE products
SET description = description || ' 通勤',
    embedding = embed(description || ' 通勤')
WHERE id = 469
RETURNING id, description;

SELECT id, name FROM products ORDER BY embedding <=> embed('通勤') LIMIT 5;` },
  { label: "維度不對", sql: `-- products.embedding 是 vector(64)，3 維的向量寫不進去
UPDATE products SET embedding = '[1,2,3]' WHERE id = 1;` },
];
const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫 SQL，自動批改", load: () => import("../postgres/practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("../postgres/traps.js") },
  { id: "sandbox", label: "寫入沙盒", hint: "INSERT / UPDATE / DELETE，自動還原", load: () => import("../postgres/sandbox.js"), opts: { examples: SANDBOX_EXAMPLES } },
  { id: "lab", label: "實驗室", hint: "語意搜尋、索引、過濾、量化", load: () => import("./lab.js") },
  { id: "console", label: "SQL 主控台", hint: "範例與自由查詢", load: () => import("./console.js") },
];
const TAB_KEY = "vec-tab";
const OPEN_KEY = "vec-schema-open";
export const CONSOLE_DRAFT_KEY = "vec-console-draft";

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:80ch">
          pgvector 讓 PostgreSQL 多了 vector 型別、距離運算子（&lt;-&gt; &lt;=&gt; &lt;#&gt;）與向量索引（HNSW、IVFFlat）：
          把文字、圖片轉成向量（嵌入），就能找「意思相近」的資料，這是語意搜尋、推薦與 RAG 的基礎。
          這裡用一個詞庫做成的迷你嵌入模型 <code>embed(文字)</code> 代替真正的語言模型；資料放在 pg-lab 容器的 vectors 資料庫。
          你輸入的 SQL 一律用權限受限的 learner 角色執行，寫入一律 ROLLBACK。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 pgvector 中</span></div></div>
      </div>

      <details class="schema" data-ref="schema">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>資料表與向量索引 <small>欄位、索引、大小</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div data-ref="schema-body" class="vec-schema"></div>
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

  const typeBadge = (type) => type.startsWith("vector") || type.startsWith("halfvec") ? '<span class="key-badge pk">向量</span>' : "";

  async function loadOverview() {
    try {
      const o = await api("/api/pgvector/overview");
      if (!alive) return;
      const load = o.load;
      const find = (n) => o.tables.find((t) => t.name === n) ?? { rows: 0 };
      $("stats").innerHTML = `
        <div class="stat"><b>${load.running ? "載入中" : fmt(find("products").rows)}</b><span>${load.running ? esc(load.step ?? "") : "件商品（64 維）"}</span></div>
        <div class="stat"><b>${fmt(o.vocabWords)}</b><span>個詞（迷你嵌入模型）</span></div>
        <div class="stat"><b>${fmt(find("passages").rows)}</b><span>筆文件片段（128 維，索引實驗用）</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset" ${load.running ? "disabled" : ""}>${load.running ? "載入中…" : "重新載入資料"}</button>
          <span>清空後重新產生（約 45 秒，大部分是建 HNSW 索引；pgvector ${esc(o.version)}）</span>
        </div>
        ${load.error ? `<div class="error" style="flex-basis:100%">上次載入失敗：${esc(load.error)}</div>` : ""}`;
      $("reset").addEventListener("click", reset);
      $("schema-body").innerHTML = `<div class="coll-grid">${o.tables.map((t) => `
          <article class="coll-card">
            <header><b class="mono">${esc(t.name)}</b><span>${fmt(t.rows)} 筆 · ${esc(t.size)}</span></header>
            <p>${esc(t.design)}</p>
            <table class="cols">${t.columns.map((c) => `<tr><td class="mono">${esc(c.name)}</td><td class="mono muted">${esc(c.type)}</td>
              <td>${typeBadge(c.type)}</td></tr>`).join("")}</table>
            ${t.indexes.length ? `<div class="vec-indexes">${t.indexes.map((i) => `<div><span class="mono">${esc(i.name)}</span> <span class="muted">${esc(i.size)}</span>
              <div class="mono muted vec-def">${esc(i.definition.replace(/^CREATE (UNIQUE )?INDEX \S+ ON public\.\S+ /, ""))}</div></div>`).join("")}</div>` : ""}
            <button type="button" class="try mono" data-try="${esc(t.sample)}">${esc(t.sample.length > 54 ? t.sample.slice(0, 52) + "…" : t.sample)}</button>
          </article>`).join("")}
          <article class="coll-card">
            <header><b class="mono">embed(text) → vector(64)</b><span>函式</span></header>
            <p>把文字裡認得的詞（vocab）的向量相加，再正規化成長度 1；一個詞都不認得時回傳 NULL。<span class="mono">tokens(text)</span> 回傳認得哪些詞。</p>
            <button type="button" class="try mono" data-try="SELECT tokens('適合冬天通勤的藍牙耳機'), embed('通勤')">SELECT tokens('適合冬天通勤的藍牙耳機'), embed('通勤')</button>
          </article></div>`;
      clearTimeout(pollTimer);
      if (load.running) pollTimer = setTimeout(loadOverview, 2000);
    } catch (e) {
      if (alive) $("stats").innerHTML = `<div class="error">讀不到 pgvector：${esc(e.message)}\n請確認 pg-lab 容器有在執行（docker compose up -d db）。</div>`;
    }
  }

  async function reset() {
    $("reset").disabled = true;
    try { await postJson("/api/pgvector/reset", {}); await loadOverview(); }
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
