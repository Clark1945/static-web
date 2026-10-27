// Neo4j 結果的顯示：資料列表格（節點、關係、路徑用 Cypher 的寫法）、寫入統計、伺服器提示、執行計畫、關係圖
import { esc, fmt } from "../../lib.js";

// 每個標籤一個顏色（亮色、暗色模式都看得清楚的中間色）
const LABEL_COLORS = {
  Customer: "#4C8EDA", Order: "#E0A030", Product: "#3FA772", Category: "#9A6FD1",
  City: "#2BA3A8", Brand: "#D16FA0", Person: "#888C94",
};
export const labelColor = (label) => LABEL_COLORS[label] ?? "#888C94";

const COUNTER_NAMES = {
  nodesCreated: "建立節點", nodesDeleted: "刪除節點", relationshipsCreated: "建立關係", relationshipsDeleted: "刪除關係",
  propertiesSet: "設定屬性", labelsAdded: "加上標籤", labelsRemoved: "移除標籤", indexesAdded: "建立索引",
  indexesRemoved: "刪除索引", constraintsAdded: "建立約束", constraintsRemoved: "刪除約束",
};

const isNode = (v) => v && typeof v === "object" && "~labels" in v;
const isRel = (v) => v && typeof v === "object" && "~type" in v;
const isPath = (v) => v && typeof v === "object" && "~path" in v;

// 節點顯示的屬性：先放最能辨識的 id、name，其他依字母順序
const PRIORITY = ["id", "name", "status", "total", "price", "qty"];

function props(p, max = 4) {
  const keys = Object.keys(p ?? {}).sort((a, b) => {
    const ia = PRIORITY.indexOf(a), ib = PRIORITY.indexOf(b);
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || a.localeCompare(b);
  });
  if (!keys.length) return "";
  const shown = keys.slice(0, max).map((k) => `<span class="c-key">${esc(k)}</span>: ${inner(p[k])}`).join(", ");
  return ` {${shown}${keys.length > max ? ", …" : ""}}`;
}
const inner = (v) => (typeof v === "string" ? `<span class="c-str">'${esc(v)}'</span>` : formatCell(v));

/** 一個值：節點 (:Label {…})、關係 [:TYPE {…}]、路徑、清單、map。 */
export function formatCell(v) {
  if (v === null || v === undefined) return '<span class="c-null">null</span>';
  if (typeof v === "number") return `<span class="c-num">${v}</span>`;
  if (typeof v === "boolean") return `<span class="c-bool">${v}</span>`;
  if (typeof v === "string") return `<span class="c-str">${esc(v)}</span>`;
  if (isNode(v)) return `<span class="g-node">(${v["~labels"].map((l) => `<b style="color:${labelColor(l)}">:${esc(l)}</b>`).join("")}${props(v["~props"])})</span>`;
  if (isRel(v)) return `<span class="g-rel">[<b>:${esc(v["~type"])}</b>${props(v["~props"], 3)}]</span>`;
  if (isPath(v)) return v["~path"].map((x, i) => i % 2 ? `-${formatCell(x)}->` : formatCell(x)).join("");
  if (Array.isArray(v)) return `[${v.map(inner).join(", ")}]`;
  return `{${Object.entries(v).map(([k, x]) => `<span class="c-key">${esc(k)}</span>: ${inner(x)}`).join(", ")}}`;
}

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

// ---------------------------------------------------------------- 執行計畫

const GOOD_OPS = /IndexSeek|IndexContainsScan|IndexEndsWithScan|UniqueIndexSeek/;
const BAD_OPS = /AllNodesScan|NodeByLabelScan|CartesianProduct|Eager$/;

