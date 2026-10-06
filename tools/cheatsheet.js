// CheatSheet：上方切換資料庫分頁、左側目錄只列目前資料庫的章節、搜尋涵蓋所有資料庫
(() => {
  const doc = document.getElementById("doc");
  const toc = document.getElementById("toc");
  const tabsNav = document.getElementById("dbtabs");
  const input = document.getElementById("q");
  const hits = document.getElementById("hits");
  const sections = [...doc.querySelectorAll(".sec")];
  const partHeads = [...doc.querySelectorAll(".part-head")];
  const parts = partHeads.map((p) => p.id);
  const tocLinks = [...toc.querySelectorAll("a")];
  const links = new Map(tocLinks.map((a) => [a.getAttribute("href").slice(1), a]));
  const TAB_KEY = "cheatsheet-tab";
  enhanceCode(doc);

  // 每一節原本的內容（已上色、不含複製按鈕），搜尋時從這裡還原再標出關鍵字
  const original = new Map(sections.map((s) => {
    const clone = s.cloneNode(true);
    clone.querySelectorAll(".copy").forEach((b) => b.remove());
    return [s, clone.innerHTML];
  }));

  let active = parts[0];
  let query = "";
  const matched = new Set(sections);

  // ---------- 分頁 ----------
  function partOf(id) {
    if (parts.includes(id)) return id;
    const el = document.getElementById(id);
    return el?.closest("[data-part]")?.dataset.part ?? null;
  }

  function render() {
    for (const p of partHeads) p.hidden = p.id !== active;
    for (const s of sections) s.hidden = s.dataset.part !== active || !matched.has(s);
    for (const a of tocLinks) {
      const sec = document.getElementById(a.getAttribute("href").slice(1))?.closest(".sec");
      a.hidden = a.dataset.part !== active || (sec && !matched.has(sec));
    }
    tabsNav.querySelectorAll("[data-tab]").forEach((t) => {
      const on = t.dataset.tab === active;
      t.setAttribute("aria-current", on ? "page" : "false");
      const n = sections.filter((s) => s.dataset.part === t.dataset.tab && matched.has(s)).length;
      t.querySelector("[data-count]").textContent = query ? n : "";
      t.classList.toggle("no-hit", Boolean(query) && n === 0);
    });
    document.title = `${partHeads.find((p) => p.id === active)?.querySelector("h1").textContent ?? ""} · CheatSheet`;
  }

  function openPart(id, scrollTo) {
    active = parts.includes(id) ? id : parts[0];
    try { localStorage.setItem(TAB_KEY, active); } catch { /* 無法儲存就算了 */ }
    render();
    if (scrollTo) document.getElementById(scrollTo)?.scrollIntoView();
    else window.scrollTo(0, 0);
  }

  tabsNav.addEventListener("click", (e) => {
    const a = e.target.closest("[data-tab]");
    if (!a) return;
    e.preventDefault();
    try { history.replaceState(null, "", "#" + a.dataset.tab); } catch { /* 有些環境不允許改網址 */ }
    openPart(a.dataset.tab);
  });

  // ---------- 目錄：標示目前讀到的章節 ----------
  const observer = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      tocLinks.forEach((a) => a.classList.remove("active"));
      links.get(e.target.id)?.classList.add("active");
    }
  }, { rootMargin: "-140px 0px -65% 0px" });
  const observeAll = () => doc.querySelectorAll(".sec, h3[id]").forEach((h) => observer.observe(h));
  observeAll();

  // ---------- 搜尋（所有資料庫） ----------
  function markText(root, q) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) {
      if (walker.currentNode.nodeValue.toLowerCase().includes(q)) nodes.push(walker.currentNode);
    }
    for (const node of nodes) {
      const text = node.nodeValue, lower = text.toLowerCase();
      const frag = document.createDocumentFragment();
      let i = 0, j;
      while ((j = lower.indexOf(q, i)) !== -1) {
        frag.append(text.slice(i, j));
        const m = document.createElement("mark");
        m.textContent = text.slice(j, j + q.length);
        frag.append(m);
        i = j + q.length;
      }
      frag.append(text.slice(i));
      node.replaceWith(frag);
    }
  }

  function addCopyButtons() {
    doc.querySelectorAll("pre.code").forEach((pre) => {
      if (pre.querySelector(".copy")) return;
      const raw = pre.querySelector("code").textContent;
      const btn = document.createElement("button");
      btn.type = "button"; btn.className = "copy"; btn.textContent = "複製";
      btn.addEventListener("click", async () => {
        try { await navigator.clipboard.writeText(raw); btn.textContent = "已複製"; }
        catch { btn.textContent = "請手動選取複製"; }
        setTimeout(() => (btn.textContent = "複製"), 1600);
      });
      pre.appendChild(btn);
    });
  }

  function search() {
    query = input.value.trim().toLowerCase();
    matched.clear();
    for (const s of sections) {
      s.innerHTML = original.get(s);
      if (!query || s.textContent.toLowerCase().includes(query)) {
        matched.add(s);
        if (query) markText(s, query);
      }
    }
    addCopyButtons();
    // 目前的資料庫沒有結果、別的資料庫有，就自動切過去
    const inActive = sections.some((s) => s.dataset.part === active && matched.has(s));
    if (query && !inActive) {
      const other = parts.find((p) => sections.some((s) => s.dataset.part === p && matched.has(s)));
      if (other) active = other;
    }
    render();
    const total = matched.size;
    hits.hidden = !query;
    hits.textContent = total
      ? `「${input.value.trim()}」在所有資料庫共找到 ${total} 節，分頁上的數字是各資料庫的節數。`
      : `沒有任何章節包含「${input.value.trim()}」。`;
    observeAll();
  }

  let timer = null;
  input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(search, 150); });
  document.addEventListener("keydown", (e) => {
    if (e.key === "/" && document.activeElement !== input) { e.preventDefault(); input.focus(); }
  });

  // ---------- 初始：網址的 #資料庫 或 #章節，否則用上次看的分頁 ----------
  const hash = location.hash.slice(1);
  let saved = null;
  try { saved = localStorage.getItem(TAB_KEY); } catch { /* 無法讀取就用預設 */ }
  const fromHash = hash ? partOf(hash) : null;
  openPart(fromHash ?? saved ?? parts[0], fromHash && hash !== fromHash ? hash : null);
  window.addEventListener("hashchange", () => {
    const id = location.hash.slice(1);
    const p = partOf(id);
    if (p) openPart(p, id !== p ? id : null);
  });
})();
