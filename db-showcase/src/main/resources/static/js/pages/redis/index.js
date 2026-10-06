// Redis 頁面外框：資料總覽（可收合的 key 清單）＋ 四個分頁
import { api, postJson, fmt, esc, pageHead, store } from "../../lib.js";

const TABS = [
  { id: "practice", label: "練習題", hint: "自己寫指令，自動批改", load: () => import("./practice.js") },
  { id: "traps", label: "陷阱題", hint: "先猜結果，再執行對照", load: () => import("./traps.js") },
  { id: "lab", label: "實戰實驗室", hint: "快取、限流、鎖、超賣、排行榜", load: () => import("./lab.js") },
  { id: "console", label: "指令主控台", hint: "直接下任何指令", load: () => import("./console.js") },
];
const TAB_KEY = "redis-tab";
const KEYS_KEY = "redis-keys-open";
export const CONSOLE_DRAFT_KEY = "redis-console-draft";

const TYPE_LABEL = {
  string: "String", hash: "Hash", list: "List", set: "Set", zset: "Sorted Set",
  stream: "Stream", bitmap: "Bitmap", hyperloglog: "HyperLogLog", geo: "Geo",
};

export function mount(el, db) {
  el.innerHTML = `
    <div class="page">
      <div style="display:grid;gap:16px">
        ${pageHead(db)}
        <p class="muted" style="margin:0;max-width:78ch">
          資料來自 PostgreSQL 的 shop 資料庫，啟動時用 Pipeline 轉成 9 種 Redis 資料結構。
          你輸入的指令一律用 ACL 受限的 <span class="mono">learner</span> 帳號執行（FLUSHALL、KEYS、CONFIG 都不能用）。
        </p>
        <div class="stats" data-ref="stats"><div class="stat"><b>…</b><span>讀取 Redis 中</span></div></div>
      </div>

      <details class="schema" data-ref="keys">
        <summary>
          <span class="chev" aria-hidden="true">›</span>
          <h2>資料結構 <small>db 0 裡有哪些 key、各是什麼型別</small></h2>
          <span class="toggle-hint" data-ref="hint"></span>
        </summary>
        <div class="key-table" data-ref="key-table"></div>
      </details>

      <section class="mode">
        <div class="tabs tabs-4" role="tablist" data-ref="tabs">
          ${TABS.map((t) => `
            <button type="button" role="tab" data-tab="${t.id}"><b>${esc(t.label)}</b><span>${esc(t.hint)}</span></button>`).join("")}
        </div>
        <div data-ref="tab-body" role="tabpanel"></div>
      </section>
    </div>`;

  const $ = (name) => el.querySelector(`[data-ref="${name}"]`);
  let alive = true;
  let unmountTab = null;
  let tabToken = 0;

  const keys = $("keys");
  keys.open = store.get(KEYS_KEY, true);
  const syncHint = () => { $("hint").textContent = keys.open ? "點擊收合" : "點擊展開"; };
  syncHint();
  keys.addEventListener("toggle", () => { syncHint(); store.set(KEYS_KEY, keys.open); });

  async function loadOverview() {
    try {
      const o = await api("/api/redis/overview");
      if (!alive) return;
      $("stats").innerHTML = `
        <div class="stat"><b>${fmt(o.keys)}</b><span>key 數量（DBSIZE）</span></div>
        <div class="stat"><b>${esc(o.memory)}</b><span>使用記憶體</span></div>
        <div class="stat"><b>${o.groups.length}</b><span>種 key</span></div>
        <div class="stat"><b>${o.loadSeconds.toFixed(2)} 秒</b><span>從 PostgreSQL 載入</span></div>
        <div class="stat stat-action">
          <button class="btn ghost" type="button" data-ref="reset">重置資料</button>
          <span>清空 db 0，重新從 PostgreSQL 載入（Redis ${esc(o.version)}）</span>
        </div>`;
      $("reset").addEventListener("click", reset);
      $("key-table").innerHTML = `
        <div class="result"><table>
          <thead><tr><th>Key</th><th>型別</th><th class="num">數量</th><th>用途</th><th>試試看</th></tr></thead>
          <tbody>${o.groups.map((g) => `
            <tr>
              <td class="mono">${esc(g.pattern)}</td>
              <td><span class="type-badge t-${esc(g.type)}">${esc(TYPE_LABEL[g.type] ?? g.type)}</span></td>
              <td class="num">${fmt(g.count)}</td>
              <td class="purpose">${esc(g.purpose)}</td>
              <td><button type="button" class="try mono" data-try="${esc(g.tryIt)}" title="放進指令主控台執行">${esc(g.tryIt)}</button></td>
            </tr>`).join("")}</tbody>
        </table></div>`;
    } catch (e) {
      if (!alive) return;
      $("stats").innerHTML = `<div class="error">讀不到 Redis：${esc(e.message)}</div>`;
    }
  }

  async function reset() {
    const btn = $("reset");
    btn.disabled = true;
    btn.textContent = "重新載入中…";
    try {
      await postJson("/api/redis/reset", {});
      await loadOverview();
    } catch (e) {
      btn.textContent = "失敗：" + e.message;
    }
  }

  // 「試試看」：把指令帶到主控台
  $("key-table").addEventListener("click", (e) => {
    const b = e.target.closest("[data-try]");
    if (!b) return;
    store.set(CONSOLE_DRAFT_KEY, b.dataset.try);
    openTab("console");
    el.querySelector(".mode").scrollIntoView({ behavior: "smooth" });
  });

  async function openTab(id) {
    const tab = TABS.find((t) => t.id === id) ?? TABS[0];
    store.set(TAB_KEY, tab.id);
    el.querySelectorAll("[data-tab]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === tab.id)));
    if (unmountTab) { unmountTab(); unmountTab = null; }
    const token = ++tabToken;
    $("tab-body").innerHTML = '<div class="empty">載入中…</div>';
    const mod = await tab.load();
    if (!alive || token !== tabToken) return;
    unmountTab = mod.mount($("tab-body")) ?? null;
  }

  $("tabs").addEventListener("click", (e) => {
    const b = e.target.closest("[data-tab]");
    if (b) openTab(b.dataset.tab);
  });

  loadOverview();
  openTab(store.get(TAB_KEY, "practice"));

  return () => { alive = false; if (unmountTab) unmountTab(); };
}
