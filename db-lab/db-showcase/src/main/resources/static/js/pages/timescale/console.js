// TimescaleDB 的 SQL 主控台：範例查詢（time_bucket、gapfill、first / last、連續聚合…），也可以自己寫 SELECT
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const EXAMPLES = [
  { label: "每小時瀏覽數", sql: `SELECT time_bucket('1 hour', view_time) AS hour, count(*) AS views
FROM page_views
WHERE view_time >= '2026-09-09 00:00+08' AND view_time < '2026-09-10 00:00+08'
GROUP BY hour
ORDER BY hour` },
  { label: "每天（台灣時間）", sql: `SELECT time_bucket('1 day', view_time, 'Asia/Taipei') AS day,
       count(*) AS views,
       count(DISTINCT customer_id) AS members
FROM page_views
WHERE view_time >= '2026-09-01 00:00+08' AND view_time < '2026-09-15 00:00+08'
GROUP BY day
ORDER BY day` },
  { label: "first / last", sql: `SELECT sensor_id,
       first(temperature, time) AS first_temp,
       last(temperature, time)  AS last_temp,
       max(temperature)         AS max_temp
FROM sensor_readings
WHERE time >= '2026-09-18 00:00+08' AND time < '2026-09-19 00:00+08'
GROUP BY sensor_id
ORDER BY sensor_id` },
  { label: "gapfill + interpolate", sql: `SELECT time_bucket_gapfill('30 minutes', time) AS t,
       avg(temperature)              AS raw,
       locf(avg(temperature))        AS locf,
       interpolate(avg(temperature)) AS interpolated
FROM sensor_readings
WHERE sensor_id = 7
  AND time >= '2026-09-10 09:00+08' AND time < '2026-09-10 15:00+08'
GROUP BY t
ORDER BY t` },
  { label: "連續聚合", sql: `SELECT hour, sensor_id, round(avg_temp::numeric, 2) AS avg_temp, max_temp, readings
FROM sensor_hourly
WHERE sensor_id = 2 AND hour >= '2026-09-18 00:00+08' AND hour < '2026-09-18 06:00+08'
ORDER BY hour` },
  { label: "chunk 資訊", sql: `SELECT chunk_name, range_start, range_end, is_compressed
FROM timescaledb_information.chunks
WHERE hypertable_name = 'page_views'
ORDER BY range_start DESC
LIMIT 10` },
  { label: "EXPLAIN", sql: `EXPLAIN (ANALYZE, COSTS OFF)
SELECT count(*) FROM page_views
WHERE view_time >= '2026-09-01 00:00+08' AND view_time < '2026-09-02 00:00+08'` },
];

export function mount(el, { api = "/api/timescale" } = {}) {
  el.innerHTML = `
    <div class="panel">
      <div><span class="eyebrow">SQL 主控台</span><h3>TimescaleDB</h3>
        <p class="desc">一次一句查詢（SELECT、WITH、EXPLAIN），用唯讀的 learner 角色執行。
          寫入請到「寫入沙盒」；建立連續聚合、壓縮這類管理操作請到「實驗室」。</p></div>
      <div class="variant-row" data-ref="examples"><span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}</div>
      <label for="ts-console">SQL（Ctrl+Enter 執行）</label>
      <textarea id="ts-console" data-ref="sql" spellcheck="false" style="min-height:150px"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">執行</button></div>
      <div class="status" data-ref="status" aria-live="polite"></div>
      <div data-ref="output"></div>
    </div>`;
  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true, runToken = 0;

  $("sql").value = store.get(CONSOLE_DRAFT_KEY, EXAMPLES[0].sql);
  $("examples").addEventListener("click", (e) => {
    const b = e.target.closest("[data-example]");
    if (!b) return;
    $("sql").value = EXAMPLES[Number(b.dataset.example)].sql;
    store.set(CONSOLE_DRAFT_KEY, $("sql").value);
  });
  $("sql").addEventListener("input", () => store.set(CONSOLE_DRAFT_KEY, $("sql").value));

  async function run() {
    if ($("run").disabled) return;
    const token = ++runToken;
    $("run").disabled = true;
    await runQuery({
      request: () => postJson(`${api}/sql`, { sql: $("sql").value }),
      statusEl: $("status"), outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (alive && token === runToken) $("run").disabled = false;
  }
  $("run").addEventListener("click", run);
  onCtrlEnter($("sql"), run);
  return () => { alive = false; };
}
