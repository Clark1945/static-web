// Cypher 主控台：所有句子在同一個交易裡執行，最後 ROLLBACK；結果裡的節點與關係會畫成圖
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { transcript } from "./format.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const EXAMPLES = [
  { label: "會員的社群圈", cmd: "// 4242 追蹤的人，以及他們追蹤的人\nMATCH p = (:Customer {id: 4242})-[:FOLLOWS*1..2]->(:Customer)\nRETURN p" },
  { label: "一張訂單", cmd: "MATCH p = (:Customer)-[:PLACED]->(:Order {id: 77621})-[:CONTAINS]->(:Product)-[:IN_CATEGORY]->(:Category)\nRETURN p" },
  { label: "分類樹", cmd: "MATCH p = (:Category)-[:SUBCATEGORY_OF]->(:Category)\nRETURN p" },
  { label: "最短路徑", cmd: "MATCH p = shortestPath((:Customer {id: 4242})-[:FOLLOWS*..10]->(:Customer {id: 19999}))\nRETURN p, length(p) AS hops" },
  { label: "推薦（表格）", cmd: "MATCH (:Product {id: 540})<-[:CONTAINS]-(:Order)<-[:PLACED]-(c:Customer)\nMATCH (c)-[:PLACED]->(:Order)-[:CONTAINS]->(other:Product)\nWHERE other.id <> 540\nRETURN other.name, count(DISTINCT c) AS buyers\nORDER BY buyers DESC LIMIT 5" },
  { label: "寫入（會 ROLLBACK）", cmd: "MATCH (c:Customer {id: 4242}), (city:City {name: '台南市'})\nCREATE (n:Customer {id: 99999, name: '測試'})-[:FOLLOWS]->(c), (n)-[:LIVES_IN]->(city)\nRETURN n, c, city;\n\nMATCH (n:Customer {id: 99999}) RETURN count(n) AS stillHereInThisTx" },
  { label: "PROFILE", cmd: "PROFILE\nMATCH (c:Customer) WHERE c.email = 'user04242@example.com'\nRETURN c.name" },
  { label: "SHOW INDEXES", cmd: "SHOW INDEXES YIELD name, type, labelsOrTypes, properties, state" },
];

export function mount(el) {
  el.innerHTML = `
    <div class="panel">
      <div><span class="eyebrow">Cypher 主控台</span><h3>Cypher</h3>
        <p class="desc">可以多句，用分號分開；所有句子在<b>同一個交易</b>裡依序執行，最後一律 <b>ROLLBACK</b>，所以可以放心試寫入。
          回傳節點、關係或路徑時會畫成關係圖；在查詢前面加上 <code>PROFILE</code> 可以看執行計畫。
          LOAD CSV、CALL dbms.*、使用者與資料庫管理指令已關閉（社群版沒有權限控管）。</p></div>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}</div>
      <label for="neo4j-console">Cypher（Ctrl+Enter 執行）</label>
      <textarea id="neo4j-console" data-ref="cmd" spellcheck="false" style="min-height:150px"></textarea>
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
      request: () => postJson("/api/neo4j/run", { commands: $("cmd").value }),
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
