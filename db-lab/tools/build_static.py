"""
產生可以直接雙擊打開的靜態 HTML，中英日三種語言：
  docs/cheatsheet.html      ← CHEATSHEET.md（英、日：i18n/{en,ja}/CHEATSHEET.md）
  docs/question-bank.html   ← db-showcase/src/main/resources/*/*.yml（英、日：i18n/{en,ja}/bank/ 底下同名檔案只放翻譯的欄位）
                               （展示台有在執行時，會順便把「正確答案的執行結果」嵌進去）
  docs/index.html
中文在 docs/，英文在 docs/en/，日文在 docs/ja/。右上角可以切換；沒選過時依瀏覽器語言決定。

用法：python tools/build_static.py
"""
import html
import json
import re
import sys
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import copy

import yaml

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
I18N = ROOT / "i18n"
TOOLS = Path(__file__).parent

# 語言：代碼、<html lang>、輸出資料夾（相對 docs/）、切換按鈕上的字
LANGS = [("zh", "zh-Hant", "", "中"), ("en", "en", "en/", "EN"), ("ja", "ja", "ja/", "日")]
UI = json.loads((TOOLS / "ui_strings.json").read_text(encoding="utf-8"))
LANG = "zh"               # 目前產生的語言


def T(key, **kw):
    """介面文字（ui_strings.json），{名稱} 換成參數。"""
    text = UI[key][LANG]
    for k, v in kw.items():
        text = text.replace("{" + k + "}", str(v))
    return text


def out_dir():
    return DOCS / next(d for c, _, d, _ in LANGS if c == LANG)


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
    return "".join(out).replace("★", f'<span class="star" title="{html.escape(T("star"))}">★</span>')


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

FONT_FAMILY = {"zh": "Noto+Sans+TC", "en": "Noto+Sans+TC", "ja": "Noto+Sans+JP"}

# 放在 <head> 最前面：使用者選過語言就用選的，沒選過看瀏覽器的語言（navigator.languages，
# 就是瀏覽器送出的 Accept-Language）；和這一頁不同時，馬上換到對應語言的同一頁
LANG_REDIRECT = """(function(){var L=%s,U=%s,p=null;try{p=localStorage.getItem("site-lang")}catch(e){}
if(!U[p]){p="en";var ls=navigator.languages&&navigator.languages.length?navigator.languages:[navigator.language||""];
for(var i=0;i<ls.length;i++){var l=String(ls[i]).toLowerCase();if(l.indexOf("zh")===0){p="zh";break}
if(l.indexOf("ja")===0){p="ja";break}if(l.indexOf("en")===0){p="en";break}}}
if(p!==L)location.replace(U[p]+location.hash);})();"""


def lang_urls(filename):
    """這一頁在各語言的相對網址。"""
    here = next(d for c, _, d, _ in LANGS if c == LANG)
    up = "../" * here.count("/")
    return {c: up + d + filename for c, _, d, _ in LANGS}


def lang_switch(filename):
    urls = lang_urls(filename)
    links = "".join(
        f'<a href="{urls[c]}" data-lang="{c}" lang="{hl}"{" aria-current=\"true\"" if c == LANG else ""}>{label}</a>'
        for c, hl, _, label in LANGS)
    return f'<nav class="lang-switch" aria-label="{html.escape(T("lang.aria"))}">{links}</nav>'


def page(title: str, body: str, extra_css: str, script: str, filename: str) -> str:
    shared_css = (TOOLS / "static_shared.css").read_text(encoding="utf-8")
    shared_js = (TOOLS / "static_shared.js").read_text(encoding="utf-8")
    html_lang = next(hl for c, hl, _, _ in LANGS if c == LANG)
    fonts = ('<link rel="preconnect" href="https://fonts.googleapis.com">'
             '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
             '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600'
             f'&family={FONT_FAMILY[LANG]}:wght@400;500;700&display=swap">')
    alternates = "".join(f'<link rel="alternate" hreflang="{hl}" href="{lang_urls(filename)[c]}">' for c, hl, _, _ in LANGS)
    ui = {k: v[LANG] for k, v in UI.items()}
    redirect = LANG_REDIRECT % (json.dumps(LANG), json.dumps(lang_urls(filename)))
    body = body.replace("<!--LANG-->", lang_switch(filename), 1)
    return f"""<!doctype html>
<html lang="{html_lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<script>{redirect}</script>
<title>{html.escape(title)}</title>
{alternates}
{fonts}
<style>
{shared_css}
{extra_css}
</style>
</head>
<body>
{body}
<script>
const LANG = {json.dumps(LANG)};
const UI = {json.dumps(ui, ensure_ascii=False)};
{shared_js}
{script}
</script>
</body>
</html>
"""