/** PROFILE / EXPLAIN 的計畫：由下而上執行，畫成縮排的樹。 */
export function planView(plan, totalDbHits) {
  if (!plan) return "";
  const rows = [];
  const walk = (p, depth) => {
    rows.push({ p, depth });
    p.children.forEach((c) => walk(c, depth + 1));
  };
  walk(plan, 0);
  const maxHits = Math.max(1, ...rows.map((r) => r.p.dbHits ?? 0));
  return `
    <div class="plan">
      <div class="plan-head"><b>執行計畫</b>${totalDbHits != null ? `<span>總 db hits <b class="mono">${fmt(totalDbHits)}</b></span>` : ""}
        <span class="muted">由下往上執行；db hits 是存取儲存層的次數</span></div>
      <div class="result plan-table"><table><thead><tr><th>運算子</th><th class="num">列數</th><th class="num">db hits</th><th>細節</th></tr></thead><tbody>
        ${rows.map(({ p, depth }) => `<tr>
          <td><span class="plan-op ${GOOD_OPS.test(p.operator) ? "good" : BAD_OPS.test(p.operator) ? "bad" : ""}" style="margin-left:${depth * 14}px">${esc(p.operator)}</span></td>
          <td class="num">${p.rows == null ? (p.estimatedRows != null ? "≈" + fmt(Math.round(p.estimatedRows)) : "") : fmt(p.rows)}</td>
          <td class="num"><span class="hit-bar" style="--w:${p.dbHits ? Math.max(2, (p.dbHits / maxHits) * 100) : 0}%"></span>${p.dbHits == null ? "" : fmt(p.dbHits)}</td>
          <td class="plan-details">${esc(p.details ?? "")}</td></tr>`).join("")}
      </tbody></table></div>
    </div>`;
}

// ---------------------------------------------------------------- 關係圖

/**
 * 把節點與關係畫成 SVG。用簡單的力導向配置（排斥 + 彈簧 + 往中心拉），固定亂數種子，同樣的資料每次畫出來一樣。
 */
export function graphView(graph, { height = 340 } = {}) {
  if (!graph || !graph.nodes?.length) return "";
  const W = 720, H = height;
  const nodes = graph.nodes.map((n, i) => ({ ...n, x: 0, y: 0, i }));
  const index = new Map(nodes.map((n) => [n.id, n]));
  const links = graph.relationships.map((r) => ({ ...r, s: index.get(r.start), t: index.get(r.end) })).filter((l) => l.s && l.t);
  let seed = 7;
  const rand = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  nodes.forEach((n, i) => {
    const a = (i / nodes.length) * Math.PI * 2;
    n.x = W / 2 + Math.cos(a) * W * 0.3 + rand() * 10;
    n.y = H / 2 + Math.sin(a) * H * 0.3 + rand() * 10;
  });
  const k = Math.sqrt((W * H) / Math.max(nodes.length, 1)) * 0.75;
  for (let it = 0; it < 220; it++) {
    const t = 0.1 * (1 - it / 220) + 0.01;
    for (const a of nodes) { a.dx = 0; a.dy = 0; }
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const a = nodes[i], b = nodes[j];
        let dx = a.x - b.x, dy = a.y - b.y;
        const d = Math.max(Math.hypot(dx, dy), 0.01);
        const f = (k * k) / d;
        dx /= d; dy /= d;
        a.dx += dx * f; a.dy += dy * f; b.dx -= dx * f; b.dy -= dy * f;
      }
    }
    for (const l of links) {
      let dx = l.s.x - l.t.x, dy = l.s.y - l.t.y;
      const d = Math.max(Math.hypot(dx, dy), 0.01);
      const f = (d * d) / k;
      dx /= d; dy /= d;
      l.s.dx -= dx * f; l.s.dy -= dy * f; l.t.dx += dx * f; l.t.dy += dy * f;
    }
    for (const a of nodes) {
      a.dx += (W / 2 - a.x) * 0.02 * k; a.dy += (H / 2 - a.y) * 0.02 * k;
      const d = Math.max(Math.hypot(a.dx, a.dy), 0.01);
      const step = Math.min(d, t * W);
      a.x = Math.min(W - 30, Math.max(30, a.x + (a.dx / d) * step));
      a.y = Math.min(H - 24, Math.max(24, a.y + (a.dy / d) * step));
    }
  }
  const r = nodes.length > 60 ? 7 : 13;
  const label = (n) => String(n.caption ?? "").slice(0, 10);
  const pair = new Map();
  const edges = links.map((l) => {
    const key = [l.s.id, l.t.id].sort().join("|");
    const n = pair.get(key) ?? 0;
    pair.set(key, n + 1);
    const self = l.s === l.t;
    const mx = (l.s.x + l.t.x) / 2, my = (l.s.y + l.t.y) / 2;
    const dx = l.t.x - l.s.x, dy = l.t.y - l.s.y, d = Math.max(Math.hypot(dx, dy), 1);
    const bend = (n % 2 ? -1 : 1) * Math.ceil(n / 2) * 18;      // 同一對節點有多條關係時彎開來
    const cx = mx - (dy / d) * bend, cy = my + (dx / d) * bend;
    // 箭頭停在終點圓圈的邊上
    const ex = l.t.x - ((l.t.x - cx) / Math.max(Math.hypot(l.t.x - cx, l.t.y - cy), 1)) * (r + 3);
    const ey = l.t.y - ((l.t.y - cy) / Math.max(Math.hypot(l.t.x - cx, l.t.y - cy), 1)) * (r + 3);
    const path = self ? `M${l.s.x},${l.s.y - r} c 30,-40 50,10 ${r},${r}` : `M${l.s.x},${l.s.y} Q${cx},${cy} ${ex},${ey}`;
    return `<g class="g-edge"><path d="${path}" marker-end="url(#arrow)"><title>${esc(l.type)}</title></path>
      ${nodes.length <= 40 ? `<text x="${self ? l.s.x + 34 : (mx + cx) / 2}" y="${self ? l.s.y - r - 16 : (my + cy) / 2 - 3}">${esc(l.type)}</text>` : ""}</g>`;
  }).join("");
  const circles = nodes.map((n) => `
    <g class="g-vertex" transform="translate(${n.x.toFixed(1)},${n.y.toFixed(1)})">
      <circle r="${r}" fill="${labelColor(n.labels[0])}"><title>${esc(":" + n.labels.join(":") + " " + JSON.stringify(n.props))}</title></circle>
      ${nodes.length <= 80 ? `<text y="${r + 12}">${esc(label(n))}</text>` : ""}
    </g>`).join("");
  const legend = [...new Set(nodes.flatMap((n) => n.labels.slice(0, 1)))]
    .map((l) => `<span><i style="background:${labelColor(l)}"></i>${esc(l)}</span>`).join("");
  return `
    <div class="graph-box">
      <svg viewBox="0 0 ${W} ${H}" role="img" aria-label="查詢結果的關係圖：${nodes.length} 個節點、${links.length} 條關係">
        <defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" class="g-arrow"/></marker></defs>
        ${edges}${circles}
      </svg>
      <div class="graph-legend">${legend}<span class="muted">${nodes.length} 個節點、${links.length} 條關係${graph.truncated ? "（節點太多，只畫前 300 個）" : ""}；滑鼠移到節點上可以看屬性</span></div>
    </div>`;
}

