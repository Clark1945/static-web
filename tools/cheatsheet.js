// CheatSheet：程式碼上色、目錄標示目前章節、搜尋（隱藏不相關的章節並標出關鍵字）
(() => {
  const doc = document.getElementById("doc");
  const toc = document.getElementById("toc");
  const sections = [...doc.querySelectorAll(".sec")];
  const partHeads = [...doc.querySelectorAll(".part-head")];
  enhanceCode(doc);

  // 目錄：標示目前讀到的章節
  const links = new Map([...toc.querySelectorAll("a")].map((a) => [a.getAttribute("href").slice(1), a]));
  const headings = [...doc.querySelectorAll("h1, h2, h3")].map((h) => (h.tagName === "H3" ? h : h.parentElement));
  const observer = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      toc.querySelectorAll("a.active").forEach((a) => a.classList.remove("active"));
      links.get(e.target.id)?.classList.add("active");
    }
  }, { rootMargin: "-80px 0px -70% 0px" });
  headings.forEach((h) => observer.observe(h));

  // 搜尋
  const original = new Map(sections.map((s) => [s, s.innerHTML]));
  const input = document.getElementById("q");
  const hits = document.getElementById("hits");
  let timer = null;

  function markText(root, q) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) {
      if (walker.currentNode.parentElement.closest(".copy")) continue;
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

  function search() {
    const q = input.value.trim().toLowerCase();
    let shown = 0;
    for (const s of sections) {
      s.innerHTML = original.get(s);
      const match = !q || s.textContent.toLowerCase().includes(q);
      s.hidden = !match;
      if (match) {
        shown++;
        if (q) markText(s, q);
      }
      const ids = [s.id, ...[...s.querySelectorAll("h3")].map((h) => h.id)];
      ids.forEach((id) => { const a = links.get(id); if (a) a.hidden = !match; });
    }
    // 大章節：底下有任何一節符合才顯示
    for (const p of partHeads) {
      const any = sections.some((s) => s.dataset.part === p.id && !s.hidden);
      p.hidden = !any;
      const a = links.get(p.id); if (a) a.hidden = !any;
    }
    enhanceCodeKeepColors();
    hits.hidden = !q;
    hits.textContent = shown ? `找到 ${shown} 個章節包含「${input.value.trim()}」` : `沒有章節包含「${input.value.trim()}」`;
    headings.forEach((h) => observer.observe(h));
  }

  // 還原 innerHTML 後程式碼已經是上色過的 HTML，只需要補回複製按鈕
  function enhanceCodeKeepColors() {
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

  // 第一次上色後才記下原始內容（含上色結果、不含按鈕）
  sections.forEach((s) => {
    const clone = s.cloneNode(true);
    clone.querySelectorAll(".copy").forEach((b) => b.remove());
    original.set(s, clone.innerHTML);
  });

  input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(search, 150); });
  document.addEventListener("keydown", (e) => {
    if (e.key === "/" && document.activeElement !== input) { e.preventDefault(); input.focus(); }
  });
})();
