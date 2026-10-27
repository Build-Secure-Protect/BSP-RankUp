#!/usr/bin/env python3
"""
Mock-ups for PA-3 (D88 to D90): the PREFIXES tab once RankUp draws LuckPerms suffixes too. Three layouts, drawn with
the CurseForge pictures' screen helpers and holo.css (GUI units, as client/AdminScreen.java), with the chosen art.

  python3 tools/prefix_art/suffix_mockups.py      writes tools/preview/prefix_suffix_mockups.html
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "curseforge"))

import gen_pages as gp  # noqa: E402
from gen_pages import W, H, btn, chip, inset, screen_frame, t  # noqa: E402
from gen_prefix_art import ACHIEVEMENTS, LADDER, MC, STAFF, data_uri, render  # noqa: E402

ART = render("BSP")
STAFF_ORDER = ["admin", "staff", "moderator"]
TEXT = {g: (f"[{n}]", MC[c]) for g, n, c, *_ in LADDER + STAFF}
TEXT.update({g: (f"✦ {n}", MC[c]) for g, n, c, *_ in ACHIEVEMENTS})
HOW = {g: how for g, _, _, _, how in ACHIEVEMENTS}


def hexc(c):
    return "#%06x" % c


def im(group, k=1, ring=False):
    a = ART[group]
    ring_css = "box-shadow:0 0 0 1px #19d3b0,0 0 4px rgba(25,211,176,.7);" if ring else ""
    return (f'<img src="{data_uri(a)}" alt="{group}" style="width:{a.w * k}px;height:{9 * k}px;image-rendering:pixelated;'
            f'display:inline-block;vertical-align:middle;{ring_css}">')


def chat_line(x, y, w, selected, player=("PieFarmer", None, "warden", ["totem_reaver", "headhunter"])):
    """One chat line: the staff and ladder images, the name, the achievement images; the selected piece ringed."""
    name, staff, rung, achs = player
    parts = ([im(staff, ring=staff == selected)] if staff else []) + [im(rung, ring=rung == selected)]
    after = [im(a, ring=a == selected) for a in achs]
    line = ('&lt;' + '<span style="display:inline-flex;gap:1px;vertical-align:middle">' + "".join(parts) + "</span>"
            + f' {name} ' + '<span style="display:inline-flex;gap:1px;vertical-align:middle">' + "".join(after) + "</span>"
            + '&gt; anyone up for a raid?')
    return (f'<div class="abs" style="left:{x}px;top:{y}px;width:{w}px;height:13px;background:rgba(0,0,0,0.5)"></div>'
            f'<div class="abs" style="left:{x + 4}px;top:{y + 2}px;font-size:8px;line-height:9px;color:#fff;text-shadow:1px 1px #000;white-space:nowrap">{line}</div>')


def tooltip(x, y, group):
    text, colour = TEXT[group]
    return (f'<div class="abs" style="left:{x}px;top:{y}px;background:rgba(16,0,16,0.94);border:1px solid #5000ff;padding:2px 3px;'
            f'font-size:7px;line-height:8px;color:#fff;white-space:nowrap"><span style="color:{hexc(colour)}">{text}</span><br>'
            f'<span style="color:#aaa">{HOW[group]}</span></div>')


def rows_html(rows, rowh=12):
    """rows: ('h', label) headers and (group, kind) entries; kind 'suffix' tags the row."""
    s = ""
    for i, r in enumerate(rows):
        ry = 1 + i * rowh
        if r[0] == "h":
            s += t("t5 " + r[2] if len(r) > 2 else "t5 tq", 6, ry + 4, r[1])
            continue
        group, sel = r[0], r[1]
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:{rowh}px">'
        s += f'<span class="t75 {"text" if sel else "muted"}" style="flex:1">{group}</span>'
        s += f'<span style="margin-right:2px;display:flex">{im(group)}</span></div>'
    return s


def scrollbar(x, y, h, top, size):
    return (f'<div class="abs" style="left:{x}px;top:{y}px;width:2px;height:{h}px;background:#1b2028"></div>'
            f'<div class="abs" style="left:{x}px;top:{y + top}px;width:2px;height:{size}px;background:#4a5060"></div>')


def detail(px, py, group, kind_line):
    """The right pane for a suffix: its text, image, the new hover note, the chat line, the files."""
    text, colour = TEXT[group]
    s = t("t1 text", px, py, group.upper()) + t("t6 hint", W - 8, py + 2, kind_line, "transform:translateX(-100%)")
    s += t("t5 hint", px, py + 14, "SUFFIX TEXT") + inset(px, py + 21, 150, 13) + "</div>"
    s += t("t75", px + 4, py + 24, f'<span style="color:{hexc(colour)}">{text}</span>')
    s += t("t6 hint", px + 156, py + 25, "Change this text in LuckPerms.")
    s += t("t5 hint", px, py + 40, "IMAGE") + inset(px, py + 47, 296, 26) + "</div>"
    s += f'<div class="abs" style="left:{px + 6}px;top:{py + 55}px">{im(group)}</div>' + t("t5 hint", px + 20, py + 57, "line size")
    s += f'<div class="abs" style="left:{px + 52}px;top:{py + 51}px">{im(group, 2)}</div>' + t("t5 hint", px + 74, py + 57, "x2")
    s += t("t6 hint", px + 290, py + 51, f"{group}.png - {ART[group].w} x 9", "transform:translateX(-100%)")
    s += t("t5 vio", px, py + 79, "HOVER NOTE  new") + inset(px, py + 86, 230, 13) + "</div>"
    s += t("t75 text", px + 4, py + 89, HOW[group]) + btn(px + 236, py + 86, 60, 13, "SAVE", "")
    s += t("t5 hint", px, py + 105, "IN CHAT")
    s += chat_line(px, py + 112, 296, group)
    s += tooltip(px + 150, py + 127, group)
    s += t("t5 tq", px, py + 152, "FILES IN prefix_images/") + inset(px, py + 159, 296, 50)
    for i, f in enumerate([group, "headhunter", "titan_slayer", "warden"]):
        ry, sel = 1 + i * 12, i == 0
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:12px"></div>'
        s += f'<div class="abs" style="left:4px;top:{ry + 1}px">{im(f)}</div>'
        s += t("t6 " + ("text" if sel else "muted"), 60, ry + 3, f"{f}.png") + t("t5 hint", 150, ry + 3, f"{ART[f].w} x 9")
        s += t("t5 " + ("tq" if sel else "muted"), 290, ry + 3, "in use here" if sel else f"on {f}", "transform:translateX(-100%)")
    s += "</div>"
    s += btn(px, py + 214, 70, 13, "USE IMAGE", "") + btn(px + 73, py + 214, 84, 13, "SHOW TEXT AGAIN", "bad") + t("t5 hint", px + 164, py + 217, "saved for every server")
    return s


STATUS = "15 prefixes - 7 suffixes - 22 with an image - 23 files"
LADDER_GROUPS = [g for g, *_ in LADDER]
ACH_GROUPS = [g for g, *_ in ACHIEVEMENTS]


def option_a():
    """One list, with a section for the suffixes after the ladder."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PREFIXES)
    rows = [(g, False) for g in LADDER_GROUPS[7:]] + [("h", "AFTER THE NAME - LUCKPERMS SUFFIXES", "vio")]
    rows += [(g, g == "totem_reaver") for g in ACH_GROUPS]
    s += inset(8, 40, 200, 216) + rows_html(rows, 14) + "</div>"
    s += scrollbar(204, 42, 212, 120, 88)
    s += btn(8, 262, 60, 13, "REFRESH", "") + chip(71, 262, 92, 13, "only without image")
    s += detail(216, 40, "totem_reaver", "a LuckPerms group's suffix - after the name")
    s += t("t6 tq", 10, H - 12, "Before the name: STAFF, LADDER RANKUP, AWARDS. After it: the suffixes.")
    return s + "</div>"


