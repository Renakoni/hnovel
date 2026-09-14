"""Offline field inventory. Does not execute source code or contact source websites."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re


def inventory(paths):
    files, definitions = [], {}
    for path in paths:
        data = path.read_bytes()
        rows = json.loads(data.decode("utf-8-sig"))
        if not isinstance(rows, list):
            raise ValueError(f"Expected an array: {path.name}")
        files.append({"file": path.name, "rows": len(rows), "sha256": hashlib.sha256(data).hexdigest()})
        for row in rows:
            key = json.dumps(row, sort_keys=True, ensure_ascii=False)
            definitions[key] = row
    novels = [row for row in definitions.values() if row.get("bookSourceType", 0) == 0]
    fields, forms, keys, types, roles = Counter(), Counter(), Counter(), Counter(), Counter()
    for source in novels:
        for field in ("searchUrl", "exploreUrl", "ruleExplore", "exploreScreen", "homepageModules", "bookSourceGroup"):
            if source.get(field):
                fields[field] += 1
        fields["enabledExplore" if source.get("enabledExplore", True) else "disabledExplore"] += 1
        text = (source.get("exploreUrl") or "").strip()
        rows = []
        if not text:
            form = "absent"
        elif text.lower().startswith(("@js:", "<js>")):
            form = "javascript"
        elif text.startswith("["):
            try:
                rows = json.loads(text)
                form = "json_array"
            except ValueError:
                form = "array_not_strict_json"
        else:
            form = "legacy_text"
            rows = [dict(zip(("title", "url"), item.split("::", 1))) for item in re.split(r"(?:&&|\r?\n)+", text) if item.strip()]
        forms[form] += 1
        for row in rows:
            if not isinstance(row, dict):
                continue
            keys.update(row.keys())
            types[str(row.get("type") or "url")] += 1
            title = str(row.get("title") or "").strip()
            if not row.get("url"):
                role = "heading" if title else "spacer"
            # These are planning candidates, NOT runtime routing. Ambiguity is retained.
            elif re.search(r"最新|更新|新书|热榜|热门|排行|推荐|畅销|人气|点击|收藏|月票|完本榜|综合榜|销售榜", title):
                role = "feed_candidate"
            elif re.fullmatch(r"玄幻(?:奇幻)?|奇幻|都市(?:人生|青春|生活)?|言情(?:小说)?|女性小说|仙侠(?:武侠)?|武侠(?:仙侠)?|历史(?:军事)?|军事(?:历史)?|科幻(?:无限|末世)?|悬疑(?:灵异)?|游戏(?:竞技|体育)?|轻小说|同人|二次元|出版(?:读物)?", title):
                role = "category_candidate"
            else:
                role = "unresolved"
            roles[role] += 1
    return {"files": files, "rows": sum(file["rows"] for file in files), "unique": len(definitions),
            "uniqueNovels": len(novels), "fields": dict(fields), "exploreForms": dict(forms),
            "staticRowKeys": dict(keys), "staticRowTypes": dict(types), "staticEntryCandidates": dict(roles),
            "scope": "Static inventory, not importer acceptance or a live pass rate. Lenient arrays and JavaScript are counted but not evaluated."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inventory(args.files), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
