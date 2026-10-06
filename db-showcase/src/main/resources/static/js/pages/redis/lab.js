// Redis 實戰場景實驗室：快取、限流、分散式鎖、庫存扣減、排行榜、Pipeline
import { api, esc, fmt } from "../../lib.js";

const API = "/api/redis/lab";
const post = (path) => api(API + path, { method: "POST" });

const CATEGORIES = [
  [2, "手機"], [3, "筆電"], [5, "無線耳機"], [6, "有線耳機"], [7, "手機配件"], [9, "廚房用品"], [10, "寢具"],
  [11, "收納"], [13, "男裝"], [14, "女裝"], [16, "運動鞋"], [17, "休閒鞋"], [19, "零食"], [20, "飲料"],
  [21, "生鮮"], [23, "保養"], [24, "彩妝"], [26, "健身器材"], [27, "露營用品"],
];

const SECTIONS = [
  { id: "cache", title: "Cache-Aside 快取", sub: "先查 Redis，沒有才查 PostgreSQL" },
  { id: "rate", title: "限流", sub: "每 10 秒最多 5 次請求" },
  { id: "lock", title: "分散式鎖", sub: "SET NX PX 與安全釋放" },
  { id: "stock", title: "庫存扣減", sub: "50 人搶 10 件，會不會超賣" },
  { id: "board", title: "排行榜", sub: "Sorted Set 即時排名" },
  { id: "pipe", title: "Pipeline", sub: "省掉網路來回的時間" },
];

const code = (s) => `<pre class="code">${esc(s.trim())}</pre>`;

