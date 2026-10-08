// TimescaleDB 實驗室：chunk exclusion、壓縮（segmentby 的影響）、連續聚合（即時聚合與 refresh）
import { api, postJson, esc, fmt, onCtrlEnter, store } from "../../lib.js";

const API = "/api/timescale/lab";
const SECTIONS = [
  { id: "chunks", title: "chunk 與 chunk exclusion", sub: "查詢讀了幾個 chunk" },
  { id: "compression", title: "壓縮（columnstore）", sub: "segmentby 怎麼選、壓縮前後的大小與速度" },
  { id: "cagg", title: "連續聚合", sub: "物化、即時聚合、refresh" },
];
const code = (s) => `<pre class="code">${esc(String(s).trim())}</pre>`;
const mb = (b) => (b == null ? "—" : (b / 1048576).toFixed(1) + " MB");
const cell = (v) => (v == null ? "—" : Array.isArray(v) ? v.map((x) => (typeof x === "number" ? fmt(x) : esc(x))).join("、") : typeof v === "number" ? fmt(v) : esc(v));

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="實驗"><div class="topic">實驗</div>
        ${SECTIONS.map((s, i) => `<button type="button" data-sec="${s.id}">${i + 1}. ${esc(s.title)}</button>`).join("")}</nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;
  const panel = el.querySelector('[data-ref="panel"]');
  let alive = true;
  const RENDER = { chunks, compression, cagg };

  function open(id) {
    const s = SECTIONS.find((x) => x.id === id) ?? SECTIONS[0];
    store.set("ts-lab-sec", s.id);
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

  // ------------------------------------------------------------ chunk exclusion
  function chunks(body) {
    body.innerHTML = `
      <div class="variant-row" data-ref="steps"></div>
      <div class="prompt" data-ref="goal"></div>
      <div class="variant-row" data-ref="variants"></div>
      <label for="ts-explain">查詢（會加上 EXPLAIN ANALYZE，在唯讀交易裡執行；Ctrl+Enter 執行）</label>
      <textarea id="ts-explain" data-ref="q" spellcheck="false" style="min-height:70px"></textarea>
      <div class="actions">
        <button class="btn" type="button" data-ref="run">EXPLAIN ANALYZE</button>
        <button class="btn ghost" type="button" data-ref="all">這一步的查詢全部執行、並排比較</button>
      </div>
      <p class="question" data-ref="question"></p>
      <div data-ref="result"></div>
      <details class="takeaway"><summary>看解說</summary><div class="explain" data-ref="takeaway"></div></details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let steps = [], step = null;

    const view = (r) => `
      <div class="stats">
        <div class="stat ${r.chunks === r.totalChunks && r.totalChunks > 1 ? "stat-bad" : "stat-good"}"><b>${r.chunks} / ${r.totalChunks}</b><span>讀到的 chunk（page_views 共 ${r.totalChunks} 個）</span></div>
        <div class="stat"><b>${r.executionMs == null ? "—" : r.executionMs.toFixed(1) + " ms"}</b><span>執行時間</span></div>
        <div class="stat"><b>${cell(r.value)}</b><span>查詢結果</span></div>
      </div>
      <details><summary>執行計畫（${r.plan.length} 行）</summary><pre class="code ts-plan">${esc(r.plan.join("\n"))}</pre></details>`;

    api(`${API}/steps`).then((list) => {
      if (!alive) return;
      steps = list;
      $("steps").innerHTML = '<span class="muted" style="align-self:center">步驟：</span>' +
        steps.map((s, i) => `<button type="button" class="option small" data-step="${i}">${i + 1}. ${esc(s.title)}</button>`).join("");
      select(store.get("ts-chunk-step", 0));
    });
    $("steps").addEventListener("click", (e) => { const b = e.target.closest("[data-step]"); if (b) select(Number(b.dataset.step)); });

    function select(i) {
      step = steps[i] ?? steps[0];
      store.set("ts-chunk-step", steps.indexOf(step));
      $("steps").querySelectorAll("[data-step]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.step) === steps.indexOf(step)));
      $("goal").textContent = step.goal.trim();
      $("question").textContent = "想一想：" + step.question;
      $("takeaway").textContent = step.takeaway.trim();
      body.querySelector(".takeaway").open = false;
      $("variants").innerHTML = step.queries.map((q, k) => `<button type="button" class="option small" data-q="${k}">${esc(q.label)}</button>`).join("");
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.addEventListener("click", () => load(Number(b.dataset.q))));
      load(0);
    }
    function load(k) {
      $("q").value = step.queries[k].sql.trim();
      $("variants").querySelectorAll("[data-q]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.q) === k));
      $("result").innerHTML = "";
    }
    async function run() {
      await act($("run"), $("result"), async () => {
        $("result").innerHTML = running("執行中…");
        const r = await postJson(`${API}/explain`, { sql: $("q").value });
        if (alive) $("result").innerHTML = view(r);
      });
    }
    $("run").addEventListener("click", run);
    onCtrlEnter($("q"), run);
    $("all").addEventListener("click", () => act($("all"), $("result"), async () => {
      const results = [];
      for (const q of step.queries) {
        $("result").innerHTML = running("執行中：" + q.label);
        results.push(await postJson(`${API}/explain`, { sql: q.sql }));
        if (!alive) return;
      }
      $("result").innerHTML = `<div class="sql-pair">${results.map((r, i) => `<div class="sql-card">
        <div class="sql-label">${esc(step.queries[i].label)}</div>${code(step.queries[i].sql)}${view(r)}</div>`).join("")}</div>`;
    }));
  }

  // ------------------------------------------------------------ 壓縮
  function compression(body) {
    body.innerHTML = `
      <p class="desc">把 9 月的瀏覽紀錄（約 33 萬筆）複製到 <code>lab_page_views</code>（每天一個 chunk），再用不同的 <code>segmentby</code> 壓縮，
        比較壓縮後的大小與查詢速度。壓縮會把同一個 chunk 的資料改成「依欄位存放」（columnstore），每 1,000 列一批。</p>
      <div class="lab-controls">
        <button class="btn ghost" type="button" data-ref="prep">建立實驗用的表</button>
        <span>壓縮：</span>
        <button class="btn" type="button" data-seg="none">不分組</button>
        <button class="btn" type="button" data-seg="device">segmentby device</button>
        <button class="btn" type="button" data-seg="product_id">segmentby product_id</button>
        <button class="btn ghost" type="button" data-ref="decomp">全部解壓</button>
        <button class="btn ghost" type="button" data-ref="bench">執行查詢比較</button>
      </div>
      <div class="status" data-ref="state"></div>
      <div data-ref="history"></div>
      <div data-ref="bench-out"></div>
      <details class="takeaway"><summary>看解說：segmentby、orderby 怎麼選</summary>
        <div class="explain">壓縮把一個 chunk 裡的資料，依 segmentby 欄位分組、依 orderby 排序，每 1,000 列打包成一筆，每個欄位用適合的演算法壓縮
（時間用 delta-of-delta、整數用 delta + simple-8b、重複的字串用字典）。

★ segmentby 選「查詢常拿來過濾、而且值的種類不多」的欄位（例如 device、sensor_id、地區）：
  同一組的資料放在一起，查詢時可以只解壓那一組；值的種類不多，每組才湊得滿 1,000 列，壓縮率才高。
  實測：segmentby device 壓縮率最高（約 9～10 倍），而且「各裝置瀏覽數」只要讀 device 那一欄，比壓縮前快很多。
★ 值的種類太多（product_id 有 1,500 種，每天每件商品只有幾筆）：每組只有幾列，壓縮率掉到 2 倍左右。
  但「查某一件商品」變快了，因為可以直接跳到那一組。
orderby 通常放時間（DESC），讓「最新的資料」與時間範圍查詢更快。

壓縮後的 chunk 仍然可以查詢、INSERT、UPDATE、DELETE（2.11 之後），但成本比較高；所以通常只壓縮「不再常變動的舊資料」：
add_compression_policy('page_views', INTERVAL '7 days')。TimescaleDB 2.18 之後也叫 columnstore（convert_to_columnstore）。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const history = [];
    const show = (s) => {
      $("state").innerHTML = !s.prepared ? '<span class="muted">還沒建立實驗用的表。</span>' : `
        <span class="chip">${fmt(s.rows)} 筆，${s.chunks} 個 chunk，已壓縮 <b>${s.compressedChunks}</b> 個</span>
        <span class="chip">目前大小 <b>${mb(s.currentBytes)}</b></span>
        ${s.compressedChunks ? `<span class="chip">segmentby <b>${esc(s.segmentBy || "（不分組）")}</b> · orderby <b>${esc(s.orderBy ?? "")}</b></span>` : ""}
        ${s.message ? `<span class="chip">${esc(s.message)}</span>` : ""}`;
      if (s.compressedChunks && s.beforeBytes) {
        history.unshift({ seg: s.segmentBy || "（不分組）", before: s.beforeBytes, after: s.afterBytes });
        history.length = Math.min(history.length, 6);
        $("history").innerHTML = `<div class="result"><table><thead><tr><th>segmentby</th><th class="num">壓縮前</th><th class="num">壓縮後</th><th class="num">壓縮率</th></tr></thead><tbody>
          ${history.map((h) => `<tr><td class="mono">${esc(h.seg)}</td><td class="num">${mb(h.before)}</td><td class="num">${mb(h.after)}</td>
            <td class="num"><b>${(h.before / h.after).toFixed(1)} 倍</b></td></tr>`).join("")}</tbody></table></div>`;
      }
    };
    api(`${API}/compression`).then((s) => alive && show(s)).catch((e) => alive && ($("state").innerHTML = `<div class="error">${esc(e.message)}</div>`));
    $("prep").addEventListener("click", () => act($("prep"), $("state"), async () => {
      $("state").innerHTML = running("複製 9 月的資料中…");
      show(await postJson(`${API}/compression/prepare`, {}));
    }));
    body.querySelectorAll("[data-seg]").forEach((b) => b.addEventListener("click", () => act(b, $("state"), async () => {
      $("state").innerHTML = running(`壓縮中（segmentby ${b.dataset.seg}）…`);
      show(await api(`${API}/compression/compress?segmentBy=${b.dataset.seg}`, { method: "POST" }));
    })));
    $("decomp").addEventListener("click", () => act($("decomp"), $("state"), async () => {
      $("state").innerHTML = running("解壓中…");
      show(await postJson(`${API}/compression/decompress`, {}));
    }));
    $("bench").addEventListener("click", () => act($("bench"), $("bench-out"), async () => {
      $("bench-out").innerHTML = running("執行中（每句跑兩次，取第二次）…");
      const r = await postJson(`${API}/compression/benchmark`, {});
      if (!alive) return;
      const s = await api(`${API}/compression`);
      $("bench-out").innerHTML = `<h4 class="sub-head">目前狀態：${s.compressedChunks ? `已壓縮（segmentby ${esc(s.segmentBy || "不分組")}）` : "沒有壓縮"}</h4>
        <div class="result"><table><thead><tr><th>查詢</th><th>讀取方式</th><th class="num">ms</th><th>結果（第一列）</th></tr></thead><tbody>
        ${r.map((b) => `<tr><td>${esc(b.label)}</td><td class="mono">${esc(b.scan)}</td><td class="num"><b>${b.ms.toFixed(1)}</b></td><td>${cell(b.value)}</td></tr>`).join("")}
        </tbody></table></div>
        <details><summary>查詢內容</summary>${r.map((b) => code(b.sql)).join("")}</details>`;
    }));
  }

  // ------------------------------------------------------------ 連續聚合
  function cagg(body) {
    body.innerHTML = `
      <p class="desc">建立一個跟 page_views_daily 一樣的連續聚合 <code>lab_views_daily</code>（每天 × 每件商品的瀏覽數），先只物化到 9/24。
        比較「從原始資料即時算」與「讀聚合」的速度，再試試即時聚合與 refresh。</p>
      ${code(`CREATE MATERIALIZED VIEW lab_views_daily WITH (timescaledb.continuous) AS
SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day, product_id, count(*) AS views
FROM page_views
GROUP BY day, product_id;`)}
      <div class="lab-controls">
        <button class="btn ghost" type="button" data-ref="prep">建立實驗用的連續聚合（物化到 9/24）</button>
        <button class="btn" type="button" data-ref="cmp">比較</button>
        <button class="btn ghost" type="button" data-ref="rt"></button>
        <button class="btn ghost" type="button" data-ref="refresh">refresh 全部</button>
      </div>
      <div class="status" data-ref="state"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：連續聚合怎麼運作</summary>
        <div class="explain">連續聚合 = 自動「增量」更新的物化視圖：
1. 物化：把聚合結果存成另一個 hypertable（每天 × 每件商品一列，而不是每次瀏覽一列）
2. refresh：只重算「有資料變動的時間範圍」（TimescaleDB 記錄了哪些區間被寫入過），不必整個重算
3. 政策：add_continuous_aggregate_policy(view, start_offset, end_offset, schedule_interval) 定期自動 refresh

★ materialized_only = true（2.13 起的預設）：只回傳已經物化的部分，還沒 refresh 的最新資料看不到（9/24 之後是 0）。
★ materialized_only = false（即時聚合）：查詢時把「已物化的部分」＋「watermark 之後的原始資料即時算」合併，資料永遠是最新的，
  代價是每次查詢都要即時算 watermark 之後那一段（實測比只讀物化的部分慢）。

跟 PostgreSQL 的 MATERIALIZED VIEW 比：PostgreSQL 的 REFRESH MATERIALIZED VIEW 每次都整個重算；連續聚合只算變動的部分，還能即時合併最新資料。
限制：聚合函式要能「分段計算再合併」，例如 count(DISTINCT …) 不能用（可以改用 HyperLogLog 之類的近似函式）。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let status = null;
    const show = (s) => {
      status = s;
      $("state").innerHTML = !s.exists ? '<span class="muted">還沒建立實驗用的連續聚合。</span>'
        : `<span class="chip">已物化到 <b>${esc(s.watermark ?? "—")}</b></span>
           <span class="chip">${s.materializedOnly ? "materialized_only = true：只回傳已物化的部分" : "materialized_only = false：即時聚合"}</span>`;
      $("rt").textContent = s.exists && !s.materializedOnly ? "關掉即時聚合" : "打開即時聚合";
      $("rt").disabled = $("cmp").disabled = $("refresh").disabled = !s.exists;
    };
    api(`${API}/aggregate`).then((s) => alive && show(s)).catch((e) => alive && ($("state").innerHTML = `<div class="error">${esc(e.message)}</div>`));
    $("prep").addEventListener("click", () => act($("prep"), $("state"), async () => {
      $("state").innerHTML = running("建立並物化中（約 3 秒）…");
      show(await postJson(`${API}/aggregate/prepare`, {}));
      $("out").innerHTML = "";
    }));
    $("rt").addEventListener("click", () => act($("rt"), $("state"), async () => {
      show(await api(`${API}/aggregate/realtime?enabled=${status.materializedOnly}`, { method: "POST" }));
    }));
    $("refresh").addEventListener("click", () => act($("refresh"), $("state"), async () => {
      $("state").innerHTML = running("refresh 中…");
      show(await postJson(`${API}/aggregate/refresh`, {}));
    }));
    $("cmp").addEventListener("click", () => act($("cmp"), $("out"), async () => {
      $("out").innerHTML = running("執行中（每句跑兩次，取第二次）…");
      const r = await postJson(`${API}/aggregate/compare`, {});
      if (!alive) return;
      $("out").innerHTML = `<div class="result"><table><thead><tr><th>查詢</th><th class="num">ms</th><th>結果（第一列）</th></tr></thead><tbody>
        ${r.map((b) => `<tr><td>${esc(b.label)}</td><td class="num"><b>${b.ms.toFixed(1)}</b></td><td>${cell(b.value)}</td></tr>`).join("")}</tbody></table></div>
        <p class="muted">目前：${status.materializedOnly ? "只讀已物化的部分（9/24 之後的資料要等 refresh）" : "即時聚合（watermark 之後的部分從原始資料即時算）"}</p>
        <details><summary>查詢內容</summary>${r.map((b) => code(b.sql)).join("")}</details>`;
    }));
  }

  open(store.get("ts-lab-sec", "chunks"));
  return () => { alive = false; };
}
