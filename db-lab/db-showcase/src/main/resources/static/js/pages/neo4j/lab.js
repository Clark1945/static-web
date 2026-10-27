// Neo4j 實驗室：PROFILE 與索引、圖 vs SQL（推薦、可到達的人數、最短路徑）
import { api, postJson, esc, fmt, onCtrlEnter, store } from "../../lib.js";
import { formatResult, formatCell, graphView } from "./format.js";

const API = "/api/neo4j/lab";
const SECTIONS = [
  { id: "profile", title: "PROFILE 與索引", sub: "NodeByLabelScan、IndexSeek、db hits、超級節點" },
  { id: "compare", title: "圖 vs SQL", sub: "同一個問題，Cypher 與 PostgreSQL 各寫一次" },
];
const code = (s, lang = "") => `<pre class="code" ${lang ? `data-lang="${lang}"` : ""}>${esc(s.trim())}</pre>`;

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="實驗"><div class="topic">實驗</div>
        ${SECTIONS.map((s, i) => `<button type="button" data-sec="${s.id}">${i + 1}. ${esc(s.title)}</button>`).join("")}</nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;
  const panel = el.querySelector('[data-ref="panel"]');
  let alive = true;
  const RENDER = { profile, compare };

  function open(id) {
    const s = SECTIONS.find((x) => x.id === id) ?? SECTIONS[0];
    store.set("neo4j-lab-sec", s.id);
    el.querySelectorAll("[data-sec]").forEach((b) => b.classList.toggle("active", b.dataset.sec === s.id));
    panel.innerHTML = `<div><span class="eyebrow">${esc(s.sub)}</span><h3>${esc(s.title)}</h3></div>
      <div data-ref="body" style="display:grid;grid-template-columns:minmax(0,1fr);gap:14px"></div>`;
    RENDER[s.id](panel.querySelector('[data-ref="body"]'));
  }
  el.querySelector("nav").addEventListener("click", (e) => { const b = e.target.closest("[data-sec]"); if (b) open(b.dataset.sec); });

  async function act(button, statusEl, fn) {
    button.disabled = true;
    try { await fn(); } catch (e) { if (alive) statusEl.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
    finally { button.disabled = false; }
  }
  const running = (text) => `<span class="running"><span class="spinner"></span>${esc(text)}</span>`;
  const firstOp = (plan) => { let p = plan; while (p && p.children.length) p = p.children[0]; return p?.operator ?? "—"; };

  // ------------------------------------------------------------ PROFILE 與索引
  function profile(body) {
    body.innerHTML = `
      <div class="bench">
        <div class="bench-head"><div><span class="eyebrow">Neo4j 的索引</span><h3>索引與約束</h3>
          <p class="muted">唯一約束（附帶索引）是載入資料用的，不能刪；自己建的索引可以刪。</p></div>
          <button class="btn ghost" type="button" data-ref="reset">刪除自己建的索引</button></div>
        <div class="index-list" data-ref="indexes"></div>
        <div class="ddl-row"><label class="sr-only" for="neo4j-ddl">建立或刪除索引</label>
          <input id="neo4j-ddl" data-ref="ddl" class="mono" spellcheck="false" placeholder="CREATE INDEX 名稱 FOR (n:標籤) ON (n.屬性)　或　DROP INDEX 名稱">
          <button class="btn" type="button" data-ref="ddl-run">執行</button></div>
        <div class="status" data-ref="ddl-status"></div>
      </div>
      <div class="variant-row" data-ref="steps"></div>
      <div class="prompt" data-ref="goal"></div>
      <div data-ref="suggest"></div>
      <div class="variant-row" data-ref="variants"></div>
      <label for="neo4j-profile">查詢（會自動加上 PROFILE，在交易裡執行後 ROLLBACK；Ctrl+Enter 執行）</label>
      <textarea id="neo4j-profile" data-ref="q" spellcheck="false" style="min-height:80px"></textarea>
      <div class="actions">
        <button class="btn" type="button" data-ref="run">PROFILE</button>
        <button class="btn ghost" type="button" data-ref="all">這一步的查詢全部執行、並排比較</button>
      </div>
      <p class="question" data-ref="question"></p>
      <div data-ref="result"></div>
      <details class="takeaway"><summary>看解說</summary><div class="explain" data-ref="takeaway"></div></details>
      <div data-ref="history"></div>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let steps = [], step = null, label = "";
    const history = [];

    const renderIndexes = (list) => {
      $("indexes").innerHTML = `<table class="idx-table"><thead><tr><th>名稱</th><th>種類</th><th>標籤</th><th>屬性</th><th>狀態</th><th></th></tr></thead><tbody>
        ${list.map((i) => `<tr><td class="mono">${esc(i.name)}</td><td>${esc(i.type)}${i.constraint ? "（唯一約束）" : ""}</td>
          <td class="mono">${esc(i.labels.join(", "))}</td><td class="mono def">${esc(i.properties.join(", "))}</td><td>${esc(i.state)}</td>
          <td>${i.constraint ? "" : `<button class="btn ghost small" type="button" data-drop="${esc(i.name)}">刪除</button>`}</td></tr>`).join("")}</tbody></table>`;
    };
    const ddl = (cmd) => act($("ddl-run"), $("ddl-status"), async () => {
      $("ddl-status").innerHTML = running("執行中（會等索引建好）：" + cmd);
      const t = performance.now();
      renderIndexes(await postJson(`${API}/indexes`, { commands: cmd }));
      $("ddl-status").innerHTML = `<span class="chip">完成：<span class="mono">${esc(cmd)}</span>（${(performance.now() - t).toFixed(0)} ms）</span>`;
    });
    api(`${API}/indexes`).then((l) => alive && renderIndexes(l)).catch((e) => alive && ($("indexes").innerHTML = `<div class="error">${esc(e.message)}</div>`));
    $("ddl-run").addEventListener("click", () => { if ($("ddl").value.trim()) ddl($("ddl").value.trim()); });
    $("ddl").addEventListener("keydown", (e) => { if (e.key === "Enter") $("ddl-run").click(); });
    $("indexes").addEventListener("click", (e) => { const b = e.target.closest("[data-drop]"); if (b) ddl(`DROP INDEX ${b.dataset.drop}`); });
    $("reset").addEventListener("click", () => act($("reset"), $("ddl-status"), async () => {
      renderIndexes(await postJson(`${API}/indexes/reset`, {}));
      $("ddl-status").innerHTML = '<span class="chip">已刪除自己建的索引（唯一約束保留）</span>';
    }));

    api(`${API}/steps`).then((list) => {
      if (!alive) return;
      steps = list;
      $("steps").innerHTML = '<span class="muted" style="align-self:center">步驟：</span>' +
        steps.map((s, i) => `<button type="button" class="option small" data-step="${i}">${i + 1}. ${esc(s.title)}</button>`).join("");
      select(store.get("neo4j-profile-step", 0));
    });
    $("steps").addEventListener("click", (e) => { const b = e.target.closest("[data-step]"); if (b) select(Number(b.dataset.step)); });

    function select(i) {
      step = steps[i] ?? steps[0];
      store.set("neo4j-profile-step", steps.indexOf(step));
      $("steps").querySelectorAll("[data-step]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.step) === steps.indexOf(step)));
      $("goal").textContent = step.goal.trim();
      $("question").textContent = "想一想：" + step.question;
      $("takeaway").textContent = step.takeaway.trim();
      body.querySelector(".takeaway").open = false;
      const idx = step.indexes ?? [];
      $("suggest").innerHTML = idx.length ? `<div class="suggest"><span class="muted">這一步會用到：</span>${idx.map((d, k) => `
        <span class="suggest-item"><code>${esc(d)}</code><button class="btn ghost small" type="button" data-ddl="${k}">執行</button></span>`).join("")}</div>` : "";
      $("suggest").querySelectorAll("[data-ddl]").forEach((b) => b.addEventListener("click", () => ddl(idx[Number(b.dataset.ddl)])));
      $("variants").innerHTML = step.queries.length > 1 ? step.queries.map((q, k) => `<button type="button" class="option small" data-q="${k}">${esc(q.label)}</button>`).join("") : "";
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.addEventListener("click", () => load(Number(b.dataset.q))));
      load(0);
    }
    function load(k) {
      label = step.queries[k].label;
      $("q").value = step.queries[k].cypher.trim();
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.q) === k));
      $("result").innerHTML = "";
    }

    function remember(r, lbl) {
      history.unshift({ step: steps.indexOf(step) + 1, label: lbl, op: r.kind === "error" ? "錯誤" : firstOp(r.plan), hits: r.totalDbHits, ms: r.millis });
      history.length = Math.min(history.length, 14);
      $("history").innerHTML = `<h4 class="sub-head">這次的執行紀錄</h4><div class="result"><table>
        <thead><tr><th>步驟</th><th>查詢</th><th>第一步（找起點）</th><th class="num">總 db hits</th><th class="num">ms</th></tr></thead>
        <tbody>${history.map((h) => `<tr><td class="num">${h.step}</td><td>${esc(h.label)}</td><td class="mono">${esc(h.op)}</td>
          <td class="num">${h.hits == null ? "—" : fmt(h.hits)}</td><td class="num">${h.ms.toFixed(1)}</td></tr>`).join("")}</tbody></table></div>`;
    }

    async function run() {
      await act($("run"), $("result"), async () => {
        $("result").innerHTML = running("執行中…");
        const r = await postJson(`${API}/profile`, { commands: $("q").value });
        if (!alive) return;
        $("result").innerHTML = formatResult(r, { showGraph: false });
        remember(r, label);
      });
    }
    $("run").addEventListener("click", run);
    onCtrlEnter($("q"), run);

    $("all").addEventListener("click", () => act($("all"), $("result"), async () => {
      const results = [];
      for (const q of step.queries) {
        $("result").innerHTML = running(`執行中：${q.label}`);
        results.push(await postJson(`${API}/profile`, { commands: q.cypher }));
        if (!alive) return;
        remember(results.at(-1), q.label);
      }
      const max = Math.max(1, ...results.map((r) => r.totalDbHits ?? 0));
      $("result").innerHTML = `
        <div class="bars">${results.map((r, i) => `<div class="bar-row"><span>${esc(step.queries[i].label)}</span>
          <span class="bar-track"><i class="${/LabelScan|AllNodes/.test(firstOp(r.plan)) ? "slow" : "fast"}" style="width:${Math.max(0.8, ((r.totalDbHits ?? 0) / max) * 100)}%"></i></span>
          <b>${r.totalDbHits == null ? "錯誤" : fmt(r.totalDbHits)}</b></div>`).join("")}</div>
        <p class="muted">長條是「總 db hits」。</p>
        <div class="sql-pair">${results.map((r, i) => `<div class="sql-card"><div class="sql-label">${esc(step.queries[i].label)}</div>
          ${code(step.queries[i].cypher)}${formatResult(r, { showGraph: false })}</div>`).join("")}</div>`;
    }));
  }

  // ------------------------------------------------------------ 圖 vs SQL
  function compare(body) {
    body.innerHTML = `
      <p class="desc">同一個問題，分別用 Cypher（Neo4j）和 SQL（PostgreSQL）回答。兩邊都先執行一次暖身，第二次才計時。
        追蹤關係在 PostgreSQL 存成 <code>graphlab.follows(src, dst)</code>，src、dst 都有索引。</p>

      <h4 class="sub-head">1. 買了這件商品的人也買了</h4>
      <div class="lab-controls"><label>商品 id <input data-ref="pid" type="number" min="1" max="1500" value="540" style="width:6em"></label>
        <button class="btn" type="button" data-ref="rec">比較</button></div>
      <div data-ref="rec-out"></div>

      <h4 class="sub-head">2. 沿著追蹤關係，幾步之內能到達幾位會員</h4>
      <div class="lab-controls"><label>會員 id <input data-ref="cid" type="number" min="1" max="20000" value="4242" style="width:6em"></label>
        <label>最多 <select data-ref="depth">${[3, 4, 5, 6].map((d) => `<option ${d === 6 ? "selected" : ""}>${d}</option>`).join("")}</select> 步</label>
        <button class="btn" type="button" data-ref="reach">比較</button></div>
      <div data-ref="reach-out"></div>

      <h4 class="sub-head">3. 最短追蹤路徑</h4>
      <div class="lab-controls"><label>從會員 <input data-ref="from" type="number" min="1" max="20000" value="4242" style="width:6em"></label>
        <label>到會員 <input data-ref="to" type="number" min="1" max="20000" value="19999" style="width:6em"></label>
        <button class="btn" type="button" data-ref="sp">比較</button></div>
      <div data-ref="sp-out"></div>

      <details class="takeaway"><summary>看解說：圖資料庫什麼時候比較好</summary>
        <div class="explain">★ 圖資料庫不是「永遠比較快」。這裡的資料量不大（8 萬條追蹤關係，全部在記憶體裡），
PostgreSQL 有索引的遞迴 CTE 在「幾步之內能到達幾人」這種問題上跟 Neo4j 差不多，甚至更快。

圖資料庫的優勢在：
1. 走訪的成本只跟「實際走過的關係」有關，跟整個資料庫有多大無關（index-free adjacency：每個節點直接記著它的關係，不必透過索引查找）；關聯式資料庫的每一次 JOIN 都要查一次索引，資料越多、層數越深，差距越大。
2. 最短路徑、可變長度路徑這類「不知道要走幾步」的問題：Cypher 一行 shortestPath，從兩端同時搜尋、找到就停；SQL 要自己寫遞迴、給最大步數、處理環，還要列舉中間所有路徑（第 3 個比較：8 步的路徑，SQL 慢了幾十倍）。
3. 查詢的可讀性：多段關係的圖樣（推薦、詐騙偵測、權限繼承）用 Cypher 寫起來就是畫出來的樣子。

不適合圖資料庫的：整張表的彙總報表（每月營收）、大量欄位的篩選，這些交給關聯式資料庫或資料倉儲。
實務上常見的組合：主要資料放 PostgreSQL，需要關係分析的部分同步一份到 Neo4j。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const side = (s, extra = "") => `<div class="sql-card">
        <div class="sql-label">${esc(s.language)}：<b>${s.millis.toFixed(1)} ms</b></div>
        ${code(s.code, s.language.startsWith("SQL") ? "sql" : "")}
        ${s.error ? `<div class="error">${esc(s.error)}</div>` : resultTable(s)}${extra}</div>`;
    const resultTable = (s) => `<div class="result cql-result"><table><thead><tr>${s.columns.map((c) => `<th>${esc(c)}</th>`).join("")}</tr></thead>
      <tbody>${s.rows.map((r) => `<tr>${r.map((v) => `<td class="${typeof v === "number" ? "num" : ""}">${formatCell(v)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;

    $("rec").addEventListener("click", () => act($("rec"), $("rec-out"), async () => {
      $("rec-out").innerHTML = running("執行中…");
      const r = await api(`${API}/compare/recommend?productId=${encodeURIComponent($("pid").value)}`, { method: "POST" });
      if (!alive) return;
      $("rec-out").innerHTML = `<div class="sql-pair">${side(r.cypher)}${side(r.sql)}</div>
        <p class="muted">Cypher 拆成兩個 MATCH：寫成一條長路徑時，兩個 PLACED 不能是同一條關係，「同一張訂單一起買」的人會被漏掉（陷阱題有示範）。</p>`;
    }));

    $("reach").addEventListener("click", () => act($("reach"), $("reach-out"), async () => {
      $("reach-out").innerHTML = running("執行中（每一步各執行兩邊）…");
      const list = await api(`${API}/compare/reach?customerId=${encodeURIComponent($("cid").value)}&depth=${$("depth").value}`, { method: "POST" });
      if (!alive) return;
      const max = Math.max(1, ...list.flatMap((c) => [c.cypher.millis, c.sql.millis]));
      $("reach-out").innerHTML = `
        <div class="result"><table><thead><tr><th>範圍</th><th class="num">可到達</th><th>Cypher</th><th>SQL</th></tr></thead><tbody>
          ${list.map((c) => `<tr><td>${esc(c.title)}</td><td class="num">${c.cypher.rows[0] ? fmt(c.cypher.rows[0][0]) : "—"}${c.sql.rows[0] && c.sql.rows[0][0] !== c.cypher.rows[0]?.[0] ? `（SQL：${fmt(c.sql.rows[0][0])}）` : ""}</td>
            <td><span class="mini-bar"><i style="width:${(c.cypher.millis / max) * 100}%"></i></span> ${c.cypher.millis.toFixed(1)} ms</td>
            <td><span class="mini-bar sql"><i style="width:${(c.sql.millis / max) * 100}%"></i></span> ${c.sql.millis.toFixed(1)} ms</td></tr>`).join("")}
        </tbody></table></div>
        <div class="sql-pair">${side(list.at(-1).cypher)}${side(list.at(-1).sql)}</div>`;
    }));

    $("sp").addEventListener("click", () => act($("sp"), $("sp-out"), async () => {
      $("sp-out").innerHTML = running("執行中…");
      const r = await api(`${API}/shortest-path?from=${encodeURIComponent($("from").value)}&to=${encodeURIComponent($("to").value)}`, { method: "POST" });
      if (!alive) return;
      const c = r.comparison;
      $("sp-out").innerHTML = `<div class="sql-pair">${side(c.cypher)}${side(c.sql)}</div>
        ${c.cypher.rows.length === 0 ? '<p class="muted">10 步之內找不到路徑。</p>' : ""}
        ${r.graph ? graphView(r.graph, { height: 220 }) : ""}`;
    }));
  }


  open(store.get("neo4j-lab-sec", "profile"));
  return () => { alive = false; };
}