export function mount(el) {
  el.innerHTML = `
    <div class="lab">
      <nav class="qlist" aria-label="實驗">
        <div class="topic">場景</div>
        ${SECTIONS.map((s, i) => `<button type="button" data-sec="${s.id}">${i + 1}. ${esc(s.title)}</button>`).join("")}
      </nav>
      <div class="panel" data-ref="panel"></div>
    </div>`;
  const panel = el.querySelector('[data-ref="panel"]');
  let alive = true;

  const RENDER = { cache, rate, lock, stock, board, pipe };
  function open(id) {
    el.querySelectorAll("[data-sec]").forEach((b) => b.classList.toggle("active", b.dataset.sec === id));
    const s = SECTIONS.find((x) => x.id === id);
    panel.innerHTML = `<div><span class="eyebrow">${esc(s.sub)}</span><h3>${esc(s.title)}</h3></div><div data-ref="body"></div>`;
    RENDER[id](panel.querySelector('[data-ref="body"]'));
  }
  el.querySelector("nav").addEventListener("click", (e) => {
    const b = e.target.closest("[data-sec]");
    if (b) open(b.dataset.sec);
  });

  /** 包一層：按鈕執行期間停用，錯誤顯示在指定位置。 */
  async function act(button, statusEl, fn) {
    button.disabled = true;
    try { await fn(); } catch (e) { if (alive) statusEl.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
    finally { button.disabled = false; }
  }

  // ------------------------------------------------------------ Cache-Aside
  function cache(body) {
    body.innerHTML = `
      <p class="desc">查詢「某分類的銷售報表」要 JOIN 三張表、彙總 20 萬筆明細。第一次從 PostgreSQL 算，
        之後 60 秒內直接讀 Redis。多按幾次「查詢」比較耗時。</p>
      <div class="lab-controls">
        <label>分類 <select data-ref="cat">${CATEGORIES.map(([id, n]) => `<option value="${id}">${esc(n)}</option>`).join("")}</select></label>
        <button class="btn" type="button" data-ref="q">查詢</button>
        <button class="btn ghost" type="button" data-ref="del">刪除快取（模擬資料更新）</button>
      </div>
      <div data-ref="status"></div>
      <div class="result"><table><thead><tr><th>#</th><th>分類</th><th>資料來源</th><th class="num">總耗時</th><th class="num">其中查資料庫</th><th class="num">快取剩餘 TTL</th></tr></thead>
        <tbody data-ref="log"><tr><td colspan="6" class="muted">還沒有查詢紀錄</td></tr></tbody></table></div>
      <details class="takeaway"><summary>看程式邏輯與解說</summary>
        ${code(`String key = "cache:category-report:" + id;
String cached = jedis.get(key);
if (cached != null) return parse(cached);          // 1. 命中：直接回傳
Report data = queryPostgres(id);                    // 2. 沒命中：查資料庫
jedis.set(key, toJson(data), SetParams.setParams().ex(60));   // 3. 寫回快取，一定要設過期時間
return data;`)}
        <div class="explain">★ Cache-Aside（旁路快取）是最常見的快取模式：讀的時候「先查快取、沒有再查資料庫並回填」。
更新資料時「先更新資料庫，再刪除快取」（不是更新快取），下一次讀取自然會回填最新的值。
為什麼是刪除而不是更新：兩個請求同時更新時，寫快取的順序可能跟寫資料庫的順序相反，快取就留下舊值。

面試三大快取問題：
・快取穿透：查詢「根本不存在」的資料，每次都打到資料庫 → 把空結果也快取起來（下方示範），或用布隆過濾器先擋。
・快取擊穿：一個熱門 key 剛好過期，大量請求同時去查資料庫 → 互斥鎖（只讓一個請求回填），或熱門資料不設過期、背景更新。
・快取雪崩：大量 key 在同一時間過期，或 Redis 整台掛掉 → 過期時間加上隨機值、多層快取、限流與降級。</div>
      </details>
      <h4 class="sub-head">快取穿透：查一個不存在的分類（id 999）</h4>
      <div class="lab-controls">
        <label class="check"><input type="checkbox" data-ref="nullcache"> 把「查不到」也快取 15 秒</label>
        <button class="btn ghost" type="button" data-ref="miss">查 3 次不存在的分類</button>
      </div>
      <div data-ref="miss-out"></div>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    let n = 0;
    const rows = [];
    $("q").addEventListener("click", () => act($("q"), $("status"), async () => {
      const id = $("cat").value;
      const r = await post(`/cache/query?categoryId=${id}`);
      if (!alive) return;
      rows.unshift(`<tr class="${r.source === "cache" ? "row-hit" : "row-miss"}"><td class="num">${++n}</td><td>${esc(r.data?.category ?? "")}</td>
        <td>${r.source === "cache" ? '<span class="type-badge t-hit">Redis 快取命中</span>' : '<span class="type-badge t-miss">查 PostgreSQL</span>'}</td>
        <td class="num"><b>${r.totalMs.toFixed(2)} ms</b></td><td class="num">${r.dbMs == null ? "—" : r.dbMs.toFixed(2) + " ms"}</td>
        <td class="num">${r.ttl} 秒</td></tr>`);
      $("log").innerHTML = rows.slice(0, 12).join("");
      $("status").innerHTML = r.data ? `<p class="muted">${esc(r.data.category)}：${fmt(r.data.products)} 件商品，已送達營收 ${fmt(r.data.revenue)} 元。
        第一名：${esc(r.data.top3[0]?.name ?? "")}</p>` : "";
    }));
    $("del").addEventListener("click", () => act($("del"), $("status"), async () => {
      const r = await post(`/cache/invalidate?categoryId=${$("cat").value}`);
      if (alive) $("status").innerHTML = `<p class="muted">DEL cache:category-report:${$("cat").value} → 刪除了 ${r.deleted} 個 key。下次查詢會重新查資料庫。</p>`;
    }));
    $("miss").addEventListener("click", () => act($("miss"), $("miss-out"), async () => {
      await post("/cache/invalidate?categoryId=999");
      const flag = $("nullcache").checked;
      const out = [];
      for (let i = 0; i < 3; i++) out.push(await post(`/cache/query?categoryId=999&cacheNull=${flag}`));
      if (!alive) return;
      $("miss-out").innerHTML = `<div class="squares">${out.map((r, i) => `
        <span class="square ${r.source === "db" ? "bad" : "ok"}">第 ${i + 1} 次<br><b>${r.source === "db" ? "打到資料庫" : "被快取擋下"}</b><br>${r.totalMs.toFixed(1)} ms</span>`).join("")}</div>
        <p class="muted">${flag ? "空結果被快取了，只有第一次打到資料庫。" : "每一次都打到資料庫：有人故意用大量不存在的 id 查詢，資料庫就會被打垮。"}</p>`;
    }));
  }

  // ------------------------------------------------------------ 限流
  function rate(body) {
    body.innerHTML = `
      <p class="desc">固定視窗限流：同一個使用者每 10 秒最多 5 次。用 INCR 計數，第一次時設定 10 秒過期，視窗結束就自動歸零。</p>
      <div class="lab-controls">
        <button class="btn" type="button" data-ref="one">送出 1 次請求</button>
        <button class="btn ghost" type="button" data-ref="burst">連續送 8 次</button>
        <button class="btn ghost" type="button" data-ref="reset">重置計數</button>
      </div>
      <div data-ref="status"></div>
      <div class="squares" data-ref="out"></div>
      <details class="takeaway"><summary>看 Lua 腳本與解說</summary>
        ${code(`-- KEYS[1] = lab:ratelimit:demo-user，ARGV[1] = 視窗秒數
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return {count, redis.call('TTL', KEYS[1])}`)}
        <div class="explain">INCR 和 EXPIRE 要放在同一個 Lua 腳本裡原子執行：分成兩個指令的話，INCR 之後程式當掉，這個 key 就永遠不會過期，使用者被永久封鎖。
Lua 腳本在 Redis 裡是一次執行完的，中間不會插入其他指令。

固定視窗的缺點：在第 9.9 秒送 5 次、第 10.1 秒再送 5 次，0.2 秒內就送了 10 次。
更精確的做法是滑動視窗（用 Sorted Set 記每次請求的時間，ZREMRANGEBYSCORE 刪掉視窗外的，再 ZCARD 計數），或令牌桶（Token Bucket）。
Spring Cloud Gateway 的 RequestRateLimiter 就是用 Redis + Lua 實作令牌桶。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const all = [];
    const draw = (rs) => {
      all.push(...rs);
      $("out").innerHTML = all.slice(-24).map((r) => `
        <span class="square ${r.allowed ? "ok" : "bad"}">#${r.request}<br><b>${r.allowed ? "通過" : "429 拒絕"}</b><br>第 ${r.count} 次 · 剩 ${r.ttl}s</span>`).join("");
    };
    $("one").addEventListener("click", () => act($("one"), $("status"), async () => { const r = await post("/rate-limit?requests=1"); if (alive) draw(r); }));
    $("burst").addEventListener("click", () => act($("burst"), $("status"), async () => { const r = await post("/rate-limit?requests=8"); if (alive) draw(r); }));
    $("reset").addEventListener("click", () => act($("reset"), $("status"), async () => { await post("/rate-limit/reset"); all.length = 0; if (alive) $("out").innerHTML = ""; }));
  }

  // ------------------------------------------------------------ 分散式鎖
  function lock(body) {
    body.innerHTML = `
      <p class="desc">多台伺服器（或多個執行緒）要確保同一時間只有一個在處理同一筆訂單時，就需要分散式鎖。</p>
      <h4 class="sub-head">1. 同時搶同一把鎖</h4>
      <div class="lab-controls">
        <label>worker 數量 <select data-ref="n"><option>3</option><option selected>6</option><option>10</option><option>20</option></select></label>
        <button class="btn" type="button" data-ref="go">同時搶鎖</button>
      </div>
      <div data-ref="contend"></div>
      ${code(`SET lab:lock:order:1 worker-3 NX PX 2000    -- NX：不存在才寫入；PX：毫秒後自動過期`)}
      <h4 class="sub-head">2. 鎖過期之後，刪到別人的鎖</h4>
      <p class="desc">A 拿到鎖（300 ms 過期），但工作做了 500 ms。鎖過期後 B 拿到了鎖，接著 A 做完要釋放鎖…</p>
      <div class="lab-controls">
        <button class="btn ghost" type="button" data-ref="unsafe">用 DEL 釋放（錯誤示範）</button>
        <button class="btn" type="button" data-ref="safe">用 Lua 比對後釋放</button>
      </div>
      <div data-ref="timeline"></div>
      <details class="takeaway"><summary>看安全釋放的 Lua 與解說</summary>
        ${code(`-- 只有 value 還是自己的 token 時才刪除
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0`)}
        <div class="explain">★ 分散式鎖的三個重點：
1. 加鎖要用 SET key token NX PX，一個指令同時完成「不存在才寫入」和「設定過期時間」。過期時間是保險：拿鎖的程式當掉，鎖也會自動釋放。
2. value 要放每個請求獨有的 token（UUID），釋放時先比對，確認是自己的鎖才刪。GET 和 DEL 分成兩個指令會有空隙，所以要用 Lua 包成原子操作。
3. 工作時間可能超過鎖的過期時間：Redisson 的「看門狗」會在拿著鎖的期間每 10 秒自動延長過期時間。

實務上 Java 直接用 Redisson 的 RLock，上面這些它都處理好了，還支援可重入、公平鎖。
Redis 主從切換時鎖可能遺失；需要更強保證時用 RedLock，或改用 ZooKeeper、etcd。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    $("go").addEventListener("click", () => act($("go"), $("contend"), async () => {
      const r = await post(`/lock/contend?workers=${$("n").value}`);
      if (!alive) return;
      $("contend").innerHTML = `<div class="squares">${r.map((a) => `
        <span class="square ${a.acquired ? "ok" : "muted-sq"}">${esc(a.worker)}<br><b>${a.acquired ? "拿到鎖" : "nil"}</b><br>${a.atMs.toFixed(1)} ms</span>`).join("")}</div>
        <p class="muted">${r.length} 個 worker 幾乎同時送出，只有 ${r.filter((a) => a.acquired).length} 個拿到鎖。Redis 一次只執行一個指令，所以 NX 判斷不會有競態問題。</p>`;
    }));
    const timeline = (safe) => act($(safe ? "safe" : "unsafe"), $("timeline"), async () => {
      $("timeline").innerHTML = '<p class="muted">執行中（約 0.6 秒）…</p>';
      const r = await post(`/lock/release?safe=${safe}`);
      if (!alive) return;
      $("timeline").innerHTML = `<div class="result"><table><thead><tr><th class="num">時間</th><th>誰</th><th>動作</th><th>結果</th></tr></thead><tbody>
        ${r.map((e) => `<tr class="${e.actor === "結果" ? (safe ? "row-hit" : "row-miss") : ""}"><td class="num">${e.atMs.toFixed(0)} ms</td><td><b>${esc(e.actor)}</b></td>
          <td class="mono" style="white-space:normal">${esc(e.action)}</td><td>${esc(e.result)}</td></tr>`).join("")}
      </tbody></table></div>`;
    });
    $("unsafe").addEventListener("click", () => timeline(false));
    $("safe").addEventListener("click", () => timeline(true));
  }

  // ------------------------------------------------------------ 庫存扣減
  function stock(body) {
    body.innerHTML = `
      <p class="desc">限量 10 件，50 個人同時搶購。三種寫法分別執行一次，看看最後賣出幾件。</p>
      <div class="lab-controls">
        <button class="btn ghost" type="button" data-mode="naive">① 先 GET 再 SET</button>
        <button class="btn ghost" type="button" data-mode="decr">② DECR 原子扣減</button>
        <button class="btn" type="button" data-mode="lua">③ Lua 判斷後扣減</button>
      </div>
      <div data-ref="status"></div>
      <div class="result"><table><thead><tr><th>寫法</th><th class="num">庫存</th><th class="num">搶購人數</th><th class="num">賣出</th><th class="num">最後庫存</th><th class="num">超賣</th><th class="num">耗時</th></tr></thead>
        <tbody data-ref="log"><tr><td colspan="7" class="muted">按上面的按鈕執行</td></tr></tbody></table></div>
      <div class="sql-pair">
        <div class="sql-card"><div class="sql-label">① 先 GET 再 SET（錯誤）</div>${code(`int stock = Integer.parseInt(jedis.get(key));
if (stock > 0) {
    // ...中間的商業邏輯
    jedis.set(key, String.valueOf(stock - 1));
    return true;
}`)}</div>
        <div class="sql-card"><div class="sql-label">③ Lua：讀、判斷、扣在同一個原子操作</div>${code(`local stock = tonumber(redis.call('GET', KEYS[1]))
if stock > 0 then
    redis.call('DECR', KEYS[1])
    return 1
end
return 0`)}</div>
      </div>
      <details class="takeaway"><summary>看解說</summary>
        <div class="explain">① 是典型的「讀取 → 修改 → 寫回」競態：好幾個人同時讀到「庫存 10」，各自扣 1 再寫回 9，結果賣出的比庫存多，而且庫存數字也是錯的（更新遺失）。
② DECR 是原子操作，回傳值小於 0 代表賣完了，再 INCR 加回去。不會超賣，但庫存會短暫出現負數。
③ Lua 把「讀取、判斷、扣減」包成一個原子操作，最乾淨。

其他做法：WATCH + MULTI/EXEC（樂觀鎖，衝突時重試）、分散式鎖（較慢）。
實務上秒殺系統還會搭配：Redis 先扣庫存、成功的請求丟進訊息佇列，再非同步寫入資料庫建立訂單。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const rows = [];
    const NAMES = { naive: "① GET 再 SET", decr: "② DECR", lua: "③ Lua" };
    body.querySelectorAll("[data-mode]").forEach((b) => b.addEventListener("click", () => act(b, $("status"), async () => {
      const r = await post(`/stock?mode=${b.dataset.mode}&stock=10&buyers=50`);
      if (!alive) return;
      rows.unshift(`<tr class="${r.oversold > 0 ? "row-miss" : "row-hit"}"><td><b>${NAMES[r.mode]}</b></td><td class="num">${r.initial}</td><td class="num">${r.buyers}</td>
        <td class="num"><b>${r.sold}</b></td><td class="num">${r.finalStock}</td><td class="num">${r.oversold > 0 ? `<b class="bad-text">${r.oversold}</b>` : "0"}</td><td class="num">${r.ms.toFixed(0)} ms</td></tr>`);
      $("log").innerHTML = rows.slice(0, 9).join("");
    })));
  }

  // ------------------------------------------------------------ 排行榜
  function board(body) {
    body.innerHTML = `
      <p class="desc">leaderboard:customers:spend 是會員累計消費排行（約 1.8 萬人）。查任何一位會員的名次與前後名，都是 O(log N)。</p>
      <div class="lab-controls">
        <label>會員 id <input data-ref="cid" type="number" min="1" max="20000" value="500" style="width:7em"></label>
        <button class="btn" type="button" data-ref="q">查名次</button>
        <button class="btn ghost" type="button" data-ref="sim">模擬 1000 筆新訂單</button>
      </div>
      <div data-ref="status"></div>
      <div class="sql-pair" data-ref="out"></div>
      <details class="takeaway"><summary>看用到的指令與解說</summary>
        ${code(`ZRANGE leaderboard:customers:spend 0 9 REV WITHSCORES     -- 前 10 名
ZREVRANK leaderboard:customers:spend 500                  -- 名次（從 0 開始）
ZSCORE   leaderboard:customers:spend 500                  -- 分數
ZRANGE leaderboard:customers:spend 2656 2660 REV WITHSCORES   -- 前後各 2 名
ZINCRBY  leaderboard:customers:spend 1200 500             -- 下單後加分`)}
        <div class="explain">用 SQL 查「我排第幾名」要先算出每個人的總額再排序，資料一多就很慢；Sorted Set 隨時都是排好的（底層是跳表 skiplist + 雜湊表）。
「模擬訂單」用 Pipeline 一次送出 1000 個 ZINCRBY，再查一次就能看到名次變化。
常見延伸：每日 / 每週排行榜用不同的 key（leaderboard:2026-10-06），用 ZUNIONSTORE 合併成週榜。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    const row = (e, me) => `<tr class="${me && e.id === me.id ? "row-hit" : ""}"><td class="num">${fmt(e.rank)}</td><td>${esc(e.name ?? "")}</td><td class="num mono">${esc(e.id)}</td><td class="num">${fmt(e.score)}</td></tr>`;
    const table = (title, list, me) => `<div class="sql-card"><div class="sql-label">${title}</div><div class="result"><table>
      <thead><tr><th class="num">名次</th><th>會員</th><th class="num">id</th><th class="num">累計消費</th></tr></thead>
      <tbody>${list.map((e) => row(e, me)).join("")}</tbody></table></div></div>`;
    async function query() {
      const b = await api(`${API}/leaderboard?customerId=${encodeURIComponent($("cid").value)}`);
      if (!alive) return;
      $("status").innerHTML = b.me
        ? `<p>會員 <b>${esc(b.me.name)}</b>（id ${esc(b.me.id)}）排第 <b>${fmt(b.me.rank)}</b> 名，累計消費 ${fmt(b.me.score)} 元。<span class="muted">（查詢 ${b.ms.toFixed(2)} ms）</span></p>`
        : '<p class="muted">這位會員不在排行榜上（沒有已送達的訂單）。</p>';
      $("out").innerHTML = table("前 10 名", b.top, b.me) + (b.me ? table("前後各 2 名", b.around, b.me) : "");
    }
    $("q").addEventListener("click", () => act($("q"), $("status"), query));
    $("sim").addEventListener("click", () => act($("sim"), $("status"), async () => {
      const r = await post("/leaderboard/simulate?orders=1000");
      await query();
      if (alive) $("status").insertAdjacentHTML("beforeend", `<p class="muted">已用 Pipeline 送出 1000 個 ZINCRBY（${r.ms.toFixed(1)} ms）。按上方「重置資料」可還原。</p>`);
    }));
    query();
  }

  // ------------------------------------------------------------ Pipeline
  function pipe(body) {
    body.innerHTML = `
      <p class="desc">寫入 N 個 key：一個一個送、用 Pipeline 一次送出、用 MSET 一個指令。Redis 執行每個 SET 只要幾微秒，時間幾乎都花在網路來回（RTT）。</p>
      <div class="lab-controls">
        <label>N = <select data-ref="n"><option>100</option><option selected>1000</option><option>5000</option></select></label>
        <button class="btn" type="button" data-ref="go">執行比較</button>
      </div>
      <div data-ref="out"></div>
      <details class="takeaway"><summary>看程式與解說</summary>
        ${code(`// 一個一個送：N 次網路來回
for (...) jedis.set(key, value);

// Pipeline：全部送出，最後一次收回結果
Pipeline p = jedis.pipelined();
for (...) p.set(key, value);
p.sync();

// MSET：一個指令
jedis.mset(k1, v1, k2, v2, ...);`)}
        <div class="explain">每個指令都要等「送出 → Redis 執行 → 回傳」一次來回。本機 Docker 一次來回約 0.3 ~ 1 ms，跨機房可能 1 ~ 2 ms，1000 個指令就是 1 ~ 2 秒。
Pipeline 不等回應、連續送出，所以只花一次左右的來回時間。
注意：Pipeline 不是交易，中間其他客戶端的指令可能插進來；需要原子性用 MULTI/EXEC 或 Lua。一次也不要塞太多（例如幾十萬個），會佔用大量記憶體，建議分批。
Spring Data Redis 對應的是 redisTemplate.executePipelined(...)。</div>
      </details>`;
    const $ = (n) => body.querySelector(`[data-ref="${n}"]`);
    $("go").addEventListener("click", () => act($("go"), $("out"), async () => {
      $("out").innerHTML = '<p class="muted">執行中…</p>';
      const r = await post(`/pipeline?n=${$("n").value}`);
      if (!alive) return;
      const max = Math.max(r.loopMs, r.pipelineMs, r.msetMs);
      const bar = (label, ms, cls) => `<div class="bar-row"><span>${label}</span>
        <span class="bar-track"><i class="${cls}" style="width:${Math.max(0.6, (ms / max) * 100)}%"></i></span><b>${ms.toFixed(1)} ms</b></div>`;
      $("out").innerHTML = `<div class="bars">
        ${bar(`一個一個送（${fmt(r.n)} 次來回）`, r.loopMs, "slow")}
        ${bar("Pipeline", r.pipelineMs, "fast")}
        ${bar("MSET", r.msetMs, "fast")}</div>
        <p class="muted">Pipeline 比一個一個送快了 <b>${(r.loopMs / r.pipelineMs).toFixed(0)}</b> 倍。</p>`;
    }));
  }

  open("cache");
  return () => { alive = false; };
}
