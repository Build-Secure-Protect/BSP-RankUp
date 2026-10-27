#!/usr/bin/env python3
"""
Mock-ups for PL-1 (D80): the PLAYERS tab for every ladder. Three layouts, drawn with the CurseForge pictures' screen
helpers and holo.css (GUI units, as client/AdminScreen.java), with the chosen prefix art for the RANKUP ladder.

The second ladder (PVP) and the three awards are example data, there to show how the tab grows past one ladder.

  python3 tools/prefix_art/players_mockups.py      writes tools/preview/players_ladders_mockups.html
"""
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "curseforge"))

import gen_pages as gp  # noqa: E402
from gen_pages import H, W, btn, chip, inset, screen_frame, t  # noqa: E402
from gen_prefix_art import LADDER, MC  # noqa: E402
from suffix_mockups import hexc, holo_css, im  # noqa: E402

STATUS = "2 ladders - 3 awards - 6 players shown"
RUNGS = {g: (n, MC[c]) for g, n, c, _ in LADDER}
PVP = [("brawler", "Brawler", 0x55FF55), ("duelist", "Duelist", 0xFFAA00), ("gladiator", "Gladiator", 0xFF5555), ("champion", "Champion", 0xAA00AA)]
PVP_RUNGS = {g: (n, c) for g, n, c in PVP}

# name, online, rankup rung, rankup points, pvp rung, pvp points, awards reached
PLAYERS = [("Mrgregles", True, "warden", 4210, "duelist", 775, ["bulwark", "founder"]),
           ("Tyler_S", True, "recruit", 640, "brawler", 120, ["founder"]),
           ("Kestrel", True, "vanguard", 6380, "gladiator", 1630, ["bulwark", "headhunter", "founder"]),
           ("Bramble", False, "settler", 95, "brawler", 0, []),
           ("Alex_Builds", False, "scout", 1110, "brawler", 60, ["founder"]),
           ("Sam_W", False, "wanderer", 30, "", 0, [])]
SEL = PLAYERS[0]

# ladder: name, track, counted signals, rungs, the selected player's rung, index, points, this season, next rung, at
LADDERS = [("RANKUP", "rankup", "every signal", 12, "Warden", 6, 4210, 640, "Vanguard", 6000),
           ("PVP", "pvp", "player kills, totems stolen", 4, "Duelist", 2, 775, 775, "Gladiator", 1500)]
# award: group, shown name, counted, points needed, the selected player's points, reached
AWARDS = [("bulwark", "Bulwark", "totems protected", 1500, 1500, True), ("founder", "Founder", "minutes played", 60, 60, True),
          ("headhunter", "Headhunter", "player kills", 1250, 775, False)]
# signal, count, points, counted by (R = RANKUP, P = PVP)
COUNTS = [("minutes played", 1180, 118, "R"), ("player kills", 31, 775, "RP"), ("mob kills", 212, 212, "R"),
          ("totems stolen", 6, 900, "RP"), ("totems protected", 4, 240, "R"), ("claims bought", 2, 80, "R"),
          ("deaths", 17, 0, ""), ("totems lost", 3, 0, "")]


def rank_tag(group, scale=1):
    """A rung as players see it: the image for the RANKUP rungs, coloured text for the example PVP ones."""
    if group in RUNGS:
        return im(group, scale)
    if group in PVP_RUNGS:
        n, c = PVP_RUNGS[group]
        return f'<span style="color:{hexc(c)}">[{n}]</span>'
    return '<span class="muted">-</span>'


def bar(x, y, w, frac, colour="var(--tq)", h=3):
    return (f'<div class="abs" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;background:#1b2028"></div>'
            f'<div class="abs" style="left:{x}px;top:{y}px;width:{int(w * frac)}px;height:{h}px;background:{colour}"></div>')


def search_box():
    return inset(8, 40, 164, 14) + "</div>" + t("t75 hint", 12, 43, "find player")


def player_list(rank_of, points_of, y=56, h=198):
    """The left list: online dot, name, a rank and the points beside it."""
    s = inset(8, y, 164, h)
    for i, p in enumerate(PLAYERS):
        ry, sel = 1 + i * 14, p is SEL
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:14px"></div>'
        s += f'<div class="abs" style="left:6px;top:{ry + 5}px;width:3px;height:3px;background:{"var(--tq)" if p[1] else "var(--line)"}"></div>'
        s += t("t75 " + ("text" if sel else "muted"), 13, ry + 3, p[0])
        s += f'<div class="abs t6" style="left:84px;top:{ry + 2}px">{rank_of(p)}</div>'
        s += t("t6 gold", 158, ry + 4, points_of(p), "transform:translateX(-100%)")
    return s + "</div>" + t("t6 hint", 8, 262, "6 players")


