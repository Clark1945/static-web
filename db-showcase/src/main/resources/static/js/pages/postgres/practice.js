// 練習題：只給題目，自己寫 SQL；後端同時跑你的 SQL 與標準答案並比對結果
import { api, postJson, esc, renderTable, runQuery, renderSandbox, sandboxSummary, store, onCtrlEnter } from "../../lib.js";

const API = "/api/postgres";
const PROGRESS_KEY = "pg-practice";
const NO_ROWS = { columns: [], rows: [], rowCount: 0, truncated: false, elapsedMs: 0 };

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="練習題">
        <div class="progress-line" data-ref="progress"></div>
        <div data-ref="list"></div>
      </nav>
      <div class="panel">
        <div>
          <span class="eyebrow" data-ref="chapter"></span>
          <h3 data-ref="title">…</h3>
        </div>
        <div class="prompt" data-ref="prompt"></div>
        <div class="meta-line" data-ref="meta"></div>
        <label for="pg-practice-sql">你的 SQL</label>
        <textarea id="pg-practice-sql" data-ref="sql" spellcheck="false"
                  placeholder="-- 在這裡寫 SQL，Ctrl+Enter 執行並批改"></textarea>
        <div class="actions">
          <button class="btn" data-ref="check" type="button">執行並批改</button>
          <button class="btn ghost" data-ref="run" type="button">只執行</button>
          <button class="btn ghost" data-ref="hint-btn" type="button">提示</button>
          <button class="btn ghost" data-ref="answer-btn" type="button">看答案</button>
        </div>
        <ol class="hints" data-ref="hints" hidden></ol>
        <div class="answer-box" data-ref="answer" hidden></div>
        <div class="status" data-ref="status" aria-live="polite"></div>
        <div data-ref="output"></div>
      </div>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  const progress = store.get(PROGRESS_KEY, {});
  for (const k of ["solved", "revealed", "attempts", "drafts"]) progress[k] ??= {};
  const save = () => store.set(PROGRESS_KEY, progress);

  let exercises = [];
  let current = null;
  let hintsShown = 0;
  let runToken = 0;
  let alive = true;

  api(`${API}/exercises`).then((list) => {
    if (!alive) return;
    exercises = list;
    renderList();
    const firstUnsolved = exercises.find((e) => !progress.solved[e.id]) ?? exercises[0];
    select(store.get("pg-practice-current", firstUnsolved.id));
  });

  function renderList() {
    const done = exercises.filter((e) => progress.solved[e.id]).length;
    $("progress").innerHTML = `
      <span>已完成 <b>${done}</b> / ${exercises.length}</span>
      <span class="bar"><i style="width:${(done / exercises.length) * 100}%"></i></span>`;
    let html = "", chapter = null;
    for (const e of exercises) {
      if (e.chapter !== chapter) { chapter = e.chapter; html += `<div class="topic">${esc(chapter)}</div>`; }
      const mark = progress.solved[e.id]
        ? `<span class="mark ok" title="${progress.revealed[e.id] ? "看過答案後做對" : "已做對"}">${progress.revealed[e.id] ? "◐" : "✓"}</span>`
        : '<span class="mark"></span>';
      html += `<button type="button" data-id="${esc(e.id)}" class="${current?.id === e.id ? "active" : ""}">${mark}${esc(e.title)}</button>`;
    }
    $("list").innerHTML = html;
  }

  $("list").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-id]");
    if (b) select(b.dataset.id);
  });

  function select(id) {
    current = exercises.find((e) => e.id === id) ?? exercises[0];
    store.set("pg-practice-current", current.id);
    renderList();
    $("chapter").textContent = current.chapter;
    $("title").textContent = current.title;
    $("prompt").textContent = current.prompt.trim();
    $("meta").innerHTML = `
      ${current.write ? '<span class="tag write">寫入題：在沙盒執行，結束後自動 ROLLBACK</span>' : ""}
      <span class="tag">${current.ordered ? "要照題目指定的順序" : "順序不拘"}</span>
      <span class="tag">欄位名稱可以自己取</span>
      ${progress.attempts[current.id] ? `<span class="tag">已嘗試 ${progress.attempts[current.id]} 次</span>` : ""}`;
    $("sql").value = progress.drafts[current.id] ?? "";
    hintsShown = 0;
    renderHints();
    $("answer").hidden = true;
    $("answer").innerHTML = "";
    runToken++;
    setBusy(false);
    $("status").innerHTML = "";
    $("output").innerHTML = progress.solved[current.id]
      ? '<div class="verdict ok"><b>這題你已經做對了</b><span>可以再寫一次不同的解法練習看看。</span></div>'
      : "";
  }

  function renderHints() {
    const hints = current.hints ?? [];
    $("hints").hidden = hintsShown === 0;
    $("hints").innerHTML = hints.slice(0, hintsShown).map((h) => `<li>${esc(h)}</li>`).join("");
    $("hint-btn").textContent = hints.length === 0 ? "沒有提示"
      : hintsShown >= hints.length ? "提示已全部顯示" : `提示（${hintsShown}/${hints.length}）`;
    $("hint-btn").disabled = hintsShown >= hints.length;
  }

  function setBusy(busy) {
    for (const r of ["check", "run"]) $(r).disabled = busy;
  }

  // ---------- 批改 ----------
  async function check() {
    const sql = $("sql").value;
    if (!sql.trim()) { $("status").innerHTML = '<span class="muted">先寫一點 SQL 再批改。</span>'; return; }
    const ex = current;
    const token = ++runToken;
    setBusy(true);
    progress.attempts[ex.id] = (progress.attempts[ex.id] ?? 0) + 1;
    save();
    const grade = await runQuery({
      request: () => postJson(`${API}/exercises/${ex.id}/check`, { sql }),
      toResult: (g) => g.result ?? NO_ROWS,
      render: renderGrade,
      statusEl: $("status"),
      outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (!alive || token !== runToken) return;
    setBusy(false);
    if (grade?.correct) {
      progress.solved[ex.id] = true;
      save();
      renderList();
    }
  }

  function renderGrade(g) {
    let verdict;
    if (g.correct) {
      verdict = `
        <div class="verdict ok">
          <b>✓ ${esc(g.message)}</b>
          <span>第 ${progress.attempts[current.id]} 次嘗試${progress.revealed[current.id] ? "（看過答案）" : ""}</span>
          ${g.explanation ? `<div class="explain">${esc(g.explanation.trim())}</div>` : ""}
        </div>`;
    } else {
      const mm = g.mismatch;
      verdict = `
        <div class="verdict bad">
          <b>✗ ${esc(g.message)}</b>
          ${mm ? `
            <div class="diff">
              <div class="diff-row"><span>你的第 ${mm.row} 列</span><code>${mm.yours.map(cell).join(" | ")}</code></div>
              <div class="diff-row"><span>正確的第 ${mm.row} 列</span><code>${mm.expected.map(cell).join(" | ")}</code></div>
            </div>` : ""}
        </div>`;
    }
    return verdict + (g.result ? `<h4 class="sub-head">你的${current.write ? "最後一個回傳結果" : "查詢結果"}</h4>` + renderTable(g.result) : "");
  }

  const cell = (v) => (v === null ? "NULL" : esc(v));

  // ---------- 只執行（不批改） ----------
  async function runOnly() {
    const sql = $("sql").value;
    if (!sql.trim()) return;
    const token = ++runToken;
    setBusy(true);
    const write = current.write;
    await runQuery({
      request: () => postJson(`${API}/${write ? "sandbox" : "sql"}`, { sql }),
      ...(write ? { toResult: sandboxSummary, render: renderSandbox } : {}),
      statusEl: $("status"),
      outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (alive && token === runToken) setBusy(false);
  }

  // ---------- 提示與答案 ----------
  $("hint-btn").addEventListener("click", () => { hintsShown++; renderHints(); });

  $("answer-btn").addEventListener("click", async () => {
    if (!$("answer").hidden) { $("answer").hidden = true; return; }
    const ex = current;
    const a = await api(`${API}/exercises/${ex.id}/answer`);
    if (!alive || ex !== current) return;
    progress.revealed[ex.id] = true;
    save();
    $("answer").hidden = false;
    $("answer").innerHTML = `
      <div class="answer-head"><b>參考答案</b>
        <button class="btn ghost small" type="button" data-ref="use-answer">放進編輯器</button></div>
      <pre class="code">${esc(a.sql.trim())}</pre>
      <div class="explain">${esc(a.explanation.trim())}</div>`;
    $("use-answer").addEventListener("click", () => {
      $("sql").value = a.sql.trim();
      progress.drafts[ex.id] = $("sql").value;
      save();
    });
  });

  $("check").addEventListener("click", check);
  $("run").addEventListener("click", runOnly);
  onCtrlEnter($("sql"), () => { if (!$("check").disabled) check(); });

  let draftTimer = null;
  $("sql").addEventListener("input", () => {
    clearTimeout(draftTimer);
    const id = current.id, value = $("sql").value;
    draftTimer = setTimeout(() => { progress.drafts[id] = value; save(); }, 400);
  });

  return () => { alive = false; clearTimeout(draftTimer); };
}
