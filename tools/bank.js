// 題庫：上方一個資料庫一個分頁，分頁裡再分「練習題 / 陷阱題 / 實驗」；資料來自頁面裡嵌入的 JSON
(() => {
  const data = JSON.parse(document.getElementById("data").textContent);
  const bank = document.getElementById("bank");
  const input = document.getElementById("q");
  const tabsNav = document.getElementById("dbtabs");
  const KNOWN_KEY = "bank-known";            // { "redis:str-get": true, … }
  const LAST_KEY = "bank-last";
  const SHOWCASE = { postgresql: "postgres", redis: "redis", mongodb: "mongodb", cassandra: "cassandra", neo4j: "neo4j", timescaledb: "timescaledb", pgvector: "pgvector", elasticsearch: "elasticsearch", influxdb: "influxdb" };
  const known = store.get(KNOWN_KEY, {});
  // 舊版只有 PostgreSQL，標記沒有加前綴：搬過來
  const legacy = store.get("pg-bank-known", null);
  if (legacy) {
    for (const id of Object.keys(legacy)) known[`postgresql:${id}`] = true;
    store.set(KNOWN_KEY, known);
    try { localStorage.removeItem("pg-bank-known"); } catch { /* 無法存取就算了 */ }
  }
  const state = { db: data.dbs[0].id, kind: "practice", chapter: "全部", showAnswers: false, hideKnown: false };
  const dbOf = (id) => data.dbs.find((d) => d.id === id) ?? data.dbs[0];
  const kinds = (db) => [
    { id: "practice", label: "練習題", n: db.exercises.length },
    { id: "traps", label: "陷阱題", n: db.traps.length },
    ...(db.steps.length ? [{ id: "lab", label: db.labName, n: db.steps.length }] : []),
  ];
  const total = (k) => data.dbs.reduce((s, d) => s + d[k].length, 0);
  document.getElementById("sub").textContent =
    `資料庫面試題庫 · ${data.dbs.length} 種資料庫 · 練習題 ${total("exercises")} · 陷阱題 ${total("traps")} · 更新於 ${data.built}`;

  // ---------- 共用片段 ----------
  const fmtNum = (n) => Number(n).toLocaleString("zh-TW");
  const code = (text, lang) => `<pre class="code" data-lang="${esc(lang)}"><code>${esc(String(text).trim())}</code></pre>`;
  const matches = (q, ...texts) => !q || texts.some((t) => String(t ?? "").toLowerCase().includes(q));

  function table(t) {
    if (t.rows.length === 0) return '<p class="result-head">0 列</p>';
    const head = t.columns.map((c) => `<th>${esc(c)}</th>`).join("");
    const body = t.rows.map((row) => "<tr>" + row.map((v) =>
      v === null ? '<td class="nul">null</td>'
      : typeof v === "number" ? `<td class="num">${v.toLocaleString("zh-TW")}</td>`
      : `<td>${esc(v)}</td>`).join("") + "</tr>").join("");
    const more = t.total > t.rows.length ? `，以下只列前 ${t.rows.length} 列` : "";
    return `<p class="result-head">共 ${fmtNum(t.total)} 列${more}</p>
      <div class="table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
  }

  /**
   * 執行結果。連續的文字結果（Redis、MongoDB、cqlsh 訊息）合成一段終端機紀錄：› 指令、下一行是回應；
   * 表格結果（PostgreSQL、Cassandra 的查詢）單獨顯示。
   */
  function blocks(list, title = "正確答案的執行結果") {
    if (!list || !list.length) return "";
    const parts = [];
    let lines = [];
    const flush = () => { if (lines.length) { parts.push(`<pre class="blk-out">${lines.join("\n")}</pre>`); lines = []; } };
    for (const b of list) {
      const warn = (b.warn ?? []).map((w) => `<p class="blk-warn">⚠ 伺服器警告：${esc(w)}</p>`).join("");
      if (b.table) {
        flush();
        parts.push(`<div class="blk">${b.cmd ? `<div class="blk-cmd"><span class="prompt-mark">›</span><code>${esc(b.cmd)}</code></div>` : ""}${table(b.table)}${warn}</div>`);
      } else {
        if (b.cmd) lines.push(`<span class="prompt-mark">›</span> <span class="tr-cmd">${esc(b.cmd)}</span>`);
        lines.push(`<span class="${b.error ? "tr-err" : ""}">${esc(b.text)}</span>`);
        if (warn) { flush(); parts.push(warn); }
      }
    }
    flush();
    return `<div class="run"><div class="run-title">${esc(title)}</div>${parts.join("")}</div>`;
  }

  // ---------- 分頁 ----------
  function searchHits(db, q) {
    if (!q) return null;
    return db.exercises.filter((e) => exerciseMatch(e, q)).length
      + db.traps.filter((t) => trapMatch(t, q)).length
      + db.steps.filter((s) => stepMatch(s, q)).length;
  }

  function renderTabs(q) {
    tabsNav.innerHTML = data.dbs.map((d) => {
      const n = searchHits(d, q);
      return `<a href="#${d.id}" data-tab="${d.id}" aria-current="${d.id === state.db ? "page" : "false"}"
        class="${n === 0 ? "no-hit" : ""}">${esc(d.name)}<span class="n">${n === null ? "" : n}</span></a>`;
    }).join("");
  }

  function render() {
    const q = input.value.trim().toLowerCase();
    const db = dbOf(state.db);
    if (!kinds(db).some((k) => k.id === state.kind)) state.kind = "practice";
    renderTabs(q);
    const kindTabs = `<div class="kinds" role="tablist" aria-label="題型">${kinds(db).map((k) => {
      const n = q ? (k.id === "practice" ? db.exercises.filter((e) => exerciseMatch(e, q)).length
        : k.id === "traps" ? db.traps.filter((t) => trapMatch(t, q)).length : db.steps.filter((s) => stepMatch(s, q)).length) : k.n;
      return `<button type="button" role="tab" data-kind="${k.id}" aria-selected="${k.id === state.kind}">${esc(k.label)}<span>${n}</span></button>`;
    }).join("")}</div>`;
    const note = `<p class="note">離線複習版：可以看題目、提示、答案與正確答案的執行結果${db.hasResults ? "" : "（這次產生時展示台沒有在執行，所以沒有附上結果）"}。
      要自己寫、自動批改，請啟動展示台後打開 <a href="http://localhost:8081/#/${SHOWCASE[db.id]}">http://localhost:8081/#/${SHOWCASE[db.id]}</a>。</p>`;
    const body = state.kind === "practice" ? renderPractice(db, q) : state.kind === "traps" ? renderTraps(db, q) : renderLab(db, q);
    bank.innerHTML = kindTabs + note + body;
    bank.dataset.db = db.id;
    document.title = `${db.name} · 題庫`;
    enhanceCode(bank);
  }

  // ---------- 練習題 ----------
  const exerciseMatch = (e, q) => matches(q, e.title, e.prompt, e.answer, e.explanation, e.chapter, ...e.hints);

  function renderPractice(db, q) {
    const chapters = [...new Set(db.exercises.map((e) => e.chapter))];
    if (state.chapter !== "全部" && !chapters.includes(state.chapter)) state.chapter = "全部";
    const doneCount = db.exercises.filter((e) => known[`${db.id}:${e.id}`]).length;
    const filters = `
      <div class="filters">
        <div class="chips" role="group" aria-label="章節">
          ${["全部", ...chapters].map((c) =>
            `<button type="button" class="chip-btn" data-chapter="${esc(c)}" aria-pressed="${state.chapter === c}">${esc(c)}</button>`).join("")}
        </div>
        <div class="toggles">
          <span class="progress">已標記會了 <b>${doneCount}</b> / ${db.exercises.length}</span>
          <label><input type="checkbox" data-toggle="showAnswers" ${state.showAnswers ? "checked" : ""}> 展開所有答案</label>
          <label><input type="checkbox" data-toggle="hideKnown" ${state.hideKnown ? "checked" : ""}> 隱藏已會的</label>
        </div>
      </div>`;
    let html = "", chapter = null, shown = 0;
    db.exercises.forEach((e, i) => {
      const key = `${db.id}:${e.id}`;
      if (state.chapter !== "全部" && e.chapter !== state.chapter) return;
      if (state.hideKnown && known[key]) return;
      if (!exerciseMatch(e, q)) return;
      if (e.chapter !== chapter) {
        chapter = e.chapter;
        html += `<h2 class="chapter-head">${esc(chapter)}<span>${db.exercises.filter((x) => x.chapter === chapter).length} 題</span></h2>`;
      }
      shown++;
      html += `
        <article class="card ${known[key] ? "known" : ""}">
          <div class="card-head">
            <span class="num">#${i + 1}</span>
            <h3>${esc(e.title)}</h3>
            ${e.write ? '<span class="tag write">寫入題</span>' : ""}
            ${e.ordered ? "" : '<span class="tag">順序不拘</span>'}
            <label class="known-box"><input type="checkbox" data-known="${esc(key)}" ${known[key] ? "checked" : ""}> 我會了</label>
          </div>
          <p class="prompt">${esc(e.prompt.trim())}</p>
          ${e.setup ? `<div class="step-label">題目已經先執行了</div>${code(e.setup, db.lang)}` : ""}
          ${e.hints.length ? `<details class="hint"><summary>提示（${e.hints.length}）</summary>
            <ol>${e.hints.map((h) => `<li>${esc(h)}</li>`).join("")}</ol></details>` : ""}
          <details class="ans" ${state.showAnswers ? "open" : ""}><summary>看答案</summary>
            ${code(e.answer, db.lang)}
            ${e.explanation ? `<div class="explain">${esc(e.explanation.trim())}</div>` : ""}
            ${blocks(e.result)}
            ${blocks(e.checks, "執行之後，用這些指令檢查資料")}
          </details>
        </article>`;
    });
    return filters + (shown ? html : '<p class="empty">沒有符合條件的題目。</p>');
  }

  // ---------- 陷阱題 ----------
  const trapMatch = (t, q) => matches(q, t.title, t.question, t.explanation, t.setup, ...t.options, ...t.scripts.map((s) => s.code));

  function renderTraps(db, q) {
    const list = db.traps.filter((t) => trapMatch(t, q));
    if (!list.length) return '<p class="empty">沒有符合條件的題目。</p>';
    return list.map((t) => `
      <article class="card" data-trap="${esc(t.id)}">
        <div class="card-head"><span class="num">#${db.traps.indexOf(t) + 1}</span><h3>${esc(t.title)}</h3></div>
        ${t.setup ? `<div class="step-label">每段指令執行前，都先執行</div>${code(t.setup, db.lang)}` : ""}
        <div class="pair ${t.scripts.length === 1 ? "single" : ""}">
          ${t.scripts.map((s) => `<div><div class="pair-label">${esc(s.label)}</div>${code(s.code, db.lang)}</div>`).join("")}
        </div>
        <p class="question">${esc(t.question)}</p>
        <div class="options">
          ${t.options.map((o, i) => `<button type="button" class="option" data-choice="${i}">${esc(o)}</button>`).join("")}
        </div>
        <div class="reveal" hidden></div>
      </article>`).join("");
  }

  function answerTrap(card, choice) {
    const db = dbOf(state.db);
    const t = db.traps.find((x) => x.id === card.dataset.trap);
    const buttons = [...card.querySelectorAll("[data-choice]")];
    buttons.forEach((b) => (b.disabled = true));
    buttons[t.answer].classList.add("correct");
    if (choice !== t.answer) buttons[choice].classList.add("wrong");
    const reveal = card.querySelector(".reveal");
    reveal.hidden = false;
    reveal.innerHTML = `
      <p class="verdict ${choice === t.answer ? "ok" : "bad"}">${choice === t.answer ? "✓ 猜對了！" : `✗ 答案是「${esc(t.options[t.answer])}」`}</p>
      <div class="explain">${esc(t.explanation.trim())}</div>
      ${t.results ? `<div class="pair ${t.results.length === 1 ? "single" : ""}">${t.results.map((r, i) =>
        `<div>${blocks(r, t.scripts[i].label + " 的執行結果")}</div>`).join("")}</div>` : ""}
      <button type="button" class="retry">重新作答</button>`;
  }

  // ---------- 實驗 ----------
  const stepMatch = (s, q) => matches(q, s.title, s.goal, s.question, s.takeaway, ...s.setup, ...s.queries.map((x) => x.code));

  function renderLab(db, q) {
    const list = db.steps.filter((s) => stepMatch(s, q));
    if (!list.length) return '<p class="empty">沒有符合條件的步驟。</p>';
    return `<p class="note">${esc(db.labNote)}</p>` + list.map((s) => `
      <article class="card">
        <div class="card-head"><span class="num">步驟 ${db.steps.indexOf(s) + 1}</span><h3>${esc(s.title)}</h3></div>
        <p class="goal">${esc(s.goal.trim())}</p>
        ${s.setup.length ? `<div class="step-label">這一步會用到的索引</div>${code(s.setup.join("\n"), db.lang)}` : ""}
        ${s.queries.map((x) => `<div class="step-label">${esc(x.label)}</div>${code(x.code, db.lang)}`).join("")}
        ${s.question ? `<p class="question">想一想：${esc(s.question)}</p>` : ""}
        <details ${state.showAnswers ? "open" : ""}><summary>看解說</summary><div class="explain">${esc(s.takeaway.trim())}</div></details>
      </article>`).join("");
  }

  // ---------- 事件 ----------
  bank.addEventListener("click", (e) => {
    const kind = e.target.closest("[data-kind]");
    if (kind) { go(state.db, kind.dataset.kind); return; }
    const chap = e.target.closest("[data-chapter]");
    if (chap) { state.chapter = chap.dataset.chapter; render(); return; }
    const opt = e.target.closest("[data-choice]");
    if (opt && !opt.disabled) { answerTrap(opt.closest("[data-trap]"), Number(opt.dataset.choice)); return; }
    const retry = e.target.closest(".retry");
    if (retry) {
      const card = retry.closest("[data-trap]");
      card.querySelectorAll("[data-choice]").forEach((b) => { b.disabled = false; b.className = "option"; });
      const reveal = card.querySelector(".reveal");
      reveal.hidden = true; reveal.innerHTML = "";
    }
  });
  bank.addEventListener("change", (e) => {
    const k = e.target.closest("[data-known]");
    if (k) {
      if (k.checked) known[k.dataset.known] = true; else delete known[k.dataset.known];
      store.set(KNOWN_KEY, known);
      k.closest(".card").classList.toggle("known", k.checked);
      const db = dbOf(state.db);
      const p = bank.querySelector(".progress b");
      if (p) p.textContent = db.exercises.filter((x) => known[`${db.id}:${x.id}`]).length;
      return;
    }
    const t = e.target.closest("[data-toggle]");
    if (t) { state[t.dataset.toggle] = t.checked; render(); }
  });

  let timer = null;
  input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(render, 150); });
  document.addEventListener("keydown", (e) => {
    if (e.key === "/" && document.activeElement !== input) { e.preventDefault(); input.focus(); }
  });

  // 網址：#資料庫/題型，例如 #cassandra/traps（方便分享或重新整理後回到同一頁）
  function go(db, kind, push = true) {
    const changedDb = db !== state.db;
    state.db = dbOf(db).id;
    state.kind = kind || "practice";
    if (changedDb) state.chapter = "全部";
    store.set(LAST_KEY, `${state.db}/${state.kind}`);
    if (push) { try { history.replaceState(null, "", `#${state.db}/${state.kind}`); } catch { /* 有些環境不允許改網址 */ } }
    render();
    window.scrollTo(0, 0);
  }
  tabsNav.addEventListener("click", (e) => {
    const a = e.target.closest("[data-tab]");
    if (!a) return;
    e.preventDefault();
    go(a.dataset.tab, state.kind);
  });
  const fromHash = () => {
    const [db, kind] = (location.hash.slice(1) || store.get(LAST_KEY, "")).split("/");
    // 舊網址 #practice / #traps / #lab 是 PostgreSQL 的題庫
    if (["practice", "traps", "lab"].includes(db)) return go("postgresql", db, false);
    go(db || data.dbs[0].id, kind, false);
  };
  window.addEventListener("hashchange", fromHash);
  fromHash();
})();
