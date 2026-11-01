"""
把概念层导出成前端可直接 import 的 JSON。

数据来源（都以 `entity` 为单位对齐，避免各脚本口径不一致）：
  target/entities-single.jsonl   概念名 + 作者写的说明 + 归属笔记   ← 主视角
  target/topics.json             主题归属（多归属）+ 未分类
  target/notes-dump.jsonl        笔记标题 / 文件夹 / 时间

产出的概念同时带 topic 与 note，前端才能做到「主题 → 概念 → 原文」三级下钻。

用法：python tools/export_concepts.py
"""
import json
import re
from collections import defaultdict

OUT = "frontend/src/data/conceptLayer.json"


IMG_MD = re.compile(r"!\[[^\]]*\]\([^)]*\)")
LINK_MD = re.compile(r"\[([^\]]*)\]\([^)]*\)")
MATH = re.compile(r"\$\$?[^$]*\$\$?")
LATEX_CMD = re.compile(r"\\[a-zA-Z]+")
SPACE = re.compile(r"\s+")


def clean_desc(text):
    r"""说明来自原文摘录，常见三种噪声：图片 markdown、LaTeX 公式、markdown 链接。

    摘录本身是对的，直接扔进 UI 只会显示成一串 $\mathbf{I}_t$ 和 /api/assets/...，
    所以在前端拿到之前先清掉——这是展示层的事，不该让每个视图各写一遍。
    """
    t = IMG_MD.sub(" ", text or "")
    t = LINK_MD.sub(r"\1", t)
    t = MATH.sub("…", t)   # 留省略号而不是删空，否则「输入门是 ，遗忘门是」读不成句
    t = LATEX_CMD.sub(" ", t)
    t = t.replace("`", " ").replace("**", " ")
    # 公式剥完后常留下孤立的反斜杠、花括号、美元符，一并清掉
    t = re.sub(r"\\[^a-zA-Z]|\\$|[${}]", " ", t)
    t = SPACE.sub(" ", t)
    t = re.sub(r"\s+([，。、；：])", r"\1", t)   # 中文标点前不留空格
    t = re.sub(r"\s*…\s*", "…", t)
    t = t.strip(" ，,、。;；:：-<>|")
    return t


def nz(s):
    return re.sub(r"[\s\-_·.,，、:：;；()（）\[\]【】「」\"'`*/]+", "", (s or "").lower())


def main():
    ents = [json.loads(l) for l in open("target/entities-single.jsonl", encoding="utf-8")]
    notes = {r["id"]: r for r in
             (json.loads(l) for l in open("target/notes-dump.jsonl", encoding="utf-8"))}
    topics_raw = json.load(open("target/topics.json", encoding="utf-8"))

    # 主题：跳过兜底桶，它由"没有主题"这件事表达，不需要一张真卡片
    topics = [{"id": t["id"], "name": t.get("llm") or t["representative"],
               "evidence": t.get("evidence", ""), "notes": t["notes"]}
              for t in topics_raw["topics"] if not t.get("structural_null")]
    tmeta = {t["id"]: t for t in topics}

    # 名字 → 主题（assignments 用的是 gran-meta 的名字，可能与 entities 略有出入，按归一化对齐）
    assign = {}
    for name, a in topics_raw["assignments"].items():
        assign[nz(name)] = a

    concepts, by_note = [], defaultdict(list)
    for r in ents:
        note = notes.get(r["id"], {})
        for e in r["entities"]:
            a = assign.get(nz(e["name"]), {})
            tids = a.get("topics", [])
            concepts.append({
                "name": e["name"],
                "desc": clean_desc(e.get("desc")),
                "noteId": r["id"],
                "noteTitle": (r["title"] or "未命名").strip(),
                "folder": (note.get("folder") or "").strip(),
                "date": (note.get("createdAt") or "")[:10],
                "topics": tids,
                "primary": a.get("primary"),
            })
            by_note[r["id"]].append(e["name"])

    # 主题下的概念（按主题聚合，供左栏计数与中栏列表使用）
    for t in topics:
        mem = [c for c in concepts if t["id"] in c["topics"]]
        t["concepts"] = [c["name"] for c in mem]
        t["explained"] = sum(1 for c in mem if c["desc"])
        t["depth"] = round(t["explained"] / len(mem), 3) if mem else 0

    unc = sorted([c["name"] for c in concepts if not c["topics"]])
    multi = {c["name"]: c["topics"] for c in concepts if len(c["topics"]) > 1}

    notes_out = [{
        "id": nid, "title": (notes[nid].get("title") or "未命名").strip(),
        "folder": (notes[nid].get("folder") or "").strip(),
        "date": (notes[nid].get("createdAt") or "")[:10],
        "concepts": names,
    } for nid, names in by_note.items() if nid in notes]

    data = {
        "meta": {
            "notes": len(notes_out),
            "concepts": len(concepts),
            "explained": sum(1 for c in concepts if c["desc"]),
            "topics": len(topics),
            "uncategorized": len(unc),
            "multi": len(multi),
            "source": "34 篇真实笔记 · 结构抽取 + LLM 抽取 · 锚点校验",
        },
        "topics": sorted(topics, key=lambda t: (-len(t["concepts"]), t["id"])),
        "concepts": sorted(concepts, key=lambda c: (c["name"])),
        "notes": sorted(notes_out, key=lambda n: -len(n["concepts"])),
        "uncategorized": unc,
    }
    import os
    os.makedirs("frontend/src/data", exist_ok=True)
    json.dump(data, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    cov = sum(1 for c in concepts if c["topics"])
    print(f"{len(concepts)} 概念｜{len(topics)} 主题｜{len(unc)} 未分类｜多归属 {len(multi)}")
    print(f"  有主题归属 {cov}（{cov/len(concepts)*100:.0f}%）→ {OUT}")


if __name__ == "__main__":
    main()
