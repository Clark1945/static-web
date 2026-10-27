// ER 圖：從 /api/postgres/schema 的資料畫出表格方塊與一對多關聯線
import { fmt, esc } from "../../lib.js";

// ER 圖版面：依關聯由左到右排列
const ORDER = ["customers", "orders", "order_items", "products", "categories"];
const W = 226, GAP = 60, HEAD = 48, ROW = 24, LOOP = 34;
const SHORT_TYPE = {
  "integer": "int", "timestamp with time zone": "timestamptz", "timestamp without time zone": "timestamp",
  "character": "char", "character varying": "varchar", "boolean": "bool",
};

export function renderER(tables, svg) {
  const byName = Object.fromEntries(tables.map((t) => [t.name, t]));
  const ordered = [...ORDER.filter((n) => byName[n]), ...tables.map((t) => t.name).filter((n) => !ORDER.includes(n))];

  const boxes = {};
  ordered.forEach((name, i) => {
    const t = byName[name];
    boxes[name] = { t, x: i * (W + GAP), h: HEAD + t.columns.length * ROW + 8 };
  });
  const rowY = (box, col) => HEAD + box.t.columns.findIndex((c) => c.name === col) * ROW + ROW / 2;

  // 關聯線（先畫，壓在表格下面）
  const many = (x, y, d) => `M${x + d * 12} ${y}L${x} ${y - 6}M${x + d * 12} ${y}L${x} ${y}M${x + d * 12} ${y}L${x} ${y + 6}`;
  const one = (x, y, d) => `M${x + d * 7} ${y - 6}V${y + 6}M${x + d * 12} ${y - 6}V${y + 6}`;
  let lines = "";
  for (const name of ordered) {
    const c = boxes[name];
    for (const col of c.t.columns.filter((k) => k.references && boxes[k.references])) {
      const p = boxes[col.references];
      const y1 = rowY(c, col.name), y2 = rowY(p, "id");
      let d;
      if (p === c) {
        const x = c.x + W;
        d = `M${x} ${y1}H${x + LOOP}V${y2}H${x}` + many(x, y1, 1) + one(x, y2, 1);
      } else if (p.x < c.x) {
        const x1 = c.x, x2 = p.x + W, mid = (x1 + x2) / 2;
        d = `M${x1} ${y1}H${mid}V${y2}H${x2}` + many(x1, y1, -1) + one(x2, y2, 1);
      } else {
        const x1 = c.x + W, x2 = p.x, mid = (x1 + x2) / 2;
        d = `M${x1} ${y1}H${mid}V${y2}H${x2}` + many(x1, y1, 1) + one(x2, y2, -1);
      }
      lines += `<path class="er-line" d="${d}"><title>${esc(name)}.${esc(col.name)} → ${esc(col.references)}.id</title></path>`;
    }
  }

  // 表格方塊
  const boxSvg = ordered.map((name) => {
    const { t, x, h } = boxes[name];
    const rows = t.columns.map((c, i) => {
      const y = HEAD + i * ROW;
      const mark = c.primaryKey && c.references ? '<tspan class="er-pk">PK</tspan><tspan class="er-fk"> FK</tspan>'
        : c.primaryKey ? '<tspan class="er-pk">PK</tspan>'
        : c.references ? '<tspan class="er-fk">FK</tspan>' : "";
      const type = (SHORT_TYPE[c.type] ?? c.type) + (c.nullable && !c.primaryKey ? "?" : "");
      return `
        ${i % 2 ? `<rect class="er-stripe" x="1" y="${y}" width="${W - 2}" height="${ROW}"/>` : ""}
        <text x="8" y="${y + 16}">${mark}</text>
        <text class="er-col" x="48" y="${y + 16}">${esc(c.name)}</text>
        <text class="er-type" x="${W - 10}" y="${y + 16}" text-anchor="end">${esc(type)}</text>`;
    }).join("");
    return `
      <g transform="translate(${x} 0)">
        <title>${esc(t.name)}：${esc(t.comment ?? "")}</title>
        <rect class="er-box" width="${W}" height="${h}" rx="8"/>
        <rect class="er-head" width="${W}" height="${HEAD}" rx="8"/>
        <rect class="er-head" y="${HEAD - 8}" width="${W}" height="8"/>
        <text class="er-title" x="12" y="21">${esc(t.name)}</text>
        <text class="er-sub" x="12" y="39">${fmt(t.rowCount)} 筆 · ${esc(t.size)}</text>
        ${rows}
      </g>`;
  }).join("");

  const width = ordered.length * W + (ordered.length - 1) * GAP + LOOP + 6;
  const height = Math.max(...Object.values(boxes).map((b) => b.h)) + 4;
  svg.setAttribute("viewBox", `-2 -2 ${width} ${height}`);
  return lines + boxSvg;
}
