"""
知识点密度图：把"我的知识在哪块厚、哪块薄"画出来。

核心判断：**「概念数/千字」不是密度，是"无散文程度"**。
实测 `语法`（C++ 版本表）是 34.2 概念/千字，`d2l notebook`（真正的教材笔记）只有 0.7——
按单一比值排序，速查表永远赢，而它恰恰是最没有知识沉淀的形态。

所以密度拆成三个可测维度，各自回答不同问题：

  广度  概念数            这块铺开了多少
  深度  有作者解释的比例    这块解释清楚了多少
  结构  被多篇复用的比例    这块是否已经形成结构（当前语料全为 0）

画法用「单元图」而不是面积图：一个概念一个方格，填色=有作者解释，空心=只有名字。
面积图会让人以为多出来的面积有信息量，但概念数本来就只是个计数，方格最诚实。

产出 target/知识点密度图.html（数据内嵌，离线可看）。
"""
import json
import math
import re
from collections import Counter, defaultdict


def load():
    recs = [json.loads(l) for l in open("target/entities-single.jsonl", encoding="utf-8")]
    notes = {r["id"]: r for r in
             (json.loads(l) for l in open("target/notes-dump.jsonl", encoding="utf-8"))}
    rows = []
    for r in recs:
        n = notes.get(r["id"], {})
        ents = r["entities"]
        rows.append({
            "id": r["id"],
            "title": (r["title"] or "未命名").strip(),
            "folder": (n.get("folder") or "(未归类)").strip(),
            "chars": len(n.get("content") or ""),
            "concepts": len(ents),
            "explained": sum(1 for e in ents if e["desc"]),
            "items": [(e["name"], bool(e["desc"])) for e in ents],
        })
    return rows


def norm(s):
    return re.sub(r"[\s\-_·.,，、:：;；()（）\[\]【】「」\"'`*/]+", "", (s or "").lower())


def build(rows):
    reuse = defaultdict(set)
    for r in rows:
        for name, _ in r["items"]:
            reuse[norm(name)].add(r["id"])
    multi = {k for k, v in reuse.items() if len(v) > 1}

    for r in rows:
        r["density"] = r["concepts"] / max(r["chars"], 1) * 1000
        r["depth"] = r["explained"] / r["concepts"] if r["concepts"] else 0.0
        r["reused"] = sum(1 for name, _ in r["items"] if norm(name) in multi)

    folders = defaultdict(lambda: {"notes": 0, "chars": 0, "items": [], "titles": []})
    for r in rows:
        f = folders[r["folder"]]
        f["notes"] += 1
        f["chars"] += r["chars"]
        f["items"] += r["items"]
        if r["concepts"]:
            f["titles"].append(r["title"])

    out = []
    for name, v in folders.items():
        n = len(v["items"])
        exp = sum(1 for _, d in v["items"] if d)
        out.append({
            "folder": name, "notes": v["notes"], "chars": v["chars"],
            "concepts": n, "explained": exp,
            "depth": exp / n if n else 0.0,
            "density": n / max(v["chars"], 1) * 1000,
            "reused": sum(1 for nm, _ in v["items"] if norm(nm) in multi),
            "items": v["items"],
            "titles": v["titles"],
        })
    out.sort(key=lambda x: (-x["concepts"], x["folder"]))
    return out, rows


# ---------- 渲染 ----------
# 版式取自财经媒体的编辑体例：一句结论做标题、严格网格、单一强调色、
# 先灰度再上色（唯一的颜色只用来区分"有解释 / 没有"）。
COLS, CELL, GAP = 14, 16, 5
STEP = CELL + GAP
LABEL_W, CELL_X = 200, 212
BAR_X, BAR_W = 518, 92
PCT_X, DEN_R = 622, 700   # 右边缘与下方排名图对齐，两图共用同一条右边线
W = 700


def esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


def short_folder(f):
    """`深度学习/DRL` 拆成 父/子，父用弱色，子用主色。"""
    parts = [p for p in f.split("/") if p]
    return (parts[-1], "/".join(parts[:-1])) if len(parts) > 1 else (parts[0] if parts else f, "")