def build_cheatsheet():
    src = ROOT / "CHEATSHEET.md" if LANG == "zh" else I18N / LANG / "CHEATSHEET.md"
    if not src.exists():
        print(f"！沒有 {src.relative_to(ROOT)}，{LANG} 的 CheatSheet 先用中文", file=sys.stderr)
        src = ROOT / "CHEATSHEET.md"
    md = src.read_text(encoding="utf-8")
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
      <span class="sub">{T("cs.sub", n=len(parts), d=f"{date.today():%Y-%m-%d}")}</span></div>
    <div class="top-tools"><label class="search"><span class="sr-only">{T("search")}</span>
      <input id="q" type="search" placeholder="{html.escape(T("cs.placeholder"))}" autocomplete="off"></label><!--LANG--></div>
  </div>
  <nav class="db-tabs" id="dbtabs" aria-label="{T("nav.dbs")}">{tabs_html}</nav>
</header>
<div class="layout">
  <nav class="toc" id="toc" aria-label="{T("nav.toc")}">{toc_html}</nav>
  <main class="doc" id="doc">
    <p class="hit-count" id="hits" hidden></p>
    {content}
  </main>
</div>"""
    css = (TOOLS / "db_tabs.css").read_text(encoding="utf-8") + (TOOLS / "cheatsheet.css").read_text(encoding="utf-8")
    js = (TOOLS / "cheatsheet.js").read_text(encoding="utf-8")
    (out_dir() / "cheatsheet.html").write_text(page("CheatSheet", body, css, js, "cheatsheet.html"), encoding="utf-8")


# ---------------------------------------------------------------- 題庫：四種資料庫

RESOURCES = ROOT / "db-showcase" / "src" / "main" / "resources"
API_BASE = "http://localhost:8081/api"
RESULT_ROWS = 10          # 每個表格結果最多嵌入幾列
RESULT_DOCS = 3           # MongoDB 每個結果最多嵌入幾份文件
TEXT_LIMIT = 1800         # 文字結果最多幾個字

# 每種資料庫：題庫檔、API 路徑、送出答案時的欄位名稱、程式碼的語言（上色用）、展示台的頁面
DATABASES = [
    {"id": "postgresql", "name": "PostgreSQL", "dir": "postgres", "api": "postgres", "key": "sql", "lang": "sql",
     "lab": "index-lab.yml",
     "labName": {"zh": "索引實驗", "en": "Index lab", "ja": "インデックス実験"},
     "labNote": {
         "zh": "實驗對象是 perf schema 的兩張 200 萬筆大表：perf.orders_big（隨機順序寫入）、perf.order_events（依時間順序寫入）。"
               "每一步先不建索引跑一次 EXPLAIN ANALYZE，再建立索引重跑，比較執行計畫和時間。",
         "en": "The lab uses two 2-million-row tables in the perf schema: perf.orders_big (inserted in random order) and "
               "perf.order_events (inserted in time order). Each step runs EXPLAIN ANALYZE without an index, then creates "
               "the index and runs it again, comparing the plans and timings.",
         "ja": "対象は perf スキーマの 200 万行の大きなテーブル 2 つ：perf.orders_big（ランダムな順序で挿入）と "
               "perf.order_events（時刻順に挿入）。各ステップでまずインデックスなしで EXPLAIN ANALYZE を実行し、"
               "インデックスを作成して再実行し、実行計画と時間を比較します。"}},
    {"id": "redis", "name": "Redis", "dir": "redis", "api": "redis", "key": "commands", "lang": "redis",
     "lab": None, "labName": None, "labNote": None},
    {"id": "mongodb", "name": "MongoDB", "dir": "mongo", "api": "mongo", "key": "commands", "lang": "mongo",
     "lab": "index-lab.yml",
     "labName": {"zh": "索引實驗", "en": "Index lab", "ja": "インデックス実験"},
     "labNote": {
         "zh": "實驗對象是 shop.orders（8 萬份訂單）。每一步先看沒有索引時的 explain(\"executionStats\")，"
               "再建立建議的索引重跑，比較 COLLSCAN / IXSCAN、docsExamined 與 SORT。",
         "en": "The lab uses shop.orders (80,000 orders). Each step first shows explain(\"executionStats\") without an "
               "index, then creates the suggested index and runs it again, comparing COLLSCAN / IXSCAN, docsExamined and SORT.",
         "ja": "対象は shop.orders（8 万件の注文）。各ステップでまずインデックスなしの explain(\"executionStats\") を見て、"
               "推奨インデックスを作成して再実行し、COLLSCAN / IXSCAN、docsExamined、SORT を比較します。"}},
    {"id": "cassandra", "name": "Cassandra", "dir": "cassandra", "api": "cassandra", "key": "commands", "lang": "cql",
     "lab": "query-lab.yml",
     "labName": {"zh": "表設計實驗", "en": "Table design lab", "ja": "テーブル設計実験"},
     "labNote": {
         "zh": "每一句查詢都開啟查詢追蹤（TRACING），比較「讀取方式」（單一分區、範圍掃描、SAI 索引）與伺服器實際讀了幾列。",
         "en": "Every query runs with TRACING on, comparing the read path (single partition, range scan, SAI index) and "
               "how many rows the server actually read.",
         "ja": "すべてのクエリでトレース（TRACING）を有効にし、読み取り方法（単一パーティション、範囲スキャン、SAI インデックス）と"
               "サーバーが実際に読んだ行数を比較します。"}},
    {"id": "neo4j", "name": "Neo4j", "dir": "neo4j", "api": "neo4j", "key": "commands", "lang": "cypher",
     "lab": "profile-lab.yml",
     "labName": {"zh": "PROFILE 實驗", "en": "PROFILE lab", "ja": "PROFILE 実験"},
     "labNote": {
         "zh": "每一句查詢前面加上 PROFILE，比較執行計畫的第一步（NodeByLabelScan 或 IndexSeek）與總 db hits（存取儲存層的次數）。",
         "en": "Every query is prefixed with PROFILE, comparing the first operator of the plan (NodeByLabelScan or "
               "IndexSeek) and the total db hits (storage accesses).",
         "ja": "各クエリの先頭に PROFILE を付け、実行計画の最初のステップ（NodeByLabelScan か IndexSeek）と"
               "合計 db hits（ストレージへのアクセス回数）を比較します。"}},
    {"id": "timescaledb", "name": "TimescaleDB", "dir": "timescale", "api": "timescale", "key": "sql", "lang": "sql",
     "lab": "chunk-lab.yml",
     "labName": {"zh": "chunk 實驗", "en": "Chunk lab", "ja": "chunk 実験"},
     "labNote": {
         "zh": "每一句查詢都用 EXPLAIN ANALYZE 執行，比較讀了幾個 chunk（page_views 共 27 個，每個 7 天）與執行時間。"
               "壓縮、連續聚合的實驗需要互動操作，請到展示台的「實驗室」。",
         "en": "Every query runs with EXPLAIN ANALYZE, comparing how many chunks were read (page_views has 27, 7 days each) "
               "and the execution time. The compression and continuous aggregate labs are interactive; use the Lab page "
               "of the showcase app.",
         "ja": "各クエリを EXPLAIN ANALYZE で実行し、読んだ chunk の数（page_views は全 27 個、各 7 日）と実行時間を比較します。"
               "圧縮・連続集約の実験は操作が必要なため、ショーケースアプリの「実験室」で行ってください。"}},
    {"id": "pgvector", "name": "pgvector", "dir": "pgvector", "api": "pgvector", "key": "sql", "lang": "sql",
     "lab": None, "labName": None, "labNote": None},
    {"id": "influxdb", "name": "InfluxDB", "dir": "influx", "api": "influx", "key": "code", "lang": "influx",
     "lab": "compare-lab.yml",
     "labName": {"zh": "InfluxQL vs Flux", "en": "InfluxQL vs Flux", "ja": "InfluxQL vs Flux"},
     "labNote": {
         "zh": "每一步是同一個問題的兩種寫法：InfluxQL（類似 SQL）與 Flux（管線式），比較寫法與回傳的資料形狀。"
               "series 數量（tag vs field）與降低精度的實驗需要寫入資料，請到展示台的「實驗室」。",
         "en": "Each step answers the same question two ways: InfluxQL (SQL-like) and Flux (pipelines), comparing the "
               "syntax and the shape of the returned data. The series cardinality (tag vs field) and downsampling labs "
               "write data; use the Lab page of the showcase app.",
         "ja": "各ステップは同じ問いを 2 通りで書きます：InfluxQL（SQL 風）と Flux（パイプライン）。書き方と返るデータの形を比較します。"
               "series 数（tag vs field）とダウンサンプリングの実験はデータを書き込むため、ショーケースアプリの「実験室」で行ってください。"}},
    {"id": "elasticsearch", "name": "Elasticsearch", "dir": "elastic", "api": "elastic", "key": "commands", "lang": "es",
     "lab": "relevance-lab.yml",
     "labName": {"zh": "相關性實驗", "en": "Relevance lab", "ja": "関連度実験"},
     "labNote": {
         "zh": "每一步用不同的查詢搜尋同一批資料，比較前幾名與 _score：IDF、欄位長度、欄位權重、function_score、filter 不計分。"
               "分析器與寫入行為（近即時、版本衝突、mapping、同義詞、深分頁）的實驗需要互動操作，請到展示台的「實驗室」。",
         "en": "Each step searches the same data with a different query, comparing the top hits and _score: IDF, field "
               "length, field boosts, function_score, and filters that don't score. The analyzer and write-behavior labs "
               "(near real-time, version conflicts, mappings, synonyms, deep paging) are interactive; use the Lab page of "
               "the showcase app.",
         "ja": "各ステップで同じデータを異なるクエリで検索し、上位の結果と _score を比較します：IDF、フィールド長、フィールドの重み、"
               "function_score、スコアに影響しない filter。アナライザと書き込み動作（ニアリアルタイム、バージョン競合、mapping、"
               "同義語、ディープページング）の実験は操作が必要なため、ショーケースアプリの「実験室」で行ってください。"}},
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
    return text if len(text) <= TEXT_LIMIT else text[:TEXT_LIMIT] + T("out.clip")


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
            head = T("out.docs", n=f"{r['total']:,}") + (T("out.docs.more", k=len(docs)) if r["total"] > len(docs) else "")
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
            b["text"] = T("out.done")
        if r.get("warnings"):
            b["warn"] = r["warnings"]
        out.append(b)
    return out


def cypher_cell(v):
    """節點 → (:Label {id: 1, name: '…'})、關係 → [:TYPE]、路徑 → 節點-[:TYPE]->節點…"""
    if isinstance(v, dict) and "~labels" in v:
        props = v["~props"]
        keys = sorted(props, key=lambda k: (["id", "name", "status", "total"].index(k) if k in ["id", "name", "status", "total"] else 9, k))[:3]
        inner = ", ".join(f"{k}: " + (f"'{props[k]}'" if isinstance(props[k], str) else str(cql_cell(props[k]))) for k in keys)
        return f"({''.join(':' + l for l in v['~labels'])} {{{inner}{', …' if len(props) > 3 else ''}}})"
    if isinstance(v, dict) and "~type" in v:
        return f"[:{v['~type']}]"
    if isinstance(v, dict) and "~path" in v:
        return "".join(cypher_cell(x) if i % 2 == 0 else f"-{cypher_cell(x)}->" for i, x in enumerate(v["~path"]))
    if isinstance(v, list):
        return "[" + ", ".join(f"'{x}'" if isinstance(x, str) else str(cypher_cell(x)) for x in v) + "]"
    return cql_cell(v)


def cypher_blocks(results):
    out = []
    for r in results:
        b = {"cmd": r["statement"]}
        if r["kind"] == "rows":
            b["table"] = {"columns": r["columns"], "rows": [[cypher_cell(v) for v in row] for row in r["rows"][:RESULT_ROWS]],
                          "total": r["total"]}
        elif r["kind"] == "error":
            b["text"], b["error"] = r["message"], True
        else:
            counters = r.get("counters") or {}
            b["text"] = T("out.list.sep").join(f"{T('counter.' + k) if 'counter.' + k in UI else k} {v}"
                                               for k, v in counters.items()) or T("out.done")
        if r.get("notifications"):
            b["warn"] = r["notifications"]
        out.append(b)
    return out


ES_FIELDS = ["name", "title", "content", "description", "message", "price", "rating", "review_count", "status",
             "@timestamp", "order_date", "total", "category", "brand", "service", "latency_ms", "tags", "helpful", "stock"]


def es_blocks(results):
    """Elasticsearch：_search 列出 hits 表格（_id、_score、幾個常見欄位）＋ 聚合的 JSON；其他回應是 JSON 文字。"""
    out = []
    for r in results:
        b, cmd = r["response"], f"{r['statement']}  ({r['status']})"
        if not 200 <= r["status"] < 300:
            reason = b.get("error") if isinstance(b, dict) else b
            if isinstance(reason, dict):
                reason = (reason.get("root_cause") or [{}])[0].get("reason") or reason.get("reason")
            out.append({"cmd": cmd, "text": str(reason), "error": True})
            continue
        if isinstance(b, str):
            out.append({"cmd": cmd, "text": clip(b.rstrip() or T("out.empty"))})
            continue
        if isinstance(b.get("hits"), dict) and isinstance(b["hits"].get("hits"), list):
            hits = b["hits"]["hits"]
            total = b["hits"].get("total") or {}
            if hits:
                keys = []
                for h in hits:
                    keys += [k for k in (h.get("_source") or {}) if k in ES_FIELDS and k not in keys]
                keys = sorted(keys, key=ES_FIELDS.index)[:4]
                rows = [[h["_id"], None if h.get("_score") is None else round(h["_score"], 3)]
                        + [cql_cell((h.get("_source") or {}).get(k)) for k in keys] for h in hits[:RESULT_ROWS]]
                out.append({"cmd": cmd, "table": {"columns": ["_id", "_score"] + keys, "rows": rows,
                                                  "total": total.get("value", len(hits))}})
            if b.get("aggregations") or not hits:
                text = f"hits.total = {total.get('value')}{'+' if total.get('relation') == 'gte' else ''}"
                if b.get("aggregations"):
                    text += "\naggregations = " + json.dumps(b["aggregations"], ensure_ascii=False, indent=2)
                out.append({"cmd": None if hits else cmd, "text": clip(text)})
            continue
        slim = {k: v for k, v in b.items() if k not in ("_shards", "took", "timed_out")}
        out.append({"cmd": cmd, "text": clip(json.dumps(slim, ensure_ascii=False, indent=2))})
    return out


def influx_time(v):
    """InfluxQL 的 time 是 epoch 秒、Flux 的 _time 是 RFC3339（UTC），都轉成台灣時間。"""
    try:
        if isinstance(v, (int, float)):
            if v == 0:
                return T("out.notime")
            d = datetime.fromtimestamp(v, timezone.utc)
        else:
            d = datetime.fromisoformat(str(v).replace("Z", "+00:00"))
        return (d + timedelta(hours=8)).strftime("%Y-%m-%d %H:%M:%S")
    except (ValueError, OSError):
        return v


def influx_blocks(run):
    """InfluxDB：每個結果區塊（InfluxQL 的一個 series、Flux 的一張表）一個表格，錯誤與訊息是文字。"""
    out = []
    for b in (run or {}).get("blocks", []):
        if b["kind"] != "table":
            out.append({"cmd": b.get("statement") or None, "text": b["message"], "error": b["kind"] == "error"})
            continue
        cols = b["columns"]
        rows = [[influx_time(v) if cols[i] in ("time", "_time", "_start", "_stop") and v is not None else
                 (round(v, 4) if isinstance(v, float) else v) for i, v in enumerate(r)] for r in b["rows"][:RESULT_ROWS]]
        out.append({"cmd": b.get("title") or None, "table": {"columns": cols, "rows": rows, "total": b["total"]}})
    return out[:12]


def blocks(db, run):
    """把一次執行（PostgreSQL 是單一查詢結果，其他是多句指令的結果）轉成共同格式。"""
    if db["id"] in ("postgresql", "timescaledb", "pgvector"):
        return pg_blocks(run)
    if db["id"] == "influxdb":
        if isinstance(run, list):                     # 陷阱題：一段腳本有好幾個步驟，各自一個結果
            return [b for step in run for b in influx_blocks(step)]
        return influx_blocks(run)
    results = run["results"]
    return {"redis": redis_blocks, "mongodb": mongo_blocks, "cassandra": cql_blocks, "neo4j": cypher_blocks,
            "elasticsearch": es_blocks}[db["id"]](results)


# ---------- 讀題庫、整理成共同的欄位 ----------

def overlay(base, tr):
    """把翻譯疊到原本的題目上：dict 依 key、dict 的 list 依位置合併，其他（字串、字串的 list）直接取代。"""
    if isinstance(base, dict) and isinstance(tr, dict):
        out = dict(base)
        for k, v in tr.items():
            out[k] = overlay(base.get(k), v)
        return out
    if isinstance(base, list) and isinstance(tr, list) and base and isinstance(base[0], dict):
        return [overlay(b, tr[i]) if i < len(tr) else b for i, b in enumerate(base)]
    return tr


MISSING = []      # 還沒翻譯的題庫檔或題目


def load_yaml(db, name):
    data = yaml.safe_load((RESOURCES / db["dir"] / name).read_text(encoding="utf-8"))
    if LANG == "zh":
        return data
    tr_file = I18N / LANG / "bank" / db["dir"] / name
    if not tr_file.exists():
        MISSING.append(str(tr_file.relative_to(ROOT)))
        return data
    tr = {t["id"]: t for t in yaml.safe_load(tr_file.read_text(encoding="utf-8")) or []}
    MISSING.extend(f"{tr_file.relative_to(ROOT)}#{x['id']}" for x in data if x["id"] not in tr)
    return [overlay(x, tr[x["id"]]) if x["id"] in tr else x for x in data]


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
        "scripts": [{"label": s["label"], "code": s.get("sql") or s.get("commands") or steps_code(s.get("steps"))}
                    for s in (t.get("sqls") or t.get("scripts"))],
    } for t in trap_raw]
    steps = [{
        "id": s["id"], "title": s["title"], "goal": s["goal"], "question": s.get("question"), "takeaway": s["takeaway"],
        "setup": s.get("ddl") or s.get("indexes") or [],
        "queries": [{"label": q["label"], "code": q.get("sql") or q.get("command") or q.get("cql") or q.get("cypher")}
                    for q in (s.get("sqls") or s.get("queries") or [])]
                   or ([{"label": T("step.request"), "code": s["commands"]}] if s.get("commands") else [])
                   or [q for q in ({"label": "InfluxQL", "code": s.get("influxql")}, {"label": "Flux", "code": s.get("flux")}) if q["code"]],
    } for s in step_raw]
    return exercises, traps, steps


def steps_code(steps):
    """InfluxDB 陷阱題的腳本由好幾個步驟組成：每一步前面加一行註解標出語言。"""
    label = {"influxql": "-- InfluxQL", "flux": "// Flux", "write": T("step.write")}
    return "\n\n".join(label.get(s["lang"], s["lang"]) + "\n" + s["code"].strip() for s in (steps or []))


def fetch_raw(db, exercises, traps):
    """請展示台執行每一題的標準答案（用中文原檔的答案），回傳原始回應。展示台沒開時回傳 None。"""
    base = "/" + db["api"]
    try:
        api(base + "/exercises")
    except Exception:
        return None
    return {"exercises": {e["id"]: api(f"{base}/exercises/{e['id']}/check", {db["key"]: e["answer"]}) for e in exercises},
            "traps": {t["id"]: api(f"{base}/traps/{t['id']}/answer", {"choice": t["answer"]}) for t in traps}}


def attach_results(db, raw, exercises, traps):
    """把原始回應轉成目前語言的顯示區塊，放進題目裡。"""
    for e in exercises:
        g = raw["exercises"][e["id"]]
        e["result"] = blocks(db, g.get("result"))
        if g.get("check"):                            # InfluxDB 寫入題：寫入後用一句查詢檢查
            e["checks"] = influx_blocks(g["check"])
        if g.get("checks"):
            e["checks"] = {"redis": redis_blocks, "mongodb": mongo_blocks, "cassandra": cql_blocks,
                           "neo4j": cypher_blocks, "elasticsearch": es_blocks}[db["id"]](g["checks"])
    for t in traps:
        t["results"] = [blocks(db, x) for x in raw["traps"][t["id"]]["results"]]


RAW = {}          # 資料庫 id → 展示台的原始回應（三種語言共用，只抓一次）


def fetch_all():
    global LANG
    LANG = "zh"
    for db in DATABASES:
        exercises, traps, _ = normalize(db)
        raw = fetch_raw(db, exercises, traps)
        if raw is None:
            print("！展示台沒有在執行（http://localhost:8081），題庫不會附上執行結果。", file=sys.stderr)
            return False
        RAW[db["id"]] = raw
    return True


def build_question_bank():
    dbs, stats = [], []
    for db in DATABASES:
        exercises, traps, steps = normalize(db)
        if db["id"] in RAW:
            attach_results(db, RAW[db["id"]], exercises, traps)
        dbs.append({"id": db["id"], "name": db["name"], "lang": db["lang"],
                    "labName": db["labName"] and db["labName"][LANG], "labNote": db["labNote"] and db["labNote"][LANG],
                    "hasResults": db["id"] in RAW, "exercises": exercises, "traps": traps, "steps": steps})
        stats.append((db["name"], len(exercises), len(traps), len(steps)))
    data = {"dbs": dbs, "built": f"{date.today():%Y-%m-%d}"}
    payload = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
    body = f"""
