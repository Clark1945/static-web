// Cassandra 結果的顯示：資料列表格（集合、UDT 以 CQL 的寫法呈現）、警告、查詢追蹤
import { esc, fmt } from "../../lib.js";

/** 一個值：字串加單引號、list / set 用 [ ]、map / UDT 用 { }。 */
export function formatCell(v) {
  if (v === null || v === undefined) return '<span class="c-null">null</span>';
  if (typeof v === "number") return `<span class="c-num">${v}</span>`;
  if (typeof v === "boolean") return `<span class="c-bool">${v}</span>`;
  if (typeof v === "string") return `<span class="c-str">${esc(v)}</span>`;
  if (Array.isArray(v)) return `[${v.map(inner).join(", ")}]`;
  return `{${Object.entries(v).map(([k, x]) => `<span class="c-key">${esc(k)}</span>: ${inner(x)}`).join(", ")}}`;
}
const inner = (v) => (typeof v === "string" ? `<span class="c-str">'${esc(v)}'</span>` : formatCell(v));

/** 一列「欄位 → 值」的物件，或這種物件的清單（批改時的差異）。 */
export function formatRowObjects(v) {
  if (v === null || v === undefined) return '<span class="c-null">（沒有資料）</span>';
  const list = Array.isArray(v) ? v : [v];
  if (list.length === 0) return '<span class="c-null">（0 列）</span>';
  const cols = Object.keys(list[0]);
  return table(cols, list.map((r) => cols.map((c) => r[c])));
}

function table(columns, rows) {
  return `<div class="result cql-result"><table><thead><tr>${columns.map((c) => `<th>${esc(c)}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((v) => `<td class="${typeof v === "number" ? "num" : ""}">${formatCell(v)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
}

/** 查詢追蹤的重點：讀取方式、讀了幾列、墓碑、伺服器耗時，以及事件清單。 */
export function traceView(t, { open = false } = {}) {
  if (!t) return "";
  const way = t.indexUsed ? ["SAI 索引", "good"] : t.rangeScan ? ["範圍掃描（沒有分區鍵）", "bad"]
    : t.partitions > 1 ? [`讀 ${t.partitions} 個分區`, "warn"] : t.partitions === 1 ? ["單一分區", "good"] : ["寫入", ""];
  return `
    <div class="trace">
      <div class="stats trace-stats">
        <div class="stat stat-${way[1]}"><b>${esc(way[0])}</b><span>讀取方式</span></div>
        <div class="stat ${t.liveRows > 1000 ? "stat-bad" : ""}"><b>${fmt(t.liveRows)}</b><span>讀取的資料列（live rows）</span></div>
        <div class="stat ${t.tombstones > 1000 ? "stat-bad" : ""}"><b>${fmt(t.tombstones)}</b><span>跳過的墓碑（tombstone cells）</span></div>
        <div class="stat"><b>${(t.durationMicros / 1000).toFixed(1)} ms</b><span>伺服器耗時</span></div>
      </div>
      ${t.indexNote ? `<p class="muted trace-note">${esc(t.indexNote)}</p>` : ""}
      <details ${open ? "open" : ""}><summary>查詢追蹤：${fmt(t.eventCount)} 個事件（伺服器內部做了哪些事）</summary>
        <div class="result trace-events"><table><thead><tr><th class="num">μs</th><th>activity</th></tr></thead><tbody>
          ${t.events.map((e) => `<tr><td class="num">${fmt(e.elapsedMicros)}</td><td>${esc(e.activity)}</td></tr>`).join("")}
          ${t.eventCount > t.events.length ? `<tr><td></td><td class="muted">…還有 ${fmt(t.eventCount - t.events.length)} 個事件沒有顯示</td></tr>` : ""}
        </tbody></table></div>
      </details>
    </div>`;
}

/** 一句 CQL 的結果。 */
export function formatResult(r, opts = {}) {
  const warn = (r.warnings ?? []).map((w) => `<div class="cql-warn">⚠ 伺服器警告：${esc(w)}</div>`).join("");
  let body;
  if (r.kind === "error") body = `<div class="error">${esc(r.message)}</div>`;
  else if (r.kind === "info") body = `<div class="cql-info">${esc(r.message)}</div>`;
  else if (r.kind === "ok") body = `<div class="cql-info">完成（沒有回傳資料）</div>`;
  else {
    const applied = r.columns[0] === "[applied]" && r.rows.length === 1;
    body = (applied ? `<div class="cql-applied ${r.rows[0][0] ? "yes" : "no"}">[applied] = ${r.rows[0][0]}${r.rows[0][0] ? "：條件成立，已寫入" : "：條件不成立，沒有寫入（後面附上目前的值）"}</div>` : "")
      + (r.rows.length === 0 ? '<div class="cql-info">0 列</div>' : table(r.columns, r.rows))
      + `<div class="cql-meta">${fmt(r.total)} 列${r.truncated ? `（只顯示前 ${fmt(r.rows.length)} 列）` : ""}</div>`;
  }
  return body + warn + traceView(r.trace, opts);
}

/** 一串 CQL 的執行紀錄：keyspace> 指令、結果、耗時、一致性等級。 */
export function transcript(results, prompt = "shop") {
  return `<div class="cql-transcript">${results.map((r) => `
    <div class="cql-step">
      <div class="cql-cmd"><span class="r-prompt">${esc(prompt)}&gt;</span> <code>${esc(r.statement)}</code>
        <span class="r-time">${r.consistency && r.consistency !== "LOCAL_ONE" ? esc(r.consistency) + " · " : ""}${r.millis.toFixed(1)} ms</span></div>
      ${formatResult(r)}
    </div>`).join("")}</div>`;
}
