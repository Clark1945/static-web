// 範例與自由查詢：示範查詢清單 + 可修改的 SQL 編輯器
import { api, postJson, esc, runQuery, onCtrlEnter } from "../../lib.js";

const API = "/api/postgres";

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" data-ref="qlist" aria-label="範例查詢"></nav>
      <div class="panel">
        <div><h3 data-ref="title">…</h3><p class="desc" data-ref="desc"></p></div>
        <label for="pg-play-sql">SQL（可以直接修改，Ctrl+Enter 執行）</label>
        <textarea id="pg-play-sql" data-ref="sql" spellcheck="false"></textarea>
        <div class="actions">
          <button class="btn" data-ref="run" type="button">執行測試</button>
          <button class="btn ghost" data-ref="reset" type="button" hidden>還原成原本的 SQL</button>
          <span class="edited" data-ref="edited" hidden>已修改，將以自訂 SQL 執行</span>
        </div>
        <div class="status" data-ref="status" aria-live="polite"><span class="muted">尚未執行</span></div>
        <div data-ref="output"><div class="empty">按「執行測試」後，查詢結果會顯示在這裡</div></div>
      </div>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let queries = [];
  let current = null;
  let runToken = 0;
  let alive = true;

  api(`${API}/queries`).then((list) => {
    if (!alive) return;
    queries = list;
    let html = "", topic = null;
    for (const q of queries) {
      if (q.topic !== topic) { topic = q.topic; html += `<div class="topic">${esc(topic)}</div>`; }
      html += `<button type="button" data-id="${esc(q.id)}">${esc(q.title)}</button>`;
    }
    $("qlist").innerHTML = html;
    select(queries[0].id);
  });

  $("qlist").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-id]");
    if (b) select(b.dataset.id);
  });

  function select(id) {
    current = queries.find((q) => q.id === id);
    el.querySelectorAll(".qlist button").forEach((b) => b.classList.toggle("active", b.dataset.id === id));
    $("title").textContent = current.title;
    $("desc").textContent = current.description;
    $("sql").value = current.sql.trim();
    updateEdited();
    runToken++;                                   // 切換查詢時，忽略還在跑的舊結果
    $("run").disabled = false;
    $("status").innerHTML = '<span class="muted">尚未執行</span>';
    $("output").innerHTML = '<div class="empty">按「執行測試」後，查詢結果會顯示在這裡</div>';
  }

  const isEdited = () => current && $("sql").value.trim() !== current.sql.trim();
  function updateEdited() {
    $("edited").hidden = $("reset").hidden = !isEdited();
  }

  async function run() {
    if (!current || $("run").disabled) return;
    const token = ++runToken;
    const sql = $("sql").value;
    const edited = isEdited();
    $("run").disabled = true;
    await runQuery({
      request: () => edited
        ? postJson(`${API}/sql`, { sql })
        : api(`${API}/queries/${current.id}/run`, { method: "POST" }),
      statusEl: $("status"),
      outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (alive && token === runToken) $("run").disabled = false;
  }

  $("run").addEventListener("click", run);
  $("reset").addEventListener("click", () => { $("sql").value = current.sql.trim(); updateEdited(); });
  $("sql").addEventListener("input", updateEdited);
  onCtrlEnter($("sql"), run);

  return () => { alive = false; };
}
