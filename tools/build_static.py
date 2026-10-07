"""
產生兩份可以直接雙擊打開的靜態 HTML：
  docs/cheatsheet.html      ← CHEATSHEET.md
  docs/question-bank.html   ← db-showcase/src/main/resources/{postgres,redis,mongo,cassandra}/*.yml
                               （展示台有在執行時，會順便把「正確答案的執行結果」嵌進去）

用法：python tools/build_static.py
"""
import html
import json
import re
import sys
import urllib.request
from datetime import date
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs"


# ---------------------------------------------------------------- Markdown

def inline(text: str) -> str:
    """行內語法：`code`、**粗體**、★。先跳脫 HTML，再處理標記。"""
    parts = re.split(r"(`[^`]+`)", text.replace("\\|", "|"))
    out = []
    for p in parts:
        if p.startswith("`") and p.endswith("`") and len(p) > 1:
            out.append(f"<code>{html.escape(p[1:-1])}</code>")
        else:
            e = html.escape(p)
            e = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", e)
            out.append(e)
    return "".join(out).replace("★", '<span class="star" title="面試高頻">★</span>')


def split_row(line: str) -> list[str]:
    cells = re.split(r"(?<!\\)\|", line.strip().strip("|"))
    return [c.strip() for c in cells]


def markdown_to_html(md: str) -> tuple[str, list[dict]]:
    """只支援 CHEATSHEET 用到的語法。回傳 (HTML, 目錄)。"""
    lines = md.splitlines()
    out, toc, para = [], [], []
    in_section = False
    seen_title = False      # 第一個 # 是頁面標題；之後的 # 是大章節（每個資料庫一章）
    part = ""
    i = 0

    def flush_para():
        if para:
            out.append(f"<p>{inline(' '.join(para))}</p>")
            para.clear()

    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            flush_para()
            lang = line[3:].strip() or "text"
            code = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                code.append(lines[i])
                i += 1
            out.append(f'<pre class="code" data-lang="{lang}"><code>{html.escape(chr(10).join(code))}</code></pre>')
        elif line.startswith("### "):
            flush_para()
            title = line[4:].strip()
            sid = f"s{len(toc)}"
            toc.append({"level": 3, "id": sid, "title": title, "part": part})
            out.append(f'<h3 id="{sid}">{inline(title)}</h3>')
        elif line.startswith("## "):
            flush_para()
            if in_section:
                out.append("</section>")
            title = line[3:].strip()
            sid = f"s{len(toc)}"
            toc.append({"level": 2, "id": sid, "title": title, "part": part})
            out.append(f'<section class="sec" id="{sid}" data-part="{part}"><h2>{inline(title)}</h2>')
            in_section = True
        elif line.startswith("# "):
            flush_para()
            if not seen_title:            # 頁面標題由外框負責
                seen_title = True
            else:
                if in_section:
                    out.append("</section>")
                title = line[2:].strip()
                part = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-") or f"s{len(toc)}"   # 例如 #redis
                toc.append({"level": 1, "id": part, "title": title, "part": part})
                out.append(f'<section class="part-head" id="{part}" data-part="{part}"><h1>{inline(title)}</h1>')
                in_section = True
        elif line.strip() == "---":
            flush_para()
        elif line.startswith("|"):
            flush_para()
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            i -= 1
            head, body = rows[0], [r for r in rows[2:]]
            aligns = ["right" if a.endswith(":") and not a.startswith(":") else "" for a in rows[1]]
            th = "".join(f"<th>{inline(c)}</th>" for c in head)
            trs = "".join(
                "<tr>" + "".join(
                    f'<td{" class=num" if aligns[k:k+1] == ["right"] else ""}>{inline(c)}</td>'
                    for k, c in enumerate(r)) + "</tr>"
                for r in body)
            out.append(f'<div class="table-wrap"><table><thead><tr>{th}</tr></thead><tbody>{trs}</tbody></table></div>')
        elif line.startswith("- "):
            flush_para()
            items = []
            while i < len(lines) and lines[i].startswith("- "):
                items.append(f"<li>{inline(lines[i][2:])}</li>")
                i += 1
            i -= 1
            out.append(f"<ul>{''.join(items)}</ul>")
        elif line.startswith("> "):
            flush_para()
            out.append(f'<blockquote>{inline(line[2:])}</blockquote>')
        elif not line.strip():
            flush_para()
        else:
            para.append(line.strip())
        i += 1
    flush_para()
    if in_section:
        out.append("</section>")
    return "\n".join(out), toc


