// InfluxDB 練習題：依題目寫 InfluxQL、Flux 或 line protocol；查詢題用唯讀 token 比對，寫入題寫進 scratch 再用檢查查詢比對
import { api, postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { runView, LANG_LABEL } from "./format.js";

const API = "/api/influx";
const PROGRESS_KEY = "ifx-practice";
const EDITOR = {
  influxql: { label: "你的 InfluxQL（類似 SQL；字串值用單引號，名稱用雙引號）",
    placeholder: `SELECT mean(usage_user) FROM cpu WHERE host = 'db-01' AND time >= '2026-09-18T00:00:00+08:00' GROUP BY time(1h)
-- Ctrl+Enter 執行並批改` },
  flux: { label: "你的 Flux（from |> range |> filter |> …）",
    placeholder: `from(bucket: "metrics")
  |> range(start: 2026-09-18T00:00:00+08:00, stop: 2026-09-19T00:00:00+08:00)
  |> filter(fn: (r) => r._measurement == "cpu")
// Ctrl+Enter 執行並批改` },
  write: { label: "你的 line protocol（一行一個點，時間戳記單位是秒；# 開頭是註解）",
    placeholder: `measurement,tag=值 field=值 1790726400
# Ctrl+Enter 寫入並批改` },
};

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
        <label for="ifx-practice-cmd" data-ref="cmd-label">你的答案</label>
        <textarea id="ifx-practice-cmd" data-ref="cmd" spellcheck="false" style="min-height:150px"></textarea>
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
    select(store.get("ifx-practice-current", first.id));
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
    store.set("ifx-practice-current", current.id);
    renderList();
    $("chapter").textContent = current.chapter;
    $("title").textContent = current.title;
    const L = EDITOR[current.lang] ?? EDITOR.influxql;
    $("cmd-label").textContent = L.label;
    $("cmd").placeholder = L.placeholder;
    $("prompt").textContent = current.prompt.trim();
    $("meta").innerHTML = `
      <span class="tag lang-${esc(current.lang)}">${esc(LANG_LABEL[current.lang] ?? current.lang)}</span>
      ${current.lang === "write" ? '<span class="tag write">寫入題：寫進清空的 scratch bucket，再用檢查查詢比對資料</span>'
        : current.lang === "flux" ? '<span class="tag">只比對標準答案有的欄位（_start、_stop 不比對）</span>'
        : '<span class="tag">依位置比對每一列，欄位名稱、別名不影響</span>'}
      ${current.ordered ? "" : '<span class="tag">順序不拘</span>'}
      <span class="tag">比對 hits 的 _id、聚合結果（名稱自己取）、count</span>
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
      ? '<div class="verdict ok"><b>這題你已經做對了</b><span>可以試試別的寫法。</span></div>' : "";
  }

  function renderHints() {
    const hints = current.hints ?? [];
    $("hints").hidden = hintsShown === 0;
    $("hints").innerHTML = hints.slice(0, hintsShown).map((h) => `<li>${esc(h)}</li>`).join("");
    $("hint-btn").textContent = hints.length === 0 ? "沒有提示" : hintsShown >= hints.length ? "提示已全部顯示" : `提示（${hintsShown}/${hints.length}）`;
    $("hint-btn").disabled = hintsShown >= hints.length;
  }

  async function check() {
    const code = $("cmd").value;
    if (!code.trim() || $("check").disabled) return;
    const ex = current, token = ++runToken;
    $("check").disabled = true;
    progress.attempts[ex.id] = (progress.attempts[ex.id] ?? 0) + 1;
    save();
    const grade = await runQuery({
      request: () => postJson(`${API}/exercises/${ex.id}/check`, { code }),
      toResult: (g) => ({ rowCount: g.result.blocks.reduce((s, b) => s + (b.total ?? 0), 0), truncated: false, elapsedMs: g.result.totalMillis }),
      render: renderGrade,
      statusEl: $("status"), outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (!alive || token !== runToken) return;
    $("check").disabled = false;
    const chip = $("status").querySelector(".chip");
    if (chip && grade) chip.innerHTML = `<b>${grade.result.blocks.length}</b> 個結果區塊`;
    if (grade?.correct) { progress.solved[ex.id] = true; save(); renderList(); }
  }

  function renderGrade(g) {
    const verdict = g.correct
      ? `<div class="verdict ok"><b>✓ ${esc(g.message)}</b>
           <span>第 ${progress.attempts[current.id]} 次嘗試${progress.revealed[current.id] ? "（看過答案）" : ""}</span>
           ${g.explanation ? `<div class="explain">${esc(g.explanation.trim())}</div>` : ""}</div>`
      : `<div class="verdict bad"><b>✗ ${esc(g.message)}</b>
           ${g.mismatch ? `<div class="reply-diff">
             <div><span>你的（${esc(g.mismatch.what)}）</span><pre class="code">${esc(typeof g.mismatch.yours === "string" ? g.mismatch.yours : JSON.stringify(g.mismatch.yours, null, 2))}</pre></div>
             <div><span>正確的</span><pre class="code">${esc(typeof g.mismatch.expected === "string" ? g.mismatch.expected : JSON.stringify(g.mismatch.expected, null, 2))}</pre></div>
           </div>` : ""}</div>`;
    const check = g.check ? `<h4 class="sub-head">寫入之後，用檢查查詢看 scratch 裡的資料</h4>${runView(g.check)}` : "";
    return verdict + `<h4 class="sub-head">你的執行結果</h4>` + runView(g.result) + check;
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
      <pre class="code">${esc(a.code.trim())}</pre><div class="explain">${esc(a.explanation.trim())}</div>`;
    $("use-answer").addEventListener("click", () => { $("cmd").value = a.code.trim(); progress.drafts[ex.id] = $("cmd").value; save(); });
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
