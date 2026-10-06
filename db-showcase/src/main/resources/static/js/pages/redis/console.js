// 指令主控台：用 learner 身分在 db 0 執行任何（ACL 允許的）指令
import { postJson, esc, store, onCtrlEnter, runQuery } from "../../lib.js";
import { transcript } from "./format.js";
import { CONSOLE_DRAFT_KEY } from "./index.js";

const EXAMPLES = [
  { label: "看看有哪些型別", cmd: "TYPE product:540\nTYPE tag:特價\nTYPE leaderboard:products:sales\nTYPE orders:stream\nTYPE store:locations" },
  { label: "物件快取（Hash）", cmd: "HGETALL product:540\nHGET customer:1 name" },
  { label: "排行榜（Sorted Set）", cmd: "ZRANGE leaderboard:customers:spend 0 4 REV WITHSCORES\nZREVRANK leaderboard:customers:spend 500\nZSCORE leaderboard:customers:spend 500" },
  { label: "Session 與 TTL", cmd: "GET session:a1b2c3\nTTL session:a1b2c3\nTTL config:site:maintenance\nTTL no-such-key" },
  { label: "記憶體與編碼", cmd: "MEMORY USAGE product:540\nOBJECT ENCODING product:540\nMEMORY USAGE leaderboard:customers:spend\nOBJECT ENCODING leaderboard:customers:spend" },
  { label: "安全地找 key（SCAN）", cmd: "SCAN 0 MATCH session:* COUNT 1000" },
  { label: "權限測試", cmd: "FLUSHALL\nKEYS *\nCONFIG GET maxmemory" },
];

export function mount(el) {
  el.innerHTML = `
    <div class="panel">
      <div>
        <span class="eyebrow">指令主控台</span>
        <h3>redis-cli</h3>
        <p class="desc">一行一個指令，依序執行。用 <span class="mono">learner</span> 帳號在 db 0 執行：寫入會保留下來，
          練習完按上方「重置資料」就能還原。</p>
      </div>
      <div class="variant-row" data-ref="examples">
        <span class="muted" style="align-self:center">範例：</span>
        ${EXAMPLES.map((e, i) => `<button type="button" class="option small" data-example="${i}">${esc(e.label)}</button>`).join("")}
      </div>
      <label for="redis-console">指令（Ctrl+Enter 執行）</label>
      <textarea id="redis-console" data-ref="cmd" spellcheck="false" style="min-height:150px"></textarea>
      <div class="actions"><button class="btn" type="button" data-ref="run">執行</button></div>
      <div class="status" data-ref="status" aria-live="polite"></div>
      <div data-ref="output"></div>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true;
  let runToken = 0;

  $("cmd").value = store.get(CONSOLE_DRAFT_KEY, EXAMPLES[0].cmd);
  $("examples").addEventListener("click", (e) => {
    const b = e.target.closest("[data-example]");
    if (!b) return;
    $("cmd").value = EXAMPLES[Number(b.dataset.example)].cmd;
    store.set(CONSOLE_DRAFT_KEY, $("cmd").value);
  });
  $("cmd").addEventListener("input", () => store.set(CONSOLE_DRAFT_KEY, $("cmd").value));

  async function run() {
    if ($("run").disabled) return;
    const token = ++runToken;
    $("run").disabled = true;
    const r = await runQuery({
      request: () => postJson("/api/redis/run", { commands: $("cmd").value }),
      toResult: (x) => ({ rowCount: x.results.length, truncated: false, elapsedMs: x.totalMicros / 1000 }),
      render: (x) => transcript(x.results),
      statusEl: $("status"),
      outputEl: $("output"),
      isStale: () => !alive || token !== runToken,
    });
    if (!alive || token !== runToken) return;
    $("run").disabled = false;
    const chip = $("status").querySelector(".chip");
    if (chip && r) chip.innerHTML = `執行了 <b>${r.results.length}</b> 個指令`;
  }

  $("run").addEventListener("click", run);
  onCtrlEnter($("cmd"), run);
  return () => { alive = false; };
}
