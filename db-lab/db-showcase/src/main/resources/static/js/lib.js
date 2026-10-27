// 各資料庫頁面共用的小工具

export const fmt = (n) => Number(n).toLocaleString("zh-TW");

export const esc = (s) =>
  String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

/** 呼叫後端 API；失敗時丟出帶有後端錯誤訊息的 Error。 */
export async function api(url, options) {
  const res = await fetch(url, options);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || body.message || `HTTP ${res.status}`);
  return body;
}

export const postJson = (url, data) =>
  api(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });

export const badgeStyle = (db) => `--badge-bg:${db.color};--badge-ink:${db.ink}`;

/** 每個資料庫頁面頂部的標題區。 */
export function pageHead(db) {
  return `
    <div class="page-head">
      <span class="mono-badge lg" style="${badgeStyle(db)}">${esc(db.mono)}</span>
      <div>
        <h1>${esc(db.name)} <span class="pill ${db.status}" style="vertical-align:middle">${db.status === "ready" ? "已串接" : "規劃中"}</span></h1>
        <p>${esc(db.kind)}資料庫 · ${esc(db.tagline)}</p>
      </div>
    </div>`;
}

/**
 * 執行一次查詢並更新狀態列：先顯示「查詢中…」與即時秒數，完成後顯示筆數與耗時。
 * request()   回傳後端的回應。
 * toResult()  從回應取出 { columns, rows, rowCount, truncated, elapsedMs }（預設回應本身就是）。
 * render()    把回應畫成 HTML（預設畫成表格）。
 * isStale()   回傳 true 時代表使用者已經換頁或重新執行，就不再更新畫面。
 * 回傳後端的回應；失敗或過期時回傳 null。
 */
export async function runQuery({
  request, statusEl, outputEl, isStale = () => false,
  toResult = (r) => r, render = (r) => renderTable(toResult(r)),
}) {
  const started = performance.now();
  statusEl.innerHTML =
    '<span class="running"><span class="spinner"></span>查詢中… <span class="mono" data-ticker>0.00 秒</span></span>';
  outputEl.innerHTML = "";
  const ticker = setInterval(() => {
    const el = statusEl.querySelector("[data-ticker]");
    if (el) el.textContent = ((performance.now() - started) / 1000).toFixed(2) + " 秒";
  }, 50);

  try {
    const response = await request();
    if (isStale()) return null;
    const result = toResult(response);
    const totalMs = performance.now() - started;
    statusEl.innerHTML = `
      <span class="chip">回傳 <b>${fmt(result.rowCount)}</b> 筆${result.truncated ? "（只顯示前 " + fmt(result.rowCount) + " 筆）" : ""}</span>
      <span class="chip">資料庫耗時 <b>${result.elapsedMs.toFixed(1)} ms</b></span>
      <span class="chip">總耗時 <b>${totalMs.toFixed(1)} ms</b> <span class="muted">（含網路與 JSON 轉換）</span></span>`;
    outputEl.innerHTML = render(response);
    return response;
  } catch (e) {
    if (isStale()) return null;
    statusEl.innerHTML = `<span class="chip">失敗，耗時 <b>${(performance.now() - started).toFixed(1)} ms</b></span>`;
    outputEl.innerHTML = `<div class="error">${esc(e.message)}</div>`;
    return null;
  } finally {
    clearInterval(ticker);
  }
}

/** localStorage 讀寫；無法使用時（無痕視窗等）安靜地退回預設值。 */
export const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 無法儲存就算了 */ }
  },
};

/** 在 textarea 按 Ctrl+Enter（Mac 是 ⌘+Enter）時執行 fn。 */
export function onCtrlEnter(textarea, fn) {
  textarea.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); fn(); }
  });
}

export function renderTable({ columns, rows }) {
  if (rows.length === 0) return '<div class="empty">查詢成功，但沒有符合條件的資料（0 筆）</div>';
  const head = columns.map((c) => `<th>${esc(c)}</th>`).join("");
  const body = rows
    .map((r) => "<tr>" + r.map((v) =>
      v === null ? '<td class="nul">NULL</td>'
      : typeof v === "number" ? `<td class="num">${fmt(v)}</td>`
      : `<td>${esc(v)}</td>`).join("") + "</tr>")
    .join("");
  return `<div class="result"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

/** 寫入沙盒的結果：每一句一張卡片（查詢 / RETURNING 顯示表格，其他顯示影響筆數）。 */
export function renderSandbox({ statements }) {
  const cards = statements.map((s) => `
    <div class="stmt">
      <div class="stmt-head"><b>第 ${s.index} 句</b>
        <span class="muted">${s.kind === "rows"
          ? `回傳 ${fmt(s.result.rowCount)} 筆${s.result.truncated ? "（只顯示前 " + fmt(s.result.rowCount) + " 筆）" : ""}`
          : `影響 ${fmt(s.updateCount)} 筆`}</span></div>
      ${s.kind === "rows" ? renderTable(s.result) : ""}
    </div>`).join("");
  return `<div class="rollback-note">已 ROLLBACK：以上變更只存在於這次的交易裡，資料庫沒有被修改。</div>${cards}`;
}

/** 把沙盒結果轉成狀態列需要的格式（筆數用最後一句的結果）。 */
export function sandboxSummary(r) {
  const last = r.statements[r.statements.length - 1];
  const rowCount = !last ? 0 : last.kind === "rows" ? last.result.rowCount : last.updateCount;
  return { rowCount, truncated: false, elapsedMs: r.elapsedMs };
}
