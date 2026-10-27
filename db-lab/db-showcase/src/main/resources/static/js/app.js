// 外框：左側導覽 + 以網址 #/資料庫id 切換頁面
import { DATABASES, findDatabase } from "./databases.js";
import { esc, badgeStyle, pageHead } from "./lib.js";

const nav = document.getElementById("nav");
const content = document.getElementById("content");
let unmount = null;

function renderNav(activeId) {
  const link = (db) => `
    <a class="nav-link ${db.status === "planned" ? "planned" : ""} ${db.id === activeId ? "active" : ""}"
       href="#/${db.id}" ${db.id === activeId ? 'aria-current="page"' : ""}>
      <span class="mono-badge" style="${badgeStyle(db)}">${esc(db.mono)}</span>
      <span class="name">${esc(db.name)}</span>
      <span class="kind">${esc(db.kind)}</span>
    </a>`;
  nav.innerHTML = `
    <div class="brand"><b>資料庫展示台</b><span>後端查詢 × ${DATABASES.length} 種資料庫</span></div>
    <a class="nav-link ${activeId ? "" : "active"}" href="#/">
      <span class="mono-badge" style="--badge-bg:var(--ink);--badge-ink:var(--bg)">ALL</span>
      <span class="name">總覽</span>
    </a>
    <div class="nav-label">已串接</div>
    ${DATABASES.filter((d) => d.status === "ready").map(link).join("")}
    <div class="nav-label">規劃中</div>
    ${DATABASES.filter((d) => d.status !== "ready").map(link).join("")}`;
}

function overviewPage(el) {
  const ready = DATABASES.filter((d) => d.status === "ready").length;
  el.innerHTML = `
    <div class="page">
      <div>
        <h1>資料庫展示台</h1>
        <p class="muted" style="max-width:70ch;margin:6px 0 0">
          用 Spring Boot 串接不同類型的資料庫，每一頁都能看到資料結構、實際執行查詢，並比較耗時。
          目前已串接 ${ready} / ${DATABASES.length} 種。
        </p>
      </div>
      <div class="db-grid">
        ${DATABASES.map((db) => `
          <a class="db-card" href="#/${db.id}" style="${badgeStyle(db)}">
            <span class="mono-badge">${esc(db.mono)}</span>
            <div class="top"><b>${esc(db.name)}</b>
              <span class="pill ${db.status}">${db.status === "ready" ? "已串接" : "規劃中"}</span></div>
            <span class="kind">${esc(db.kind)}資料庫</span>
            <p>${esc(db.tagline)}</p>
          </a>`).join("")}
      </div>
    </div>`;
}

function placeholderPage(el, db) {
  el.innerHTML = `
    <div class="page">
      ${pageHead(db)}
      <div class="placeholder">
        <b>尚未串接</b>
        <span>這一頁之後會放 ${esc(db.name)} 的資料結構與查詢測試。</span>
      </div>
    </div>`;
}

async function route() {
  const id = location.hash.replace(/^#\/?/, "");
  const db = findDatabase(id);
  if (unmount) { unmount(); unmount = null; }

  renderNav(db?.id);
  content.dataset.db = db?.id ?? "";
  document.title = db ? `${db.name} · 資料庫展示台` : "資料庫展示台";
  window.scrollTo(0, 0);

  if (!db) return overviewPage(content);
  if (db.status !== "ready") return placeholderPage(content, db);

  const mod = await db.page();
  if (findDatabase(location.hash.replace(/^#\/?/, ""))?.id !== db.id) return;   // 載入期間又換頁了
  unmount = mod.mount(content, db) ?? null;
}

window.addEventListener("hashchange", route);
route();
