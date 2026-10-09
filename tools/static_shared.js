// 兩份靜態頁共用：SQL 上色、複製按鈕、HTML 跳脫
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

const SQL_KEYWORDS = new Set(`
  select from where group by having order limit offset join left right full inner outer cross on using as and or not
  in is null exists distinct union all intersect except case when then else end with recursive insert into values
  update set delete returning conflict do nothing create index unique table drop alter add column primary key
  references foreign check default constraint cascade begin commit rollback over partition window rows range
  between preceding following current row asc desc nulls first last filter lateral explain analyze true false
  interval date timestamp timestamptz int integer bigint numeric text boolean jsonb serial unbound include brin
  gin btree hash concurrently if only like ilike any array for share zone at time cast varchar char smallint
  identity generated always schema role grant usage to owner vacuum`.trim().split(/\s+/));

// CQL：SQL 的關鍵字再加上 Cassandra 特有的
const CQL_KEYWORDS = new Set([...SQL_KEYWORDS, ...`
  keyspace keyspaces allow filtering ttl writetime token clustering frozen counter batch apply logged unlogged
  static list map type contains key per describe consistency tracing materialized view custom timeuuid uuid
  double float decimal varint tinyint blob inet replication quorum serial truncate expand copy`.trim().split(/\s+/)]);

const SQL_TOKEN = /(--[^\n]*)|(\/\*[\s\S]*?\*\/)|('(?:[^']|'')*')|("(?:[^"]|"")*")|(->>|->|#>>|#>|@>|<@|&&|\|\||::|\?\||\?&)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)/g;