# ---------------------------------------------------------------- 頁面

FONTS = ('<link rel="preconnect" href="https://fonts.googleapis.com">'
         '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
         '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600'
         '&family=Noto+Sans+TC:wght@400;500;700&display=swap">')


def page(title: str, body: str, extra_css: str, script: str) -> str:
    shared_css = (Path(__file__).parent / "static_shared.css").read_text(encoding="utf-8")
    shared_js = (Path(__file__).parent / "static_shared.js").read_text(encoding="utf-8")
    return f"""<!doctype html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
{FONTS}
<style>
{shared_css}
{extra_css}
</style>
</head>
<body>
{body}
<script>
{shared_js}
{script}
</script>
</body>
</html>
"""


def build_cheatsheet():
    md = (ROOT / "CHEATSHEET.md").read_text(encoding="utf-8")
    content, toc = markdown_to_html(md)
    toc_html = "".join(
        f'<a class="toc-{t["level"]}" href="#{t["id"]}" data-part="{t["part"]}">{inline(t["title"])}</a>'
        for t in toc if t["level"] > 1)
    parts = [t for t in toc if t["level"] == 1]
    tabs_html = "".join(
        f'<a href="#{t["id"]}" data-tab="{t["id"]}">{html.escape(t["title"])}<span class="n" data-count="{t["id"]}"></span></a>'
        for t in parts)
    body = f"""
<header class="top">
  <div class="top-in">
    <div class="title-block"><h1 class="page-title">CheatSheet</h1>
      <span class="sub">資料庫面試速查表 · {len(parts)} 種資料庫 · 更新於 {date.today():%Y-%m-%d}</span></div>
    <label class="search"><span class="sr-only">搜尋</span>
      <input id="q" type="search" placeholder="搜尋所有資料庫，例如 JSONB、Lua、NULL…" autocomplete="off"></label>
  </div>
  <nav class="db-tabs" id="dbtabs" aria-label="資料庫">{tabs_html}</nav>
</header>
<div class="layout">
  <nav class="toc" id="toc" aria-label="目錄">{toc_html}</nav>
  <main class="doc" id="doc">
    <p class="hit-count" id="hits" hidden></p>
    {content}
  </main>
</div>"""
    css = ((Path(__file__).parent / "db_tabs.css").read_text(encoding="utf-8")
           + (Path(__file__).parent / "cheatsheet.css").read_text(encoding="utf-8"))
    js = (Path(__file__).parent / "cheatsheet.js").read_text(encoding="utf-8")
    (OUT / "cheatsheet.html").write_text(page("CheatSheet", body, css, js), encoding="utf-8")


# ---------------------------------------------------------------- 題庫：四種資料庫

RESOURCES = ROOT / "db-showcase" / "src" / "main" / "resources"
API_BASE = "http://localhost:8081/api"
RESULT_ROWS = 10          # 每個表格結果最多嵌入幾列
RESULT_DOCS = 3           # MongoDB 每個結果最多嵌入幾份文件
TEXT_LIMIT = 1800         # 文字結果最多幾個字

# 每種資料庫：題庫檔、API 路徑、送出答案時的欄位名稱、程式碼的語言（上色用）、展示台的頁面
DATABASES = [
    {"id": "postgresql", "name": "PostgreSQL", "dir": "postgres", "api": "postgres", "key": "sql", "lang": "sql",
     "lab": "index-lab.yml", "labName": "索引實驗",
     "labNote": "實驗對象是 perf schema 的兩張 200 萬筆大表：perf.orders_big（隨機順序寫入）、perf.order_events（依時間順序寫入）。"
                "每一步先不建索引跑一次 EXPLAIN ANALYZE，再建立索引重跑，比較執行計畫和時間。"},
    {"id": "redis", "name": "Redis", "dir": "redis", "api": "redis", "key": "commands", "lang": "redis",
     "lab": None, "labName": None, "labNote": None},
    {"id": "mongodb", "name": "MongoDB", "dir": "mongo", "api": "mongo", "key": "commands", "lang": "mongo",
     "lab": "index-lab.yml", "labName": "索引實驗",
     "labNote": "實驗對象是 shop.orders（8 萬份訂單）。每一步先看沒有索引時的 explain(\"executionStats\")，"
                "再建立建議的索引重跑，比較 COLLSCAN / IXSCAN、docsExamined 與 SORT。"},
    {"id": "cassandra", "name": "Cassandra", "dir": "cassandra", "api": "cassandra", "key": "commands", "lang": "cql",
     "lab": "query-lab.yml", "labName": "表設計實驗",
     "labNote": "每一句查詢都開啟查詢追蹤（TRACING），比較「讀取方式」（單一分區、範圍掃描、SAI 索引）與伺服器實際讀了幾列。"},
]


