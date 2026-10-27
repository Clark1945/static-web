#!/usr/bin/env node
// 驗證 data/*.js 的格式與 index.html 的載入設定。
// 用法：node scripts/validate.mjs
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const errors = [];
const warnings = [];
const err = (m) => errors.push(m);
const warn = (m) => warnings.push(m);

// 在沙盒裡執行瀏覽器腳本，取得註冊的國家資料
const ctx = {};
ctx.window = ctx;
vm.createContext(ctx);
const load = (file) =>
  vm.runInContext(readFileSync(file, "utf8"), ctx, { filename: file });

load(join(root, "js/registry.js"));
const dataFiles = readdirSync(join(root, "data"))
  .filter((f) => f.endsWith(".js"))
  .sort();
for (const f of dataFiles) load(join(root, "data", f));

// index.html 是否載入了每個資料檔
const html = readFileSync(join(root, "index.html"), "utf8");
for (const f of dataFiles) {
  if (!html.includes(`data/${f}`)) err(`index.html 沒有載入 data/${f}`);
}

const countries = ctx.HistoryDB?.countries ?? [];
if (!countries.length) err("沒有註冊任何國家");

const PRECISIONS = new Set(["exact", "approx", "disputed"]);
const ID_RE = /^[a-z0-9-]+$/;
const countryIds = new Set();
const eventIds = new Set();
const isYear = (n) => Number.isInteger(n) && n !== 0;

for (const c of countries) {
  const tag = `[${c.id}]`;

  if (!ID_RE.test(c.id)) err(`${tag} country.id 只能用小寫英數與連字號`);
  if (countryIds.has(c.id)) err(`${tag} country.id 重複`);
  countryIds.add(c.id);
  if (!c.name) err(`${tag} 缺少 name`);
  if (!/^#[0-9a-f]{6}$/i.test(c.color ?? "")) err(`${tag} color 需為 #rrggbb`);

  const eraIds = new Set();
  if (!Array.isArray(c.eras)) err(`${tag} eras 必須是陣列`);
  for (const era of c.eras ?? []) {
    if (!era.id || !era.name) err(`${tag} era 缺少 id 或 name：${JSON.stringify(era)}`);
    if (eraIds.has(era.id)) err(`${tag} era.id 重複：${era.id}`);
    eraIds.add(era.id);
    if (!isYear(era.start)) err(`${tag} era ${era.id} 的 start 不是有效年份`);
    if (era.end !== null && !isYear(era.end)) err(`${tag} era ${era.id} 的 end 需為年份或 null`);
    if (era.end !== null && era.end < era.start) err(`${tag} era ${era.id} 的 end 早於 start`);
  }

  if (!Array.isArray(c.events)) {
    err(`${tag} events 必須是陣列`);
    continue;
  }

  let prevYear = -Infinity;
  let majors = 0;
  for (const e of c.events) {
    const t = `${tag} ${e.id ?? "(無 id)"}`;
    if (!e.id || !ID_RE.test(e.id)) err(`${t}：id 只能用小寫英數與連字號`);
    if (eventIds.has(e.id)) err(`${t}：id 在所有國家中必須唯一`);
    eventIds.add(e.id);
    if (!isYear(e.year)) err(`${t}：year 需為非 0 整數`);
    if (e.yearEnd !== undefined && (!isYear(e.yearEnd) || e.yearEnd < e.year)) {
      err(`${t}：yearEnd 需為不早於 year 的整數`);
    }
    if (e.precision !== undefined && !PRECISIONS.has(e.precision)) {
      err(`${t}：precision 只能是 ${[...PRECISIONS].join(" / ")}`);
    }
    if (!e.title?.trim()) err(`${t}：缺少 title`);
    if (!e.summary?.trim()) err(`${t}：缺少 summary`);
    if (e.era === undefined) warn(`${t}：沒有 era`);
    else if (!eraIds.has(e.era)) err(`${t}：era「${e.era}」不在 eras 裡`);
    for (const l of e.links ?? []) {
      if (!l.label || !/^https:\/\//.test(l.url ?? "")) {
        err(`${t}：links 需要 label 與 https 開頭的 url`);
      }
    }
    if (!e.wiki && !(e.links ?? []).length) warn(`${t}：沒有任何延伸閱讀連結`);
    if (e.major) majors++;
    if (e.year < prevYear) warn(`${t}：沒有依年份排序（不影響顯示，但資料檔不好維護）`);
    prevYear = e.year;
  }

  console.log(`${c.name}（${c.id}）：${c.events.length} 筆事件，其中重大 ${majors} 筆，${c.eras?.length ?? 0} 個時代`);
}

for (const w of warnings) console.warn("警告：" + w);
for (const m of errors) console.error("錯誤：" + m);
console.log(errors.length ? `\n驗證失敗：${errors.length} 個錯誤` : "\n驗證通過");
process.exit(errors.length ? 1 : 0);