<header class="top">
  <div class="top-in">
    <div class="title-block"><h1 class="page-title">{T("bank.title")}</h1><span class="sub" id="sub"></span></div>
    <div class="top-tools"><label class="search"><span class="sr-only">{T("bank.search")}</span>
      <input id="q" type="search" placeholder="{html.escape(T("bank.placeholder"))}" autocomplete="off"></label><!--LANG--></div>
  </div>
  <nav class="db-tabs" id="dbtabs" aria-label="{T("nav.dbs")}"></nav>
</header>
<main class="bank" id="bank"></main>
<script type="application/json" id="data">{payload}</script>"""
    css = (TOOLS / "db_tabs.css").read_text(encoding="utf-8") + (TOOLS / "bank.css").read_text(encoding="utf-8")
    js = (TOOLS / "bank.js").read_text(encoding="utf-8")
    (out_dir() / "question-bank.html").write_text(page(T("bank.title"), body, css, js, "question-bank.html"), encoding="utf-8")
    return stats


def build_index(stats):
    bank_items = T("home.bank.sep").join(T("home.bank.item", name=name, e=e, t=t) + (T("home.bank.steps", s=st) if st else "")
                                         for name, e, t, st in stats)
    body = f"""
<header class="top"><div class="top-in"><div><b class="brand">{T("site.title")}</b>
  <span class="sub">{T("site.sub")}</span></div><!--LANG--></div></header>
