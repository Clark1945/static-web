// pgvector 的 SQL 主控台：範例查詢（距離運算子、語意搜尋、推薦、EXPLAIN…），也可以自己寫 SELECT
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const EXAMPLES = [
  { label: "語意搜尋", sql: `SELECT id, name, description,
       round((embedding <=> embed('通勤 安靜'))::numeric, 3) AS distance
FROM products
ORDER BY embedding <=> embed('通勤 安靜')
LIMIT 10` },
  { label: "認得哪些詞", sql: `SELECT tokens('寒流來了想買保暖的外套') AS tokens,
       vector_dims(embed('寒流來了想買保暖的外套')) AS dims` },
  { label: "近義詞", sql: `SELECT word, recipe,
       round((embedding <=> (SELECT embedding FROM vocab WHERE word = '減肥'))::numeric, 3) AS distance
FROM vocab
ORDER BY embedding <=> (SELECT embedding FROM vocab WHERE word = '減肥')
LIMIT 8` },
  { label: "相似商品", sql: `SELECT id, name,
       round((1 - (embedding <=> (SELECT embedding FROM products WHERE id = 1132)))::numeric, 3) AS similarity
FROM products
WHERE id <> 1132
ORDER BY embedding <=> (SELECT embedding FROM products WHERE id = 1132)
LIMIT 5` },
  { label: "四種距離", sql: `SELECT '[1,2,3]'::vector <-> '[3,2,1]' AS l2,
       '[1,2,3]'::vector <=> '[3,2,1]' AS cosine,
       '[1,2,3]'::vector <#> '[3,2,1]' AS neg_inner_product,
       '[1,2,3]'::vector <+> '[3,2,1]' AS l1` },
  { label: "向量運算", sql: `SELECT '[1,2,3]'::vector + '[1,1,1]' AS plus,
       '[1,2,3]'::vector * '[2,2,2]' AS times,
       l2_normalize('[3,4]'::vector) AS normalized,
       '[1,2,3]'::vector::halfvec AS half,
       binary_quantize('[1,-2,3]'::vector) AS bits,
       subvector('[1,2,3,4,5]'::vector, 2, 3) AS sub` },
  { label: "EXPLAIN", sql: `EXPLAIN (ANALYZE, COSTS OFF)
SELECT id FROM passages
ORDER BY embedding <=> (SELECT embedding FROM questions WHERE id = 1)
LIMIT 10` },
];

export function mount(el, { api = "/api/pgvector" } = {}) {
  el.innerHTML = `
    <div class="panel">
      <div><span class="eyebrow">SQL 主控台</span><h3>pgvector</h3>
        <p class="desc">一次一句查詢（SELECT、WITH、EXPLAIN），用唯讀的 learner 角色執行。
          寫入請到「寫入沙盒」；建立索引、調整 ef_search 這類操作請到「實驗室」。</p></div>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}</div>
      <label for="vec-console">SQL（Ctrl+Enter 執行）</label>
      <textarea id="vec-console" data-ref="sql" spellcheck="false" style="min-height:150px"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">執行</button></div>
      <div class="status" data-ref="status" aria-live="polite"></div>
      <div data-ref="output"></div>
    </div>`;
  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true, runToken = 0;

  $("sql").value = store.get(CONSOLE_DRAFT_KEY, EXAMPLES[0].sql);
  $("examples").addEventListener("click", (e) => {
    const b = e.target.closest("[data-example]");
    if (!b) return;
    $("sql").value = EXAMPLES[Number(b.dataset.example)].sql;
    store.set(CONSOLE_DRAFT_KEY, $("sql").value);
  });
  $("sql").addEventListener("input", () => store.set(CONSOLE_DRAFT_KEY, $("sql").value));

  async function run() {
    if ($("run").disabled) return;
    const token = ++runToken;
    $("run").disabled = true;
    await runQuery({
      request: () => postJson(`${api}/sql`, { sql: $("sql").value }),
      statusEl: $("status"), outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (alive && token === runToken) $("run").disabled = false;
  }
  $("run").addEventListener("click", run);
  onCtrlEnter($("sql"), run);
  return () => { alive = false; };
}
