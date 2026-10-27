// Elasticsearch 結果的顯示：每個請求一段（方法 路徑、HTTP 狀態、耗時），
// _search 先列出 hits 摘要表與聚合，完整 JSON 放在可展開的區塊；_cat 的文字表格原樣顯示
import { esc, fmt } from "../../lib.js";

/** JSON 上色（key、字串、數字、布林 / null）。 */
export function jsonHtml(v) {
  const text = typeof v === "string" ? v : JSON.stringify(v, null, 2);
  return esc(text).replace(/(&quot;(?:\\&quot;|\\.|[^&\\]|&(?!quot;))*?&quot;)(\s*:)?|\b(true|false|null)\b|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)/g,
    (m, str, colon, lit, num) => {
      if (str) return colon ? `<span class="j-key">${str}</span>${colon}` : `<span class="j-str">${str}</span>`;
      if (lit) return `<span class="j-lit">${lit}</span>`;
      return `<span class="j-num">${num}</span>`;
    });
}

const SHOW = ["name", "title", "content", "description", "message", "product_name", "price", "rating", "review_count", "status",
  "@timestamp", "order_date", "total", "category", "brand", "service", "latency_ms", "tags", "helpful", "stock"];

/** hits 摘要：_id、_score、幾個常見欄位。 */
function hitsTable(hits) {
  if (!hits.length) return '<div class="empty">沒有符合的文件</div>';
  const keys = [];
  for (const h of hits) for (const k of Object.keys(h._source ?? {})) if (SHOW.includes(k) && !keys.includes(k)) keys.push(k);
  keys.sort((a, b) => SHOW.indexOf(a) - SHOW.indexOf(b));
  const cols = keys.slice(0, 5);
  const val = (v) => v == null ? '<td class="nul">—</td>' : typeof v === "number" ? `<td class="num">${fmt(v)}</td>`
    : `<td>${esc(Array.isArray(v) ? v.join("、") : typeof v === "object" ? JSON.stringify(v) : v)}</td>`;
  return `<div class="result"><table><thead><tr><th>_id</th><th class="num">_score</th>${cols.map((c) => `<th>${esc(c)}</th>`).join("")}</tr></thead><tbody>
    ${hits.map((h) => `<tr><td class="mono">${esc(h._id)}</td>${h._score == null ? '<td class="nul">—</td>' : `<td class="num">${h._score.toFixed(3)}</td>`}
      ${cols.map((c) => val(h._source?.[c])).join("")}</tr>`).join("")}</tbody></table></div>`;
}

/** 聚合結果攤平成表格：每個 bucket 一列（巢狀的聚合用「上層 › 下層」表示）。 */
function aggRows(aggs, path, rows) {
  for (const [name, a] of Object.entries(aggs ?? {})) {
    if (a == null || typeof a !== "object") continue;
    const label = path ? `${path} › ${name}` : name;
    if (Array.isArray(a.buckets)) {
      for (const b of a.buckets) {
        const metrics = [];
        const sub = {};
        for (const [k, v] of Object.entries(b)) {
          if (["key", "key_as_string", "doc_count", "from", "to", "from_as_string", "to_as_string"].includes(k)) continue;
          if (v && typeof v === "object" && ("value" in v || "values" in v)) metrics.push(`${k} = ${metric(v)}`);
          else if (v && typeof v === "object") sub[k] = v;
        }
        rows.push({ agg: label, key: b.key_as_string ?? b.key, count: b.doc_count, metrics: metrics.join("，") });
        if (Object.keys(sub).length) aggRows(sub, `${label}［${b.key_as_string ?? b.key}］`, rows);
      }
    } else if ("value" in a || "values" in a) {
      rows.push({ agg: label, key: "", count: null, metrics: metric(a) });
    } else if ("doc_count" in a) {
      const sub = Object.fromEntries(Object.entries(a).filter(([, v]) => v && typeof v === "object"));
      rows.push({ agg: label, key: "", count: a.doc_count, metrics: "" });
      aggRows(sub, label, rows);
    }
  }
  return rows;
}

function metric(v) {
  if ("value" in v) return v.value == null ? "null" : typeof v.value === "number" ? fmt(Math.round(v.value * 100) / 100) : esc(v.value);
  return Object.entries(v.values).map(([k, x]) => `${k}: ${x == null ? "null" : fmt(Math.round(x * 100) / 100)}`).join("，");
}

function aggTable(aggs) {
  const rows = aggRows(aggs, "", []);
  if (!rows.length) return "";
  return `<div class="result"><table><thead><tr><th>聚合</th><th>key</th><th class="num">doc_count</th><th>數值</th></tr></thead><tbody>
    ${rows.map((r) => `<tr><td class="mono">${esc(r.agg)}</td><td>${esc(String(r.key))}</td>
      <td class="num">${r.count == null ? "" : fmt(r.count)}</td><td>${r.metrics}</td></tr>`).join("")}</tbody></table></div>`;
}

/** 一個請求的結果。 */
export function formatResponse(r) {
  const b = r.response;
  const ok = r.status >= 200 && r.status < 300;
  if (typeof b === "string") return `<pre class="code es-text">${esc(b || "（沒有內容）")}</pre>`;
  if (!ok) {
    const reason = b?.error?.root_cause?.[0]?.reason ?? b?.error?.reason ?? (typeof b?.error === "string" ? b.error : "");
    return `${reason ? `<div class="error">${esc(reason)}</div>` : ""}<details><summary>完整回應</summary><pre class="code es-json">${jsonHtml(b)}</pre></details>`;
  }
  if (b && b.hits && Array.isArray(b.hits.hits)) {
    const total = b.hits.total;
    const head = `<div class="es-meta">hits.total = <b>${total ? `${fmt(total.value)}${total.relation === "gte" ? "+（至少）" : ""}` : "—"}</b>
      · 回傳 ${fmt(b.hits.hits.length)} 筆${b.hits["_展示台"] ? `（${esc(b.hits["_展示台"])}）` : ""}</div>`;
    return head + (b.hits.hits.length || !b.aggregations ? hitsTable(b.hits.hits) : "") + (b.aggregations ? aggTable(b.aggregations) : "")
      + `<details><summary>完整回應（JSON）</summary><pre class="code es-json">${jsonHtml(b)}</pre></details>`;
  }
  return `<pre class="code es-json">${jsonHtml(b)}</pre>`;
}

/** 一串請求的執行紀錄。 */
export function transcript(results) {
  return `<div class="es-transcript">${results.map((r) => `
    <div class="es-req">
      <div class="es-head"><span class="mono es-stmt">${esc(r.statement)}</span>
        <span class="es-status ${r.status >= 200 && r.status < 300 ? "ok" : "bad"}">${r.status}</span>
        <span class="muted">${r.millis.toFixed(1)} ms</span></div>
      ${formatResponse(r)}
    </div>`).join("")}</div>`;
}
