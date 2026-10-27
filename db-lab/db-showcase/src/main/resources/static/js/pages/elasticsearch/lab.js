// Elasticsearch 實驗室：分析器（_analyze 與 cjk / standard 的搜尋比較）、相關性排序（BM25、boost、function_score）、寫入行為（scratch）
import { api, postJson, esc, fmt, store, onCtrlEnter } from "../../lib.js";
import { transcript } from "./format.js";

const API = "/api/elastic/lab";
const SECTIONS = [
  { id: "analyzer", title: "分析器", sub: "同一段文字被切成哪些詞，搜尋結果差多少" },
  { id: "relevance", title: "相關性排序", sub: "BM25、欄位權重、function_score、filter" },
  { id: "behavior", title: "寫入與索引的行為", sub: "近即時、版本、mapping、刪除、同義詞、深分頁" },
];
const ANALYZERS = ["standard", "cjk", "whitespace", "simple", "keyword", "english"];
const code = (s) => `<pre class="code">${esc(String(s).trim())}</pre>`;

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="實驗"><div class="topic">實驗</div>
        ${SECTIONS.map((s, i) => `<button type="button" data-sec="${s.id}">${i + 1}. ${esc(s.title)}</button>`).join("")}</nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;
  const panel = el.querySelector('[data-ref="panel"]');
  let alive = true;
  const RENDER = { analyzer, relevance: (b) => steps(b, "relevance"), behavior: (b) => steps(b, "behavior") };

  function open(id) {
    const s = SECTIONS.find((x) => x.id === id) ?? SECTIONS[0];
    store.set("es-lab-sec", s.id);
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

  // ------------------------------------------------------------ 1. 分析器
  function analyzer(body) {
    const EXAMPLES = ["Sony 無線耳機，主動降噪效果很棒", "通勤戴很舒服", "The QUICK brown foxes jumped!", "iPhone-15 Pro Max 256GB"];
    body.innerHTML = `
      <p class="desc">寫入時，text 欄位的內容會經過分析器（字元過濾 → 切詞 tokenizer → 詞過濾 filter），切出來的「詞」才是倒排索引的 key；
        搜尋時查詢文字也經過同一個分析器，詞對得上才算符合。</p>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">試試：</span>
        ${EXAMPLES.map((q) => `<button type="button" class="option small" data-q="${esc(q)}">${esc(q)}</button>`).join("")}</div>
      <div class="lab-controls">
        <input type="text" class="es-input" data-ref="text" maxlength="200" aria-label="要分析的文字">
        <button class="btn" type="button" data-ref="run">分析</button>
      </div>
      <div class="lab-controls">${ANALYZERS.map((a) => `<label class="check"><input type="checkbox" value="${a}" ${["standard", "cjk", "whitespace"].includes(a) ? "checked" : ""}> ${a}</label>`).join("")}</div>
      <div data-ref="tokens"></div>
      <h4 class="sub-head">搜尋評論：同一個詞，cjk 與 standard 的欄位、match 與 match_phrase</h4>
      <div class="lab-controls">
        <input type="text" class="es-input" data-ref="q" maxlength="50" aria-label="搜尋文字">
        <button class="btn" type="button" data-ref="cmp">比較</button>
      </div>
      <div data-ref="cmp-out"></div>
      <details class="takeaway"><summary>看解說：中文要選哪個分析器</summary>
        <div class="explain">standard：依 Unicode 規則切詞、轉小寫。英文以空白和標點切開；中文每個字都是一個詞（「降噪」→ 降、噪）。
cjk：中日韓文字兩個字一組（bigram：主動、動降、降噪、噪耳、耳機），英文跟 standard 一樣；不用外掛，召回率高，但會產生「動降」這種沒有意義的詞。
whitespace 只依空白切；keyword 整段當一個詞；english 會去掉英文的停用詞並還原詞幹（jumped → jump、foxes → fox）。

搜尋比較（評論）：
  standard 的 match「音質」= 音 OR 質：含有「品質」「肉質」的評論也算符合，數量是 cjk 的好幾倍。
  cjk 的 match 只要 bigram「音質」出現就符合，結果乾淨很多；match_phrase 則要求每個 bigram 依序相鄰。
★ 實務上中文會用斷詞外掛（IK、smartcn、jieba），切出真正的詞（主動 / 降噪 / 耳機），再配合同義詞表；
  分析器在建立索引時決定，之後要改就得建新索引、reindex。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    $("text").value = store.get("es-analyze-text", EXAMPLES[0]);
    $("q").value = store.get("es-compare-text", "音質");
    async function run() {
      const analyzers = [...body.querySelectorAll('input[type="checkbox"]:checked')].map((c) => c.value);
      store.set("es-analyze-text", $("text").value);
      await act($("run"), $("tokens"), async () => {
        const r = await postJson(`${API}/analyze`, { text: $("text").value, analyzers });
        if (!alive) return;
        $("tokens").innerHTML = `<div class="es-tokens">${r.map((t) => `<div class="es-token-row"><b class="mono">${esc(t.analyzer)}</b>
          <span class="muted">${t.tokens.length} 個詞</span><div>${t.tokens.map((k) => `<span class="es-token">${esc(k)}</span>`).join("")}</div></div>`).join("")}</div>`;
      });
    }
    async function compare() {
      store.set("es-compare-text", $("q").value);
      await act($("cmp"), $("cmp-out"), async () => {
        $("cmp-out").innerHTML = running("搜尋中…");
        const r = await postJson(`${API}/compare`, { text: $("q").value });
        if (!alive) return;
        $("cmp-out").innerHTML = `<div class="sql-pair">${r.map((c) => `<div class="sql-card">
          <div class="sql-label">${esc(c.label)}</div>
          <div class="es-meta">符合 <b>${fmt(c.total)}</b> 則</div>
          <div class="result"><table><tbody>${c.hits.map((h) => `<tr><td class="num">${h.score.toFixed(2)}</td><td>${esc(h.text)}</td></tr>`).join("")
            || '<tr><td class="muted">沒有符合的評論</td></tr>'}</tbody></table></div>
          <details><summary>請求</summary>${code(c.request)}</details></div>`).join("")}</div>`;
      });
    }
    $("examples").addEventListener("click", (e) => { const b = e.target.closest("[data-q]"); if (b) { $("text").value = b.dataset.q; run(); } });
    $("run").addEventListener("click", run);
    $("text").addEventListener("keydown", (e) => { if (e.key === "Enter") run(); });
    body.querySelectorAll('input[type="checkbox"]').forEach((c) => c.addEventListener("change", run));
    $("cmp").addEventListener("click", compare);
    $("q").addEventListener("keydown", (e) => { if (e.key === "Enter") compare(); });
    run();
    compare();
  }

  // ------------------------------------------------------------ 2、3. 步驟式實驗
  function steps(body, kind) {
    const readOnly = kind === "relevance";
    body.innerHTML = `
      <p class="desc">${readOnly
        ? "每一步有一兩個查詢，用唯讀帳號執行。可以修改請求再執行；「第一名的分數怎麼算」會加上 explain，列出 BM25 的計算過程。"
        : "每一步的請求在乾淨的 scratch_* 索引上用 learner 帳號依序執行，執行完自動刪除。可以修改請求再執行。"}</p>
      <div class="variant-row" data-ref="steps"></div>
      <div class="prompt" data-ref="goal"></div>
      <label for="es-${kind}">請求（Ctrl+Enter 執行）</label>
      <textarea id="es-${kind}" data-ref="cmd" spellcheck="false" style="min-height:180px"></textarea>
      <div class="actions">
        <button class="btn" type="button" data-ref="run">執行</button>
        ${readOnly ? '<button class="btn ghost" type="button" data-ref="explain">第一名的分數怎麼算（只看第一個請求）</button>' : ""}
        <button class="btn ghost" type="button" data-ref="restore">還原這一步的請求</button>
      </div>
      <p class="question" data-ref="question"></p>
      <div data-ref="result"></div>
      <details class="takeaway"><summary>看解說</summary><div class="explain" data-ref="takeaway"></div></details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let list = [], step = null;
    const KEY = `es-${kind}-step`;

    api(`${API}/${kind}`).then((l) => {
      if (!alive) return;
      list = l;
      $("steps").innerHTML = '<span class="muted" style="align-self:center">步驟：</span>' +
        list.map((s, i) => `<button type="button" class="option small" data-step="${i}">${i + 1}. ${esc(s.title)}</button>`).join("");
      select(store.get(KEY, 0));
    });
    $("steps").addEventListener("click", (e) => { const b = e.target.closest("[data-step]"); if (b) select(Number(b.dataset.step)); });

    function select(i) {
      step = list[i] ?? list[0];
      store.set(KEY, list.indexOf(step));
      $("steps").querySelectorAll("[data-step]").forEach((b) => b.classList.toggle("chosen", Number(b.dataset.step) === list.indexOf(step)));
      $("goal").textContent = step.goal.trim();
      $("question").textContent = "想一想：" + step.question;
      $("takeaway").textContent = step.takeaway.trim();
      body.querySelector(".takeaway").open = false;
      $("cmd").value = step.commands.trim();
      $("result").innerHTML = "";
    }
    async function run() {
      await act($("run"), $("result"), async () => {
        $("result").innerHTML = running("執行中…");
        const r = await postJson(`${API}/${kind}/run`, { commands: $("cmd").value });
        if (alive) $("result").innerHTML = transcript(r.results);
      });
    }
    $("run").addEventListener("click", run);
    onCtrlEnter($("cmd"), run);
    $("restore").addEventListener("click", () => { $("cmd").value = step.commands.trim(); });
    if (readOnly) {
      $("explain").addEventListener("click", () => act($("explain"), $("result"), async () => {
        $("result").innerHTML = running("加上 explain 執行中…");
        const r = await postJson(`${API}/relevance/explain`, { commands: $("cmd").value });
        if (!alive) return;
        $("result").innerHTML = r.map((h, i) => `<div class="es-explain">
          <div class="es-head"><b>第 ${i + 1} 名</b> <span class="mono">_id ${esc(h.id)}</span> <span class="es-status ok">${h.score.toFixed(3)}</span>
            <span>${esc(h.text)}</span></div>
          <pre class="code es-tree">${h.lines.map((l) => `${"  ".repeat(l.depth)}<b>${l.value.toFixed(3)}</b>  ${esc(l.description)}`).join("\n")}</pre></div>`).join("");
      }));
    }
  }

  open(store.get("es-lab-sec", "analyzer"));
  return () => { alive = false; };
}