def render(folders, rows):
    total_c = sum(f["concepts"] for f in folders)
    total_e = sum(f["explained"] for f in folders)
    total_reuse = sum(f["reused"] for f in folders)
    total_chars = sum(f["chars"] for f in folders)
    empty_notes = sum(1 for r in rows if r["concepts"] == 0)

    # 标题直接给结论，不是给栏目名（McKinsey 的 action title 做法）
    if total_reuse == 0:
        head = f"{total_c} 个概念里 {total_e} 个有解释，但没有一个被第二篇笔记用到"
    else:
        head = f"{total_c} 个概念里 {total_e} 个有解释，{total_reuse} 个跨篇复用"
    deck = (f"{len(rows)} 篇笔记，{empty_notes} 篇没抽出任何概念。"
            f"下面每一格是一个概念——实心表示原文里能找到作者自己的那句解释，空心表示只剩一个名字。")

    out = []
    HDR = 24   # 列头区：解释率条 / 密度数字，只有一次，不重复到每行
    out.append(f'<text class="colhead" x="{BAR_X}" y="13" text-anchor="start">解释率</text>')
    out.append(f'<text class="colhead" x="{DEN_R}" y="13" text-anchor="end">概念/千字</text>')
    y = HDR
    # 零概念的文件夹不占行——三行破折号只会稀释图，收成图下一句话
    shown = [f for f in folders if f["concepts"] > 0]
    empty_folders = [f["folder"] for f in folders if f["concepts"] == 0]
    for f in shown:
        n, exp = f["concepts"], f["explained"]
        depth = f["depth"]
        rows_needed = max(1, math.ceil(n / COLS)) if n else 1
        h = max(40, rows_needed * STEP + 14)
        main, parent = short_folder(f["folder"])

        out.append(f'<g class="row">')
        out.append(f'<rect class="hit" x="-8" y="{y}" width="{W + 16}" height="{h}" rx="6"/>')
        # 父路径并入主标签（用弱色），第二行固定放篇数/字数——
        # 否则第二行一会儿是路径一会儿是统计，读者无从判断它是什么意思
        path = (f'<tspan class="fparent">{esc(parent)} / </tspan>' if parent else "")
        out.append(f'<text class="fname" x="0" y="{y + 19}">{path}{esc(main)}</text>')
        out.append(f'<text class="fmeta" x="0" y="{y + 33}">'
                   f'{f["notes"]} 篇 · {f["chars"]:,} 字</text>')

        for i, (name, has) in enumerate(f["items"]):
            cx = CELL_X + (i % COLS) * STEP
            cy = y + 6 + (i // COLS) * STEP
            cls = "on" if has else "off"
            out.append(f'<rect class="cell {cls}" x="{cx}" y="{cy}" width="{CELL}" '
                       f'height="{CELL}" rx="3"/>')
            out.append(f'<rect class="tip" x="{cx}" y="{cy}" width="{CELL}" height="{CELL}">'
                       f'<title>{esc(name)}｜{"有解释" if has else "仅名字"}</title></rect>')

        if n:
            # 0% 时不画轨道：一条空槽在这个尺度上会被读成"满格"，比不画更糟
            if depth > 0:
                out.append(f'<rect class="bar-bg" x="{BAR_X}" y="{y + 20}" width="{BAR_W}" '
                           f'height="3" rx="1.5"/>')
                out.append(f'<rect class="bar-fg" x="{BAR_X}" y="{y + 20}" '
                           f'width="{max(2, BAR_W * depth):.1f}" height="3" rx="1.5"/>')
            out.append(f'<text class="pct" x="{PCT_X}" y="{y + 25}">{depth * 100:.0f}%</text>')
            out.append(f'<text class="den" x="{DEN_R}" y="{y + 25}" text-anchor="end">'
                       f'{f["density"]:.1f}</text>')
        else:
            out.append(f'<text class="empty" x="{BAR_X}" y="{y + 25}">没有概念</text>')
            out.append(f'<text class="den" x="{DEN_R}" y="{y + 25}" text-anchor="end">—</text>')
        out.append('</g>')
        y += h

    H = y

    # —— 按笔记排名：用分段条而不是散点
    # 散点试过，两个问题：x 轴被一篇 14 概念的笔记压扁，其余挤在左侧；
    # 100% 解释率那一条横线上十几个点互相重叠，重叠即丢失计数。
    # 排名条不表达位置，只表达长短，这两个问题都不存在。
    dup = {t for t, c in Counter(r["title"] for r in rows).items() if c > 1}
    rank = sorted([r for r in rows if r["concepts"] >= 1],
                  key=lambda r: (-r["explained"], -r["concepts"], r["density"]))
    RX, RW = 226, 186
    bar_h, row_h = 8, 23
    bars, top = [], 12
    for i, r in enumerate(rank):
        y = i * row_h
        # 分母固定为全库最大概念数（14），条长才能跨行横向比较
        fx = RW * r["explained"] / 14
        wx = RW * (r["concepts"] - r["explained"]) / 14
        title = r["title"] if len(r["title"]) <= 15 else r["title"][:14] + "…"
        if r["title"] in dup:   # 同名笔记补目录，否则两行看起来是同一篇
            title += f'  ·{r["folder"].split("/")[-1][:6]}'
        bars.append(f'<g class="brow">')
        bars.append(f'<rect class="hit" x="-8" y="{y - 3}" width="{W + 16}" height="{row_h}" rx="5"/>')
        bars.append(f'<text class="bname" x="0" y="{y + 12}">{esc(title)}</text>')
        if fx > 0.5:
            bars.append(f'<rect class="bseg on" x="{RX}" y="{y + 3}" width="{fx:.1f}" '
                        f'height="{bar_h}" rx="{min(4, fx / 2):.1f}"/>')
        if wx > 0.5:
            bars.append(f'<rect class="bseg off" x="{RX + fx:.1f}" y="{y + 3}" width="{wx:.1f}" '
                        f'height="{bar_h}" rx="{min(4, wx / 2):.1f}"/>')
        bars.append(f'<text class="bnum" x="{RX + RW + 16}" y="{y + 12}">'
                    f'{r["explained"]}/{r["concepts"]}</text>')
        bars.append(f'<text class="bden" x="{W}" y="{y + 12}" text-anchor="end">'
                    f'{r["density"]:.1f}</text>')
        bars.append('</g>')
    BH = len(rank) * row_h

    # 两列对照：同一批笔记按单一密度指标排出来的两个极端
    dense = sorted([r for r in rank if r["depth"] < 0.6], key=lambda r: -r["density"])[:3]
    solid = sorted([r for r in rank if r["density"] < 3], key=lambda r: -r["explained"])[:3]

    def li(r):
        name = r["title"][:16]
        if r["title"] in dup:
            name += f' ·{r["folder"].split("/")[-1][:6]}'
        return (f'<li><span>{esc(name)}</span>'
                f'<em>解释 {r["explained"]} · {r["density"]:.1f}/千字</em></li>')

    trap_rows = "".join(li(r) for r in dense)
    solid_rows = "".join(li(r) for r in solid)

    return f"""<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>知识点密度图</title>
<style>
  :root {{
    --paper:#fbf9f5; --card:#fffefb; --ink:#1b1a17; --ink-soft:#4a4740;
    --muted:#7d776d; --hair:#e6e0d5; --hair-soft:#efeae1;
    --accent:#0d6e66; --accent-dim:#8fbfb8;
    --warn:#a9552a; --mid:#c08a3e; --cell-off:#ddd6c9;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --paper:#15161a; --card:#1b1d21; --ink:#eae7e0; --ink-soft:#c2beb5;
      --muted:#928c82; --hair:#2b2d33; --hair-soft:#232529;
      --accent:#4bb3a6; --accent-dim:#2f5f5a;
      --warn:#d98a5b; --mid:#c9a35e; --cell-off:#3a4149;
    }}
  }}
  * {{ box-sizing:border-box }}
  html {{ -webkit-text-size-adjust:100% }}
  body {{
    margin:0; background:var(--paper); color:var(--ink);
    font:15px/1.62 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
    font-feature-settings:"kern" 1,"liga" 1;
    -webkit-font-smoothing:antialiased;
  }}
  .wrap {{ max-width:880px; margin:0 auto; padding:64px 28px 96px }}

  .kicker {{
    font-size:11px; letter-spacing:.16em; text-transform:uppercase;
    color:var(--muted); margin-bottom:18px; font-weight:600;
  }}
  h1 {{
    font-family:Georgia,"Songti SC","Noto Serif CJK SC",serif;
    font-size:31px; line-height:1.34; font-weight:600; letter-spacing:-.2px;
    margin:0 0 16px; max-width:19em;
  }}
  .deck {{ font-size:14.5px; color:var(--ink-soft); max-width:34em; margin:0 0 34px }}
  .rule {{ height:1px; background:var(--hair); border:0; margin:0 0 26px }}

  .stats {{ display:flex; flex-wrap:wrap; gap:0 40px; margin:0 0 46px }}
  .stat {{ padding:2px 0 2px 13px; border-left:1px solid var(--hair) }}
  .stat:first-child {{ padding-left:0; border-left:0 }}
  .stat b {{
    display:block; font-size:26px; font-weight:600; letter-spacing:-.6px;
    font-variant-numeric:tabular-nums; line-height:1.2;
  }}
  .stat span {{ display:block; font-size:11.5px; color:var(--muted); margin-top:1px }}

  h2 {{
    font-size:12px; font-weight:600; letter-spacing:.1em; color:var(--muted);
    margin:0 0 4px; text-transform:uppercase;
  }}
  .hint {{ font-size:12.5px; color:var(--muted); margin:0 0 20px }}

  svg {{ display:block; width:100%; height:auto; overflow:visible }}
  .fname {{ font-size:13px; font-weight:600; fill:var(--ink) }}
  .fparent {{ font-size:11px; fill:var(--muted); letter-spacing:.02em }}
  .colhead {{ font-size:9.5px; fill:var(--muted); letter-spacing:.1em; text-transform:uppercase }}
  .fmeta {{ font-size:10.5px; fill:var(--muted); font-variant-numeric:tabular-nums }}
  .cell {{ transition:opacity .15s ease }}
  .cell.on {{ fill:var(--accent) }}
  .cell.off {{ fill:none; stroke:var(--cell-off); stroke-width:1.1 }}
  .hit {{ fill:transparent }}
  .row:hover .hit {{ fill:var(--hair-soft) }}
  .row:hover .cell.off {{ stroke:var(--muted) }}
  .tip {{ fill:transparent; cursor:default }}
  .bar-bg {{ fill:var(--cell-off) }}
  .bar-fg {{ fill:var(--accent) }}
  .pct {{ font-size:11.5px; fill:var(--ink-soft); font-variant-numeric:tabular-nums }}
  .den {{ font-size:11px; fill:var(--muted); font-variant-numeric:tabular-nums }}
  .empty {{ font-size:11px; fill:var(--muted); font-style:italic }}
  .bname {{ font-size:12px; fill:var(--ink-soft) }}
  .bseg.on {{ fill:var(--accent) }}
  .bseg.off {{ fill:var(--hair) }}
  .bnum {{ font-size:11px; fill:var(--ink-soft); font-variant-numeric:tabular-nums }}
  .bden {{ font-size:11px; fill:var(--muted); font-variant-numeric:tabular-nums }}
  .brow:hover .hit {{ fill:var(--hair-soft) }}

  .legend {{ display:flex; gap:20px; flex-wrap:wrap; margin-top:18px;
             font-size:11.5px; color:var(--muted) }}
  .legend i {{ display:inline-block; width:11px; height:11px; border-radius:3px;
               vertical-align:-1px; margin-right:6px }}
  .legend .solid {{ background:var(--accent) }}
  .legend .hollow {{ border:1.1px solid var(--cell-off) }}
  .legend .dot {{ border-radius:50%; background:var(--accent) }}
  .legend .dotw {{ border-radius:50%; background:var(--warn) }}

  .split {{ display:grid; grid-template-columns:1fr 1fr; gap:34px; margin-top:52px }}
  @media (max-width:700px) {{
    .split {{ grid-template-columns:1fr; gap:26px }}
    h1 {{ font-size:25px }}
    .wrap {{ padding:44px 20px 70px }}
  }}
  .split h3 {{ font-size:12px; letter-spacing:.06em; margin:0 0 10px; color:var(--muted);
               font-weight:600; text-transform:uppercase }}
  .split ul {{ list-style:none; margin:0; padding:0 }}
  .split li {{ display:flex; justify-content:space-between; gap:12px;
               padding:7px 0; border-bottom:1px solid var(--hair-soft); font-size:13px }}
  .split li:last-child {{ border-bottom:0 }}
  .split em {{ font-style:normal; color:var(--muted); font-size:11.5px; white-space:nowrap;
               font-variant-numeric:tabular-nums }}
  .foot {{ margin-top:52px; padding-top:16px; border-top:1px solid var(--hair);
           font-size:11.5px; color:var(--muted); line-height:1.7 }}
  .foot b {{ color:var(--ink-soft); font-weight:600 }}
</style></head><body><div class="wrap">

  <div class="kicker">知识图谱 · 密度</div>
  <h1>{esc(head)}</h1>
  <p class="deck">{esc(deck)}</p>
  <hr class="rule">

  <div class="stats">
    <div class="stat"><b>{len(rows)}</b><span>篇笔记</span></div>
    <div class="stat"><b>{total_c}</b><span>个概念</span></div>
    <div class="stat"><b>{total_e}</b><span>带解释</span></div>
    <div class="stat"><b>{total_reuse}</b><span>跨篇复用</span></div>
    <div class="stat"><b>{total_c / max(total_chars, 1) * 1000:.1f}</b><span>概念 / 千字</span></div>
  </div>

  <h2>按文件夹</h2>
  <p class="hint">每一格是一个概念；行右侧的细条是该文件夹的解释率，最右是概念密度。</p>
  <svg viewBox="0 0 {W} {H}" role="img" aria-label="按文件夹的知识点密度方格图">
    {''.join(out)}
  </svg>
  <div class="legend">
    <span><i class="solid"></i>有作者写的解释</span>
    <span><i class="hollow"></i>只抽到名字</span>
    <span>悬停方格看是哪个概念</span>
  </div>
  {f'<p class="hint" style="margin-top:10px">另有 {len(empty_folders)} 个文件夹没有产出概念：'
     f'{"、".join(esc(x) for x in empty_folders)}。</p>' if empty_folders else ''}

  <h2 id="by-note" style="margin-top:58px">按笔记 · 谁厚谁薄</h2>
  <p class="hint">条形总长是概念数，深色部分是其中有解释的。排序按深色部分从多到少。</p>
  <svg viewBox="0 0 {W} {BH + 4}" role="img" aria-label="各笔记的概念数与解释数排名">
    <text class="colhead" x="{RX + RW + 16}" y="-8" text-anchor="start">解释 / 总数</text>
    <text class="colhead" x="{W}" y="-8" text-anchor="end">概念/千字</text>
    {''.join(bars)}
  </svg>

  <div class="split">
    <div>
      <h3>高密度 · 低沉淀</h3>
      <ul>{trap_rows}</ul>
    </div>
    <div>
      <h3>低密度 · 高沉淀</h3>
      <ul>{solid_rows}</ul>
    </div>
  </div>

  <div class="foot">
    <b>为什么不用单一密度。</b>「概念 / 千字」奖励的是无散文，不是知识厚度——
    左右两列就是同一批笔记按这个指标排出来的两种极端。所以密度只在最右侧作对照，
    不参与任何排序。判定"厚"用两个独立维度：概念数（广度）与有解释的比例（深度）。
  </div>
</div></body></html>"""


if __name__ == "__main__":
    rows = load()
    folders, rows = build(rows)
    html = render(folders, rows)
    open("target/知识点密度图.html", "w", encoding="utf-8").write(html)
    print(f"{len(rows)} 篇 / {sum(f['concepts'] for f in folders)} 概念 → target/知识点密度图.html")
    print(f"  有解释 {sum(f['explained'] for f in folders)}｜跨篇复用 {sum(f['reused'] for f in folders)}")
