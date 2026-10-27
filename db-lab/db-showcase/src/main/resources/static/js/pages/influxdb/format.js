// InfluxDB 結果的顯示：每個區塊一張表（InfluxQL 是一個 series，Flux 是一張結果表），錯誤與訊息另外顯示。
// InfluxQL 的 time 是 epoch 秒，Flux 的 _time 是 RFC3339（UTC），都轉成台灣時間顯示。
import { esc, fmt } from "../../lib.js";

const TIME_COLS = new Set(["time", "_time", "_start", "_stop"]);
const pad = (n) => String(n).padStart(2, "0");

/** 轉成台灣時間（UTC+8）的「YYYY-MM-DD HH:mm:ss」。 */
export function taipei(v) {
  const d = typeof v === "number" ? new Date(v * 1000) : new Date(v);
  if (Number.isNaN(d.getTime())) return String(v);
  const t = new Date(d.getTime() + 8 * 3600 * 1000);
  return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())} ${pad(t.getUTCHours())}:${pad(t.getUTCMinutes())}:${pad(t.getUTCSeconds())}`;
}

function cell(col, v) {
  if (v === null || v === undefined) return '<td class="nul">null</td>';
  if (TIME_COLS.has(col) && (typeof v === "number" || /^\d{4}-\d\d-\d\dT/.test(v))) {
    return `<td class="mono">${esc(typeof v === "number" && v === 0 ? "1970-01-01（沒有時間）" : taipei(v))}</td>`;
  }
  if (typeof v === "number") return `<td class="num">${Number.isInteger(v) ? fmt(v) : fmt(Math.round(v * 10000) / 10000)}</td>`;
  return `<td>${esc(String(v))}</td>`;
}

export function block(b) {
  if (b.kind === "error") return `<div class="error">${esc(b.message)}</div>`;
  if (b.kind === "info") return `<div class="ifx-info">${esc(b.message)}</div>`;
  const more = b.total > b.rows.length ? `<span class="muted">（共 ${fmt(b.total)} 列，只顯示前 ${fmt(b.rows.length)} 列）</span>` : `<span class="muted">${fmt(b.total)} 列</span>`;
  return `<div class="ifx-block">
    ${b.title ? `<div class="ifx-title mono">${esc(b.title)} ${more}</div>` : `<div class="ifx-title">${more}</div>`}
    <div class="result"><table><thead><tr>${b.columns.map((c) => `<th>${esc(c)}</th>`).join("")}</tr></thead>
      <tbody>${b.rows.map((r) => `<tr>${r.map((v, i) => cell(b.columns[i], v)).join("")}</tr>`).join("")}</tbody></table></div>
  </div>`;
}

/** 一次執行（RunResult）的所有區塊。 */
export function runView(r) {
  if (!r) return "";
  return `<div class="ifx-run">${r.blocks.map(block).join("")}</div>`;
}

export const LANG_LABEL = { influxql: "InfluxQL", flux: "Flux", write: "line protocol（寫入 scratch）" };
