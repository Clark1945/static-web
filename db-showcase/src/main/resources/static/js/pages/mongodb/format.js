// MongoDB 結果的顯示：仿照 mongosh 的格式（key 不加引號、ObjectId('…')、ISODate('…')）
import { esc, fmt } from "../../lib.js";

const IDENT = /^[A-Za-z_$][A-Za-z0-9_$]*$/;

/** 把一個值轉成 mongosh 風格的多行 HTML。 */
export function formatValue(v, indent = "") {
  if (v === null || v === undefined) return '<span class="m-null">null</span>';
  if (typeof v === "number") return `<span class="m-num">${v}</span>`;
  if (typeof v === "boolean") return `<span class="m-bool">${v}</span>`;
  if (typeof v === "string") return `<span class="m-str">'${esc(v)}'</span>`;
  if (Array.isArray(v)) {
    if (v.length === 0) return "[]";
    const inner = indent + "  ";
    const simple = v.every((x) => x === null || typeof x !== "object");
    if (simple && v.length <= 8) return `[ ${v.map((x) => formatValue(x)).join(", ")} ]`;
    return "[\n" + v.map((x) => inner + formatValue(x, inner)).join(",\n") + "\n" + indent + "]";
  }
  if (typeof v === "object") {
    const keys = Object.keys(v);
    if (keys.length === 1 && keys[0] === "$oid") return `<span class="m-fn">ObjectId(</span><span class="m-str">'${esc(v.$oid)}'</span><span class="m-fn">)</span>`;
    if (keys.length === 1 && keys[0] === "$date") return `<span class="m-fn">ISODate(</span><span class="m-str">'${esc(v.$date)}'</span><span class="m-fn">)</span>`;
    if (keys.length === 0) return "{}";
    const inner = indent + "  ";
    return "{\n" + keys.map((k) => `${inner}<span class="m-key">${IDENT.test(k) ? esc(k) : `'${esc(k)}'`}</span>: ${formatValue(v[k], inner)}`).join(",\n") + "\n" + indent + "}";
  }
  return esc(String(v));
}

/** 一句指令的結果。 */
export function formatResult(r) {
  if (r.kind === "error") return `<span class="m-err">${esc(String(r.value))}</span>`;
  if (r.kind === "docs") {
    const head = `<span class="m-meta">// 共 ${fmt(r.total)} 筆${r.truncated ? `，以下只顯示前 ${fmt(r.value.length)} 筆` : ""}</span>`;
    if (r.value.length === 0) return head;
    return head + "\n" + r.value.map((d) => formatValue(d)).join("\n");
  }
  return formatValue(r.value);
}

/** 一串指令的執行紀錄：shop> 指令、結果、耗時。 */
export function transcript(results, prompt = "shop") {
  return `<pre class="cli mongo">${results.map((r) => `<span class="r-prompt">${prompt}&gt;</span> <span class="r-cmd">${esc(r.statement)}</span>  <span class="r-time">${r.millis.toFixed(1)} ms</span>
${formatResult(r)}`).join("\n\n")}</pre>`;
}
