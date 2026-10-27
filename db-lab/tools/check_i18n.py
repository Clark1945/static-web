"""
檢查翻譯檔（i18n/{en,ja}/bank/**/*.yml）和中文原檔對得上：
id 都有、hints / options 數量一樣、sqls / scripts / queries 的數量一樣；英文版列出還留著中文的欄位（資料值除外，要自己看）。

用法：python tools/check_i18n.py [en|ja]
"""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "db-showcase" / "src" / "main" / "resources"
HAN = re.compile(r"[一-鿿]")
QUOTED = re.compile(r"「[^」]*」|'[^']*'|\"[^\"]*\"|\{[^}]*\}|\[[^\]]*\]")


def check(lang):
    problems = 0
    for tr_file in sorted((ROOT / "i18n" / lang / "bank").rglob("*.yml")):
        rel = tr_file.relative_to(ROOT / "i18n" / lang / "bank")
        src = {x["id"]: x for x in yaml.safe_load((RES / rel).read_text(encoding="utf-8"))}
        try:
            tr = yaml.safe_load(tr_file.read_text(encoding="utf-8")) or []
        except yaml.YAMLError as e:
            print(f"{lang}/{rel}: YAML 錯誤 {e}")
            problems += 1
            continue
        ids = [t["id"] for t in tr]
        for i in src:
            if i not in ids:
                print(f"{lang}/{rel}: 缺少 {i}"); problems += 1
        for t in tr:
            s = src.get(t["id"])
            if s is None:
                print(f"{lang}/{rel}: 原檔沒有 {t['id']}"); problems += 1
                continue
            for k in ("hints", "options", "sqls", "scripts", "queries"):
                if k in t and len(t[k]) != len(s.get(k) or []):
                    print(f"{lang}/{rel}#{t['id']}: {k} 數量 {len(t[k])} ≠ 原檔 {len(s.get(k) or [])}"); problems += 1
            for k in ("title", "prompt", "explanation", "question", "goal", "takeaway", "chapter"):
                if s.get(k) and k not in t:
                    print(f"{lang}/{rel}#{t['id']}: 沒有翻譯 {k}"); problems += 1
            if lang == "en":
                for k, v in t.items():
                    texts = v if isinstance(v, list) else [v]
                    for x in texts:
                        if isinstance(x, str) and HAN.search(QUOTED.sub("", x)) and k not in ("answer", "setup"):
                            print(f"  （留意）{rel}#{t['id']}.{k}: {x.strip()[:70]}")
    print(f"{lang}: {problems} 個問題")
    return problems


if __name__ == "__main__":
    langs = sys.argv[1:] or ["en", "ja"]
    sys.exit(1 if sum(check(l) for l in langs) else 0)
