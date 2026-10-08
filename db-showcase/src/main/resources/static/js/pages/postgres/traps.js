// 陷阱題：先選答案，再執行兩段 SQL 對照，最後看解說
import { api, postJson, esc, fmt, renderTable, store } from "../../lib.js";


/** options：api（API 路徑）、prefix（瀏覽器儲存結果用的前綴），TimescaleDB 頁面也共用這個模組。 */
export function mount(el, { api: API = "/api/postgres", prefix = "pg" } = {}) {
  const RESULT_KEY = `${prefix}-traps`;
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="陷阱題">
        <div class="progress-line" data-ref="progress"></div>
        <div data-ref="list"></div>
      </nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  const results = store.get(RESULT_KEY, {});      // id → true（第一次就答對）/ false
  let traps = [];
  let current = null;
  let alive = true;

  api(`${API}/traps`).then((list) => {
    if (!alive) return;
    traps = list;
    select((traps.find((t) => !(t.id in results)) ?? traps[0]).id);
  });

  function renderList() {
    const answered = traps.filter((t) => t.id in results);
    const right = answered.filter((t) => results[t.id]).length;
    $("progress").innerHTML = `
      <span>猜對 <b>${right}</b> / 已作答 ${answered.length} / ${traps.length}</span>
      <span class="bar"><i style="width:${(answered.length / traps.length) * 100}%"></i></span>`;
    $("list").innerHTML = traps.map((t, i) => {
      const mark = !(t.id in results) ? '<span class="mark"></span>'
        : results[t.id] ? '<span class="mark ok">✓</span>' : '<span class="mark bad">✗</span>';
      return `<button type="button" data-id="${esc(t.id)}" class="${current?.id === t.id ? "active" : ""}">${mark}${i + 1}. ${esc(t.title)}</button>`;
    }).join("");
  }

  $("list").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-id]");
    if (b) select(b.dataset.id);
  });

  function select(id) {
    current = traps.find((t) => t.id === id);
    renderList();
    $("panel").innerHTML = `
      <div><span class="eyebrow">陷阱題</span><h3>${esc(current.title)}</h3></div>
      <div class="sql-pair">
        ${current.sqls.map((s) => `
          <div class="sql-card">
            <div class="sql-label">${esc(s.label)}</div>
            <pre class="code">${esc(s.sql.trim())}</pre>
          </div>`).join("")}
      </div>
      <p class="question">${esc(current.question)}</p>
      <div class="options" data-ref="options">
        ${current.options.map((o, i) => `<button type="button" class="option" data-choice="${i}">${esc(o)}</button>`).join("")}
      </div>
      <div class="status" data-ref="status" aria-live="polite"><span class="muted">選一個答案後，才會執行兩段 SQL。</span></div>
      <div data-ref="reveal"></div>`;
    $("options").addEventListener("click", (e) => {
      const b = e.target.closest("[data-choice]");
      if (b && !b.disabled) answer(Number(b.dataset.choice));
    });
  }

  async function answer(choice) {
    const trap = current;
    const buttons = [...el.querySelectorAll("[data-choice]")];
    buttons.forEach((b) => (b.disabled = true));
    buttons[choice].classList.add("chosen");
    $("status").innerHTML = '<span class="running"><span class="spinner"></span>執行兩段 SQL 中…</span>';
    try {
      const r = await postJson(`${API}/traps/${trap.id}/answer`, { choice });
      if (!alive || trap !== current) return;
      if (!(trap.id in results)) { results[trap.id] = r.correct; store.set(RESULT_KEY, results); }
      buttons[r.answer].classList.add("correct");
      if (!r.correct) buttons[choice].classList.add("wrong");
      $("status").innerHTML = "";
      $("reveal").innerHTML = `
        <div class="verdict ${r.correct ? "ok" : "bad"}">
          <b>${r.correct ? "✓ 猜對了！" : `✗ 答案是「${esc(trap.options[r.answer])}」`}</b>
          <div class="explain">${esc(r.explanation.trim())}</div>
        </div>
        <div class="sql-pair">
          ${r.results.map((res, i) => `
            <div class="sql-card">
              <div class="sql-label">${esc(trap.sqls[i].label)}
                <span class="muted">· ${fmt(res.rowCount)} 筆 · ${res.elapsedMs.toFixed(1)} ms</span></div>
              ${renderTable(res)}
            </div>`).join("")}
        </div>
        <div class="actions"><button class="btn ghost" type="button" data-ref="retry">重新作答</button>
          ${nextTrap(trap) ? '<button class="btn" type="button" data-ref="next">下一題</button>' : ""}</div>`;
      renderList();
      $("retry").addEventListener("click", () => select(trap.id));
      $("next")?.addEventListener("click", () => select(nextTrap(trap).id));
    } catch (e) {
      if (!alive) return;
      $("status").innerHTML = `<div class="error">${esc(e.message)}</div>`;
      buttons.forEach((b) => (b.disabled = false));
    }
  }

  const nextTrap = (t) => traps[traps.indexOf(t) + 1];

  return () => { alive = false; };
}
