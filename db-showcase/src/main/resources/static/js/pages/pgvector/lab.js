// pgvector 實驗室：語意 / 關鍵字 / 混合搜尋、索引（精確、HNSW、IVFFlat）、過濾 + 向量索引、量化
import { api, postJson, esc, store, renderTable } from "../../lib.js";

const API = "/api/pgvector/lab";
const SECTIONS = [
  { id: "search", title: "語意搜尋 vs 關鍵字", sub: "同一段文字，三種搜尋方式並排比較" },
  { id: "index", title: "向量索引：HNSW 與 IVFFlat", sub: "10 萬筆、100 個查詢：速度與召回率" },
  { id: "filter", title: "過濾 + 向量索引", sub: "多租戶：WHERE tenant_id = … 之後還剩幾筆" },
  { id: "quant", title: "量化：halfvec 與 bit", sub: "用精度換空間，召回率掉多少" },
];
const code = (s) => `<pre class="code">${esc(String(s).trim())}</pre>`;
const pct = (r) => (r * 100).toFixed(1) + "%";
const ms = (v) => (v < 10 ? v.toFixed(2) : v.toFixed(1)) + " ms";
const sec = (v) => (v == null ? "—" : v.toFixed(1) + " 秒");

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="實驗"><div class="topic">實驗</div>
        ${SECTIONS.map((s, i) => `<button type="button" data-sec="${s.id}">${i + 1}. ${esc(s.title)}</button>`).join("")}</nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;
  const panel = el.querySelector('[data-ref="panel"]');
  let alive = true;
  const RENDER = { search, index, filter, quant };

  function open(id) {
    const s = SECTIONS.find((x) => x.id === id) ?? SECTIONS[0];
    store.set("vec-lab-sec", s.id);
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

  /** 量測紀錄表：每次執行加一列（最新的在最上面），最近一次的執行計畫放在下面。 */
  function benchTable(history, { rows = false } = {}) {
    if (!history.length) return "";
    const last = history[0];
    return `<div class="result"><table><thead><tr><th>做法</th><th class="num">召回率</th><th class="num">平均</th><th class="num">最慢</th>
      ${rows ? '<th class="num">平均回傳</th><th class="num">最少回傳</th>' : ""}</tr></thead><tbody>
      ${history.map((b) => `<tr><td>${esc(b.label)}</td>
        <td class="num"><b class="${b.recall >= 0.95 ? "vec-good" : b.recall < 0.8 ? "vec-bad" : ""}">${pct(b.recall)}</b></td>
        <td class="num">${ms(b.avgMs)}</td><td class="num">${ms(b.maxMs)}</td>
        ${rows ? `<td class="num"><b class="${b.avgRows < 10 ? "vec-bad" : ""}">${b.avgRows.toFixed(1)} 筆</b></td><td class="num">${b.minRows} 筆</td>` : ""}</tr>`).join("")}
      </tbody></table></div>
      <p class="muted vec-note">召回率 = 跟精確搜尋的前 10 名相比找回幾筆；100 個測試向量（questions）各查一次取平均，時間含展示台到資料庫的來回。</p>
      <details><summary>最近一次：查詢與執行計畫（questions 第 1 題）</summary>${code(last.sql)}<pre class="code vec-plan">${esc(last.plan.join("\n"))}</pre></details>`;
  }

  // ------------------------------------------------------------ 1. 語意搜尋
  function search(body) {
    const EXAMPLES = ["通勤 安靜", "Sony 降噪", "冬天 保暖", "皮膚乾燥", "宿舍 收納", "F178", "iPhone 15"];
    body.innerHTML = `
      <p class="desc">輸入一段文字，同時用三種方式搜尋 products：<b>語意搜尋</b>（embed(文字) 的向量距離）、
        <b>關鍵字搜尋</b>（描述裡出現幾個詞）、<b>混合搜尋</b>（兩邊的名次用 RRF 合併）。</p>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">試試：</span>
        ${EXAMPLES.map((q) => `<button type="button" class="option small" data-q="${esc(q)}">${esc(q)}</button>`).join("")}</div>
      <div class="lab-controls">
        <input type="text" class="vec-input" data-ref="text" maxlength="100" aria-label="搜尋文字">
        <button class="btn" type="button" data-ref="run">搜尋</button>
      </div>
      <div class="status" data-ref="tokens"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：什麼時候用哪一種</summary>
        <div class="explain">語意搜尋找「意思相近」：「通勤 安靜」找到主動降噪耳機，描述裡根本沒有這兩個詞，關鍵字搜尋一筆都找不到。
語意搜尋的弱點是專有名詞：品牌、型號（Sony、F178）不在詞庫裡，embed() 直接忽略；真正的嵌入模型也常把型號弄錯。
「F178」「iPhone 15」：語意搜尋完全沒有依據（embed() 回傳 NULL，結果是隨便幾件），關鍵字一找就中。

混合搜尋（hybrid search）兩邊都做，再合併名次。RRF（Reciprocal Rank Fusion）：每份名單裡排第 r 名得 1 / (60 + r) 分，加總後排序。
好處是只看名次、不看分數，所以不用煩惱「cosine 距離」跟「關鍵字分數」的單位不同。「Sony 降噪」：兩邊都排前面的 Sony 降噪耳機排第一。
實務上關鍵字那一邊會用全文檢索（tsvector + ts_rank，或 Elasticsearch 的 BM25）；中文要另外處理斷詞（例如 pg_bigm、zhparser）。
RAG 系統大多用混合搜尋，再交給 reranker 模型精排。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    $("text").value = store.get("vec-search-text", EXAMPLES[0]);
    async function run() {
      const text = $("text").value;
      store.set("vec-search-text", text);
      await act($("run"), $("out"), async () => {
        $("out").innerHTML = running("搜尋中…");
        const r = await postJson(`${API}/search`, { text });
        if (!alive) return;
        $("tokens").innerHTML = r.tokens.length
          ? `<span class="chip">embed() 認得的詞：<b>${r.tokens.map(esc).join("、")}</b></span>`
          : '<span class="chip bad">embed() 一個詞都不認得，回傳 NULL：語意搜尋的結果沒有意義</span>';
        $("out").innerHTML = `<div class="vec-three">${r.sections.map((s) => `<div class="sql-card">
          <div class="sql-label">${esc(s.title)}</div>${renderTable(s.result)}
          <details><summary>SQL</summary>${code(s.sql)}</details></div>`).join("")}</div>`;
      });
    }
    $("examples").addEventListener("click", (e) => { const b = e.target.closest("[data-q]"); if (b) { $("text").value = b.dataset.q; run(); } });
    $("run").addEventListener("click", run);
    $("text").addEventListener("keydown", (e) => { if (e.key === "Enter") run(); });
    run();
  }

  // ------------------------------------------------------------ 2. 向量索引
  function index(body) {
    body.innerHTML = `
      <p class="desc">passages 有 10 萬筆 128 維的向量，已經建好 HNSW 索引（m = 16、ef_construction = 64）。
        用 100 個測試向量各找最近的 10 筆，比較精確搜尋、HNSW、IVFFlat 的速度與召回率。IVFFlat 建在複製出來的 <code>lab_passages</code>。</p>
      <div class="status" data-ref="state"></div>
      <div class="lab-controls">
        <button class="btn" type="button" data-ref="exact">精確搜尋</button>
        <span class="vec-sep"></span>
        <label>ef_search<select data-ref="ef">${[10, 20, 40, 100, 200, 400].map((v) => `<option ${v === 40 ? "selected" : ""}>${v}</option>`).join("")}</select></label>
        <button class="btn" type="button" data-ref="hnsw">HNSW</button>
      </div>
      <div class="lab-controls">
        <label>lists<select data-ref="lists">${[10, 100, 1000].map((v) => `<option ${v === 100 ? "selected" : ""}>${v}</option>`).join("")}</select></label>
        <button class="btn ghost" type="button" data-ref="build">建立 IVFFlat 索引</button>
        <span class="vec-sep"></span>
        <label>probes<select data-ref="probes">${[1, 3, 10, 30, 100].map((v) => `<option>${v}</option>`).join("")}</select></label>
        <button class="btn" type="button" data-ref="ivf">IVFFlat</button>
      </div>
      <div class="status" data-ref="status"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：HNSW 與 IVFFlat 怎麼選</summary>
        <div class="explain">精確搜尋（不用索引）要算 10 萬次距離再排序：召回率 100%，每次約 19 ms，資料量翻倍時間就翻倍。

HNSW（Hierarchical Navigable Small World）：把向量連成多層的「鄰居圖」，從最上層的少數節點開始，每次往更近的鄰居走。
  實測 ef_search = 40（預設）召回率 99%、約 1.3 ms；ef_search = 10 掉到 94%；200 是 100%，但慢了一倍多。
  ef_search = 搜尋時候選名單的長度，越大越準越慢，而且它是「最多回傳幾筆」的上限（LIMIT 100 要配 ef_search ≥ 100）。
  建索引的 m（每個節點連幾個鄰居）、ef_construction（建圖時的候選名單長度）越大，圖的品質越好，但建得更慢、更大。
  缺點：建索引慢（這 10 萬筆要約 38 秒）、索引大（79 MB，比資料本身 56 MB 還大），而且整張圖最好放得進記憶體。

IVFFlat（Inverted File）：先用 k-means 把向量分成 lists 群，查詢時只看離查詢最近的 probes 群。
  實測 lists = 100：probes = 1 召回率只有 45%，3 是 61%，10 是 81%，30 才到 95%（已經比 HNSW 慢）。
  lists = 1000：probes = 1 就有 88%，但建索引的時間從 0.8 秒變成 9 秒。
  官方建議 lists = 資料筆數 / 1000（100 萬筆以上用 √筆數），probes 從 √lists 開始調。
  優點：建得很快、索引較小；缺點：同樣召回率下比 HNSW 慢，而且分群是建索引當下決定的，資料分布變了要重建（所以要等資料載入後才建）。

★ 面試結論：大部分情況選 HNSW（召回率與速度的平衡最好，可以邊寫入邊建）；資料量極大、建索引時間或記憶體有限時才考慮 IVFFlat。
  兩種都是近似搜尋（ANN），上線前要用自己的資料量測召回率，再決定 ef_search / probes。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const history = [];
    const showState = (s) => {
      $("state").innerHTML = `<span class="chip">passages 資料 <b>${esc(s.tableSize)}</b></span>
        <span class="chip">HNSW 索引 <b>${esc(s.hnswSize ?? "—")}</b>，建立花了 <b>${sec(s.hnswSeconds)}</b></span>
        ${s.ivfLists ? `<span class="chip">IVFFlat lists = <b>${s.ivfLists}</b>，<b>${esc(s.ivfSize)}</b>${s.ivfSeconds ? `，建立花了 <b>${sec(s.ivfSeconds)}</b>` : ""}</span>`
          : '<span class="chip">還沒建立 IVFFlat 索引</span>'}`;
      $("ivf").disabled = !s.ivfLists;
      if (s.ivfLists) $("lists").value = String(s.ivfLists);
    };
    api(`${API}/index`).then((s) => alive && showState(s)).catch((e) => alive && ($("state").innerHTML = `<div class="error">${esc(e.message)}</div>`));
    const run = (button, payload, text) => act(button, $("status"), async () => {
      $("status").innerHTML = running(text);
      const r = await postJson(`${API}/index/run`, payload);
      if (!alive) return;
      history.unshift(r);
      $("status").innerHTML = "";
      $("out").innerHTML = benchTable(history);
    });
    $("exact").addEventListener("click", () => run($("exact"), { method: "exact" }, "精確搜尋中（100 次 × 10 萬筆，約 2 秒）…"));
    $("hnsw").addEventListener("click", () => run($("hnsw"), { method: "hnsw", efSearch: Number($("ef").value) }, "HNSW 搜尋中…"));
    $("ivf").addEventListener("click", () => run($("ivf"), { method: "ivfflat", probes: Number($("probes").value) }, "IVFFlat 搜尋中…"));
    $("build").addEventListener("click", () => act($("build"), $("status"), async () => {
      $("status").innerHTML = running(`複製 10 萬筆並建立 IVFFlat 索引（lists = ${$("lists").value}，最多約 10 秒）…`);
      const s = await postJson(`${API}/index/ivfflat`, { lists: Number($("lists").value) });
      if (!alive) return;
      $("status").innerHTML = "";
      showState(s);
    }));
  }

  // ------------------------------------------------------------ 3. 過濾
  function filter(body) {
    const FILTERS = [
      { v: "tenant_id = 7", label: "tenant_id = 7（2%，約 2,000 筆）" },
      { v: "tenant_id <= 5", label: "tenant_id <= 5（10%）" },
      { v: "tenant_id <= 25", label: "tenant_id <= 25（50%）" },
    ];
    body.innerHTML = `
      <p class="desc">多租戶的 RAG：每個租戶只能搜自己的文件。passages 平均分給 50 個租戶，租戶跟向量內容無關。
        比較同一句 <code>WHERE 過濾條件 ORDER BY embedding &lt;=&gt; q LIMIT 10</code> 在不同做法下回傳幾筆、準不準。</p>
      ${code(`SELECT id FROM passages
WHERE tenant_id = 7
ORDER BY embedding <=> $1
LIMIT 10;`)}
      <div class="lab-controls">
        <label>過濾條件<select data-ref="filter">${FILTERS.map((f) => `<option value="${esc(f.v)}">${esc(f.label)}</option>`).join("")}</select></label>
        <label>ef_search<select data-ref="ef">${[40, 100, 400, 1000].map((v) => `<option>${v}</option>`).join("")}</select></label>
      </div>
      <div class="lab-controls">
        <button class="btn" type="button" data-mode="off">HNSW（預設）</button>
        <button class="btn" type="button" data-mode="strict_order">iterative_scan = strict_order</button>
        <button class="btn" type="button" data-mode="relaxed_order">iterative_scan = relaxed_order</button>
        <button class="btn ghost" type="button" data-mode="exact">精確搜尋（不用向量索引）</button>
      </div>
      <div class="status" data-ref="status"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：為什麼會少、怎麼解</summary>
        <div class="explain">HNSW 先依 ef_search 找出 40 個最近的候選，「之後」才套用 WHERE（post-filtering）。
租戶 7 只佔 2%，40 個候選裡平均不到 1 筆屬於它：實測平均只回傳 0.7 筆，很多查詢 0 筆，而且不會報錯。
過濾 10% 時平均回傳 4 筆；過濾 50% 時才幾乎都有 10 筆。過濾條件越嚴格，問題越嚴重。

解法與實測（租戶 7）：
1. 調高 ef_search：1000 時才湊滿 10 筆，召回率 89%，約 12 ms（候選名單變長，每次都變慢）。
2. iterative scan（pgvector 0.8 起）：候選不夠時自動繼續往下找，一定湊滿 10 筆。
   relaxed_order 召回率 77%、約 8 ms；strict_order 保證結果依距離排好，召回率 54%。
   relaxed_order 的結果可能沒有完全依距離排序，要的話外面再包一層 ORDER BY（可用 MATERIALIZED CTE）。
   hnsw.max_scan_tuples（預設 2 萬）限制最多掃幾筆，避免無止境地找下去。
3. 精確搜尋：只算租戶 7 的資料，召回率 100%，約 10 ms（這裡沒有 tenant_id 的 B-tree 索引，所以還是掃了整張表；建了會更快）。
   過濾後剩下的資料不多時（幾千、幾萬筆），精確搜尋往往又準又快。
4. 部分索引：大租戶各建一個 CREATE INDEX … USING hnsw (embedding vector_cosine_ops) WHERE tenant_id = 7。
5. 分區：依 tenant_id 分區（PARTITION BY LIST），每個分區有自己的 HNSW 索引，查詢自動只看自己的分區。

★ 面試重點：「向量搜尋 + 過濾」是向量資料庫最難的問題之一。過濾條件寬鬆時直接用索引；很嚴格時改用精確搜尋或分區；
  中間地帶用 iterative scan，並且一定要量測召回率。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const history = [];
    body.querySelectorAll("[data-mode]").forEach((b) => b.addEventListener("click", () => act(b, $("status"), async () => {
      $("status").innerHTML = running("量測中（第一次會先算出精確答案，約 2 秒）…");
      const r = await postJson(`${API}/filter/run`, { filter: $("filter").value, mode: b.dataset.mode, efSearch: Number($("ef").value) });
      if (!alive) return;
      r.label = `${$("filter").value}｜${r.label}`;
      history.unshift(r);
      $("status").innerHTML = "";
      $("out").innerHTML = benchTable(history, { rows: true });
    })));
  }

  // ------------------------------------------------------------ 4. 量化
  function quant(body) {
    body.innerHTML = `
      <p class="desc">在 passages 上另外建兩個「運算式索引」：把向量轉成 halfvec（每維 2 bytes）或 binary_quantize（每維 1 bit）再建 HNSW，
        比較索引大小與召回率。查詢時 ORDER BY 要寫成同樣的運算式，才用得到這兩個索引。</p>
      ${code(`CREATE INDEX lab_passages_half ON passages USING hnsw ((embedding::halfvec(128)) halfvec_cosine_ops);
CREATE INDEX lab_passages_bit  ON passages USING hnsw ((binary_quantize(embedding)::bit(128)) bit_hamming_ops);

-- 用 halfvec 索引
SELECT id FROM passages ORDER BY embedding::halfvec(128) <=> $1::halfvec(128) LIMIT 10;
-- 用 bit 索引撈 100 筆候選，再用原本的向量重排（rerank）
SELECT id FROM (
  SELECT id, embedding FROM passages
  ORDER BY binary_quantize(embedding)::bit(128) <~> binary_quantize($1) LIMIT 100
) c ORDER BY embedding <=> $1 LIMIT 10;`)}
      <div class="lab-controls">
        <button class="btn ghost" type="button" data-ref="prep">建立量化索引（約 70 秒）</button>
        <label>ef_search<select data-ref="ef">${[40, 100, 200].map((v) => `<option>${v}</option>`).join("")}</select></label>
        <button class="btn" type="button" data-m="full">vector（原本的 HNSW）</button>
        <button class="btn" type="button" data-m="half">halfvec</button>
        <button class="btn" type="button" data-m="bit">bit</button>
        <button class="btn" type="button" data-m="rerank">bit + 重排</button>
      </div>
      <div data-ref="sizes"></div>
      <div class="status" data-ref="status"></div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看解說：量化怎麼選</summary>
        <div class="explain">向量很佔空間：1536 維（OpenAI text-embedding-3-small）一筆 6 KB，1000 萬筆就是 60 GB，HNSW 索引還要再一份，而且最好整個放進記憶體。

實測（10 萬筆 128 維，HNSW）：
  vector（float4）索引 79 MB，召回率 99%
  halfvec（float2）索引 54 MB，召回率 99.9%：幾乎沒有損失（嵌入模型的輸出本來就不需要那麼高的精度）
  bit（每維只留正負號，用 Hamming 距離）索引 30 MB，召回率只剩 42%
  bit 撈 100 筆 → 原始向量重排：召回率回到 95%
（索引裡除了向量，還有 HNSW 的鄰居連結，所以不會剛好小一半、小 32 倍。）

★ halfvec 幾乎是「免費」的空間減半：pgvector 的 vector 最多只能建 2000 維的 HNSW 索引，halfvec 可以到 4000 維，
  所以 3072 維的模型（text-embedding-3-large）一定要用 halfvec 建索引。
★ binary quantization 適合維度很高的模型（1000 維以上，位元數多才分得出差異；這裡只有 128 維所以很不準），一定要搭配重排。
  資料表裡保留原本的 vector，只有索引用量化的運算式，就能「用小索引撈候選、用原始向量精排」。
另一種省空間的做法是降維：有些模型（text-embedding-3、Matryoshka 嵌入）可以直接截短成前 256 / 512 維（subvector）。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const history = [];
    const LABELS = { table: "passages 資料（vector）", passages_hnsw: "HNSW：vector", lab_passages_half: "HNSW：halfvec", lab_passages_bit: "HNSW：bit" };
    const showSizes = (s) => {
      $("sizes").innerHTML = `<div class="result"><table><thead><tr><th></th><th class="num">大小</th><th class="num">建立時間</th></tr></thead><tbody>
        ${Object.entries(s.sizes).map(([k, v]) => `<tr><td>${esc(LABELS[k] ?? k)}</td><td class="num"><b>${esc(v ?? "還沒建立")}</b></td>
          <td class="num">${k === "table" ? "" : sec(s.buildSeconds[k])}</td></tr>`).join("")}</tbody></table></div>`;
      body.querySelectorAll('[data-m]:not([data-m="full"])').forEach((b) => { b.disabled = !s.prepared; });
    };
    api(`${API}/quant`).then((s) => alive && showSizes(s)).catch((e) => alive && ($("sizes").innerHTML = `<div class="error">${esc(e.message)}</div>`));
    $("prep").addEventListener("click", () => act($("prep"), $("status"), async () => {
      $("status").innerHTML = running("建立 halfvec 與 bit 兩個 HNSW 索引中（約 70 秒）…");
      const s = await postJson(`${API}/quant/prepare`, {});
      if (!alive) return;
      $("status").innerHTML = "";
      showSizes(s);
    }));
    body.querySelectorAll("[data-m]").forEach((b) => b.addEventListener("click", () => act(b, $("status"), async () => {
      $("status").innerHTML = running("量測中…");
      const r = await postJson(`${API}/quant/run`, { method: b.dataset.m, efSearch: Number($("ef").value) });
      if (!alive) return;
      history.unshift(r);
      $("status").innerHTML = "";
      $("out").innerHTML = benchTable(history);
    })));
  }

  open(store.get("vec-lab-sec", "search"));
  return () => { alive = false; };
}
