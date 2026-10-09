// Dev Tools 主控台：Kibana Dev Tools 寫法，用 learner 帳號執行（練習資料唯讀，scratch* 索引可以隨意建立、寫入、刪除）
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { transcript } from "./format.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const EXAMPLES = [
  { label: "全文檢索 + highlight", cmd: `GET /reviews/_search
{
  "size": 5,
  "query": { "match": { "content": "降噪 續航" } },
  "highlight": { "fields": { "content": {} } }
}` },
  { label: "bool 組合", cmd: `GET /products/_search
{
  "query": {
    "bool": {
      "must":     [{ "match": { "description": "輕薄" } }],
      "filter":   [{ "range": { "price": { "lte": 40000 } } }],
      "must_not": [{ "term": { "brand": "Apple" } }],
      "should":   [{ "term": { "tags": "熱銷" } }]
    }
  },
  "_source": ["name", "price", "tags"]
}` },
  { label: "聚合：每月營收", cmd: `GET /orders/_search
{
  "size": 0,
  "query": { "terms": { "status": ["paid", "shipped", "delivered"] } },
  "aggs": {
    "per_month": {
      "date_histogram": { "field": "order_date", "calendar_interval": "month", "time_zone": "+08:00" },
      "aggs": { "revenue": { "sum": { "field": "total" } } }
    }
  }
}` },
  { label: "錯誤訊息排行", cmd: `GET /logs/_search
{
  "size": 0,
  "query": { "term": { "level": "ERROR" } },
  "aggs": { "messages": { "terms": { "field": "message.keyword", "size": 5 } } }
}` },
  { label: "_cat 與 mapping", cmd: `GET /_cat/indices?v&s=index

GET /reviews/_mapping` },
  { label: "_analyze", cmd: `POST /_analyze
{ "analyzer": "standard", "text": "Sony 無線耳機，降噪效果很棒" }

POST /_analyze
{ "analyzer": "cjk", "text": "Sony 無線耳機，降噪效果很棒" }` },
  { label: "自己建索引（scratch）", cmd: `PUT /scratch_demo
{
  "mappings": {
    "properties": {
      "title": { "type": "text", "analyzer": "cjk" },
      "price": { "type": "integer" }
    }
  }
}

POST /scratch_demo/_bulk?refresh=true
{"index": {"_id": 1}}
{"title": "降噪藍牙耳機", "price": 3990}
{"index": {"_id": 2}}
{"title": "有線耳機", "price": 990}

GET /scratch_demo/_search
{ "query": { "match": { "title": "耳機" } } }

DELETE /scratch_demo` },
  { label: "權限測試", cmd: `# learner 只能讀練習資料，寫入會被拒絕
PUT /products/_doc/1
{ "name": "x" }` },
];

export function mount(el) {
  el.innerHTML = `
    <div class="panel">
      <div><span class="eyebrow">Dev Tools 主控台</span><h3>Elasticsearch REST API</h3>
        <p class="desc">跟 Kibana Dev Tools 一樣：一行「方法 路徑」，下面接 JSON（_bulk 是一行一個 JSON）；可以一次寫好幾個請求，# 開頭是註解。
          以 learner 帳號執行：products、reviews、orders、logs 只能讀；名稱以 scratch 開頭的索引可以自己建立、寫入、刪除。</p></div>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}</div>
      <label for="es-console">請求（Ctrl+Enter 執行）</label>
      <textarea id="es-console" data-ref="cmd" spellcheck="false" style="min-height:200px"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">執行</button></div>
      <div class="status" data-ref="status" aria-live="polite"></div>
      <div data-ref="output"></div>
    </div>`;
  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true, runToken = 0;

  $("cmd").value = store.get(CONSOLE_DRAFT_KEY, EXAMPLES[0].cmd);
  $("examples").addEventListener("click", (e) => {
    const b = e.target.closest("[data-example]");
    if (!b) return;
    $("cmd").value = EXAMPLES[Number(b.dataset.example)].cmd;
    store.set(CONSOLE_DRAFT_KEY, $("cmd").value);
  });
  $("cmd").addEventListener("input", () => store.set(CONSOLE_DRAFT_KEY, $("cmd").value));

  async function run() {
    if ($("run").disabled) return;
    const token = ++runToken;
    $("run").disabled = true;
    const r = await runQuery({
      request: () => postJson("/api/elastic/run", { commands: $("cmd").value }),
      toResult: (x) => ({ rowCount: x.results.length, truncated: false, elapsedMs: x.totalMillis }),
      render: (x) => transcript(x.results),
      statusEl: $("status"), outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (!alive || token !== runToken) return;
    $("run").disabled = false;
    const chip = $("status").querySelector(".chip");
    if (chip && r) chip.innerHTML = `執行了 <b>${r.results.length}</b> 個請求`;
  }
  $("run").addEventListener("click", run);
  onCtrlEnter($("cmd"), run);
  return () => { alive = false; };
}