<main class="home">
  <p class="lead">{html.escape(T("home.lead"))}</p>
  <div class="home-grid">
    <a class="home-card" href="cheatsheet.html">
      <b>CheatSheet</b>
      <span>{html.escape(T("home.cheatsheet"))}</span>
    </a>
    <a class="home-card" href="question-bank.html">
      <b>{T("bank.title")}</b>
      <span>{html.escape(bank_items + T("home.bank.tail"))}</span>
    </a>
  </div>
  <p class="foot">{T("updated", d=f"{date.today():%Y-%m-%d}")}</p>
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
    (out_dir() / "index.html").write_text(page(T("site.title"), body, css, "", "index.html"), encoding="utf-8")


if __name__ == "__main__":
    online = fetch_all()
    for code, *_ in LANGS:
        LANG = code
        out_dir().mkdir(parents=True, exist_ok=True)
        build_cheatsheet()
        stats = build_question_bank()
        build_index(stats)
    LANG = "zh"
    print("docs/ 已產生（中、英、日）：" + "；".join(f"{n} 練習 {e}、陷阱 {t}、實驗 {s}" for n, e, t, s in stats)
          + f"（{'含' if online else '不含'}執行結果）")
    if MISSING:
        print(f"！還沒翻譯 {len(MISSING)} 項（先用中文）：" + "、".join(MISSING[:20]) + ("…" if len(MISSING) > 20 else ""),
              file=sys.stderr)
