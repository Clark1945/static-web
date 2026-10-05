// 題庫：練習題 / 陷阱題 / 索引實驗三個分頁，資料來自頁面裡嵌入的 JSON
(() => {
  const data = JSON.parse(document.getElementById("data").textContent);
  const bank = document.getElementById("bank");
  const input = document.getElementById("q");
  const KNOWN_KEY = "pg-bank-known";
  const known = store.get(KNOWN_KEY, {});
  const state = { tab: "practice", chapter: "全部", showAnswers: false, hideKnown: false };

  const TABS = [
    { id: "practice", label: "練習題", n: data.exercises.length },
    { id: "traps", label: "陷阱題", n: data.traps.length },
    { id: "lab", label: "索引實驗室", n: data.steps.length },
  ];
  document.getElementById("sub").textContent =
    `練習題 ${data.exercises.length} · 陷阱題 ${data.traps.length} · 索引實驗 ${data.steps.length} 步 · 更新於 ${data.built}`;

  const chapters = [...new Set(data.exercises.map((e) => e.chapter))];

  // ---------- 共用片段 ----------
  const code = (sql) => `<pre class="code"><code>${esc(String(sql).trim())}</code></pre>`;

  function resultTable(r) {
    if (!r) return "";
    if (r.rows.length === 0) return `<p class="result-head">執行結果：0 筆</p>`;
    const head = r.columns.map((c) => `<th>${esc(c)}</th>`).join("");
    const body = r.rows.map((row) => "<tr>" + row.map((v) =>
      v === null ? '<td class="nul">NULL</td>'
      : typeof v === "number" ? `<td class="num">${v.toLocaleString("zh-TW")}</td>`
      : `<td>${esc(v)}</td>`).join("") + "</tr>").join("");
    const more = r.total > r.rows.length ? `，以下只列前 ${r.rows.length} 筆` : "";
    return `<p class="result-head">執行結果：共 ${r.total.toLocaleString("zh-TW")} 筆${more}</p>
      <div class="table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
  }

  const matches = (q, ...texts) => !q || texts.some((t) => String(t ?? "").toLowerCase().includes(q));

  // ---------- 分頁 ----------
  function renderTabs() {
    document.getElementById("tabs").innerHTML = TABS.map((t) =>
      `<a href="#${t.id}" ${t.id === state.tab ? 'aria-current="page"' : ""}>${esc(t.label)}<span class="n">${t.n}</span></a>`).join("");
  }

  function render() {
    renderTabs();
    const q = input.value.trim().toLowerCase();
    const note = `<p class="note">這是離線複習版：可以看題目、提示、答案與正確的執行結果。
      要實際寫 SQL、自動批改，請啟動展示台後打開 <a href="http://localhost:8081/#/postgres">http://localhost:8081</a>。</p>`;
    if (state.tab === "practice") bank.innerHTML = note + renderPractice(q);
    else if (state.tab === "traps") bank.innerHTML = note + renderTraps(q);
    else bank.innerHTML = note + renderLab(q);
    enhanceCode(bank);
  }

  // ---------- 練習題 ----------
  function renderPractice(q) {
    const doneCount = data.exercises.filter((e) => known[e.id]).length;
    const filters = `
      <div class="filters">
        <div class="chips" role="group" aria-label="章節">
          ${["全部", ...chapters].map((c) =>
            `<button type="button" class="chip-btn" data-chapter="${esc(c)}" aria-pressed="${state.chapter === c}">${esc(c)}</button>`).join("")}
        </div>
        <div class="toggles">
          <span class="progress">已標記會了 <b>${doneCount}</b> / ${data.exercises.length}</span>
          <label><input type="checkbox" data-toggle="showAnswers" ${state.showAnswers ? "checked" : ""}> 展開所有答案</label>
          <label><input type="checkbox" data-toggle="hideKnown" ${state.hideKnown ? "checked" : ""}> 隱藏已會的</label>
        </div>
      </div>`;
    let html = "", chapter = null, shown = 0;
    data.exercises.forEach((e, i) => {
      if (state.chapter !== "全部" && e.chapter !== state.chapter) return;
      if (state.hideKnown && known[e.id]) return;
      if (!matches(q, e.title, e.prompt, e.answer, e.explanation, e.chapter, ...(e.hints ?? []))) return;
      if (e.chapter !== chapter) {
        chapter = e.chapter;
        const total = data.exercises.filter((x) => x.chapter === chapter).length;
        html += `<h2 class="chapter-head">${esc(chapter)}<span>${total} 題</span></h2>`;
      }
      shown++;
      const write = e.mode === "write";
      html += `
        <article class="card ${known[e.id] ? "known" : ""}" data-id="${esc(e.id)}">
          <div class="card-head">
            <span class="num">#${i + 1}</span>
            <h3>${esc(e.title)}</h3>
            ${write ? '<span class="tag write">寫入題</span>' : ""}
            <span class="tag">${e.ordered ? "要照指定順序" : "順序不拘"}</span>
            <label class="known-box"><input type="checkbox" data-known="${esc(e.id)}" ${known[e.id] ? "checked" : ""}> 我會了</label>
          </div>
          <p class="prompt">${esc(e.prompt.trim())}</p>
          ${e.hints?.length ? `<details class="hint"><summary>提示（${e.hints.length}）</summary>
            <ol>${e.hints.map((h) => `<li>${esc(h)}</li>`).join("")}</ol></details>` : ""}
          <details class="ans" ${state.showAnswers ? "open" : ""}><summary>看答案</summary>
            ${code(e.answer)}
            <div class="explain">${esc(e.explanation.trim())}</div>
            ${resultTable(e.result)}
          </details>
        </article>`;
    });
    return filters + (shown ? html : '<p class="empty">沒有符合條件的題目。</p>');
  }

  // ---------- 陷阱題 ----------
  function renderTraps(q) {
    const list = data.traps.filter((t) => matches(q, t.title, t.question, t.explanation, ...t.sqls.map((s) => s.sql)));
    if (!list.length) return '<p class="empty">沒有符合條件的題目。</p>';
    return list.map((t) => `
      <article class="card" data-trap="${esc(t.id)}">
        <div class="card-head"><span class="num">#${data.traps.indexOf(t) + 1}</span><h3>${esc(t.title)}</h3></div>
        <div class="pair">
          ${t.sqls.map((s) => `<div><div class="pair-label">${esc(s.label)}</div>${code(s.sql)}</div>`).join("")}
        </div>
        <p class="question">${esc(t.question)}</p>
        <div class="options">
          ${t.options.map((o, i) => `<button type="button" class="option" data-choice="${i}">${esc(o)}</button>`).join("")}
        </div>
        <div class="reveal" hidden></div>
      </article>`).join("");
  }

  function answerTrap(card, choice) {
    const t = data.traps.find((x) => x.id === card.dataset.trap);
    const buttons = [...card.querySelectorAll("[data-choice]")];
    buttons.forEach((b) => (b.disabled = true));
    buttons[t.answer].classList.add("correct");
    if (choice !== t.answer) buttons[choice].classList.add("wrong");
    const reveal = card.querySelector(".reveal");
    reveal.hidden = false;
    reveal.innerHTML = `
      <p class="verdict ${choice === t.answer ? "ok" : "bad"}">${choice === t.answer ? "✓ 猜對了！" : `✗ 答案是「${esc(t.options[t.answer])}」`}</p>
      <div class="explain">${esc(t.explanation.trim())}</div>
      ${t.results ? `<div class="pair">${t.results.map((r, i) =>
        `<div><div class="pair-label">${esc(t.sqls[i].label)}</div>${resultTable(r)}</div>`).join("")}</div>` : ""}
      <button type="button" class="retry">重新作答</button>`;
  }

  // ---------- 索引實驗 ----------
  function renderLab(q) {
    const list = data.steps.filter((s) => matches(q, s.title, s.goal, s.question, s.takeaway, ...s.ddl, ...s.sqls.map((x) => x.sql)));
    if (!list.length) return '<p class="empty">沒有符合條件的步驟。</p>';
    return `<p class="note">實驗對象是 perf schema 的兩張 200 萬筆大表：perf.orders_big（隨機順序寫入）、perf.order_events（依時間順序寫入）。
      每一步先不建索引跑一次 EXPLAIN ANALYZE，再建立索引重跑，比較執行計畫和時間。</p>` +
      list.map((s) => `
      <article class="card">
        <div class="card-head"><span class="num">步驟 ${data.steps.indexOf(s) + 1}</span><h3>${esc(s.title)}</h3></div>
        <p class="goal">${esc(s.goal.trim())}</p>
        ${s.ddl.length ? `<div class="step-label">這一步會用到的索引</div>${code(s.ddl.join("\n"))}` : ""}
        ${s.sqls.map((x) => `<div class="step-label">${esc(x.label)}</div>${code(x.sql)}`).join("")}
        ${s.question ? `<p class="question">想一想：${esc(s.question)}</p>` : ""}
        <details ${state.showAnswers ? "open" : ""}><summary>看解說</summary><div class="explain">${esc(s.takeaway.trim())}</div></details>
      </article>`).join("");
  }

  // ---------- 事件 ----------
  bank.addEventListener("click", (e) => {
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
      known[k.dataset.known] = k.checked;
      if (!k.checked) delete known[k.dataset.known];
      store.set(KNOWN_KEY, known);
      k.closest(".card").classList.toggle("known", k.checked);
      const p = bank.querySelector(".progress b");
      if (p) p.textContent = data.exercises.filter((x) => known[x.id]).length;
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

  // 分頁：點擊直接切換（網址的 #practice / #traps / #lab 只是方便分享或重新整理後回到同一頁）
  function openTab(id) {
    state.tab = TABS.some((t) => t.id === id) ? id : "practice";
    render();
    window.scrollTo(0, 0);
  }
  document.getElementById("tabs").addEventListener("click", (e) => {
    const a = e.target.closest("a[href^='#']");
    if (!a) return;
    e.preventDefault();
    const id = a.getAttribute("href").slice(1);
    try { history.replaceState(null, "", "#" + id); } catch { /* 有些環境不允許改網址 */ }
    openTab(id);
  });
  window.addEventListener("hashchange", () => openTab(location.hash.slice(1)));
  openTab(location.hash.slice(1));
})();
