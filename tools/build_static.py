"""
產生兩份可以直接雙擊打開的靜態 HTML：
  docs/cheatsheet.html      ← CHEATSHEET.md
  docs/question-bank.html   ← db-showcase/src/main/resources/postgres/*.yml
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
CONTENT = ROOT / "db-showcase" / "src" / "main" / "resources" / "postgres"
OUT = ROOT / "docs"
API = "http://localhost:8081/api/postgres"
RESULT_ROWS = 10          # 每個結果最多嵌入幾筆


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


# ---------------------------------------------------------------- 執行結果

def api(path, data=None):
    req = urllib.request.Request(
        API + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={"Content-Type": "application/json"},
        method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def trim(result):
    if not result:
        return None
    return {"columns": result["columns"], "rows": result["rows"][:RESULT_ROWS], "total": result["rowCount"]}


def fetch_results(exercises, traps):
    try:
        api("/exercises")
    except Exception:
        print("！展示台沒有在執行（http://localhost:8081），題庫不會附上執行結果。", file=sys.stderr)
        return False
    for e in exercises:
        g = api(f"/exercises/{e['id']}/check", {"sql": e["answer"]})
        e["result"] = trim(g.get("result"))
    for t in traps:
        r = api(f"/traps/{t['id']}/answer", {"choice": t["answer"]})
        t["results"] = [trim(x) for x in r["results"]]
    return True


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
    css = (Path(__file__).parent / "cheatsheet.css").read_text(encoding="utf-8")
    js = (Path(__file__).parent / "cheatsheet.js").read_text(encoding="utf-8")
    (OUT / "cheatsheet.html").write_text(page("CheatSheet", body, css, js), encoding="utf-8")


def build_question_bank():
    exercises = yaml.safe_load((CONTENT / "exercises.yml").read_text(encoding="utf-8"))
    traps = yaml.safe_load((CONTENT / "traps.yml").read_text(encoding="utf-8"))
    steps = yaml.safe_load((CONTENT / "index-lab.yml").read_text(encoding="utf-8"))
    has_results = fetch_results(exercises, traps)
    data = {"exercises": exercises, "traps": traps, "steps": steps, "hasResults": has_results,
            "built": f"{date.today():%Y-%m-%d}"}
    payload = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
    body = f"""
<header class="top">
  <div class="top-in">
    <div><b class="brand">PostgreSQL 題庫</b><span class="sub" id="sub"></span></div>
    <label class="search"><span class="sr-only">搜尋題目</span>
      <input id="q" type="search" placeholder="搜尋題目、SQL、解說…" autocomplete="off"></label>
  </div>
  <nav class="tabs" id="tabs" aria-label="題型"></nav>
</header>
<main class="bank" id="bank"></main>
<script type="application/json" id="data">{payload}</script>"""
    css = (Path(__file__).parent / "bank.css").read_text(encoding="utf-8")
    js = (Path(__file__).parent / "bank.js").read_text(encoding="utf-8")
    (OUT / "question-bank.html").write_text(page("PostgreSQL 題庫", body, css, js), encoding="utf-8")
    return len(exercises), len(traps), len(steps), has_results


def build_index(n_exercises, n_traps, n_steps):
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
      <b>PostgreSQL 題庫</b>
      <span>練習題 {n_exercises} 題、陷阱題 {n_traps} 題、索引實驗 {n_steps} 步。附提示、答案、解說與正確答案的實際執行結果。</span>
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
    n = build_question_bank()
    build_index(*n[:3])
    print(f"docs/cheatsheet.html、docs/question-bank.html 已產生（練習 {n[0]}、陷阱 {n[1]}、實驗 {n[2]}，"
          f"{'含' if n[3] else '不含'}執行結果）")
