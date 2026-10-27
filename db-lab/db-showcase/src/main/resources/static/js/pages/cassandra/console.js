// cqlsh 主控台：用 learner 角色在 shop 執行 CQL，支援 cqlsh 的 CONSISTENCY 與 TRACING
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { transcript } from "./format.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const EXAMPLES = [
  { label: "會員的訂單", cmd: "SELECT * FROM orders_by_customer WHERE customer_id = 4242;" },
  { label: "整張訂單（UDT 清單）", cmd: "SELECT order_id, status, total, items FROM orders WHERE order_id = 77621;" },
  { label: "查詢追蹤", cmd: "TRACING ON;\nSELECT order_id, total FROM orders_by_customer WHERE customer_id = 4242;\nSELECT order_id, total FROM orders WHERE customer_id = 4242 ALLOW FILTERING;" },
  { label: "集合與 map", cmd: "SELECT name, tags, specs['color'], specs FROM products WHERE product_id = 540;" },
  { label: "WRITETIME / TTL", cmd: "SELECT name, WRITETIME(name), city, TTL(city) FROM customers WHERE customer_id = 4242;" },
  { label: "token（分區放在哪）", cmd: "SELECT customer_id, token(customer_id) FROM customers LIMIT 5;" },
  { label: "輕量交易（記得重新載入）", cmd: "UPDATE products SET stock = 10 WHERE product_id = 540 IF stock = 11;\nSELECT stock FROM products WHERE product_id = 540;" },
  { label: "DESCRIBE", cmd: "DESCRIBE TABLE orders_by_day;" },
  { label: "權限測試", cmd: "DROP TABLE orders;" },
];

export function mount(el) {
  el.innerHTML = `
    <div class="panel">
      <div><span class="eyebrow">cqlsh 主控台</span><h3>CQL</h3>
        <p class="desc">可以多句，用分號分開。除了 CQL，也支援 cqlsh 的 <code>CONSISTENCY QUORUM;</code>（之後的句子改用這個一致性等級）
          和 <code>TRACING ON;</code>（之後的句子附上查詢追蹤）。用的是可讀寫 shop 的 learner 角色，不能建表、刪表；
          寫入會保留，練習完按上方「重新載入資料」還原。</p></div>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}</div>
      <label for="cassandra-console">CQL（Ctrl+Enter 執行）</label>
      <textarea id="cassandra-console" data-ref="cmd" spellcheck="false" style="min-height:150px"></textarea>
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
      request: () => postJson("/api/cassandra/run", { commands: $("cmd").value }),
      toResult: (x) => ({ rowCount: x.results.length, truncated: false, elapsedMs: x.totalMillis }),
      render: (x) => transcript(x.results),
      statusEl: $("status"), outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (!alive || token !== runToken) return;
    $("run").disabled = false;
    const chip = $("status").querySelector(".chip");
    if (chip && r) chip.innerHTML = `執行了 <b>${r.results.length}</b> 句`;
  }
  $("run").addEventListener("click", run);
  onCtrlEnter($("cmd"), run);
  return () => { alive = false; };
}
