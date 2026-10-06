// MongoDB 實驗室：內嵌 vs 參照、索引與 explain（ESR）、聚合管線逐步看
import { api, postJson, esc, fmt, onCtrlEnter, store } from "../../lib.js";
import { formatValue } from "./format.js";

const API = "/api/mongo/lab";
const SECTIONS = [
  { id: "embed", title: "內嵌 vs 參照", sub: "同一份資料，兩種文件設計" },
  { id: "index", title: "索引與 explain", sub: "COLLSCAN、IXSCAN、ESR 規則" },
  { id: "pipe", title: "聚合管線逐步看", sub: "每個 stage 之後資料長什麼樣子" },
];
const PIPELINES = [
  { label: "最暢銷商品", cmd: 'db.orders.aggregate([\n  { $match: { status: "delivered" } },\n  { $unwind: "$items" },\n  { $group: { _id: "$items.productId", name: { $first: "$items.name" }, qty: { $sum: "$items.qty" } } },\n  { $sort: { qty: -1 } },\n  { $limit: 5 }\n])' },
  { label: "各城市每月營收", cmd: 'db.orders.aggregate([\n  { $match: { status: "delivered", orderDate: { $gte: ISODate("2026-07-01T00:00:00+08:00") } } },\n  { $group: { _id: { city: "$shipping.city", month: { $dateToString: { format: "%Y-%m", date: "$orderDate", timezone: "Asia/Taipei" } } }, revenue: { $sum: "$total" } } },\n  { $sort: { revenue: -1 } },\n  { $limit: 5 }\n])' },
  { label: "會員的消費統計（$lookup）", cmd: 'db.orders.aggregate([\n  { $match: { status: "delivered" } },\n  { $group: { _id: "$customerId", orders: { $sum: 1 }, spend: { $sum: "$total" } } },\n  { $sort: { spend: -1 } },\n  { $limit: 3 },\n  { $lookup: { from: "customers", localField: "_id", foreignField: "_id", as: "customer" } },\n  { $project: { orders: 1, spend: 1, name: { $first: "$customer.name" }, vip: { $first: "$customer.vipLevel" } } }\n])' },
  { label: "金額分級（$bucket）", cmd: 'db.orders.aggregate([\n  { $match: { status: "delivered" } },\n  { $bucket: { groupBy: "$total", boundaries: [0, 1000, 5000, 20000, 100000, 1000000], default: "其他", output: { count: { $sum: 1 } } } }\n])' },
];
const code = (s) => `<pre class="code">${esc(s.trim())}</pre>`;

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="實驗"><div class="topic">實驗</div>
        ${SECTIONS.map((s, i) => `<button type="button" data-sec="${s.id}">${i + 1}. ${esc(s.title)}</button>`).join("")}</nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;
  const panel = el.querySelector('[data-ref="panel"]');
  let alive = true;
  const RENDER = { embed, index, pipe };

  function open(id) {
    store.set("mongo-lab-sec", id);
    el.querySelectorAll("[data-sec]").forEach((b) => b.classList.toggle("active", b.dataset.sec === id));
    const s = SECTIONS.find((x) => x.id === id);
    panel.innerHTML = `<div><span class="eyebrow">${esc(s.sub)}</span><h3>${esc(s.title)}</h3></div><div data-ref="body" style="display:grid;gap:14px"></div>`;
    RENDER[id](panel.querySelector('[data-ref="body"]'));
  }
  el.querySelector("nav").addEventListener("click", (e) => { const b = e.target.closest("[data-sec]"); if (b) open(b.dataset.sec); });

  async function act(button, statusEl, fn) {
    button.disabled = true;
    try { await fn(); } catch (e) { if (alive) statusEl.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
    finally { button.disabled = false; }
  }

  // ------------------------------------------------------------ 內嵌 vs 參照
  function embed(body) {
    body.innerHTML = `
      <p class="desc">orders 的明細是「內嵌」在訂單裡。把它拆成關聯式的做法：order_headers（訂單）＋ order_lines（一項明細一份文件），
        查同一位會員的訂單與明細，比較讀取方式與效能。</p>
      <div class="sql-pair">
        <div class="sql-card"><div class="sql-label">A：內嵌（orders_embedded）</div>${code(`{ _id: 77621, customerId: 1, status: "cancelled",
  items: [ { productId: 237, qty: 1, … } ],
  total: 1519 }`)}</div>
        <div class="sql-card"><div class="sql-label">B：參照（order_headers ＋ order_lines）</div>${code(`{ _id: 77621, customerId: 1, status: "cancelled", total: 1519 }
{ orderId: 77621, productId: 237, qty: 1, … }`)}</div>
      </div>
      <div class="lab-controls">
        <button class="btn ghost" type="button" data-ref="prep">建立參照版本</button>
        <span data-ref="state" class="muted"></span>
      </div>
      <div class="lab-controls">
        <label>會員 id <input data-ref="cid" type="number" min="1" max="20000" value="4242" style="width:7em"></label>
        <button class="btn" type="button" data-ref="go">比較</button>
        <button class="btn ghost" type="button" data-ref="idx"></button>
      </div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：什麼時候內嵌、什麼時候參照</summary>
        <div class="explain">★ MongoDB 設計的核心原則：「一起讀取的資料就一起存放」。
內嵌：一次讀取就拿到全部，不用 JOIN；單一文件的更新是原子的（改訂單和明細不需要交易）。
參照：資料不重複，可以單獨查詢、單獨更新；但讀取時要 $lookup（或在程式裡查兩次）。

適合內嵌：一對少量、總是一起讀、子資料不會單獨被查（訂單明細、地址、規格）。
適合參照：一對很多或數量會無限增長（一個商品的所有評論、一位會員的所有訂單）、子資料常被單獨查詢或更新、多對多。
★ 單一文件上限 16 MB；陣列會一直變大的設計（例如把所有訂單都塞進會員文件）是常見的反模式。

這個實驗也示範了：$lookup 的 foreignField 沒有索引時，每一筆都要掃過整個 order_lines，docsExamined 會暴增。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let status = null;
    const show = (s) => {
      status = s;
      $("state").innerHTML = s.ready
        ? `已建立：order_headers ${fmt(s.headers)} 份（平均 ${s.headerAvg} bytes）、order_lines ${fmt(s.lines)} 份（平均 ${s.lineAvg} bytes）；內嵌版平均 ${s.embeddedAvg} bytes`
        : "還沒建立參照版本。";
      $("idx").textContent = s.linesIndexed ? "刪除 order_lines.orderId 的索引" : "替 order_lines.orderId 建索引";
      $("idx").disabled = !s.ready;
      $("go").disabled = !s.ready;
    };
    api(`${API}/embed`).then((s) => alive && show(s));
    $("prep").addEventListener("click", () => act($("prep"), $("out"), async () => {
      $("state").textContent = "建立中（約 2 秒）…";
      show(await postJson(`${API}/embed/prepare`, {}));
    }));
    $("idx").addEventListener("click", () => act($("idx"), $("out"), async () => {
      show(await api(`${API}/embed/index?create=${!status.linesIndexed}`, { method: "POST" }));
    }));
    $("go").addEventListener("click", () => act($("go"), $("out"), async () => {
      $("out").innerHTML = '<p class="muted">執行中…</p>';
      const r = await api(`${API}/embed/compare?customerId=${encodeURIComponent($("cid").value)}`, { method: "POST" });
      if (!alive) return;
      const max = Math.max(...r.map((x) => x.millis));
      $("out").innerHTML = `
        <div class="bars">${r.map((x, i) => `<div class="bar-row"><span>${esc(x.label)}</span>
          <span class="bar-track"><i class="${i === 0 ? "fast" : "slow"}" style="width:${Math.max(0.8, (x.millis / max) * 100)}%"></i></span><b>${x.millis.toFixed(1)} ms</b></div>`).join("")}</div>
        <div class="result"><table><thead><tr><th>做法</th><th class="num">訂單</th><th class="num">明細</th><th class="num">讀了幾份文件</th><th class="num">耗時</th></tr></thead><tbody>
          ${r.map((x) => `<tr><td>${esc(x.label)}</td><td class="num">${fmt(x.orders)}</td><td class="num">${fmt(x.items)}</td>
            <td class="num"><b>${fmt(x.docsExamined)}</b></td><td class="num">${x.millis.toFixed(1)} ms</td></tr>`).join("")}</tbody></table></div>
        <div class="sql-pair">${r.map((x) => `<div class="sql-card"><div class="sql-label">${esc(x.label)} 的指令</div>${code(x.command)}</div>`).join("")}</div>
        <p class="muted">${status.linesIndexed ? "order_lines 有 orderId 索引：$lookup 每筆訂單直接用索引找到明細。" : "order_lines 沒有索引：每一筆訂單都要掃過全部 20 萬份明細。試試看建立索引再比較一次。"}</p>`;
    }));
  }

  // ------------------------------------------------------------ 索引與 explain
  function index(body) {
    body.innerHTML = `
      <div class="bench">
        <div class="bench-head"><div><span class="eyebrow">實驗對象</span><h3 class="mono">shop.orders</h3>
          <p class="muted">8 萬份訂單；除了 _id，索引都要自己建</p></div>
          <button class="btn ghost" type="button" data-ref="reset">重置：刪掉所有索引</button></div>
        <div class="index-list" data-ref="indexes"></div>
        <div class="ddl-row"><label class="sr-only" for="mongo-ddl">建立或刪除索引</label>
          <input id="mongo-ddl" data-ref="ddl" class="mono" spellcheck="false" placeholder='db.orders.createIndex({ 欄位: 1 })　或　db.orders.dropIndex("索引名稱")'>
          <button class="btn" type="button" data-ref="ddl-run">執行</button></div>
        <div class="status" data-ref="ddl-status"></div>
      </div>
      <div class="variant-row" data-ref="steps"></div>
      <div class="prompt" data-ref="goal"></div>
      <div data-ref="suggest"></div>
      <div class="variant-row" data-ref="variants"></div>
      <label for="mongo-explain">查詢（會自動加上 .explain("executionStats")，Ctrl+Enter 執行）</label>
      <textarea id="mongo-explain" data-ref="q" spellcheck="false" style="min-height:70px"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">執行 explain</button></div>
      <p class="question" data-ref="question"></p>
      <div data-ref="result"></div>
      <details class="takeaway"><summary>看解說</summary><div class="explain" data-ref="takeaway"></div></details>
      <div data-ref="history"></div>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let steps = [], step = null, label = "", indexCount = 0;
    const history = [];

    const renderIndexes = (list) => {
      indexCount = list.length - 1;
      $("indexes").innerHTML = `<table class="idx-table"><thead><tr><th>索引名稱</th><th>欄位</th><th>大小</th><th></th></tr></thead><tbody>
        ${list.map((i) => `<tr><td class="mono">${esc(i.name)}</td><td class="mono def">${esc(JSON.stringify(i.key))}</td><td class="num">${esc(i.size)}</td>
          <td>${i.name === "_id_" ? "" : `<button class="btn ghost small" type="button" data-drop="${esc(i.name)}">刪除</button>`}</td></tr>`).join("")}</tbody></table>`;
    };
    const ddl = (cmd) => act($("ddl-run"), $("ddl-status"), async () => {
      $("ddl-status").innerHTML = `<span class="running"><span class="spinner"></span>執行中：<span class="mono">${esc(cmd)}</span></span>`;
      const t = performance.now();
      renderIndexes(await postJson(`${API}/indexes`, { commands: cmd }));
      $("ddl-status").innerHTML = `<span class="chip">完成：<span class="mono">${esc(cmd)}</span>（${(performance.now() - t).toFixed(0)} ms）</span>`;
    });
    api(`${API}/indexes`).then((l) => alive && renderIndexes(l));
    $("ddl-run").addEventListener("click", () => { if ($("ddl").value.trim()) ddl($("ddl").value.trim()); });
    $("ddl").addEventListener("keydown", (e) => { if (e.key === "Enter") $("ddl-run").click(); });
    $("indexes").addEventListener("click", (e) => { const b = e.target.closest("[data-drop]"); if (b) ddl(`db.orders.dropIndex("${b.dataset.drop}")`); });
    $("reset").addEventListener("click", () => act($("reset"), $("ddl-status"), async () => {
      renderIndexes(await postJson(`${API}/indexes/reset`, {}));
      $("ddl-status").innerHTML = '<span class="chip">已刪除 _id 以外的所有索引</span>';
    }));

    api(`${API}/steps`).then((list) => {
      if (!alive) return;
      steps = list;
      $("steps").innerHTML = '<span class="muted" style="align-self:center">步驟：</span>' +
        steps.map((s, i) => `<button type="button" class="option small" data-step="${i}">${i + 1}. ${esc(s.title)}</button>`).join("");
      select(store.get("mongo-index-step", 0));
    });
    $("steps").addEventListener("click", (e) => { const b = e.target.closest("[data-step]"); if (b) select(Number(b.dataset.step)); });

    function select(i) {
      step = steps[i] ?? steps[0];
      store.set("mongo-index-step", steps.indexOf(step));
      $("steps").querySelectorAll("[data-step]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.step) === steps.indexOf(step)));
      $("goal").textContent = step.goal.trim();
      $("question").textContent = "想一想：" + step.question;
      $("takeaway").textContent = step.takeaway.trim();
      body.querySelector(".takeaway").open = false;
      $("suggest").innerHTML = step.indexes.length ? `<div class="suggest"><span class="muted">這一步會用到：</span>${step.indexes.map((d, k) => `
        <span class="suggest-item"><code>${esc(d)}</code><button class="btn ghost small" type="button" data-ddl="${k}">執行</button></span>`).join("")}</div>` : "";
      $("suggest").querySelectorAll("[data-ddl]").forEach((b) => b.addEventListener("click", () => ddl(step.indexes[Number(b.dataset.ddl)])));
      $("variants").innerHTML = step.queries.length > 1 ? step.queries.map((q, k) => `<button type="button" class="option small" data-q="${k}">${esc(q.label)}</button>`).join("") : "";
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.addEventListener("click", () => load(Number(b.dataset.q))));
      load(0);
    }
    function load(k) {
      label = step.queries[k].label;
      $("q").value = step.queries[k].command;
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.q) === k));
      $("result").innerHTML = "";
    }

    async function run() {
      await act($("run"), $("result"), async () => {
        const r = await postJson(`${API}/explain`, { commands: $("q").value });
        if (!alive) return;
        const stages = [...r.stages].reverse();
        $("result").innerHTML = `
          <div class="status">
            ${r.stages.map((s) => `<span class="chip ${s === "COLLSCAN" ? "bad" : s === "SORT" ? "warn" : /IXSCAN|COVERED/.test(s) ? "good" : ""}">${esc(s)}</span>`).join("")}
            ${r.indexName ? `<span class="chip">索引 <b>${esc(r.indexName)}</b>${r.multiKey ? "（多鍵）" : ""}</span>` : ""}
          </div>
          <div class="stage-flow">${stages.map((s) => `<span class="stage-box ${s === "COLLSCAN" ? "bad" : s === "SORT" ? "warn" : /IXSCAN|COVERED/.test(s) ? "good" : ""}">${esc(s)}</span>`).join('<span class="arrow">→</span>')}<span class="arrow">→</span><span class="stage-box">回傳</span></div>
          <div class="stats">
            <div class="stat"><b>${fmt(r.nReturned)}</b><span>nReturned 回傳</span></div>
            <div class="stat"><b>${fmt(r.keysExamined)}</b><span>keysExamined 看過的索引項目</span></div>
            <div class="stat ${r.docsExamined > r.nReturned * 10 && r.docsExamined > 100 ? "stat-bad" : ""}"><b>${fmt(r.docsExamined)}</b><span>docsExamined 讀過的文件</span></div>
            <div class="stat"><b>${fmt(r.millis)} ms</b><span>executionTimeMillis</span></div>
          </div>
          <details><summary>winningPlan 原始內容</summary><pre class="cli mongo">${formatValue(r.plan)}</pre></details>`;
        history.unshift({ step: steps.indexOf(step) + 1, label, plan: r.stages.join(" ← "), keys: r.keysExamined, docs: r.docsExamined, ret: r.nReturned, ms: r.millis, idx: indexCount });
        history.length = Math.min(history.length, 12);
        $("history").innerHTML = `<h4 class="sub-head">這次的執行紀錄</h4><div class="result"><table>
          <thead><tr><th>步驟</th><th>查詢</th><th>計畫</th><th class="num">keys</th><th class="num">docs</th><th class="num">回傳</th><th class="num">ms</th><th class="num">索引數</th></tr></thead>
          <tbody>${history.map((h) => `<tr><td class="num">${h.step}</td><td>${esc(h.label)}</td><td class="mono">${esc(h.plan)}</td>
            <td class="num">${fmt(h.keys)}</td><td class="num">${fmt(h.docs)}</td><td class="num">${fmt(h.ret)}</td><td class="num">${h.ms}</td><td class="num">${h.idx}</td></tr>`).join("")}</tbody></table></div>`;
      });
    }
    $("run").addEventListener("click", run);
    onCtrlEnter($("q"), run);
  }

  // ------------------------------------------------------------ 聚合管線逐步看
  function pipe(body) {
    body.innerHTML = `
      <p class="desc">輸入一個聚合管線，看資料流過每一個 stage 之後剩幾份文件、長什麼樣子（各顯示前 3 份）。可以直接修改管線再執行。</p>
      <div class="variant-row" data-ref="presets"><span class="muted" style="align-self:center">範例：</span>
        ${PIPELINES.map((p, i) => `<button type="button" class="option small" data-p="${i}">${esc(p.label)}</button>`).join("")}</div>
      <label for="mongo-pipe">管線（Ctrl+Enter 執行）</label>
      <textarea id="mongo-pipe" data-ref="cmd" spellcheck="false" style="min-height:170px"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">逐步執行</button></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：常用的 stage</summary>
        <div class="explain">$match 篩選（放越前面越好，開頭的 $match 可以用索引）、$project / $addFields / $set 決定或新增欄位、
$group 分組彙總（$sum、$avg、$min、$max、$first、$push、$addToSet）、$sort、$limit、$skip、
$unwind 攤平陣列、$lookup 關聯其他集合、$bucket 分級、$facet 一次算多組結果、$out / $merge 把結果寫進集合。

對照 SQL：$match ≈ WHERE、$group ≈ GROUP BY、$project ≈ SELECT、$sort ≈ ORDER BY、$lookup ≈ LEFT JOIN、$unwind ≈ unnest。
★ 每個 stage 的記憶體上限是 100 MB，超過要加 allowDiskUse（MongoDB 6.0 起預設允許）。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    $("cmd").value = store.get("mongo-pipe-draft", PIPELINES[0].cmd);
    $("presets").addEventListener("click", (e) => {
      const b = e.target.closest("[data-p]");
      if (!b) return;
      $("cmd").value = PIPELINES[Number(b.dataset.p)].cmd;
      store.set("mongo-pipe-draft", $("cmd").value);
    });
    $("cmd").addEventListener("input", () => store.set("mongo-pipe-draft", $("cmd").value));
    async function run() {
      await act($("run"), $("out"), async () => {
        $("out").innerHTML = '<p class="muted">執行中…</p>';
        const stages = await postJson(`${API}/pipeline`, { commands: $("cmd").value });
        if (!alive) return;
        const max = Math.max(...stages.map((s) => s.count), 1);
        $("out").innerHTML = stages.map((s) => `
          <div class="stage-card">
            <div class="stage-head"><span class="stage-no">${s.index === 0 ? "輸入" : "Stage " + s.index}</span>
              <code>${esc(s.index === 0 ? "（原始集合）" : s.stage)}</code></div>
            <div class="bar-row"><span>${fmt(s.count)} 份文件</span><span class="bar-track"><i class="fast" style="width:${Math.max(0.6, (s.count / max) * 100)}%"></i></span><b>${s.millis.toFixed(0)} ms</b></div>
            <details ${s.index === stages.length - 1 ? "open" : ""}><summary>前 ${s.sample.length} 份</summary>
              <pre class="cli mongo">${s.sample.map((d) => formatValue(d)).join("\n")}</pre></details>
          </div>`).join("");
      });
    }
    $("run").addEventListener("click", run);
    onCtrlEnter($("cmd"), run);
  }

  open(store.get("mongo-lab-sec", "embed"));
  return () => { alive = false; };
}