function highlightSQL(text, keywords = SQL_KEYWORDS) {
  let out = "", last = 0, m;
  SQL_TOKEN.lastIndex = 0;
  while ((m = SQL_TOKEN.exec(text))) {
    out += esc(text.slice(last, m.index));
    const [tok, cmt1, cmt2, str, ident, op, num, word] = m;
    if (cmt1 || cmt2) out += `<span class="hl-cmt">${esc(tok)}</span>`;
    else if (str) out += `<span class="hl-str">${esc(tok)}</span>`;
    else if (ident) out += esc(tok);
    else if (op) out += `<span class="hl-op">${esc(tok)}</span>`;
    else if (num) out += `<span class="hl-num">${esc(tok)}</span>`;
    else if (word) {
      const isCall = /^\s*\(/.test(text.slice(m.index + tok.length));
      if (keywords.has(word.toLowerCase()) && !(isCall && !/^(in|exists|any|values|over|filter|on|using|as|array|interval)$/i.test(word))) {
        out += `<span class="hl-kw">${esc(tok)}</span>`;
      } else if (isCall) out += `<span class="hl-fn">${esc(tok)}</span>`;
      else out += esc(tok);
    }
    last = m.index + tok.length;
  }
  return out + esc(text.slice(last));
}

/** Redis 指令：每行第一個字是指令（上色），-- 之後是註解。 */
function highlightRedis(text) {
  return text.split("\n").map((line) => {
    const c = line.indexOf("--");
    const body = c >= 0 ? line.slice(0, c) : line;
    const comment = c >= 0 ? `<span class="hl-cmt">${esc(line.slice(c))}</span>` : "";
    const html = esc(body)
      .replace(/^(\s*)([A-Za-z][A-Za-z.]*)/, '$1<span class="hl-kw">$2</span>')
      .replace(/(&quot;[^&]*&quot;|'[^']*')/g, '<span class="hl-str">$1</span>')
      .replace(/(\s)(-?\d+(?:\.\d+)?)(?=\s|$)/g, '$1<span class="hl-num">$2</span>');
    return html + comment;
  }).join("\n");
}

/** mongosh：字串、數字、$運算子、// 註解、db.集合.方法。 */
function highlightMongo(text) {
  const TOKEN = /(\/\/[^\n]*)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")|(\$[A-Za-z]+)|(\b(?:db|ISODate|ObjectId|NumberDecimal|true|false|null)\b)|(\b\d+(?:\.\d+)?\b)|(\.[a-zA-Z]+(?=\())/g;
  let out = "", last = 0, m;
  while ((m = TOKEN.exec(text))) {
    out += esc(text.slice(last, m.index));
    const [tok, cmt, str, op, word, num, method] = m;
    const cls = cmt ? "hl-cmt" : str ? "hl-str" : op ? "hl-op" : word ? "hl-kw" : num ? "hl-num" : method ? "hl-fn" : "";
    out += `<span class="${cls}">${esc(tok)}</span>`;
    last = m.index + tok.length;
  }
  return out + esc(text.slice(last));
}

/** Cypher：關鍵字、// 註解、字串、:標籤 / :關係型別、$參數。 */
const CYPHER_KEYWORDS = new Set(`
  match optional where return with order by skip limit create merge set remove delete detach on unwind as and or not
  xor in is null exists count call yield distinct case when then else end asc desc union all profile explain index
  constraint for require unique drop if show indexes constraints transactions true false starts ends contains
  shortestpath allshortestpaths collect`.trim().split(/\s+/));

function highlightCypher(text) {
  const TOKEN = /(\/\/[^\n]*)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")|(:[A-Za-z_][A-Za-z0-9_]*)|(\$[A-Za-z_][A-Za-z0-9_]*)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)/g;
  let out = "", last = 0, m;
  while ((m = TOKEN.exec(text))) {
    out += esc(text.slice(last, m.index));
    const [tok, cmt, str, label, param, num, word] = m;
    if (cmt) out += `<span class="hl-cmt">${esc(tok)}</span>`;
    else if (str) out += `<span class="hl-str">${esc(tok)}</span>`;
    else if (label) out += `<span class="hl-fn">${esc(tok)}</span>`;
    else if (param) out += `<span class="hl-op">${esc(tok)}</span>`;
    else if (num) out += `<span class="hl-num">${esc(tok)}</span>`;
    else if (CYPHER_KEYWORDS.has(word.toLowerCase())) out += `<span class="hl-kw">${esc(tok)}</span>`;
    else out += esc(tok);
    last = m.index + tok.length;
  }
  return out + esc(text.slice(last));
}

/** Elasticsearch（Kibana Dev Tools 寫法）：方法 + 路徑、# 註解、JSON 的 key / 字串 / 數字。 */
function highlightES(text) {
  const TOKEN = /^(\s*)(GET|POST|PUT|DELETE|HEAD)(\s+)(\S+)|(^\s*#[^\n]*)|("(?:[^"\\]|\\.)*")(\s*:)?|(\b(?:true|false|null)\b)|(-?\b\d+(?:\.\d+)?\b)/gm;
  let out = "", last = 0, m;
  while ((m = TOKEN.exec(text))) {
    out += esc(text.slice(last, m.index));
    const [tok, indent, method, gap, path, cmt, str, colon, lit, num] = m;
    if (method) out += `${esc(indent)}<span class="hl-kw">${method}</span>${esc(gap)}<span class="hl-fn">${esc(path)}</span>`;
    else if (cmt) out += `<span class="hl-cmt">${esc(tok)}</span>`;
    else if (str) out += colon ? `<span class="hl-op">${esc(str)}</span>${esc(colon)}` : `<span class="hl-str">${esc(str)}</span>`;
    else if (lit) out += `<span class="hl-kw">${esc(tok)}</span>`;
    else if (num) out += `<span class="hl-num">${esc(tok)}</span>`;
    last = m.index + tok.length;
  }
  return out + esc(text.slice(last));
}

/** 把 root 底下的 pre.code 上色並加上「複製」按鈕。 */
function enhanceCode(root) {
  root.querySelectorAll("pre.code").forEach((pre) => {
    if (pre.dataset.ready) return;
    pre.dataset.ready = "1";
    const code = pre.querySelector("code") ?? pre;
    const raw = code.textContent;
    if (pre.dataset.lang === "redis") code.innerHTML = highlightRedis(raw);
    else if (pre.dataset.lang === "mongo") code.innerHTML = highlightMongo(raw);
    else if (pre.dataset.lang === "cql") code.innerHTML = highlightSQL(raw, CQL_KEYWORDS);
    else if (pre.dataset.lang === "cypher") code.innerHTML = highlightCypher(raw);
    else if (pre.dataset.lang === "es") code.innerHTML = highlightES(raw);
    else if (pre.dataset.lang === "sql" || pre.dataset.lang === undefined && !pre.closest(".doc")) code.innerHTML = highlightSQL(raw);
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "copy";
    btn.textContent = "複製";
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(raw);
        btn.textContent = "已複製";
      } catch {
        const sel = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(code);
        sel.removeAllRanges();
        sel.addRange(range);
        btn.textContent = "已選取，按 Ctrl+C";
      }
      setTimeout(() => (btn.textContent = "複製"), 1600);
    });
    pre.appendChild(btn);
  });
}

const store = {
  get(key, fallback) { try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 無法儲存就算了 */ } },
};
