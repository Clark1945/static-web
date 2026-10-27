// 索引實驗室：在 200 萬筆的 perf.orders_big 上建立 / 刪除索引，比較 EXPLAIN ANALYZE 的結果
import { api, postJson, esc, fmt, onCtrlEnter, store } from "../../lib.js";

const API = "/api/postgres";
const STEP_KEY = "pg-lab-step";
const SCAN_PATTERN = /(Parallel Seq Scan|Seq Scan|Index Only Scan|Bitmap Index Scan|Bitmap Heap Scan|Index Scan)/g;

export function mount(el) {
  el.innerHTML = `
    <div class="bench">
      <div class="bench-head">
        <div>
          <span class="eyebrow">實驗對象</span>
          <div class="lab-tables" data-ref="tables"><span class="muted">讀取中…</span></div>
        </div>
        <button class="btn ghost" type="button" data-ref="reset">重置：刪掉所有索引</button>
      </div>
      <div class="index-list" data-ref="indexes"></div>
      <div class="ddl-row">
        <label class="sr-only" for="pg-lab-ddl">建立或刪除索引</label>
        <input id="pg-lab-ddl" data-ref="ddl" class="mono" spellcheck="false"
               placeholder="CREATE INDEX idx_xxx ON perf.orders_big (欄位)　或　DROP INDEX perf.idx_xxx">
        <button class="btn" type="button" data-ref="ddl-run">執行</button>
      </div>
      <div class="status" data-ref="ddl-status" aria-live="polite"></div>
    </div>

    <div class="lab">
      <nav class="qlist" aria-label="實驗步驟" data-ref="steps"></nav>
      <div class="panel">
        <div><span class="eyebrow" data-ref="step-no"></span><h3 data-ref="step-title">…</h3></div>
        <div class="prompt" data-ref="goal"></div>
        <div data-ref="ddl-suggest"></div>
        <div class="variant-row" data-ref="variants"></div>
        <label for="pg-lab-sql">查詢（Ctrl+Enter 執行）</label>
        <textarea id="pg-lab-sql" data-ref="sql" spellcheck="false" style="min-height:150px"></textarea>
        <div class="actions"><button class="btn" type="button" data-ref="run">執行</button></div>
        <p class="question" data-ref="question"></p>
        <div class="status" data-ref="status" aria-live="polite"></div>
        <div data-ref="plan"></div>
        <details class="takeaway"><summary>看解說</summary><div class="explain" data-ref="takeaway"></div></details>
        <div data-ref="history"></div>
      </div>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let steps = [];
  let current = null;
  let currentLabel = "";
  let indexCount = 0;
  let alive = true;
  let runToken = 0;
  const history = [];

  // ---------- 索引管理 ----------
  function renderIndexes(indexes) {
    indexCount = indexes.filter((i) => !i.primary).length;
    $("indexes").innerHTML = `
      <table class="idx-table">
        <thead><tr><th>資料表</th><th>索引</th><th>定義</th><th>大小</th><th></th></tr></thead>
        <tbody>${indexes.map((i) => `
          <tr>
            <td class="mono muted">${esc(i.table.replace("perf.", ""))}</td>
            <td class="mono">${esc(i.name)}${i.primary ? ' <span class="tag">主鍵</span>' : ""}</td>
            <td class="mono def">${esc(i.definition.replace(/ USING btree/, ""))}</td>
            <td class="num">${esc(i.size)}</td>
            <td>${i.primary ? "" : `<button class="btn ghost small" type="button" data-drop="${esc(i.name)}">刪除</button>`}</td>
          </tr>`).join("")}</tbody>
      </table>`;
  }

  async function loadTable() {
    const info = await api(`${API}/lab/info`);
    if (!alive) return;
    $("tables").innerHTML = info.tables.map((t) => `
      <div class="lab-table"><b class="mono">${esc(t.table)}</b>
        <span class="muted">約 ${fmt(t.rowCount)} 筆 · ${esc(t.size)} · ${esc(t.comment ?? "")}</span></div>`).join("");
    renderIndexes(info.indexes);
  }

  async function runDdl(sql, label = sql) {
    $("ddl-status").innerHTML = `<span class="running"><span class="spinner"></span>執行中：<span class="mono">${esc(label)}</span></span>`;
    for (const b of el.querySelectorAll(".bench button")) b.disabled = true;
    try {
      const r = sql === null
        ? await postJson(`${API}/lab/reset`, {})
        : await postJson(`${API}/lab/ddl`, { sql });
      if (!alive) return;
      renderIndexes(r.indexes);
      $("ddl-status").innerHTML = `<span class="chip">完成：<span class="mono">${esc(label)}</span>，耗時 <b>${fmtMs(r.elapsedMs)}</b></span>`;
    } catch (e) {
      if (!alive) return;
      $("ddl-status").innerHTML = `<div class="error">${esc(e.message)}</div>`;
    } finally {
      for (const b of el.querySelectorAll(".bench button")) b.disabled = false;
    }
  }

  $("ddl-run").addEventListener("click", () => { if ($("ddl").value.trim()) runDdl($("ddl").value.trim()); });
  $("ddl").addEventListener("keydown", (e) => { if (e.key === "Enter") $("ddl-run").click(); });
  $("reset").addEventListener("click", () => runDdl(null, "刪掉所有索引"));
  $("indexes").addEventListener("click", (e) => {
    const b = e.target.closest("[data-drop]");
    if (b) runDdl(`DROP INDEX perf.${b.dataset.drop}`);
  });

  // ---------- 步驟 ----------
  api(`${API}/lab/steps`).then((list) => {
    if (!alive) return;
    steps = list;
    select(store.get(STEP_KEY, steps[0].id));
  });

  $("steps").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-id]");
    if (b) select(b.dataset.id);
  });

  function select(id) {
    current = steps.find((s) => s.id === id) ?? steps[0];
    store.set(STEP_KEY, current.id);
    const no = steps.indexOf(current) + 1;
    $("steps").innerHTML = `<div class="topic">步驟</div>` + steps.map((s, i) =>
      `<button type="button" data-id="${esc(s.id)}" class="${s === current ? "active" : ""}">${i + 1}. ${esc(s.title)}</button>`).join("");
    $("step-no").textContent = `步驟 ${no} / ${steps.length}`;
    $("step-title").textContent = current.title;
    $("goal").textContent = current.goal.trim();
    $("question").textContent = current.question ? "想一想：" + current.question : "";
    $("takeaway").textContent = current.takeaway.trim();
    el.querySelector(".takeaway").open = false;

    $("ddl-suggest").innerHTML = current.ddl.length === 0 ? "" : `
      <div class="suggest">
        <span class="muted">這一步會用到：</span>
        ${current.ddl.map((d, i) => `
          <span class="suggest-item"><code>${esc(d)}</code>
            <button class="btn ghost small" type="button" data-ddl="${i}">執行</button></span>`).join("")}
      </div>`;
    $("ddl-suggest").querySelectorAll("[data-ddl]").forEach((b) =>
      b.addEventListener("click", () => runDdl(current.ddl[Number(b.dataset.ddl)])));

    $("variants").innerHTML = current.sqls.length < 2 ? "" : current.sqls.map((s, i) =>
      `<button type="button" class="option small" data-variant="${i}">${esc(s.label)}</button>`).join("");
    $("variants").querySelectorAll("[data-variant]").forEach((b) =>
      b.addEventListener("click", () => loadVariant(Number(b.dataset.variant))));
    loadVariant(0);
  }

  function loadVariant(i) {
    const s = current.sqls[i];
    currentLabel = s.label;
    $("sql").value = s.sql.trim();
    $("variants").querySelectorAll("[data-variant]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.variant) === i));
    runToken++;
    $("run").disabled = false;
    $("status").innerHTML = "";
    $("plan").innerHTML = "";
  }

  // ---------- 執行與解析執行計畫 ----------
  async function run() {
    if ($("run").disabled) return;
    const sql = $("sql").value;
    const token = ++runToken;
    const started = performance.now();
    $("run").disabled = true;
    $("plan").innerHTML = "";
    $("status").innerHTML = '<span class="running"><span class="spinner"></span>查詢中… <span class="mono" data-ticker>0.00 秒</span></span>';
    const ticker = setInterval(() => {
      const t = $("status").querySelector("[data-ticker]");
      if (t) t.textContent = ((performance.now() - started) / 1000).toFixed(2) + " 秒";
    }, 50);
    try {
      const r = await postJson(`${API}/sql`, { sql });
      if (!alive || token !== runToken) return;
      const isPlan = r.columns.length === 1 && r.columns[0] === "QUERY PLAN";
      if (!isPlan) {
        $("status").innerHTML = `<span class="chip">回傳 <b>${fmt(r.rowCount)}</b> 筆</span><span class="chip">資料庫耗時 <b>${r.elapsedMs.toFixed(1)} ms</b></span>
          <span class="muted">加上 EXPLAIN ANALYZE 才看得到執行計畫。</span>`;
        $("plan").innerHTML = "";
        return;
      }
      const text = r.rows.map((row) => row[0]).join("\n");
      const exec = text.match(/Execution Time: ([\d.]+) ms/);
      const scans = [...new Set(text.match(SCAN_PATTERN) ?? [])];
      const hasSort = /->\s+Sort|^Sort/m.test(text);
      $("status").innerHTML = `
        ${exec ? `<span class="chip">執行時間 <b>${exec[1]} ms</b></span>` : ""}
        ${scans.map((s) => `<span class="chip ${/Seq/.test(s) ? "bad" : "good"}">${esc(s)}</span>`).join("")}
        ${hasSort ? '<span class="chip warn">有 Sort</span>' : ""}`;
      $("plan").innerHTML = `<pre class="plan">${highlight(text)}</pre>`;
      history.unshift({
        step: steps.indexOf(current) + 1, label: currentLabel, scans: scans.join(" + ") || "—",
        sort: hasSort, ms: exec ? Number(exec[1]) : null, indexes: indexCount,
      });
      history.length = Math.min(history.length, 12);
      renderHistory();
    } catch (e) {
      if (!alive || token !== runToken) return;
      $("status").innerHTML = `<div class="error">${esc(e.message)}</div>`;
    } finally {
      clearInterval(ticker);
      if (alive && token === runToken) $("run").disabled = false;
    }
  }

  function highlight(text) {
    return esc(text)
      .replace(/(Parallel Seq Scan|Seq Scan)/g, '<span class="p-bad">$1</span>')
      .replace(/(Index Only Scan|Bitmap Index Scan|Bitmap Heap Scan|Index Scan)/g, '<span class="p-good">$1</span>')
      .replace(/(-&gt;\s+)(Sort|Incremental Sort)/g, '$1<span class="p-warn">$2</span>')
      .replace(/^(Sort|Incremental Sort)/gm, '<span class="p-warn">$1</span>')
      .replace(/(Rows Removed by Filter: \d+)/g, '<span class="p-bad">$1</span>')
      .replace(/(Heap Fetches: \d+)/g, '<span class="p-note">$1</span>')
      .replace(/(Execution Time: [\d.]+ ms)/g, '<b class="p-time">$1</b>');
  }

  function renderHistory() {
    const fastest = Math.min(...history.filter((h) => h.ms !== null).map((h) => h.ms));
    $("history").innerHTML = `
      <h4 class="sub-head">這次的執行紀錄</h4>
      <div class="result"><table>
        <thead><tr><th>步驟</th><th>查詢</th><th>掃描方式</th><th>排序</th><th>索引數</th><th>執行時間</th></tr></thead>
        <tbody>${history.map((h) => `
          <tr>
            <td class="num">${h.step}</td><td>${esc(h.label)}</td><td>${esc(h.scans)}</td>
            <td>${h.sort ? "有 Sort" : "—"}</td><td class="num">${h.indexes}</td>
            <td class="num ${h.ms === fastest ? "best" : ""}">${h.ms === null ? "—" : h.ms.toFixed(3) + " ms"}</td>
          </tr>`).join("")}</tbody>
      </table></div>`;
  }

  const fmtMs = (ms) => (ms >= 1000 ? (ms / 1000).toFixed(2) + " 秒" : ms.toFixed(1) + " ms");

  $("run").addEventListener("click", run);
  onCtrlEnter($("sql"), run);
  loadTable().catch((e) => { if (alive) $("tables").innerHTML = `<span class="error">${esc(e.message)}</span>`; });

  return () => { alive = false; };
}