// ---------------------------------------------------------------- 一句的結果

export function formatResult(r, { showGraph = true } = {}) {
  const notes = (r.notifications ?? []).map((n) => `<div class="cql-warn">⚠ 伺服器提示：${esc(n)}</div>`).join("");
  const counters = Object.entries(r.counters ?? {});
  const counterLine = counters.length
    ? `<div class="g-counters">${counters.map(([k, v]) => `<span>${esc(COUNTER_NAMES[k] ?? k)} <b>${fmt(v)}</b></span>`).join("")}</div>` : "";
  let body;
  if (r.kind === "error") body = `<div class="error">${esc(r.message)}</div>`;
  else if (r.kind === "ok") body = counters.length ? "" : '<div class="cql-info">完成（沒有回傳資料）</div>';
  else body = (r.rows.length === 0 ? '<div class="cql-info">0 列</div>' : table(r.columns, r.rows))
    + `<div class="cql-meta">${fmt(r.total)} 列${r.truncated ? `（只顯示前 ${fmt(r.rows.length)} 列）` : ""}</div>`;
  const graph = showGraph && r.graph && (r.graph.nodes.length > 1 || r.graph.relationships.length)
    ? `<details class="graph-toggle" open><summary>關係圖</summary>${graphView(r.graph)}</details>` : "";
  return counterLine + body + notes + graph + planView(r.plan, r.totalDbHits);
}

/** 一串 Cypher 的執行紀錄。最後註明整個交易已經 ROLLBACK。 */
export function transcript(results, { rolledBack = true } = {}) {
  const wrote = results.some((r) => Object.keys(r.counters ?? {}).length);
  return `<div class="cql-transcript">${results.map((r) => `
    <div class="cql-step">
      <div class="cql-cmd"><span class="r-prompt">neo4j$</span> <code>${esc(r.statement)}</code>
        <span class="r-time">${r.millis.toFixed(1)} ms</span></div>
      ${formatResult(r)}
    </div>`).join("")}
    ${rolledBack && wrote ? '<div class="rollback-note">已 ROLLBACK：以上的寫入只存在於這次的交易裡，資料庫沒有被修改。</div>' : ""}</div>`;
}