def option_b():
    """Two switches over the list: before the name, after the name."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PREFIXES)
    s += chip(8, 40, 98, 13, "BEFORE THE NAME  15", "big") + chip(110, 40, 98, 13, "AFTER THE NAME  7", "big on")
    rows = [("h", "ACHIEVEMENTS", "vio")] + [(g, g == "totem_reaver") for g in ACH_GROUPS]
    s += inset(8, 57, 200, 199) + rows_html(rows, 14) + "</div>"
    s += btn(8, 262, 60, 13, "REFRESH", "") + chip(71, 262, 92, 13, "only without image")
    s += detail(216, 40, "totem_reaver", "a LuckPerms group's suffix")
    s += t("t6 tq", 10, H - 12, "The switch picks which side of the name the list shows.")
    return s + "</div>"


def option_c():
    """The name line first: a player's line built piece by piece, every tag as a tile, a compact editor."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PREFIXES)
    s += t("t5 tq", 8, 40, "NAME LINE") + t("t5 hint", 50, 40, "preview as") + chip(86, 37, 70, 11, "PieFarmer", "big on")
    s += inset(8, 50, 504, 36) + "</div>"
    pieces = [("warden", "ladder"), (None, "name"), ("totem_reaver", "suffix"), ("headhunter", "suffix")]
    x = 20
    for g, label in pieces:
        if g is None:
            s += t("text", x, 58, '<span style="font-size:16px;line-height:18px;color:#fff;text-shadow:2px 2px #000">PieFarmer</span>')
            s += t("t5 hint", x + 22, 79, label)
            x += 92
            continue
        sel = g == "totem_reaver"
        s += f'<div class="abs" style="left:{x - 3}px;top:55px;width:{ART[g].w * 2 + 6}px;height:24px;border:1px solid {"#19d3b0" if sel else "#2a2f3a"};background:{"rgba(25,211,176,.13)" if sel else "transparent"}"></div>'
        s += f'<div class="abs" style="left:{x}px;top:58px">{im(g, 2)}</div>'
        s += t("t5 " + ("tq" if sel else "hint"), x, 81, label)
        x += ART[g].w * 2 + 14
    s += t("t6 hint", 504, 81, "click a piece to edit it", "transform:translateX(-100%)")
    # the tiles
    s += t("t5 tq", 8, 92, "BEFORE THE NAME") + inset(8, 99, 246, 92)
    before = STAFF_ORDER + LADDER_GROUPS
    for i, g in enumerate(before):
        cx, cy = 3 + (i % 4) * 60, 3 + (i // 4) * 22
        s += f'<div class="abs" style="left:{cx}px;top:{cy}px;width:57px;height:20px;border:1px solid #2a2f3a;background:#10151c"></div>'
        s += f'<div class="abs" style="left:{cx + 3}px;top:{cy + 2}px">{im(g)}</div>' + t("t5 hint", cx + 3, cy + 13, g)
    s += "</div>"
    s += t("t5 vio", 8, 196, "AFTER THE NAME") + inset(8, 203, 246, 53)
    for i, g in enumerate(ACH_GROUPS):
        cx, cy = 3 + (i % 4) * 60, 3 + (i // 4) * 24
        sel = g == "totem_reaver"
        s += f'<div class="abs" style="left:{cx}px;top:{cy}px;width:57px;height:22px;border:1px solid {"#19d3b0" if sel else "#2a2f3a"};background:{"rgba(25,211,176,.13)" if sel else "#10151c"}"></div>'
        s += f'<div class="abs" style="left:{cx + 3}px;top:{cy + 2}px">{im(g)}</div>' + t("t5 " + ("tq" if sel else "hint"), cx + 3, cy + 14, g)
    s += "</div>"
    # the compact editor
    px, py = 262, 92
    text, colour = TEXT["totem_reaver"]
    s += t("t1 text", px, py, "TOTEM_REAVER") + t("t6 hint", W - 8, py + 2, "suffix", "transform:translateX(-100%)")
    s += t("t5 hint", px, py + 14, "SUFFIX TEXT") + inset(px, py + 21, 250, 13) + "</div>"
    s += t("t75", px + 4, py + 24, f'<span style="color:{hexc(colour)}">{text}</span>') + t("t5 hint", px + 140, py + 25, "set in LuckPerms")
    s += t("t5 vio", px, py + 40, "HOVER NOTE  new") + inset(px, py + 47, 196, 13) + "</div>" + t("t75 text", px + 4, py + 50, HOW["totem_reaver"])
    s += btn(px + 200, py + 47, 50, 13, "SAVE", "")
    s += t("t5 tq", px, py + 66, "IMAGE FILE") + inset(px, py + 73, 250, 62)
    for i, f in enumerate(["totem_reaver", "headhunter", "titan_slayer", "bulwark", "founder"]):
        ry = 1 + i * 12
        s += f'<div class="row{" sel" if i == 0 else ""}" style="top:{ry}px;height:12px"></div>'
        s += f'<div class="abs" style="left:4px;top:{ry + 1}px">{im(f)}</div>' + t("t6 " + ("text" if i == 0 else "muted"), 20, ry + 3, f"{f}.png")
        s += t("t5 " + ("tq" if i == 0 else "hint"), 244, ry + 3, "in use here" if i == 0 else f"on {f}", "transform:translateX(-100%)")
    s += "</div>"
    s += btn(px, py + 141, 70, 13, "USE IMAGE", "") + btn(px + 73, py + 141, 84, 13, "SHOW TEXT AGAIN", "bad") + btn(px + 160, py + 141, 60, 13, "REFRESH", "")
    s += tooltip(px + 60, py - 40, "totem_reaver")
    s += t("t6 tq", 10, H - 12, "The line is built the way LuckPerms formats it for that player.")
    return s + "</div>"


OPTIONS = [
    ("A", "One list, a suffix section", option_a,
     "Today's PREFIXES, with a new section at the end of the list for what comes after the name. The editor adds a HOVER NOTE, and IN CHAT shows a whole line with the chosen piece ringed.",
     "Smallest change; one place to look.", "The list gets long (25 rows with headers), so the suffixes sit below a scroll."),
    ("B", "Before / after switch", option_b,
     "The same editor. Two switches over the list pick which side of the name it shows: 15 before, 7 after.",
     "Short lists; prefixes and suffixes never mix.", "One more click; you can't see both sides at once."),
    ("C", "The name line first", option_c,
     "Across the top, one player's name line built as LuckPerms formats it; click a piece to edit it. Below, every tag as a tile, split before and after the name, and a compact editor.",
     "Shows exactly what players will see; good for checking a stack.", "The biggest rework; the editor and file list get less room."),
]

QUESTIONS = [
    ("Layout", "A, B or C, or parts combined.", "A: the smallest change, and it keeps one list."),
    ("How RankUp reads the name line", "LuckPerms gives RankUp one formatted prefix (\"[Admin] [Legend] \") and one suffix (\" ✦ Champion ✦ Founder\"). RankUp splits them into the prefixes and suffixes it knows: each group's own and RankUp's titles. Each known piece is drawn as its image; anything else stays text.",
     "Yes. Whatever you set in LuckPerms' meta-formatting is what shows."),
    ("Hover note", "D89 says hovering an icon shows how it's earned, but LuckPerms has nowhere to keep that, so RankUp keeps a note per tag. It's typed on PREFIXES and shared through the database.",
     "Yes, on suffixes and prefixes alike. commands.txt fills the seven achievement notes from the bsp-ranks table."),
    ("Spacing", "LuckPerms puts a space between pieces. Between two images that's 4 pixels.",
     "1 pixel between two images (as D68), a normal space next to text and on each side of the name."),
    ("Commands", "",
     "/rankup prefix image|clear suffix <group> <file>, /rankup prefix note prefix|suffix <group> <text>, and prefix list shows suffixes."),
    ("The OPTIONS switch", "\"Show prefixes\" today.", "One switch for both, renamed \"Show prefixes and suffixes\"."),
    ("Version", "The admin packets gain suffix rows and notes, so the protocol changes.",
     "Protocol 4 and version 0.5.0: the server and the modpack update together."),
    ("The rank screen", "The player's own rank screen shows titles per ladder.", "Unchanged for now; achievements already show on AWARDS when they're RankUp awards."),
]


def holo_css():
    css = (ROOT / "tools" / "curseforge" / "holo.css").read_text()
    css = re.sub(r'@import url\([^)]*\);', "", css)
    css = css.replace(":root {", ".stage {")
    css = re.sub(r"html, body \{[^}]*\}", "", css)
    css = re.sub(r"body \{[^}]*\}", "", css)
    css = re.sub(r"/\* diagrams \*/.*", "", css, flags=re.S)  # the caption classes clash with the page's
    return css


def page():
    opts = ""
    for key, name, fn, what, good, bad in OPTIONS:
        opts += f"""
<section class="opt" id="opt-{key.lower()}">
  <header><span class="tag">Option {key}</span><h2>{name}</h2><p>{what}</p>
  <div class="pc"><p><b>For</b> {good}</p><p><b>Against</b> {bad}</p></div></header>
  <div class="frame"><div class="stage">{fn()}</div></div>
</section>"""
    qs = "".join(f'<li><b>{i + 1}. {q}</b>{f"<p>{why}</p>" if why else ""}<p class="def">Default: {d}</p></li>' for i, (q, why, d) in enumerate(QUESTIONS))
    return f"""<title>RankUp Suffixes on PREFIXES</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=VT323&family=Atkinson+Hyperlegible:wght@400;700&display=swap">
<style>
/* Layout: per option a brief, then the admin screen at 2x its GUI size (520 x 300 units) on a scrolling frame; the questions last. One dark look on purpose, the admin screen's own. */
:root {{
  --pg-bg: #0b0e13; --pg-panel: #10151c; --pg-line: #2a2f3a; --pg-fg: #e6eaf2; --pg-muted: #9aa3b5;
  --pg-accent: #19d3b0; --pg-vio: #b58cff; --pg-gold: #ffd23a;
  --pg-display: "VT323", "Courier New", monospace; --pg-body: "Atkinson Hyperlegible", system-ui, sans-serif;
  color-scheme: dark;
}}
body {{ background: var(--pg-bg); color: var(--pg-fg); font-family: var(--pg-body); font-size: 15px; line-height: 1.5; }}
.wrap {{ max-width: 1104px; margin: 0 auto; padding-block: 28px 64px; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); display: grid; gap: 36px; }}
h1, h2 {{ font-family: var(--pg-display); font-weight: 400; margin: 0; text-wrap: balance; }}
h1 {{ font-size: 40px; color: var(--pg-accent); }}
h2 {{ font-size: 30px; }}
p {{ margin: 0; max-width: 75ch; }}
.lead {{ color: var(--pg-muted); display: grid; gap: 8px; }}
.lead b {{ color: var(--pg-fg); }}
.opt {{ display: grid; gap: 12px; }}
.opt header {{ display: grid; gap: 6px; }}
.tag {{ font-family: var(--pg-display); font-size: 20px; color: var(--pg-gold); }}
.pc {{ display: flex; flex-wrap: wrap; gap: 6px 28px; color: var(--pg-muted); }}
.pc b {{ color: var(--pg-accent); }}
.pc p + p b {{ color: var(--pg-gold); }}
.frame {{ overflow-x: auto; border: 1px solid var(--pg-line); background: #06080c; }}
.stage {{ position: relative; width: 552px; height: 332px; zoom: 2; font-family: "VT323", monospace; color: var(--text); -webkit-font-smoothing: none; }}
.qs {{ background: var(--pg-panel); border: 1px solid var(--pg-line); padding: 20px; display: grid; gap: 12px; }}
.qs ol {{ list-style: none; margin: 0; padding: 0; display: grid; gap: 14px; }}
.qs li p {{ color: var(--pg-muted); margin-top: 2px; }}
.qs .def {{ color: var(--pg-fg); }}
{holo_css()}
</style>
<div class="wrap">
  <div class="lead">
    <h1>RankUp Suffixes on PREFIXES</h1>
    <p><b>PA-3:</b> RankUp starts drawing LuckPerms <b>suffixes</b> (the seven achievements, after the name), and shows the staff tag and the ladder tag together. Images come from what LuckPerms formats (D88 to D90). These are three ways to manage it on the PREFIXES tab.</p>
    <p>Each screen is the admin screen at twice its GUI size with your new art. Totem Reaver is selected, and the tooltip shows the new hover note.</p>
  </div>
  {opts}
  <section class="qs" id="questions"><h2>Questions</h2><ol>{qs}</ol></section>
</div>
"""


def main():
    target = ROOT / "tools" / "preview" / "prefix_suffix_mockups.html"
    target.write_text(page(), encoding="utf-8")
    print("wrote", target.relative_to(ROOT))


if __name__ == "__main__":
    main()
