// InfluxDB 實驗室：series 數量（tag vs field）、InfluxQL vs Flux 對照、降低精度（downsampling）
import { api, postJson, esc, fmt, store, onCtrlEnter } from "../../lib.js";
import { runView } from "./format.js";

const API = "/api/influx/lab";
const SECTIONS = [
  { id: "cardinality", title: "series 數量：tag 還是 field", sub: "同樣的事件，user_id 放 tag 與放 field 差多少" },
  { id: "compare", title: "InfluxQL vs Flux", sub: "同一個問題兩種寫法，結果並排" },
  { id: "downsample", title: "降低精度（downsampling）", sub: "每分鐘 → 每小時，點數與查詢速度" },
];
const code = (s) => `<pre class="code">${esc(String(s).trim())}</pre>`;
const ms = (v) => (v < 10 ? v.toFixed(2) : v.toFixed(1)) + " ms";

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="實驗"><div class="topic">實驗</div>
        ${SECTIONS.map((s, i) => `<button type="button" data-sec="${s.id}">${i + 1}. ${esc(s.title)}</button>`).join("")}</nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;
  const panel = el.querySelector('[data-ref="panel"]');
  let alive = true;
  const RENDER = { cardinality, compare, downsample };

  function open(id) {
    const s = SECTIONS.find((x) => x.id === id) ?? SECTIONS[0];
    store.set("ifx-lab-sec", s.id);
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
  const timedCards = (runs) => `<div class="sql-pair">${runs.map((r) => `<div class="sql-card">
      <div class="sql-label">${esc(r.label)} <span class="muted">· ${ms(r.millis)}</span></div>${code(r.code)}${runView(r.result)}</div>`).join("")}</div>`;

  // ------------------------------------------------------------ 1. cardinality
  function cardinality(body) {
    body.innerHTML = `
      <p class="desc">產生一批網頁事件（每位使用者 20 個），寫兩份到 scratch：<code>events_tag</code> 把 user_id 放在 tag，<code>events_field</code> 放在 field。
        比較 series 數量、找某位使用者的事件、依使用者分組三件事。</p>
      ${code(`events_tag,page=home,user_id=42 latency_ms=87i 1790726400      ← user_id 是 tag：每位使用者一條 series
events_field,page=home user_id=42i,latency_ms=87i 1790726400    ← user_id 是 field：全部只有一條 series`)}
      <div class="lab-controls">
        <label>使用者人數<select data-ref="users">${[100, 1000, 5000].map((v) => `<option ${v === 1000 ? "selected" : ""}>${v}</option>`).join("")}</select></label>
        <button class="btn" type="button" data-ref="run">產生資料並比較</button>
      </div>
      <div class="status" data-ref="status"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：什麼要放 tag、什麼要放 field</summary>
        <div class="explain">series = measurement + tag set（+ field）。InfluxDB 為每條 series 建索引（TSI）並各自依時間存放：
  user_id 放 tag → 有幾位使用者就有幾條 series；放 field → 不管多少人都只有 1 條。

tag 的好處：用 tag 篩選（WHERE user_id = '42'）直接查索引找到那條 series；可以 GROUP BY。
field 的代價：WHERE user_id = 42 要把整條 series 的資料讀出來逐筆比對；不能 GROUP BY。

但 tag 的值種類太多（使用者 ID、訂單編號、trace id、IP、URL 參數）時，series 數量暴增（high cardinality）：
  索引吃記憶體、寫入變慢、查詢要合併很多條 series；百萬級以上 series 是 InfluxDB 1.x / 2.x 效能問題的頭號原因。

★ 規則：拿來篩選、分組、而且值的種類有限（主機、區域、服務、狀態）→ tag；量測值、ID、高基數的值 → field。
  需要依高基數欄位分析的，改用 ClickHouse、PostgreSQL / TimescaleDB 這類欄式或關聯式資料庫。
  InfluxDB 3 改用 Arrow / Parquet 的欄式儲存，對高基數友善很多，這也是重寫的主要原因之一。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    $("run").addEventListener("click", () => act($("run"), $("status"), async () => {
      $("status").innerHTML = running("寫入並查詢中（每句各跑兩次，取第二次的時間）…");
      const r = await postJson(`${API}/cardinality`, { users: Number($("users").value) });
      if (!alive) return;
      $("status").innerHTML = `<span class="chip">寫入 <b>${fmt(r.points)}</b> 個事件 × 2 份，<b>${fmt(r.users)}</b> 位使用者（做完已清空 scratch）</span>`;
      $("out").innerHTML = timedCards(r.runs);
    }));
  }

  // ------------------------------------------------------------ 2. InfluxQL vs Flux
  function compare(body) {
    body.innerHTML = `
      <p class="desc">每一步是同一個問題的兩種寫法，用唯讀 token 執行。可以修改後再執行。</p>
      <div class="variant-row" data-ref="steps"></div>
      <div class="prompt" data-ref="goal"></div>
      <div class="sql-pair">
        <div class="sql-card"><label class="sql-label" for="ifx-cmp-ql">InfluxQL</label>
          <textarea id="ifx-cmp-ql" data-ref="ql" spellcheck="false" style="min-height:140px"></textarea></div>
        <div class="sql-card"><label class="sql-label" for="ifx-cmp-flux">Flux</label>
          <textarea id="ifx-cmp-flux" data-ref="flux" spellcheck="false" style="min-height:140px"></textarea></div>
      </div>
      <div class="actions"><button class="btn" type="button" data-ref="run">兩邊都執行（Ctrl+Enter）</button>
        <button class="btn ghost" type="button" data-ref="restore">還原這一步</button></div>
      <p class="question" data-ref="question"></p>
      <div data-ref="result"></div>
      <details class="takeaway"><summary>看解說</summary><div class="explain" data-ref="takeaway"></div></details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let steps = [], step = null;
    api(`${API}/compare`).then((list) => {
      if (!alive) return;
      steps = list;
      $("steps").innerHTML = '<span class="muted" style="align-self:center">步驟：</span>' +
        steps.map((s, i) => `<button type="button" class="option small" data-step="${i}">${i + 1}. ${esc(s.title)}</button>`).join("");
      select(store.get("ifx-cmp-step", 0));
    });
    $("steps").addEventListener("click", (e) => { const b = e.target.closest("[data-step]"); if (b) select(Number(b.dataset.step)); });
    function select(i) {
      step = steps[i] ?? steps[0];
      store.set("ifx-cmp-step", steps.indexOf(step));
      $("steps").querySelectorAll("[data-step]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.step) === steps.indexOf(step)));
      $("goal").textContent = step.goal.trim();
      $("question").textContent = "想一想：" + step.question;
      $("takeaway").textContent = step.takeaway.trim();
      body.querySelector(".takeaway").open = false;
      $("ql").value = step.influxql.trim();
      $("flux").value = step.flux.trim();
      $("result").innerHTML = "";
    }
    async function run() {
      await act($("run"), $("result"), async () => {
        $("result").innerHTML = running("執行中…");
        const [a, b] = await Promise.all([
          postJson(`${API}/compare/run`, { lang: "influxql", code: $("ql").value }),
          postJson(`${API}/compare/run`, { lang: "flux", code: $("flux").value })]);
        if (!alive) return;
        $("result").innerHTML = `<div class="sql-pair">
          <div class="sql-card"><div class="sql-label">InfluxQL <span class="muted">· ${ms(a.totalMillis)}</span></div>${runView(a)}</div>
          <div class="sql-card"><div class="sql-label">Flux <span class="muted">· ${ms(b.totalMillis)}</span></div>${runView(b)}</div></div>`;
      });
    }
    $("run").addEventListener("click", run);
    onCtrlEnter($("ql"), run);
    onCtrlEnter($("flux"), run);
    $("restore").addEventListener("click", () => { $("ql").value = step.influxql.trim(); $("flux").value = step.flux.trim(); });
  }

  // ------------------------------------------------------------ 3. downsampling
  function downsample(body) {
    body.innerHTML = `
      <p class="desc">用 Flux 把 metrics 裡 9 月每分鐘的 CPU 彙總成每小時平均，<code>to()</code> 寫進 scratch 的 <code>cpu_1h</code>，
        再用同樣的查詢比較原始資料與降低精度後的點數與速度。</p>
      <div class="lab-controls"><button class="btn" type="button" data-ref="run">執行降低精度並比較</button></div>
      <div class="status" data-ref="status"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：時序資料的生命週期</summary>
        <div class="explain">監控資料越舊越少人看、看的時候也不需要每分鐘的細節：
  最近 7 天保留原始精度（每 10 秒、每分鐘），之後只留每小時、每天的彙總，原始資料由 retention policy 自動刪除。
  實測：點數變成原本的 1/60，查詢整個月的趨勢也快很多。

InfluxDB 2.x 的做法：
  1. bucket 設定保留時間（retention period，例如 raw 保留 7d、hourly 保留 1y）
  2. 建一個 Task（排程執行的 Flux）：option task = {name: "downsample_cpu", every: 1h}，內容就是這裡的 aggregateWindow |> to()
  1.x 用 Continuous Query + Retention Policy；TimescaleDB 用連續聚合 + drop_chunks / 保留政策，觀念相同。

★ 注意：
  - 降低精度會失去細節：每小時「平均」看不出 db-01 滿載 40 分鐘的尖峰（比較兩邊的最大值），常常同時存 mean、max、min、count。
  - 遲到的資料（late data）可能錯過已經執行過的 Task，排程要留一點緩衝（例如處理 1 小時前的區間）。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    $("run").addEventListener("click", () => act($("run"), $("status"), async () => {
      $("status").innerHTML = running("彙總並寫入 scratch，再執行比較查詢（約幾秒）…");
      const r = await postJson(`${API}/downsample`, {});
      if (!alive) return;
      $("status").innerHTML = '<span class="chip">做完已清空 scratch</span>';
      $("out").innerHTML = `<h4 class="sub-head">降低精度用的 Flux（寫入了幾個點）</h4>${code(r.code)}${runView(r.written)}${timedCards(r.runs)}`;
    }));
  }

  open(store.get("ifx-lab-sec", "cardinality"));
  return () => { alive = false; };
}
