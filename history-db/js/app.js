/* 全球歷史資料庫：時間軸渲染（純 JS，無相依套件） */
(function () {
  "use strict";

  const WIKI_SEARCH =
    "https://zh.wikipedia.org/w/index.php?title=Special:Search&go=Go&search=";
  const PRECISION_LABEL = { approx: "約略年份", disputed: "說法不一" };

  const countries = (window.HistoryDB && window.HistoryDB.countries) || [];

  const $ = (sel) => document.querySelector(sel);
  const scroller = $("#scroller");
  const grid = $("#grid");
  const stats = $("#stats");
  const search = $("#search");
  const majorBtn = $("#majorBtn");
  const chipBox = $("#countryChips");
  const bucketNav = $("#bucketNav");

  const state = {
    query: "",
    majorOnly: false,
    active: new Set(countries.map((c) => c.id)),
  };

  let dividers = [];
  let navButtons = [];

  /* ───────── 工具 ───────── */

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === "class") el.className = v;
        else el.setAttribute(k, v === true ? "" : v);
      }
    }
    for (const c of children.flat()) {
      if (c == null || c === false) continue;
      el.append(c.nodeType ? c : document.createTextNode(String(c)));
    }
    return el;
  }

  // 西元前用負數：-221 → 「前 221」（沒有 0 年）
  function fmtYear(y) {
    return y < 0 ? "前 " + -y : String(y);
  }

  // 每 100 年一組：1786 → 1700
  function bucketOf(y) {
    return Math.floor(y / 100) * 100;
  }

  function scrollBehavior() {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches
      ? "auto"
      : "smooth";
  }

  function headHeight() {
    const el = grid.querySelector(".corner");
    return el ? el.offsetHeight : 0;
  }

  /* ───────── 資料篩選 ───────── */

  function visibleEvents(country) {
    const q = state.query.trim().toLowerCase();
    return country.events
      .filter((e) => {
        if (state.majorOnly && !e.major) return false;
        if (q && !(e.title + e.summary).toLowerCase().includes(q)) return false;
        return true;
      })
      .sort((a, b) => a.year - b.year); // 穩定排序，同年維持資料檔順序
  }

  /* ───────── 元件 ───────── */

  function eventRangeText(e) {
    if (e.yearLabel) return e.yearLabel;
    if (e.yearEnd && e.yearEnd !== e.year) {
      return fmtYear(e.year) + "～" + fmtYear(e.yearEnd);
    }
    return null; // 與左側年份相同，不重複顯示
  }

  function eventCard(e) {
    const range = eventRangeText(e);
    const prec = PRECISION_LABEL[e.precision];

    const links = [];
    if (e.wiki) {
      links.push(
        h(
          "a",
          {
            href: WIKI_SEARCH + encodeURIComponent(e.wiki),
            target: "_blank",
            rel: "noopener noreferrer",
          },
          "維基百科"
        )
      );
    }
    for (const l of e.links || []) {
      links.push(
        h(
          "a",
          { href: l.url, target: "_blank", rel: "noopener noreferrer" },
          l.label
        )
      );
    }

    return h(
      "article",
      { class: "event" + (e.major ? " major" : ""), id: e.id },
      range || prec
        ? h(
            "div",
            { class: "ev-meta" },
            range && h("span", { class: "ev-range" }, range),
            prec && h("span", { class: "chip" }, prec)
          )
        : null,
      h("h3", {}, h("a", { class: "anchor", href: "#" + e.id }, e.title)),
      h("p", {}, e.summary),
      links.length ? h("div", { class: "ev-links" }, links) : null
    );
  }

  function eraBanner(country, eraId) {
    const era = (country.eras || []).find((x) => x.id === eraId);
    if (!era) return null;
    const end = era.end == null ? "今" : fmtYear(era.end);
    return h(
      "div",
      { class: "era" },
      h("strong", {}, era.name),
      h("span", {}, fmtYear(era.start) + "～" + end)
    );
  }

  /* ───────── 渲染 ───────── */

  function render(resetScroll) {
    const shown = countries.filter((c) => state.active.has(c.id));
    grid.replaceChildren();
    bucketNav.replaceChildren();
    dividers = [];
    navButtons = [];

    if (!countries.length) {
      grid.append(h("div", { class: "empty" }, "尚未載入任何國家的資料。"));
      stats.textContent = "";
      return;
    }
    if (!shown.length) {
      grid.style.setProperty("--cols", "1");
      grid.append(h("div", { class: "empty" }, "請至少選擇一個國家。"));
      stats.textContent = "";
      return;
    }

    grid.style.setProperty("--cols", String(shown.length));

    // 依年份整理：year → (countryId → events[])
    const perCountry = new Map();
    const years = new Set();
    let total = 0;
    for (const c of shown) {
      const byYear = new Map();
      for (const e of visibleEvents(c)) {
        total++;
        years.add(e.year);
        if (!byYear.has(e.year)) byYear.set(e.year, []);
        byYear.get(e.year).push(e);
      }
      perCountry.set(c.id, byYear);
    }

    // 表頭：X 軸 = 國家
    grid.append(h("div", { class: "corner" }, "年份 ↓"));
    for (const c of shown) {
      grid.append(
        h(
          "div",
          { class: "col-head", style: "--c:" + c.color },
          h("strong", {}, c.name),
          c.nameEn ? h("small", {}, c.nameEn) : null
        )
      );
    }

    if (!total) {
      grid.append(h("div", { class: "empty" }, "找不到符合的事件，換個關鍵字試試。"));
      stats.textContent = "0 筆事件";
      return;
    }

    // 內容：Y 軸 = 年份（由上到下，由早到晚）
    const sortedYears = Array.from(years).sort((a, b) => a - b);
    const lastEra = {};
    const buckets = [];
    let lastBucket = null;

    for (const year of sortedYears) {
      const b = bucketOf(year);
      if (b !== lastBucket) {
        lastBucket = b;
        buckets.push(b);
        grid.append(
          h(
            "div",
            {
              class: "bucket" + (buckets.length === 1 ? " first" : ""),
              id: "bucket-" + b,
              "data-bucket": b,
            },
            h("span", {}, fmtYear(b) + "～" + fmtYear(b + 99))
          )
        );
      }

      const rowHasMajor = shown.some((c) =>
        (perCountry.get(c.id).get(year) || []).some((e) => e.major)
      );
      grid.append(
        h("div", { class: "year" + (rowHasMajor ? " major" : "") }, fmtYear(year))
      );

      for (const c of shown) {
        const cell = h("div", { class: "cell", style: "--c:" + c.color });
        for (const e of perCountry.get(c.id).get(year) || []) {
          if (e.era && e.era !== lastEra[c.id]) {
            lastEra[c.id] = e.era;
            cell.append(eraBanner(c, e.era));
          }
          cell.append(eventCard(e));
        }
        grid.append(cell);
      }
    }

    // 年代跳轉
    dividers = Array.from(grid.querySelectorAll(".bucket"));
    navButtons = buckets.map((b) =>
      h(
        "button",
        { type: "button", class: "bucket-btn", "data-bucket": b },
        fmtYear(b) + " 年代"
      )
    );
    bucketNav.append(...navButtons);

    stats.textContent =
      "共 " + total + " 筆事件" + (shown.length > 1 ? "，" + shown.length + " 個國家" : "");

    if (resetScroll) scroller.scrollTop = 0;
    updateActiveBucket();
  }

  /* ───────── 捲動與跳轉 ───────── */

  function updateActiveBucket() {
    const y = scroller.scrollTop + headHeight() + 8;
    let active = null;
    for (const d of dividers) {
      if (d.offsetTop <= y) active = d.dataset.bucket;
    }
    for (const btn of navButtons) {
      const on = btn.dataset.bucket === active;
      btn.classList.toggle("active", on);
      if (on) {
        const left = btn.offsetLeft - bucketNav.offsetLeft;
        if (left < bucketNav.scrollLeft || left + btn.offsetWidth > bucketNav.scrollLeft + bucketNav.clientWidth) {
          bucketNav.scrollLeft = left - 12;
        }
      }
    }
  }

  let ticking = false;
  scroller.addEventListener(
    "scroll",
    () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        ticking = false;
        updateActiveBucket();
      });
    },
    { passive: true }
  );

  bucketNav.addEventListener("click", (ev) => {
    const btn = ev.target.closest(".bucket-btn");
    if (!btn) return;
    const target = document.getElementById("bucket-" + btn.dataset.bucket);
    if (!target) return;
    scroller.scrollTo({
      top: Math.max(0, target.offsetTop - headHeight()),
      behavior: scrollBehavior(),
    });
  });

  function focusEvent(id, smooth) {
    const target = document.getElementById(id);
    if (!target || !target.classList.contains("event")) return false;
    const offset =
      target.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
    scroller.scrollTo({
      top: Math.max(0, scroller.scrollTop + offset - headHeight() - 16),
      behavior: smooth ? scrollBehavior() : "auto",
    });
    target.classList.remove("flash");
    void target.offsetWidth; // 重新觸發動畫
    target.classList.add("flash");
    return true;
  }

  // 點事件標題 → 更新網址 hash，方便分享單一事件
  grid.addEventListener("click", (ev) => {
    const a = ev.target.closest("a.anchor");
    if (!a) return;
    ev.preventDefault();
    const id = a.getAttribute("href").slice(1);
    history.replaceState(null, "", "#" + id);
    focusEvent(id, true);
  });

  window.addEventListener("hashchange", () => {
    focusEvent(decodeURIComponent(location.hash.slice(1)), true);
  });

  /* ───────── 控制列 ───────── */

  function buildChips() {
    if (countries.length < 2) return; // 只有一個國家時不需要篩選
    for (const c of countries) {
      const chip = h(
        "button",
        {
          type: "button",
          class: "pill",
          "aria-pressed": "true",
          style: "--c:" + c.color,
        },
        h("span", { class: "swatch" }),
        c.name
      );
      chip.addEventListener("click", () => {
        if (state.active.has(c.id)) state.active.delete(c.id);
        else state.active.add(c.id);
        chip.setAttribute("aria-pressed", String(state.active.has(c.id)));
        render(true);
      });
      chipBox.append(chip);
    }
  }

  majorBtn.addEventListener("click", () => {
    state.majorOnly = !state.majorOnly;
    majorBtn.setAttribute("aria-pressed", String(state.majorOnly));
    render(true);
  });

  let searchTimer;
  search.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      state.query = search.value;
      render(true);
    }, 120);
  });

  /* ───────── 啟動 ───────── */

  buildChips();
  render(false);
  if (location.hash.length > 1) {
    focusEvent(decodeURIComponent(location.hash.slice(1)), false);
  }
})();
