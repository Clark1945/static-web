// 寫入沙盒：可以 INSERT / UPDATE / DELETE、可以一次送多句，最後一律 ROLLBACK
import { postJson, esc, runQuery, renderSandbox, sandboxSummary, onCtrlEnter, store } from "../../lib.js";

const API = "/api/postgres";
const DRAFT_KEY = "pg-sandbox-draft";

const EXAMPLES = [
  {
    label: "INSERT … RETURNING",
    sql: `INSERT INTO customers (name, email, signup_date)
VALUES ('王小明', 'xiaoming@example.com', current_date)
RETURNING id, name, vip_level;`,
  },
  {
    label: "UPDATE 後再查一次",
    sql: `UPDATE products
SET price = round(price * 1.1)
WHERE category_id = 20;

SELECT id, name, price
FROM products
WHERE category_id = 20
ORDER BY id
LIMIT 5;`,
  },
  {
    label: "UPSERT",
    sql: `INSERT INTO categories (id, name, parent_id)
VALUES (2, '智慧型手機', 1),
       (28, '寵物用品', NULL)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
RETURNING *;`,
  },
  {
    label: "DELETE 與 CASCADE",
    sql: `SELECT count(*) AS items_before FROM order_items;

DELETE FROM orders WHERE status = 'pending';

SELECT count(*) AS items_after FROM order_items;`,
  },
  {
    label: "搬資料（CTE + DELETE）",
    sql: `WITH removed AS (
    DELETE FROM orders
    WHERE status = 'cancelled' AND order_date < '2023-01-01'
    RETURNING id, customer_id
)
SELECT count(*) AS removed_orders,
       count(DISTINCT customer_id) AS customers
FROM removed;`,
  },
  {
    label: "JSONB 與陣列",
    sql: `UPDATE products
SET specs = COALESCE(specs, '{}') || '{"on_sale": true}',
    tags  = array_append(tags, '週年慶')
WHERE id <= 3
RETURNING id, specs, tags;`,
  },
];

export function mount(el) {
  el.innerHTML = `
    <div class="panel">
      <div>
        <span class="eyebrow">寫入沙盒</span>
        <h3>INSERT / UPDATE / DELETE，執行完自動還原</h3>
        <p class="desc">可以一次寫好幾句（用分號隔開），全部在同一個交易裡執行。例如先 UPDATE、再 SELECT，就能看到改完的樣子。
          結束時一律 ROLLBACK，資料不會真的被改；BEGIN、COMMIT、DROP 這類指令會被擋下。</p>
      </div>
      <div class="variant-row" data-ref="examples">
        <span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}
      </div>
      <label for="pg-sandbox-sql">SQL（Ctrl+Enter 執行）</label>
      <textarea id="pg-sandbox-sql" data-ref="sql" spellcheck="false"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">執行（最後自動 ROLLBACK）</button></div>
      <div class="status" data-ref="status" aria-live="polite"></div>
      <div data-ref="output"></div>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true;
  let runToken = 0;

  $("sql").value = store.get(DRAFT_KEY, EXAMPLES[0].sql);
  $("examples").addEventListener("click", (e) => {
    const b = e.target.closest("[data-example]");
    if (!b) return;
    $("sql").value = EXAMPLES[Number(b.dataset.example)].sql;
    store.set(DRAFT_KEY, $("sql").value);
  });
  $("sql").addEventListener("input", () => store.set(DRAFT_KEY, $("sql").value));

  async function run() {
    if ($("run").disabled) return;
    const token = ++runToken;
    $("run").disabled = true;
    await runQuery({
      request: () => postJson(`${API}/sandbox`, { sql: $("sql").value }),
      toResult: sandboxSummary,
      render: renderSandbox,
      statusEl: $("status"),
      outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (alive && token === runToken) $("run").disabled = false;
  }

  $("run").addEventListener("click", run);
  onCtrlEnter($("sql"), run);
  return () => { alive = false; };
}