def api(path, data=None):
    req = urllib.request.Request(
        API_BASE + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={"Content-Type": "application/json"},
        method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def clip(text):
    return text if len(text) <= TEXT_LIMIT else text[:TEXT_LIMIT] + "\n…（以下省略）"


# ---------- 各種資料庫的結果，轉成共同的格式：[{cmd?, table? | text?, error?}] ----------

def pg_blocks(r):
    if not r:
        return []
    return [{"table": {"columns": r["columns"], "rows": r["rows"][:RESULT_ROWS], "total": r["rowCount"]}}]


def redis_reply(v, indent=""):
    if v is None:
        return "(nil)"
    if isinstance(v, bool):
        return "(integer) " + ("1" if v else "0")
    if isinstance(v, (int, float)):
        return f"(integer) {v}" if isinstance(v, int) else f'"{v}"'
    if isinstance(v, str):
        return f'"{v}"'
    if isinstance(v, dict) and list(v) == ["error"]:     # 錯誤回覆（也可能出現在 EXEC 的陣列裡）
        return f"(error) {v['error']}"
    if isinstance(v, dict):
        v = [x for kv in v.items() for x in kv]
    if isinstance(v, list):
        if not v:
            return "(empty array)"
        width = len(str(len(v)))
        lines = []
        for i, x in enumerate(v, 1):
            prefix = f"{str(i).rjust(width)}) "
            inner = redis_reply(x, indent + " " * len(prefix))
            lines.append((indent if i > 1 else "") + prefix + inner)
        return "\n".join(lines)
    return str(v)


def redis_error(reply):
    msg = reply.get("error") if isinstance(reply, dict) else reply
    return f"(error) {msg}"


def redis_blocks(results):
    return [{"cmd": c["command"], "text": clip(redis_error(c["reply"]) if c["error"] else redis_reply(c["reply"])),
             "error": c["error"]} for c in results]


IDENT = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")


def mongo_value(v, indent=""):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, str):
        return "'" + v + "'"
    if isinstance(v, list):
        if not v:
            return "[]"
        if all(not isinstance(x, (dict, list)) for x in v) and len(v) <= 8:
            return "[ " + ", ".join(mongo_value(x) for x in v) + " ]"
        inner = indent + "  "
        return "[\n" + ",\n".join(inner + mongo_value(x, inner) for x in v) + "\n" + indent + "]"
    if isinstance(v, dict):
        if list(v) == ["$oid"]:
            return f"ObjectId('{v['$oid']}')"
        if list(v) == ["$date"]:
            return f"ISODate('{v['$date']}')"
        if not v:
            return "{}"
        inner = indent + "  "
        return "{\n" + ",\n".join(
            f"{inner}{k if IDENT.match(k) else repr(k)}: {mongo_value(x, inner)}" for k, x in v.items()) + "\n" + indent + "}"
    return str(v)


def mongo_blocks(results):
    out = []
    for r in results:
        if r["kind"] == "error":
            out.append({"cmd": r["statement"], "text": str(r["value"]), "error": True})
        elif r["kind"] == "docs":
            docs = r["value"][:RESULT_DOCS]
            head = f"// 共 {r['total']:,} 份" + (f"，以下只列前 {len(docs)} 份" if r["total"] > len(docs) else "")
            out.append({"cmd": r["statement"], "text": clip("\n".join([head] + [mongo_value(d) for d in docs]))})
        else:
            out.append({"cmd": r["statement"], "text": clip(mongo_value(r["value"]))})
    return out


