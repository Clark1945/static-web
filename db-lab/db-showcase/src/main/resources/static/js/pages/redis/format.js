// Redis 回傳值的顯示：仿照 redis-cli 的格式
import { esc } from "../../lib.js";

/** 把一個回傳值轉成 redis-cli 風格的多行 HTML。 */
export function formatReply(reply, indent = "") {
  if (reply === null || reply === undefined) return '<span class="r-nil">(nil)</span>';
  if (typeof reply === "number") return `<span class="r-int">(integer) ${reply}</span>`;
  if (typeof reply === "string") return `<span class="r-str">"${esc(reply)}"</span>`;
  if (!Array.isArray(reply) && typeof reply === "object" && "error" in reply) {
    return `<span class="r-err">(error) ${esc(reply.error)}</span>`;
  }
  if (Array.isArray(reply)) {
    if (reply.length === 0) return '<span class="r-nil">(empty array)</span>';
    const width = String(reply.length).length;
    return reply.map((item, i) => {
      const label = `${String(i + 1).padStart(width)}) `;
      const inner = formatReply(item, indent + " ".repeat(label.length));
      return (i === 0 ? "" : indent) + `<span class="r-idx">${label}</span>` + inner;
    }).join("\n");
  }
  return esc(String(reply));
}

/** 一串指令的執行紀錄：redis> 指令、回傳值、耗時。 */
export function transcript(results) {
  return `<pre class="cli">${results.map((r) => `<span class="r-prompt">redis&gt;</span> <span class="r-cmd">${esc(r.command)}</span>  <span class="r-time">${formatMicros(r.micros)}</span>
${formatReply(r.reply)}`).join("\n\n")}</pre>`;
}

export const formatMicros = (us) => (us >= 1000 ? (us / 1000).toFixed(2) + " ms" : Math.round(us) + " µs");
