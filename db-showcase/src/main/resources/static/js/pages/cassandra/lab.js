// Cassandra 實驗室：查詢與表設計（查詢追蹤）、墓碑、一致性等級與輕量交易
import { api, postJson, esc, fmt, onCtrlEnter, store } from "../../lib.js";
import { formatResult, traceView } from "./format.js";

const API = "/api/cassandra/lab";
const SECTIONS = [
  { id: "design", title: "查詢與表設計", sub: "分區鍵、叢集鍵、ALLOW FILTERING、SAI 索引" },
  { id: "tomb", title: "墓碑", sub: "把 Cassandra 當佇列用會發生什麼事" },
  { id: "consistency", title: "一致性與輕量交易", sub: "QUORUM 的計算、庫存超賣、LWT 的代價" },
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
  const RENDER = { design, tomb, consistency };

  function open(id) {
    const s = SECTIONS.find((x) => x.id === id) ?? SECTIONS[0];
    store.set("cassandra-lab-sec", s.id);
    el.querySelectorAll("[data-sec]").forEach((b) => b.classList.toggle("active", b.dataset.sec === s.id));
    panel.innerHTML = `<div><span class="eyebrow">${esc(s.sub)}</span><h3>${esc(s.title)}</h3></div><div data-ref="body" style="display:grid;grid-template-columns:minmax(0,1fr);gap:14px"></div>`;
    RENDER[s.id](panel.querySelector('[data-ref="body"]'));
  }
  el.querySelector("nav").addEventListener("click", (e) => { const b = e.target.closest("[data-sec]"); if (b) open(b.dataset.sec); });

  async function act(button, statusEl, fn) {
    button.disabled = true;
    try { await fn(); } catch (e) { if (alive) statusEl.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
    finally { button.disabled = false; }
  }
  const running = (text) => `<span class="running"><span class="spinner"></span>${esc(text)}</span>`;
  const way = (t) => !t ? "—" : t.indexUsed ? "SAI 索引" : t.rangeScan ? "範圍掃描" : t.partitions > 1 ? `${t.partitions} 個分區` : "單一分區";

  // ------------------------------------------------------------ 查詢與表設計
  function design(body) {
    body.innerHTML = `
      <div class="bench">
        <div class="bench-head"><div><span class="eyebrow">shop 上的索引</span><h3>SAI 索引</h3>
          <p class="muted">預設沒有任何索引。建立後會等索引建好（約 1～2 秒）才回應。</p></div>
          <button class="btn ghost" type="button" data-ref="reset">刪除全部索引</button></div>
        <div class="index-list" data-ref="indexes"></div>
        <div class="ddl-row"><label class="sr-only" for="cassandra-ddl">建立或刪除索引</label>
          <input id="cassandra-ddl" data-ref="ddl" class="mono" spellcheck="false" placeholder="CREATE INDEX 名稱 ON 表 (欄位) USING 'sai'　或　DROP INDEX 名稱">
          <button class="btn" type="button" data-ref="ddl-run">執行</button></div>
        <div class="status" data-ref="ddl-status"></div>
      </div>
      <div class="variant-row" data-ref="steps"></div>
      <div class="prompt" data-ref="goal"></div>
      <div data-ref="suggest"></div>
      <div class="variant-row" data-ref="variants"></div>
      <label for="cassandra-trace">查詢（一句，會開啟查詢追蹤；Ctrl+Enter 執行）</label>
      <textarea id="cassandra-trace" data-ref="q" spellcheck="false" style="min-height:80px"></textarea>
      <div class="actions">
        <button class="btn" type="button" data-ref="run">執行並追蹤</button>
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
      $("indexes").innerHTML = list.length === 0 ? '<p class="muted">目前沒有索引。</p>'
        : `<table class="idx-table"><thead><tr><th>索引名稱</th><th>資料表</th><th>欄位</th><th>種類</th><th></th></tr></thead><tbody>
        ${list.map((i) => `<tr><td class="mono">${esc(i.name)}</td><td class="mono">${esc(i.table)}</td><td class="mono def">${esc(i.target)}</td>
          <td>${esc(i.kind)}${i.queryable ? "" : "（建立中）"}</td><td><button class="btn ghost small" type="button" data-drop="${esc(i.name)}">刪除</button></td></tr>`).join("")}</tbody></table>`;
    };
    const ddl = (cmd) => act($("ddl-run"), $("ddl-status"), async () => {
      $("ddl-status").innerHTML = running("執行中：" + cmd);
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
      $("ddl-status").innerHTML = '<span class="chip">已刪除 shop 上的所有索引</span>';
    }));

    api(`${API}/steps`).then((list) => {
      if (!alive) return;
      steps = list;
      $("steps").innerHTML = '<span class="muted" style="align-self:center">步驟：</span>' +
        steps.map((s, i) => `<button type="button" class="option small" data-step="${i}">${i + 1}. ${esc(s.title)}</button>`).join("");
      select(store.get("cassandra-design-step", 0));
    });
    $("steps").addEventListener("click", (e) => { const b = e.target.closest("[data-step]"); if (b) select(Number(b.dataset.step)); });

    function select(i) {
      step = steps[i] ?? steps[0];
      store.set("cassandra-design-step", steps.indexOf(step));
      $("steps").querySelectorAll("[data-step]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.step) === steps.indexOf(step)));
      $("goal").textContent = step.goal.trim();
      $("question").textContent = "想一想：" + step.question;
      $("takeaway").textContent = step.takeaway.trim();
      body.querySelector(".takeaway").open = false;
      const idx = step.indexes ?? [];
      $("suggest").innerHTML = idx.length ? `<div class="suggest"><span class="muted">這一步會用到：</span>${idx.map((d, k) => `
        <span class="suggest-item"><code>${esc(d)}</code><button class="btn ghost small" type="button" data-ddl="${k}">執行</button></span>`).join("")}</div>` : "";
      $("suggest").querySelectorAll("[data-ddl]").forEach((b) => b.addEventListener("click", () => ddl(idx[Number(b.dataset.ddl)])));
      $("variants").innerHTML = step.queries.map((q, k) => `<button type="button" class="option small" data-q="${k}">${esc(q.label)}</button>`).join("");
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.addEventListener("click", () => load(Number(b.dataset.q))));
      load(0);
    }
    function load(k) {
      label = step.queries[k].label;
      $("q").value = step.queries[k].cql.trim();
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.q) === k));
      $("result").innerHTML = "";
    }

    function remember(r, lbl) {
      const t = r.trace;
      history.unshift({ step: steps.indexOf(step) + 1, label: lbl, way: r.kind === "error" ? "錯誤" : way(t), rows: r.total,
        live: t?.liveRows, tomb: t?.tombstones, ms: t ? t.durationMicros / 1000 : null });
      history.length = Math.min(history.length, 14);
      $("history").innerHTML = `<h4 class="sub-head">這次的執行紀錄</h4><div class="result"><table>
        <thead><tr><th>步驟</th><th>查詢</th><th>讀取方式</th><th class="num">回傳</th><th class="num">讀取列數</th><th class="num">墓碑</th><th class="num">伺服器 ms</th></tr></thead>
        <tbody>${history.map((h) => `<tr><td class="num">${h.step}</td><td>${esc(h.label)}</td><td>${esc(h.way)}</td>
          <td class="num">${h.rows == null ? "—" : fmt(h.rows)}</td><td class="num">${h.live == null ? "—" : fmt(h.live)}</td>
          <td class="num">${h.tomb == null ? "—" : fmt(h.tomb)}</td><td class="num">${h.ms == null ? "—" : h.ms.toFixed(1)}</td></tr>`).join("")}</tbody></table></div>`;
    }

    async function run() {
      await act($("run"), $("result"), async () => {
        $("result").innerHTML = running("執行中…");
        const r = await postJson(`${API}/trace`, { commands: $("q").value });
        if (!alive) return;
        $("result").innerHTML = formatResult(r);
        remember(r, label);
      });
    }
    $("run").addEventListener("click", run);
    onCtrlEnter($("q"), run);

    $("all").addEventListener("click", () => act($("all"), $("result"), async () => {
      const results = [];
      for (const q of step.queries) {
        $("result").innerHTML = running(`執行中：${q.label}`);
        results.push(await postJson(`${API}/trace`, { commands: q.cql }));
        if (!alive) return;
        remember(results.at(-1), q.label);
      }
      const max = Math.max(1, ...results.map((r) => r.trace?.liveRows ?? 0));
      $("result").innerHTML = `
        <div class="bars">${results.map((r, i) => `<div class="bar-row"><span>${esc(step.queries[i].label)}</span>
          <span class="bar-track"><i class="${r.trace?.rangeScan ? "slow" : "fast"}" style="width:${r.trace ? Math.max(0.8, (r.trace.liveRows / max) * 100) : 0}%"></i></span>
          <b>${r.trace ? fmt(r.trace.liveRows) + " 列" : "錯誤"}</b></div>`).join("")}</div>
        <p class="muted">長條是「伺服器讀取的資料列數」。</p>
        <div class="sql-pair">${results.map((r, i) => `<div class="sql-card"><div class="sql-label">${esc(step.queries[i].label)}</div>
          ${code(step.queries[i].cql)}${formatResult(r)}</div>`).join("")}</div>`;
    }));
  }

  // ------------------------------------------------------------ 墓碑
  function tomb(body) {
    body.innerHTML = `
      <p class="desc">情境：把「待處理的訂單通知」放在 Cassandra 當佇列，處理完一則就刪一則。
        lab.queue 的主鍵是 ((queue), msg_id)，建立三個分區，各放 N 則訊息：</p>
      <div class="sql-pair">
        <div class="sql-card"><div class="sql-label">fresh：沒刪過</div>${code("-- 對照組，什麼都不刪")}</div>
        <div class="sql-card"><div class="sql-label">row：逐筆刪除（留下最後 10 則）</div>${code("DELETE FROM queue WHERE queue = 'row' AND msg_id = 1;\nDELETE FROM queue WHERE queue = 'row' AND msg_id = 2;\n…")}</div>
        <div class="sql-card"><div class="sql-label">range：範圍刪除（留下最後 10 則）</div>${code("DELETE FROM queue WHERE queue = 'range' AND msg_id <= N - 10;")}</div>
      </div>
      <div class="lab-controls">
        <label>每個分區的訊息數 <select data-ref="n"><option>1000</option><option selected>10000</option><option>50000</option></select></label>
        <button class="btn ghost" type="button" data-ref="prep">建立佇列並刪除</button>
        <button class="btn" type="button" data-ref="read">讀取三個佇列的前 10 則</button>
        <button class="btn ghost" type="button" data-ref="boom">再對 row 刪 100,001 則</button>
      </div>
      <div class="status" data-ref="state"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：墓碑是什麼、為什麼佇列是反模式</summary>
        <div class="explain">Cassandra 的資料檔（SSTable）寫入後就不會再修改，所以「刪除」其實是寫入一個墓碑（tombstone），
標記「這筆資料在這個時間點被刪掉了」。讀取時要讀到墓碑，才知道要把舊資料跳過。

逐筆刪除：每刪一則就多一個墓碑。讀「前 10 則」時，要先跳過前面 9,990 個墓碑才找得到活的資料，
伺服器會回傳警告（超過 tombstone_warn_threshold 1,000）；超過 tombstone_failure_threshold（100,000）時，查詢直接失敗。
範圍刪除：一句 DELETE … WHERE msg_id <= N 只寫一個「範圍墓碑」（兩個邊界），讀取時一次就跳過整段。

墓碑要等 gc_grace_seconds（預設 10 天）過後、compaction 時才會真的清掉；這段時間是為了讓下線的複本回來時，也能知道資料被刪了（否則被刪的資料會「復活」）。

★ 所以「佇列」「最新狀態一直被覆寫再刪除」這類使用方式是 Cassandra 的經典反模式。
真的要做：依時間分桶（每小時一個分區）、用範圍刪除或 TTL 讓整個分區一起過期，並搭配 TimeWindowCompactionStrategy。
或者直接用專門的工具（Kafka、Redis Stream、RabbitMQ）。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const show = (s) => {
      $("state").innerHTML = s.ready
        ? `<span class="chip">每個分區 <b>${fmt(s.messages)}</b> 則；row 逐筆刪了 <b>${fmt(s.deleted)}</b> 則${s.overwhelmed ? `，另外又刪了 <b>${fmt(s.overwhelmed)}</b> 則` : ""}</span>
           <span class="chip">寫入 ${fmt(Math.round(s.insertMillis))} ms · 刪除 ${fmt(Math.round(s.deleteMillis))} ms</span>`
        : '<span class="muted">還沒建立佇列。</span>';
      $("read").disabled = !s.ready;
      $("boom").disabled = !s.ready || s.overwhelmed > 0;
    };
    api(`${API}/tombstones`).then((s) => alive && show(s));
    $("prep").addEventListener("click", () => act($("prep"), $("out"), async () => {
      $("state").innerHTML = running("寫入並刪除中…");
      $("out").innerHTML = "";
      show(await postJson(`${API}/tombstones/prepare?messages=${$("n").value}`, {}));
    }));
    $("boom").addEventListener("click", () => act($("boom"), $("out"), async () => {
      $("state").innerHTML = running("寫入 100,001 個墓碑中（約 2 秒）…");
      show(await postJson(`${API}/tombstones/overwhelm`, {}));
      $("read").click();
    }));
    $("read").addEventListener("click", () => act($("read"), $("out"), async () => {
      $("out").innerHTML = running("讀取中…");
      const r = await postJson(`${API}/tombstones/read`, {});
      if (!alive) return;
      $("out").innerHTML = `
        <div class="result"><table><thead><tr><th>分區</th><th class="num">回傳</th><th class="num">讀取列數</th><th class="num">跳過的墓碑</th><th class="num">伺服器 ms</th></tr></thead><tbody>
          ${r.map((x) => `<tr><td>${esc(x.partition)}（${esc(x.label)}）</td>
            <td class="num">${x.result.kind === "rows" ? fmt(x.result.total) : "失敗"}</td>
            <td class="num">${x.result.trace ? fmt(x.result.trace.liveRows) : "—"}</td>
            <td class="num"><b>${x.result.trace ? fmt(x.result.trace.tombstones) : "—"}</b></td>
            <td class="num">${x.result.trace ? (x.result.trace.durationMicros / 1000).toFixed(1) : "—"}</td></tr>`).join("")}</tbody></table></div>
        <div class="sql-pair">${r.map((x) => `<div class="sql-card"><div class="sql-label">${esc(x.partition)}（${esc(x.label)}）</div>
          ${code(x.result.statement)}${formatResult(x.result)}</div>`).join("")}</div>`;
    }));
  }

  // ------------------------------------------------------------ 一致性與輕量交易
  function consistency(body) {
    body.innerHTML = `
      <h4 class="sub-head">1. 一致性等級：要幾個複本回應才算成功</h4>
      <p class="desc">每一次讀寫都可以指定一致性等級（CL）。lab 的複本數（RF）是 1，lab_rf3 是 3，但這個叢集只有 <b>1 個節點</b>：
        RF = 3 的 keyspace 實際上只放得下 1 份複本。同一句讀 / 寫分別用 ONE、QUORUM、ALL 執行：</p>
      <div class="lab-controls"><button class="btn" type="button" data-ref="cl">執行</button></div>
      <div data-ref="cl-out"></div>
      <details class="takeaway"><summary>看解說：QUORUM 怎麼算、R + W > RF</summary>
        <div class="explain">QUORUM = ⌊RF / 2⌋ + 1。RF = 3 時 QUORUM 是 2：3 份複本裡有 2 份回應就算成功，所以可以容忍 1 台掛掉。
ONE 只要 1 份、ALL 要全部。上面 RF = 3 的錯誤「2 required but only 1 alive」就是這個計算：需要 2 份，但活著的只有 1 份。

★ 讀寫都用 QUORUM 時，R（2）+ W（2）> RF（3），讀到的複本裡一定至少有一份是最新寫入的，所以是強一致。
寫 ONE、讀 ONE 最快，但可能讀到舊資料（最終一致），之後靠 read repair、hinted handoff、nodetool repair 補齊。
多機房時常用 LOCAL_QUORUM：只在本地機房湊 quorum，不必等跨機房的網路延遲。

這個實驗只有一個節點，看不到「節點掛掉」的效果；但錯誤訊息裡的數字，就是面試時要能算出來的東西。</div>
      </details>

      <h4 class="sub-head">2. 庫存超賣：先讀再寫 vs IF 條件寫入</h4>
      <p class="desc">lab.stock 有一件商品，很多人同時下單。每個人：讀出庫存 → 如果大於 0 → 寫回「庫存 − 1」。</p>
      <div class="sql-pair">
        <div class="sql-card"><div class="sql-label">A：先讀再寫</div>${code("SELECT stock FROM stock WHERE sku = 'iphone';\n-- 讀到 s，s > 0 的話：\nUPDATE stock SET stock = s - 1 WHERE sku = 'iphone';")}</div>
        <div class="sql-card"><div class="sql-label">B：輕量交易（LWT）</div>${code("SELECT stock FROM stock WHERE sku = 'iphone';   -- SERIAL\nUPDATE stock SET stock = s - 1 WHERE sku = 'iphone' IF stock = s;\n-- [applied] = false 代表被別人搶先，重讀再試")}</div>
      </div>
      <div class="lab-controls">
        <label>同時搶購 <input data-ref="buyers" type="number" min="2" max="200" value="50" style="width:5em"> 人</label>
        <label>庫存 <input data-ref="stock" type="number" min="1" max="100" value="10" style="width:5em"> 件</label>
        <button class="btn ghost" type="button" data-ref="naive">A：先讀再寫</button>
        <button class="btn" type="button" data-ref="lwt">B：IF 條件寫入</button>
      </div>
      <div data-ref="sell-out"></div>
      <details class="takeaway"><summary>看解說：為什麼 A 會超賣</summary>
        <div class="explain">A 的「讀」和「寫」是兩個獨立的請求，中間沒有任何鎖：50 個人幾乎同時讀到 10，各自寫回 9，每個人都以為自己買到了。
Cassandra 沒有交易、也沒有 SELECT … FOR UPDATE；counter 雖然可以原子地加減，但不能加上「大於 0 才扣」的條件。

B 用 IF stock = s（compare-and-set）：寫入前用 Paxos 確認值還是 s，不是的話就不寫入（[applied] = false）。
代價是延遲大很多（Paxos 要好幾次來回），而且大家搶同一個分區時會互相衝突、一直重試。

★ 實務上：真正的庫存扣減通常放在關聯式資料庫或 Redis（Lua / DECR），Cassandra 的 LWT 用在低頻、需要唯一性的地方，
例如「帳號不能重複註冊」「同一張優惠券只能領一次」。Cassandra 5.0 之後的 Accord（ACID 交易）還在發展中。</div>
      </details>

      <h4 class="sub-head">3. 輕量交易的代價：一般寫入 vs IF NOT EXISTS</h4>
      <div class="lab-controls"><button class="btn ghost" type="button" data-ref="cost">兩種寫入各追蹤一次</button></div>
      <div data-ref="cost-out"></div>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);

    $("cl").addEventListener("click", () => act($("cl"), $("cl-out"), async () => {
      $("cl-out").innerHTML = running("執行中…");
      const r = await postJson(`${API}/consistency`, {});
      if (!alive) return;
      const rows = [];
      for (const ks of ["lab", "lab_rf3"]) {
        for (const cl of ["ONE", "QUORUM", "ALL"]) {
          const w = r.find((x) => x.keyspace === ks && x.level === cl && x.operation === "寫入");
          const rd = r.find((x) => x.keyspace === ks && x.level === cl && x.operation === "讀取");
          const need = cl === "ONE" ? 1 : cl === "QUORUM" ? Math.floor(w.replicationFactor / 2) + 1 : w.replicationFactor;
          rows.push(`<tr><td class="mono">${ks}</td><td class="num">${w.replicationFactor}</td><td class="mono">${cl}</td><td class="num">${need}</td>
            ${[w, rd].map((x) => `<td class="${x.ok ? "ok-cell" : "bad-cell"}">${x.ok ? "✓ " + esc(x.message) : "✗ " + esc(x.message)}</td>`).join("")}</tr>`);
        }
      }
      $("cl-out").innerHTML = `<div class="result cl-table"><table><thead><tr><th>keyspace</th><th class="num">RF</th><th>CL</th><th class="num">需要幾份回應</th><th>寫入</th><th>讀取</th></tr></thead>
        <tbody>${rows.join("")}</tbody></table></div>`;
    }));

    const sells = [];
    const sell = (mode, btn) => act(btn, $("sell-out"), async () => {
      const buyers = $("buyers").value, stock = $("stock").value;
      const r = await postJson(`${API}/oversell?mode=${mode}&buyers=${encodeURIComponent(buyers)}&stock=${encodeURIComponent(stock)}`, {});
      if (!alive) return;
      sells.unshift(r);
      sells.length = Math.min(sells.length, 8);
      $("sell-out").innerHTML = `
        <div class="stats">
          <div class="stat"><b>${fmt(r.sold)}</b><span>賣出（庫存 ${fmt(r.stock)}）</span></div>
          <div class="stat ${r.oversold ? "stat-bad" : "stat-good"}"><b>${fmt(r.oversold)}</b><span>超賣</span></div>
          <div class="stat"><b>${fmt(r.finalStock)}</b><span>最後的庫存欄位</span></div>
          <div class="stat"><b>${r.millis.toFixed(0)} ms</b><span>${fmt(r.buyers)} 人全部完成</span></div>
          ${r.mode === "lwt" ? `<div class="stat"><b>${fmt(r.conflicts)}</b><span>[applied] = false 後重試</span></div>` : ""}
        </div>
        ${r.sample ? `<p class="muted">例如：${esc(r.sample)}</p>` : ""}
        <div class="result"><table><thead><tr><th>做法</th><th class="num">人數</th><th class="num">庫存</th><th class="num">賣出</th><th class="num">超賣</th><th class="num">重試</th><th class="num">錯誤</th><th class="num">ms</th></tr></thead>
          <tbody>${sells.map((x) => `<tr><td>${x.mode === "lwt" ? "B：IF 條件寫入" : "A：先讀再寫"}</td><td class="num">${fmt(x.buyers)}</td><td class="num">${fmt(x.stock)}</td>
            <td class="num">${fmt(x.sold)}</td><td class="num ${x.oversold ? "bad-cell" : ""}">${fmt(x.oversold)}</td><td class="num">${fmt(x.conflicts)}</td>
            <td class="num">${fmt(x.errors)}</td><td class="num">${x.millis.toFixed(0)}</td></tr>`).join("")}</tbody></table></div>`;
    });
    $("naive").addEventListener("click", () => sell("naive", $("naive")));
    $("lwt").addEventListener("click", () => sell("lwt", $("lwt")));

    $("cost").addEventListener("click", () => act($("cost"), $("cost-out"), async () => {
      const r = await postJson(`${API}/lwt-cost`, {});
      if (!alive) return;
      $("cost-out").innerHTML = `<div class="sql-pair">${r.map((x, i) => `<div class="sql-card">
          <div class="sql-label">${i === 0 ? "一般寫入" : "輕量交易（IF NOT EXISTS）"}：${fmt(x.trace.eventCount)} 個事件、伺服器 ${(x.trace.durationMicros / 1000).toFixed(1)} ms</div>
          ${code(x.statement)}${x.rows ? formatResult({ ...x, trace: null }) : ""}${traceView(x.trace, { open: true })}</div>`).join("")}</div>
        <p class="muted">一般寫入：決定複本 → 寫 commitlog → 寫 memtable。輕量交易多了 Paxos 的 prepare / promise（Promising ballot）、
          讀取現有的值（Reading existing values for CAS precondition）、propose / accept、commit，每一步在多節點時都是一次網路來回。</p>`;
    }));
  }

  open(store.get("cassandra-lab-sec", "design"));
  return () => { alive = false; };
}