def cql_cell(v):
    if v is None or isinstance(v, (bool, int, float, str)):
        return v
    if isinstance(v, list):
        return "[" + ", ".join(f"'{x}'" if isinstance(x, str) else str(cql_cell(x)) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + ", ".join(f"{k}: " + (f"'{x}'" if isinstance(x, str) else str(cql_cell(x))) for k, x in v.items()) + "}"
    return str(v)


def cql_blocks(results):
    out = []
    for r in results:
        b = {"cmd": r["statement"]}
        if r["kind"] == "rows":
            b["table"] = {"columns": r["columns"], "rows": [[cql_cell(v) for v in row] for row in r["rows"][:RESULT_ROWS]],
                          "total": r["total"]}
        elif r["kind"] == "error":
            b["text"], b["error"] = r["message"], True
        elif r["kind"] == "info":
            b["text"] = r["message"]
        else:
            b["text"] = "完成（沒有回傳資料）"
        if r.get("warnings"):
            b["warn"] = r["warnings"]
        out.append(b)
    return out


def blocks(db, run):
    """把一次執行（PostgreSQL 是單一查詢結果，其他是多句指令的結果）轉成共同格式。"""
    if db["id"] == "postgresql":
        return pg_blocks(run)
    results = run["results"]
    return {"redis": redis_blocks, "mongodb": mongo_blocks, "cassandra": cql_blocks}[db["id"]](results)


# ---------- 讀題庫、整理成共同的欄位 ----------

def load_yaml(db, name):
    return yaml.safe_load((RESOURCES / db["dir"] / name).read_text(encoding="utf-8"))


def normalize(db):
    ex_raw = load_yaml(db, "exercises.yml")
    trap_raw = load_yaml(db, "traps.yml")
    step_raw = load_yaml(db, db["lab"]) if db["lab"] else []
    exercises = [{
        "id": e["id"], "chapter": e["chapter"], "title": e["title"], "prompt": e["prompt"],
        "hints": e.get("hints") or [], "answer": e["answer"], "explanation": e.get("explanation", ""),
        "ordered": e.get("ordered", True) is not False,
        "write": e.get("mode") == "write" or bool(e.get("fixture")) or bool(e.get("check")),
        "setup": e.get("setup"),
    } for e in ex_raw]
    traps = [{
        "id": t["id"], "title": t["title"], "question": t["question"], "options": t["options"],
        "answer": t["answer"], "explanation": t["explanation"], "setup": t.get("setup"),
        "scripts": [{"label": s["label"], "code": s.get("sql") or s.get("commands")} for s in (t.get("sqls") or t.get("scripts"))],
    } for t in trap_raw]
    steps = [{
        "id": s["id"], "title": s["title"], "goal": s["goal"], "question": s.get("question"), "takeaway": s["takeaway"],
        "setup": s.get("ddl") or s.get("indexes") or [],
        "queries": [{"label": q["label"], "code": q.get("sql") or q.get("command") or q.get("cql")}
                    for q in (s.get("sqls") or s.get("queries") or [])],
    } for s in step_raw]
    return exercises, traps, steps


def fetch_results(db, exercises, traps):
    """請展示台執行每一題的標準答案，把結果嵌進題庫。展示台沒開時回傳 False。"""
    base = "/" + db["api"]
    try:
        api(base + "/exercises")
    except Exception:
        return False
    for e in exercises:
        g = api(f"{base}/exercises/{e['id']}/check", {db["key"]: e["answer"]})
        e["result"] = blocks(db, g.get("result"))
        if g.get("checks"):
            checks = g["checks"]
            e["checks"] = (redis_blocks(checks) if db["id"] == "redis"
                           else mongo_blocks(checks) if db["id"] == "mongodb" else cql_blocks(checks))
    for t in traps:
        r = api(f"{base}/traps/{t['id']}/answer", {"choice": t["answer"]})
        t["results"] = [blocks(db, x) for x in r["results"]]
    return True


def build_question_bank():
    dbs, stats = [], []
    online = True
    for db in DATABASES:
        exercises, traps, steps = normalize(db)
        has = online and fetch_results(db, exercises, traps)
        if not has and online:
            print("！展示台沒有在執行（http://localhost:8081），題庫不會附上執行結果。", file=sys.stderr)
            online = False
        dbs.append({"id": db["id"], "name": db["name"], "lang": db["lang"], "labName": db["labName"],
                    "labNote": db["labNote"], "hasResults": bool(has),
                    "exercises": exercises, "traps": traps, "steps": steps})
        stats.append((db["name"], len(exercises), len(traps), len(steps)))
    data = {"dbs": dbs, "built": f"{date.today():%Y-%m-%d}"}
    payload = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
    body = f"""
<header class="top">
  <div class="top-in">
    <div class="title-block"><h1 class="page-title">題庫</h1><span class="sub" id="sub"></span></div>
    <label class="search"><span class="sr-only">搜尋題目</span>
      <input id="q" type="search" placeholder="搜尋題目、指令、解說…（按 / 快速搜尋）" autocomplete="off"></label>
  </div>
  <nav class="db-tabs" id="dbtabs" aria-label="資料庫"></nav>
</header>
<main class="bank" id="bank"></main>
<script type="application/json" id="data">{payload}</script>"""
    css = ((Path(__file__).parent / "db_tabs.css").read_text(encoding="utf-8")
           + (Path(__file__).parent / "bank.css").read_text(encoding="utf-8"))
    js = (Path(__file__).parent / "bank.js").read_text(encoding="utf-8")
    (OUT / "question-bank.html").write_text(page("題庫", body, css, js), encoding="utf-8")
    return stats, online


def build_index(stats):
    body = f"""
<header class="top"><div class="top-in"><div><b class="brand">資料庫學習筆記</b>
  <span class="sub">面試準備 · shop 練習資料庫</span></div></div></header>
<main class="home">
  <p class="lead">以一個台灣電商「shop」的模擬資料（會員、訂單、明細、商品、分類，約 30 萬筆）為例，
    練習 PostgreSQL、Redis、MongoDB、Cassandra 四種資料庫的查詢、資料模型與面試常考的觀念。</p>
  <div class="home-grid">
    <a class="home-card" href="cheatsheet.html">
      <b>CheatSheet</b>
      <span>PostgreSQL：SQL 執行順序、JOIN、NULL、視窗函數、索引、交易、JSONB / UPSERT。
        Redis：資料結構、交易與 Lua、快取穿透 / 擊穿 / 雪崩、分散式鎖、持久化、叢集。
        MongoDB：查詢與聚合管線、內嵌 vs 參照、索引與 ESR、交易與分片。
        Cassandra：分區鍵與叢集鍵、查詢先行的表設計、墓碑、一致性等級、LWT。</span>
    </a>
    <a class="home-card" href="question-bank.html">
      <b>題庫</b>
      <span>{"、".join(f"{name} 練習 {e} 題、陷阱 {t} 題" + (f"、實驗 {s} 步" if s else "") for name, e, t, s in stats)}。
        附提示、答案、解說與正確答案的實際執行結果。</span>
    </a>
  </div>
  <p class="foot">更新於 {date.today():%Y-%m-%d}</p>
</main>"""
    css = """
.home { max-width: 860px; margin: 0 auto; padding: 32px 20px 80px; }
.lead { font-size: 1.05rem; color: var(--muted); max-width: 62ch; margin: 0 0 24px; }
.home-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; }
.home-card { display: grid; gap: 8px; padding: 20px; border-radius: 12px; background: var(--surface);
  border: 1px solid var(--line); text-decoration: none; color: inherit; transition: border-color .15s; }
.home-card:hover { border-color: var(--accent); }
.home-card:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.home-card b { font-size: 1.15rem; color: var(--accent); }
.home-card span { font-size: .9rem; color: var(--muted); }
.foot { color: var(--muted); font-size: .8rem; margin-top: 32px; }
"""
    (OUT / "index.html").write_text(page("資料庫學習筆記", body, css, ""), encoding="utf-8")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    build_cheatsheet()
    stats, online = build_question_bank()
    build_index(stats)
    print("docs/ 已產生：" + "；".join(f"{n} 練習 {e}、陷阱 {t}、實驗 {s}" for n, e, t, s in stats)
          + f"（{'含' if online else '不含'}執行結果）")
