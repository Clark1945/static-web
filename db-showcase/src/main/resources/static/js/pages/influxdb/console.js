// InfluxDB 主控台：InfluxQL、Flux、line protocol 三種模式，用 learner token 執行（metrics 唯讀，scratch 可讀寫）
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { runView } from "./format.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const LANG_KEY = "ifx-console-lang";
const EXAMPLES = [
  { label: "看資料結構", lang: "influxql", code: `SHOW MEASUREMENTS;
SHOW TAG KEYS FROM cpu;
SHOW FIELD KEYS FROM cpu;
SHOW SERIES FROM cpu` },
  { label: "每 10 分鐘的最大 CPU", lang: "influxql", code: `SELECT max(usage_user) FROM cpu
WHERE host = 'db-01' AND time >= '2026-09-18T13:00:00+08:00' AND time < '2026-09-18T16:00:00+08:00'
GROUP BY time(10m) tz('Asia/Taipei')` },
  { label: "每天的營收", lang: "influxql", code: `SELECT count(total), sum(total) FROM orders
WHERE time >= '2026-09-01T00:00:00+08:00' AND time < '2026-09-08T00:00:00+08:00'
GROUP BY time(1d) tz('Asia/Taipei')` },
  { label: "錯誤率（%）", lang: "influxql", code: `SELECT 100 * non_negative_difference(last(errors)) / non_negative_difference(last(requests)) AS error_pct
FROM http WHERE service = 'api' AND time >= '2026-09-18T13:30:00+08:00' AND time < '2026-09-18T15:00:00+08:00'
GROUP BY time(10m)` },
  { label: "Flux：每小時平均", lang: "flux", code: `from(bucket: "metrics")
  |> range(start: 2026-09-18T12:00:00+08:00, stop: 2026-09-18T17:00:00+08:00)
  |> filter(fn: (r) => r._measurement == "cpu" and r._field == "usage_user")
  |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
  |> keep(columns: ["_time", "host", "_value"])` },
  { label: "Flux：失聯的主機", lang: "flux", code: `from(bucket: "metrics")
  |> range(start: 2026-09-01T00:00:00+08:00, stop: 2026-10-01T00:00:00+08:00)
  |> filter(fn: (r) => r._measurement == "cpu" and r._field == "usage_user")
  |> last()
  |> group()
  |> sort(columns: ["_time"])
  |> keep(columns: ["host", "_time"])` },
  { label: "寫入 scratch", lang: "write", code: `# 一行一個點：measurement,tag=值 field=值 時間戳記（秒）
sensors,kind=cold,sensor_id=9 temperature=4.1,humidity=86.5 1790726400
sensors,kind=cold,sensor_id=9 temperature=4.3,humidity=85.9 1790726700` },
  { label: "查 scratch", lang: "influxql", code: `SELECT * FROM "scratch"."autogen"."sensors"` },
  { label: "權限測試", lang: "flux", code: `// learner 不能寫入 metrics
import "array"
array.from(rows: [{_measurement: "cpu", _field: "usage_user", _value: 1.0, _time: 2026-09-30T00:00:00Z, host: "x"}])
  |> to(bucket: "metrics")` },
];
const LANGS = [
  { id: "influxql", label: "InfluxQL", hint: "查詢 metrics；查 scratch 用 \"scratch\".\"autogen\".\"量測\"" },
  { id: "flux", label: "Flux", hint: "from(bucket: \"metrics\" 或 \"scratch\") |> range |> …" },
  { id: "write", label: "line protocol", hint: "寫入 scratch bucket，時間戳記單位是秒" },
];

export function mount(el) {
  el.innerHTML = `
    <div class="panel">
      <div><span class="eyebrow">主控台</span><h3>InfluxQL / Flux / line protocol</h3>
        <p class="desc">用 learner token 執行：metrics bucket 唯讀，scratch bucket 可以寫入與查詢（隨時可能被批改清空）。
          InfluxQL 可以一次多句（用分號分隔）；Flux 不能 import 會連到外部的套件。</p></div>
      <div class="variant-row" data-ref="langs" role="radiogroup" aria-label="語言">
        ${LANGS.map((l) => `<button type="button" class="option small" data-lang="${l.id}" role="radio">${esc(l.label)}</button>`).join("")}
        <span class="muted" data-ref="hint" style="align-self:center"></span></div>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}</div>
      <label for="ifx-console">內容（Ctrl+Enter 執行）</label>
      <textarea id="ifx-console" data-ref="code" spellcheck="false" style="min-height:170px"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">執行</button></div>
      <div class="status" data-ref="status" aria-live="polite"></div>
      <div data-ref="output"></div>
    </div>`;
  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true, runToken = 0, lang = store.get(LANG_KEY, "influxql");

  function setLang(id) {
    lang = LANGS.some((l) => l.id === id) ? id : "influxql";
    store.set(LANG_KEY, lang);
    el.querySelectorAll("[data-lang]").forEach((b) => {
      b.classList.toggle("chosen", b.dataset.lang === lang);
      b.setAttribute("aria-checked", String(b.dataset.lang === lang));
    });
    $("hint").textContent = LANGS.find((l) => l.id === lang).hint;
  }
  setLang(lang);
  $("code").value = store.get(CONSOLE_DRAFT_KEY, EXAMPLES[0].code);
  $("langs").addEventListener("click", (e) => { const b = e.target.closest("[data-lang]"); if (b) setLang(b.dataset.lang); });
  $("examples").addEventListener("click", (e) => {
    const b = e.target.closest("[data-example]");
    if (!b) return;
    const ex = EXAMPLES[Number(b.dataset.example)];
    $("code").value = ex.code;
    setLang(ex.lang);
    store.set(CONSOLE_DRAFT_KEY, ex.code);
  });
  $("code").addEventListener("input", () => store.set(CONSOLE_DRAFT_KEY, $("code").value));

  async function run() {
    if ($("run").disabled) return;
    const token = ++runToken;
    $("run").disabled = true;
    const r = await runQuery({
      request: () => postJson("/api/influx/run", { lang, code: $("code").value }),
      toResult: (x) => ({ rowCount: x.blocks.reduce((s, b) => s + (b.total ?? 0), 0), truncated: false, elapsedMs: x.totalMillis }),
      render: runView,
      statusEl: $("status"), outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (!alive || token !== runToken) return;
    $("run").disabled = false;
    const chip = $("status").querySelector(".chip");
    if (chip && r) chip.innerHTML = `<b>${r.blocks.length}</b> 個結果區塊`;
  }
  $("run").addEventListener("click", run);
  onCtrlEnter($("code"), run);
  return () => { alive = false; };
}
