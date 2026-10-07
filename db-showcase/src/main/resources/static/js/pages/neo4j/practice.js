// Neo4j 練習題：自己寫 Cypher；你的答案與標準答案各自在交易裡執行後 ROLLBACK，寫入題在同一個交易裡用檢查查詢比對資料
import { api, postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { formatRowObjects, transcript } from "./format.js";

const API = "/api/neo4j";
const PROGRESS_KEY = "neo4j-practice";

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="練習題">
        <div class="progress-line" data-ref="progress"></div>
        <div data-ref="list"></div>
      </nav>
      <div class="panel">
        <div><span class="eyebrow" data-ref="chapter"></span><h3 data-ref="title">…</h3></div>
        <div class="prompt" data-ref="prompt"></div>
        <div class="meta-line" data-ref="meta"></div>
        <label for="neo4j-practice-cmd">你的 Cypher（可以多句，用分號分開；// 開頭是註解）</label>
        <textarea id="neo4j-practice-cmd" data-ref="cmd" spellcheck="false" style="min-height:150px"
                  placeholder="// 例如：MATCH (c:Customer {id: 4242}) RETURN c.name&#10;// Ctrl+Enter 執行並批改"></textarea>
        <div class="actions">
          <button class="btn" data-ref="check" type="button">執行並批改</button>
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
  let exercises = [], current = null, hintsShown = 0, runToken = 0, alive = true;

  api(`${API}/exercises`).then((list) => {
    if (!alive) return;
    exercises = list;
    const first = exercises.find((e) => !progress.solved[e.id]) ?? exercises[0];
    select(store.get("neo4j-practice-current", first.id));
  });

  function renderList() {
    const done = exercises.filter((e) => progress.solved[e.id]).length;
    $("progress").innerHTML = `<span>已完成 <b>${done}</b> / ${exercises.length}</span>
      <span class="bar"><i style="width:${(done / exercises.length) * 100}%"></i></span>`;
    let html = "", chapter = null;
    for (const e of exercises) {
      if (e.chapter !== chapter) { chapter = e.chapter; html += `<div class="topic">${esc(chapter)}</div>`; }
      const mark = progress.solved[e.id] ? `<span class="mark ok">${progress.revealed[e.id] ? "◐" : "✓"}</span>` : '<span class="mark"></span>';
      html += `<button type="button" data-id="${esc(e.id)}" class="${current?.id === e.id ? "active" : ""}">${mark}${esc(e.title)}</button>`;
    }
    $("list").innerHTML = html;
  }
  $("list").addEventListener("click", (e) => { const b = e.target.closest("button[data-id]"); if (b) select(b.dataset.id); });

  function select(id) {
    current = exercises.find((e) => e.id === id) ?? exercises[0];
    store.set("neo4j-practice-current", current.id);
    renderList();
    $("chapter").textContent = current.chapter;
    $("title").textContent = current.title;
    $("prompt").textContent = current.prompt.trim();
    $("meta").innerHTML = `
      ${current.write ? '<span class="tag write">寫入題：在交易裡執行後用檢查查詢比對資料，最後 ROLLBACK</span>' : '<span class="tag">查詢題：比對最後一句的結果</span>'}
      ${current.ordered ? "" : '<span class="tag">順序不拘</span>'}
      <span class="tag">依欄位位置比對，欄位名稱（別名）不影響批改</span>
      ${progress.attempts[current.id] ? `<span class="tag">已嘗試 ${progress.attempts[current.id]} 次</span>` : ""}`;
    $("cmd").value = progress.drafts[current.id] ?? "";
    hintsShown = 0;
    renderHints();
    $("answer").hidden = true;
    $("answer").innerHTML = "";
    runToken++;
    $("check").disabled = false;
    $("status").innerHTML = "";
    $("output").innerHTML = progress.solved[current.id]
      ? '<div class="verdict ok"><b>這題你已經做對了</b><span>想一想：換一個方向、或用可變長度關係，還能怎麼寫？</span></div>' : "";
  }

  function renderHints() {
    const hints = current.hints ?? [];
    $("hints").hidden = hintsShown === 0;
    $("hints").innerHTML = hints.slice(0, hintsShown).map((h) => `<li>${esc(h)}</li>`).join("");
    $("hint-btn").textContent = hints.length === 0 ? "沒有提示" : hintsShown >= hints.length ? "提示已全部顯示" : `提示（${hintsShown}/${hints.length}）`;
    $("hint-btn").disabled = hintsShown >= hints.length;
  }

  async function check() {
    const commands = $("cmd").value;
    if (!commands.trim() || $("check").disabled) return;
    const ex = current, token = ++runToken;
    $("check").disabled = true;
    progress.attempts[ex.id] = (progress.attempts[ex.id] ?? 0) + 1;
    save();
    const grade = await runQuery({
      request: () => postJson(`${API}/exercises/${ex.id}/check`, { commands }),
      toResult: (g) => ({ rowCount: g.result.results.length, truncated: false, elapsedMs: g.result.totalMillis }),
      render: renderGrade,
      statusEl: $("status"), outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (!alive || token !== runToken) return;
    $("check").disabled = false;
    const chip = $("status").querySelector(".chip");
    if (chip && grade) chip.innerHTML = `執行了 <b>${grade.result.results.length}</b> 句`;
    if (grade?.correct) { progress.solved[ex.id] = true; save(); renderList(); }
  }

  function renderGrade(g) {
    const verdict = g.correct
      ? `<div class="verdict ok"><b>✓ ${esc(g.message)}</b>
           <span>第 ${progress.attempts[current.id]} 次嘗試${progress.revealed[current.id] ? "（看過答案）" : ""}</span>
           ${g.explanation ? `<div class="explain">${esc(g.explanation.trim())}</div>` : ""}</div>`
      : `<div class="verdict bad"><b>✗ ${esc(g.message)}</b>
           ${g.mismatch ? `<div class="reply-diff">
             <div><span>你的（${esc(g.mismatch.what)}）</span>${formatRowObjects(g.mismatch.yours)}</div>
             <div><span>正確的</span>${formatRowObjects(g.mismatch.expected)}</div>
           </div>` : ""}</div>`;
    const checks = g.checks?.length ? `<h4 class="sub-head">執行完之後（同一個交易裡），用這些查詢檢查資料</h4>${transcript(g.checks, { rolledBack: false })}` : "";
    return verdict + `<h4 class="sub-head">你的 Cypher 執行結果</h4>` + transcript(g.result.results) + checks;
  }

  $("hint-btn").addEventListener("click", () => { hintsShown++; renderHints(); });
  $("answer-btn").addEventListener("click", async () => {
    if (!$("answer").hidden) { $("answer").hidden = true; return; }
    const ex = current;
    const a = await api(`${API}/exercises/${ex.id}/answer`);
    if (!alive || ex !== current) return;
    progress.revealed[ex.id] = true;
    save();
    $("answer").hidden = false;
    $("answer").innerHTML = `<div class="answer-head"><b>參考答案</b>
        <button class="btn ghost small" type="button" data-ref="use-answer">放進編輯器</button></div>
      <pre class="code">${esc(a.commands.trim())}</pre><div class="explain">${esc(a.explanation.trim())}</div>`;
    $("use-answer").addEventListener("click", () => { $("cmd").value = a.commands.trim(); progress.drafts[ex.id] = $("cmd").value; save(); });
  });
  $("check").addEventListener("click", check);
  onCtrlEnter($("cmd"), check);
  let draftTimer = null;
  $("cmd").addEventListener("input", () => {
    clearTimeout(draftTimer);
    const id = current.id, value = $("cmd").value;
    draftTimer = setTimeout(() => { progress.drafts[id] = value; save(); }, 400);
  });
  return () => { alive = false; clearTimeout(draftTimer); };
}