def counts_grid(x, y, w, h, cols, rows, mark=None):
    """The counts; mark(sig) decides a line's colour (counted on what is shown, or not)."""
    s = inset(x, y, w, h)
    cw = (w - 8) // cols
    for i, (sig, n, pts, by) in enumerate(COUNTS[:cols * rows]):
        cx, ry = 4 + (i // rows) * cw, 3 + (i % rows) * 10
        on = mark(by) if mark else bool(by)
        tags = "".join(f'<span style="color:{"var(--tq)" if b == "R" else "var(--vio)"};margin-left:2px">{b}</span>' for b in by)
        s += t("t6 " + ("text" if on else "muted"), cx, ry, sig + (f' <span class="t5">{tags}</span>' if mark is None else ""))
        s += t("t6 " + ("gold" if on else "muted"), cx + cw - 6, ry, f"{n}  ({pts})", "transform:translateX(-100%)")
    return s + "</div>"


def award_chips(x, y, w, k=1):
    """Each award as a chip: reached ones lit, the rest with their progress."""
    s, cx = "", x
    for g, n, _, need, have, got in AWARDS:
        cw = 100
        s += f'<div class="abs" style="left:{cx}px;top:{y}px;width:{cw}px;height:20px;border:1px solid {"var(--vio)" if got else "var(--line)"};background:{"rgba(181,140,255,.12)" if got else "#10151c"}"></div>'
        s += f'<div class="abs" style="left:{cx + 3}px;top:{y + 2}px">{im(g)}</div>'
        s += t("t6 " + ("vio" if got else "muted"), cx + 16, y + 3, n)
        if got:
            s += t("t5 vio", cx + cw - 3, y + 4, "REACHED", "transform:translateX(-100%)")
            s += t("t5 hint", cx + 16, y + 12, f"{need} points")
        else:
            s += t("t5 gold", cx + cw - 3, y + 4, f"{have}/{need}", "transform:translateX(-100%)") + bar(cx + 16, y + 14, cw - 20, have / need, "var(--gold)", 2)
        cx += cw + 4
    return s


def option_a():
    """Ladder chips on the pane (as LADDER, D51): the list and the pane follow the chosen ladder."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PLAYERS)
    s += search_box() + player_list(lambda p: rank_tag(p[2]), lambda p: str(p[3]))
    px, py = 180, 40
    s += t("t1 text", px, py, "Mrgregles") + t("t6 tq", px + 56, py + 2, "online")
    cx = px
    for name, *_ in LADDERS:
        s += chip(cx, py + 13, 52, 12, name, "big on" if name == "RANKUP" else "big")
        cx += 55
    s += chip(cx, py + 13, 62, 12, "AWARDS 2/3", "big vio")
    _, track, counts, n, rung, idx, pts, season, nxt, at = LADDERS[0]
    s += f'<div class="abs" style="left:{px}px;top:{py + 30}px">{im("warden")}</div>'
    s += t("t6 muted", px + 50, py + 31, f"rung {idx} of {n} on RANKUP - track {track}")
    s += t("t75 gold", px, py + 43, f"{pts} points - {season} this season") + t("t6 hint", px + 332, py + 44, f"next: {nxt} at {at}", "transform:translateX(-100%)")
    s += bar(px, py + 54, 332, pts / at)
    s += t("t5 hint", px, py + 61, "COUNTS - bright ones count on RANKUP")
    s += counts_grid(px, py + 68, 332, 64, 2, 6, mark=lambda by: "R" in by)
    s += t("t6 muted", px, py + 136, "bonus from admins: -105 (added on every ladder and award)")
    by = py + 148
    s += t("t6 muted", px, by + 3, "SET RANK ON RANKUP TO")
    bx, row = px + 74, 0
    for g, n, *_ in LADDER:
        w = 40
        if bx + w > px + 332 - 44 * (row == 0):
            row, bx = row + 1, px + 74
        s += chip(bx, by + row * 15, w, 13, n, "on" if g == "warden" else "")
        bx += w + 3
    s += btn(px + 292, by, 40, 13, "CANCEL", "")
    s += btn(px, by + 50, 70, 13, "SET RANK") + btn(px + 74, by + 50, 76, 13, "ADD POINTS", "gold") + btn(px + 262, by + 50, 70, 13, "RESET", "bad")
    s += t("t5 hint", px, by + 66, "(SET RANK opens the rungs above; shown open here)")
    s += t("t6 tq", 10, H - 12, "The chips pick a ladder for the list and the pane. AWARDS shows each award's progress instead.")
    return s + "</div>"


def option_b():
    """Every ladder at once: one row per ladder in the pane, awards as chips, the counts below."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PLAYERS)
    s += search_box() + player_list(lambda p: rank_tag(p[2]), lambda p: str(p[3]))
    px, py = 180, 40
    s += t("t1 text", px, py, "Mrgregles") + t("t6 tq", px + 56, py + 2, "online") + t("t6 hint", px + 332, py + 2, "bonus from admins: -105", "transform:translateX(-100%)")
    s += t("t5 tq", px, py + 14, "LADDERS") + inset(px, py + 21, 332, 44)
    for x0, head in ((4, "LADDER"), (50, "RANK"), (120, "POINTS"), (162, "THIS SEASON"), (214, "TO THE NEXT RUNG")):
        s += t("t5 hint", x0, 3, head)
    for i, (name, track, counts, n, rung, idx, pts, season, nxt, at) in enumerate(LADDERS):
        ry = 12 + i * 15
        s += t("t75 text", 4, ry + 2, name)
        s += f'<div class="abs t6" style="left:50px;top:{ry + 1}px">{rank_tag("warden" if i == 0 else "duelist")}</div>'
        s += t("t5 hint", 102, ry + 3, f"{idx}/{n}")
        s += t("t6 gold", 120, ry + 3, str(pts)) + t("t6 muted", 162, ry + 3, str(season))
        s += t("t5 hint", 214, ry + 1, f"{nxt} at {at}") + bar(214, ry + 8, 70, pts / at)
        s += btn(294, ry, 32, 12, "SET", "tq" if i == 1 else "")
    s += "</div>"
    # SET on PVP, open
    s += t("t6 muted", px, py + 70, "SET PVP TO") + t("t5 hint", px, py + 78, "no rewards")
    bx = px + 44
    for g, n, c in PVP:
        s += chip(bx, py + 69, 48, 13, n, "on" if g == "duelist" else "")
        bx += 51
    s += btn(px + 292, py + 69, 40, 13, "CANCEL", "")
    s += t("t5 vio", px, py + 88, "AWARDS  2 of 3") + award_chips(px, py + 95, 332)
    s += t("t5 tq", px, py + 120, "COUNTS") + t("t5 hint", px + 30, py + 120, "the letters say which ladder counts it:") + t("t5 tq", px + 136, py + 120, "R RANKUP") + t("t5 vio", px + 166, py + 120, "P PVP")
    s += counts_grid(px, py + 127, 332, 44, 2, 4)
    by = py + 178
    s += btn(px, by, 76, 13, "ADD POINTS", "gold") + btn(px + 262, by, 70, 13, "RESET", "bad")
    s += t("t5 hint", px + 82, by + 4, "adds to every ladder and award") + t("t5 hint", px + 256, by + 4, "every ladder", "transform:translateX(-100%)")
    s += t("t6 tq", 10, H - 12, "One look shows every ladder; SET opens that ladder's rungs under the table.")
    return s + "</div>"


def option_c():
    """A wide table, one column per ladder; the chosen player's tools in a strip below."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PLAYERS)
    s += inset(8, 40, 164, 14) + "</div>" + t("t75 hint", 12, 43, "find player")
    s += t("t6 hint", 180, 43, "sort by") + chip(206, 40, 52, 12, "RANKUP", "on") + chip(261, 40, 40, 12, "PVP") + chip(304, 40, 40, 12, "NAME")
    s += inset(8, 56, 504, 102)
    for x0, head in ((13, "PLAYER"), (96, "RANKUP"), (196, "PVP"), (296, "AWARDS"), (420, "ONLINE")):
        s += t("t5 " + ("tq" if head == "RANKUP" else "hint"), x0, 3, head + (" v" if head == "RANKUP" else ""))
    order = sorted(PLAYERS, key=lambda p: -p[3])
    for i, p in enumerate(order):
        ry, sel = 11 + i * 15, p is SEL
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:15px"></div>'
        s += f'<div class="abs" style="left:6px;top:{ry + 6}px;width:3px;height:3px;background:{"var(--tq)" if p[1] else "var(--line)"}"></div>'
        s += t("t75 " + ("text" if sel else "muted"), 13, ry + 4, p[0])
        s += f'<div class="abs t6" style="left:96px;top:{ry + 3}px">{rank_tag(p[2])}</div>' + t("t6 gold", 186, ry + 5, str(p[3]), "transform:translateX(-100%)")
        s += f'<div class="abs t6" style="left:196px;top:{ry + 4}px">{rank_tag(p[4])}</div>' + t("t6 gold", 286, ry + 5, str(p[5]), "transform:translateX(-100%)")
        s += f'<div class="abs" style="left:296px;top:{ry + 3}px;display:flex;gap:1px">{"".join(im(a) for a in p[6])}</div>'
        s += t("t6 " + ("tq" if p[1] else "hint"), 420, ry + 5, "online" if p[1] else "away")
    s += "</div>"
    # the strip
    px, py = 8, 164
    s += t("t1 text", px, py, "Mrgregles") + t("t6 hint", px + 56, py + 2, "bonus from admins: -105 - on every ladder and award")
    s += counts_grid(px, py + 13, 250, 54, 2, 4)
    s += t("t5 hint", px, py + 70, "letters: which ladder counts it - R RANKUP, P PVP")
    rx = 266
    for i, (name, track, counts, n, rung, idx, pts, season, nxt, at) in enumerate(LADDERS):
        ry = py + 13 + i * 16
        s += t("t6 text", rx, ry + 3, name) + t("t5 hint", rx + 34, ry + 4, f"{season} this season - {nxt} at {at}")
        s += btn(rx + 206, ry, 40, 12, "SET", "")
    s += t("t5 vio", rx, py + 47, "AWARDS")
    ax = rx + 30
    for g, n, _, need, have, got in AWARDS:
        s += f'<div class="abs" style="left:{ax}px;top:{py + 45}px">{im(g)}</div>' + t("t5 " + ("vio" if got else "gold"), ax + 12, py + 47, n if got else f"{n} {have}/{need}")
        ax += 62 if got else 84
    s += btn(rx, py + 62, 76, 13, "ADD POINTS", "gold") + btn(rx + 176, py + 62, 70, 13, "RESET", "bad")
    s += t("t6 tq", 10, H - 12, "Each ladder is a column; with more ladders than fit, the table scrolls sideways.")
    return s + "</div>"


OPTIONS = [
    ("A", "Ladder chips", option_a,
     "Like the LADDER tab: chips over the player pick a ladder. The list's rank and points, the rank line, the bright counts and SET RANK all follow that ladder. The AWARDS chip swaps the pane for each award's progress.",
     "Smallest change; the same habit as LADDER; room for many ladders.", "Only one ladder at a time; seeing a player's whole standing takes clicks."),
    ("B", "Every ladder at once", option_b,
     "The pane gets a LADDERS table, one row per ladder (rank, points, this season, the bar to the next rung, SET). Awards sit below as chips, then the counts, each tagged with the ladders that count it.",
     "A player's whole standing in one look; SET right where the rank is.", "The counts get less room (8 lines show, the rest scroll); about 3 ladders fit before the table scrolls."),
    ("C", "Wide table", option_c,
     "The list becomes a table across the screen: one column per ladder, the awards reached as icons, sorting by any ladder. The chosen player's counts and tools sit in a strip below.",
     "Compares players across ladders; good for finding who leads a ladder.", "The biggest rework; fewer players show (6 rows); the strip is tight."),
]

QUESTIONS = [
    ("Layout", "", "B: a player's whole standing in one look, and SET is next to the rank it changes."),
    ("The list's rank column", "With several ladders, the list row has room for one rank.",
     "The first ladder's rank and points (in A, the chosen ladder's)."),
    ("SET RANK", "Today it sets the first ladder only. The server can already set any ladder's rung (/rankup player set).",
     "SET on each ladder opens that ladder's rungs; the rank changes without rewards, as today."),
    ("Awards", "There is no give or take for awards yet.",
     "Show them only: reached, or the points so far out of the points needed. Giving and taking would be a new server rule, so it's a later task."),
    ("ADD POINTS", "The admin bonus is one number added to every ladder and award.",
     "Keep one ADD POINTS and say so beside it: \"adds to every ladder and award\"."),
    ("The counts", "Which signals each ladder counts is shown on LADDER and SIGNALS (CS-1).",
     "In B and C, each count is tagged with the ladders that count it (R, P). In A, the chosen ladder's counts are bright."),
    ("RESET", "", "Unchanged: every count, the bonus and every ladder's rank are forgotten, and the button says \"every ladder\"."),
    ("Version", "The player details sent to the admin screen gain a row per ladder and the awards, so the packet changes.",
     "Protocol 4. If 0.5.0 hasn't gone out yet, it ships in 0.5.0; otherwise 0.6.0. Either way the server and the modpack update together."),
]


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
    return f"""<title>RankUp PLAYERS Tab</title>
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
    <h1>RankUp PLAYERS Tab</h1>
    <p><b>PL-1:</b> today PLAYERS shows only the first ladder: its rank in the list, its rank line and points, and SET RANK on its rungs. These are three ways to show a player on <b>every ladder</b>, with the awards they've reached.</p>
    <p>Each screen is the admin screen at twice its GUI size. RANKUP is your ladder with its new art. <b>PVP and the three awards are example data</b>, there to show more than one ladder. Mrgregles is selected.</p>
  </div>
  {opts}
  <section class="qs" id="questions"><h2>Questions</h2><ol>{qs}</ol></section>
</div>
"""


def main():
    target = ROOT / "tools" / "preview" / "players_ladders_mockups.html"
    target.write_text(page(), encoding="utf-8")
    print("wrote", target.relative_to(ROOT))


if __name__ == "__main__":
    main()
