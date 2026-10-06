// 指令主控台：mongosh 寫法，用 learner 帳號在 shop 執行
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { transcript } from "./format.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const EXAMPLES = [
  { label: "看一份訂單", cmd: "db.orders.findOne({ _id: 77621 })" },
  { label: "條件 + 投影 + 排序", cmd: 'db.products.find(\n  { "category.name": "手機", price: { $lt: 10000 } },\n  { name: 1, price: 1, "specs.storage_gb": 1 }\n).sort({ price: -1 }).limit(5)' },
  { label: "聚合：分類營收", cmd: 'db.orders.aggregate([\n  { $match: { status: "delivered" } },\n  { $unwind: "$items" },\n  { $lookup: { from: "products", localField: "items.productId", foreignField: "_id", as: "p" } },\n  { $group: { _id: { $first: "$p.category.name" }, revenue: { $sum: { $multiply: ["$items.qty", "$items.unitPrice"] } } } },\n  { $sort: { revenue: -1 } },\n  { $limit: 5 }\n])' },
  { label: "陣列運算子", cmd: 'db.products.countDocuments({ tags: { $all: ["熱銷", "特價"] } })\ndb.products.countDocuments({ tags: { $size: 0 } })\ndb.orders.countDocuments({ items: { $size: 4 } })' },
  { label: "寫入（記得重置）", cmd: 'db.products.updateOne({ _id: 540 }, { $set: { stock: 99 }, $push: { tags: "週年慶" } })\ndb.products.findOne({ _id: 540 }, { stock: 1, tags: 1 })' },
  { label: "explain", cmd: 'db.orders.find({ customerId: 4242 }).explain("executionStats")' },
  { label: "權限測試", cmd: 'db.orders.find({ $where: "this.total > 1000" })' },
];

export function mount(el) {
  el.innerHTML = `
    <div class="panel">
      <div><span class="eyebrow">指令主控台</span><h3>mongosh</h3>
        <p class="desc">支援 find、findOne、countDocuments、distinct、aggregate、insert / update / delete、findOneAndUpdate、createIndex、explain…
          指令可以換行，每一句用 db. 開頭。寫入會保留在 shop，練習完按上方「重置資料」還原。</p></div>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}</div>
      <label for="mongo-console">指令（Ctrl+Enter 執行）</label>
      <textarea id="mongo-console" data-ref="cmd" spellcheck="false" style="min-height:170px"></textarea>
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
      request: () => postJson("/api/mongo/run", { commands: $("cmd").value }),
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
