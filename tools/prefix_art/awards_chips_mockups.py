#!/usr/bin/env python3
"""
Mock-ups for three follow-ups (2026-10-27): giving and taking awards on PLAYERS (AW-1), the rank screen's ladder
chips past one row (LC-1), and the questions for the login line about what was reached while away (AW-2, chat only,
no screen). Drawn with the CurseForge pictures' screen helpers and holo.css (GUI units, as client/AdminScreen.java and
client/RankScreen.java), with the chosen prefix art.

PVP, the extra ladders and the awards are example data, there to show the screens past BSP's one ladder.

  python3 tools/prefix_art/awards_chips_mockups.py      writes tools/preview/awards_chips_mockups.html
"""
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "curseforge"))

import gen_pages as gp  # noqa: E402
from gen_pages import H, W, btn, chip, inset, screen_frame, t  # noqa: E402
from gen_prefix_art import LADDER  # noqa: E402
from players_mockups import AWARDS, LADDERS, PVP, bar, counts_grid, player_list, rank_tag, search_box  # noqa: E402
from suffix_mockups import holo_css, im  # noqa: E402

STATUS = "2 ladders - 3 awards - 6 players shown"
CHOSEN = "headhunter"  # the award picked in option A


# ------------------------------------------------------------------ PLAYERS: give and take (AW-1)

def players_top(px, py):
    """The pane as built in PL-1: the name, the LADDERS table (two ladders, nothing open)."""
    s = t("t1 text", px, py, "Mrgregles") + t("t6 tq", px + 56, py + 2, "online") + t("t6 hint", px + 332, py + 2, "bonus from admins: -105", "transform:translateX(-100%)")
    s += t("t5 tq", px, py + 14, "LADDERS") + inset(px, py + 21, 332, 44)
    for x0, head in ((4, "LADDER"), (50, "RANK"), (124, "POINTS"), (164, "THIS SEASON"), (214, "TO THE NEXT RUNG")):
        s += t("t5 hint", x0, 3, head)
    for i, (name, track, counts, n, rung, idx, pts, season, nxt, at) in enumerate(LADDERS):
        ry = 12 + i * 15
        s += t("t75 text", 4, ry + 2, name)
        s += f'<div class="abs t6" style="left:50px;top:{ry + 1}px">{rank_tag("warden" if i == 0 else "duelist")}</div>'
        s += t("t5 hint", 105, ry + 3, f"{idx}/{n}")
        s += t("t6 gold", 124, ry + 3, str(pts)) + t("t6 muted", 164, ry + 3, str(season))
        s += t("t5 hint", 214, ry + 1, f"{nxt} at {at}") + bar(214, ry + 8, 70, pts / at)
        s += btn(294, ry, 32, 12, "SET", "")
    return s + "</div>"


def players_bottom(px, py, counts_y, counts_h, rows):
    s = t("t5 tq", px, counts_y - 7, "COUNTS") + t("t5 hint", px + 30, counts_y - 7, "letters: the ladders that count it") + t("t5 tq", px + 150, counts_y - 7, "R RANKUP") + t("t5 vio", px + 180, counts_y - 7, "P PVP")
    s += counts_grid(px, counts_y, 332, counts_h, 2, rows)
    by = py + 226
    s += btn(px, by, 76, 13, "ADD POINTS", "gold") + btn(px + 262, by, 70, 13, "RESET", "bad")
    s += t("t5 hint", px + 82, by + 4, "adds to every ladder and award") + t("t5 hint", px + 256, by + 4, "every ladder", "transform:translateX(-100%)")
    return s


def award_chip(cx, y, g, n, need, have, got, cw=80, ring=False, corner=None, taken=False):
    """One award chip as PL-1 draws it (80 wide); ring marks the chosen one, corner adds a small button."""
    edge = "var(--tq)" if ring else "var(--vio)" if got else "var(--bad)" if taken else "var(--line)"
    s = f'<div class="abs" style="left:{cx}px;top:{y}px;width:{cw}px;height:20px;border:1px solid {edge};background:{"rgba(181,140,255,.12)" if got else "#10151c"}"></div>'
    s += f'<div class="abs" style="left:{cx + 3}px;top:{y + 2}px">{im(g)}</div>'
    s += t("t6 " + ("vio" if got else "muted"), cx + 15, y + 3, n)
    if got:
        s += t("t5 vio", cx + 3, y + 12, "REACHED")
    elif taken:
        s += t("t5 bad", cx + 3, y + 12, "TAKEN - not given again")
    else:
        s += t("t5 gold", cx + 3, y + 12, f"{have}/{need}") + bar(cx + 30, y + 13, cw - 34, have / need, "var(--gold)", 2)
    if corner:
        label, cls = corner
        s += btn(cx + cw - 14, y + 2, 12, 9, label, cls)
    return s


def option_aw_a():
    """Pick a chip, act below: like SET on a ladder, the award's GIVE or TAKE opens under the chips."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PLAYERS) + search_box() + player_list(lambda p: rank_tag(p[2]), lambda p: str(p[3]))
    px, py = 180, 40
    s += players_top(px, py)
    ay = py + 70
    s += t("t5 vio", px, ay, "AWARDS  2 of 3") + t("t5 hint", px + 332, ay, "click one to give or take it", "transform:translateX(-100%)")
    for i, (g, n, _, need, have, got) in enumerate(AWARDS):
        s += award_chip(px + i * 84, ay + 7, g, n, need, have, got, ring=g == CHOSEN)
    # the chosen award's line, as SET's panel
    ly = ay + 32
    s += t("t6 muted", px, ly + 3, "HEADHUNTER") + t("t5 hint", px, ly + 11, "775 of 1250 points")
    s += btn(px + 70, ly, 60, 13, "GIVE IT", "vio") + t("t5 hint", px + 136, ly + 4, "no rewards, no announcement (as SET)")
    s += btn(px + 292, ly, 40, 13, "CANCEL", "")
    s += players_bottom(px, py, py + 136, 82, 8)
    s += t("t6 tq", 10, H - 12, "A reached award shows TAKE IT here instead. The chosen chip is ringed.")
    return s + "</div>"


def option_aw_b():
    """A small button on each chip: + gives, - takes; the first click arms it, the second does it (as RESET)."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PLAYERS) + search_box() + player_list(lambda p: rank_tag(p[2]), lambda p: str(p[3]))
    px, py = 180, 40
    s += players_top(px, py)
    ay = py + 70
    s += t("t5 vio", px, ay, "AWARDS  2 of 3") + t("t5 hint", px + 332, ay, "+ gives, - takes: click twice", "transform:translateX(-100%)")
    corners = {"bulwark": ("-", ""), "founder": ("-", ""), "headhunter": ("SURE?", "bad")}
    for i, (g, n, _, need, have, got) in enumerate(AWARDS):
        label, cls = corners[g]
        if g == "headhunter":
            s += award_chip(px + i * 84, ay + 7, g, n, need, have, got)
            s += btn(px + i * 84 + 80 - 28, ay + 9, 26, 9, label, cls)
        else:
            s += award_chip(px + i * 84, ay + 7, g, n, need, have, got, corner=(label, cls))
    s += t("t5 hint", px, ay + 31, "Headhunter's + is armed: a second click gives it. Moving the mouse away disarms it.")
    s += players_bottom(px, py, py + 118, 100, 9)
    s += t("t6 tq", 10, H - 12, "Fewest clicks; nothing opens. The award chips keep their place.")
    return s + "</div>"


def option_aw_c():
    """An AWARDS table under LADDERS, one row per award with GIVE or TAKE, as the ladders have SET."""
    s = screen_frame("RANKUP ADMIN", STATUS, gp.T_PLAYERS) + search_box() + player_list(lambda p: rank_tag(p[2]), lambda p: str(p[3]))
    px, py = 180, 40
    s += players_top(px, py)
    ay = py + 70
    s += t("t5 vio", px, ay, "AWARDS  2 of 3") + inset(px, ay + 7, 332, 57)
    for x0, head in ((4, "AWARD"), (100, "POINTS"), (150, "NEEDED"), (200, "STATE")):
        s += t("t5 hint", x0, 3, head)
    for i, (g, n, _, need, have, got) in enumerate(AWARDS):
        ry = 12 + i * 14
        s += f'<div class="abs" style="left:4px;top:{ry + 1}px">{im(g)}</div>' + t("t6 " + ("vio" if got else "text"), 16, ry + 2, n)
        s += t("t6 gold", 100, ry + 2, str(have)) + t("t6 muted", 150, ry + 2, str(need))
        s += t("t5 " + ("vio" if got else "hint"), 200, ry + 3, "REACHED" if got else "on its way")
        s += btn(274, ry, 52, 12, "TAKE" if got else "GIVE", "bad" if got else "vio")
    s += "</div>"
    s += players_bottom(px, py, py + 148, 70, 7)
    s += t("t6 tq", 10, H - 12, "Like the LADDERS table; the counts lose a row; past 3 awards the table scrolls.")
    return s + "</div>"


# ------------------------------------------------------------------ the rank screen's ladder chips (LC-1)

EXTRA = ["RANKUP", "PVP", "BUILDER", "MINER", "FARMER", "FISHER", "TRADER", "EXPLORER", "RAIDER", "DEFENDER", "HUNTER", "CRAFTER", "SAILOR", "NETHER"]


def chip_w(label):
    return max(40, int(len(label) * 4.5) + 10)


def rank_body(top):
    """The ladder card and the standing pane, from y = top (36 today); the card's height follows."""
    lx, lw, lh = 8, 172, 284 - top
    s = inset(lx, top, lw, lh)
    step, n = 30, max(1, (lh - 8) // 30)
    for row in range(n):
        i = 6 + (n - 1 - row) - 2
        if i < 0 or i >= len(LADDER):
            continue
        g, name, _, _ = LADDER[i]
        cy, mine = 4 + row * step, i == 5
        s += f'<div class="abs" style="left:26px;top:{cy}px;width:138px;height:24px;background:{"rgba(255,210,58,0.13)" if mine else "var(--inset)"};border:1px solid {"var(--gold)" if mine else "var(--line)"}"></div>'
        s += f'<div class="abs" style="left:30px;top:{cy + 3}px">{im(g)}</div>'
        s += t("t5 " + ("gold" if mine else "hint"), 30, cy + 14, "you, since 11 days" if mine else "")
        s += f'<div class="abs" style="left:13px;top:{cy + 9}px;width:6px;height:6px;background:{"var(--gold)" if mine else "var(--tq)" if i < 5 else "var(--line)"}"></div>'
    s += "</div>"
    px, py = 190, top + 2
    s += f'<div class="abs" style="left:{px}px;top:{py}px;font-size:15.75px;line-height:1;color:var(--text)">WARDEN</div>'
    s += t("t5 hint", px + 70, py + 6, "rank 6 of 12 on RANKUP") + f'<div class="abs" style="left:{px + 150}px;top:{py + 3}px">{im("warden")}</div>'
    s += t("t6 gold", px, py + 18, "4210 points - 640 this season")
    s += t("t5 tq", px, py + 32, "NEXT: VANGUARD AT 6000 POINTS") + inset(px, py + 41, 322, 12) + "</div>"
    s += f'<div class="abs" style="left:{px + 1}px;top:{py + 42}px;width:{int(320 * 0.70)}px;height:10px;background:rgba(25,211,176,0.8)"></div>'
    s += t("t5 tq", px, py + 60, "WHAT COUNTS") + inset(px, py + 68, 322, 284 - (py + 68)) + "</div>"
    return s


def rank_frame():
    s = '<div class="world"></div>' + f'<div class="screen" style="left:16px;top:16px;width:{W}px;height:{H}px">'
    return s + t("t1 tq", 10, 8, "YOUR RANK") + t("t6 hint", W - 10, 10, "Mrgregles - survival-2 - season 4", "transform:translateX(-100%)")


def rank_chip(x, y, label, on=False, gold=False):
    cls = "big " + ("on " if on else "") + ("gold" if gold else "")
    return chip(x, y, chip_w(label), 11, label, cls)


def option_lc_a():
    """Wrap: the chips flow onto a second row (a third if need be); the screen below moves down only then."""
    s = rank_frame()
    x, y, rows = 8, 22, 0
    for label in EXTRA + ["AWARDS"]:
        w = chip_w(label)
        if x + w > W - 8:
            x, y, rows = 8, y + 13, rows + 1
        s += rank_chip(x, y, label, on=label == "RANKUP", gold=label == "AWARDS")
        x += w + 3
    s += rank_body(36 + rows * 13)
    s += t("t5 hint", 10, H - 10, "With BSP's one ladder nothing changes: one row, as today.")
    return s + "</div>"


def option_lc_b():
    """Arrows: one row that pages with < and >; AWARDS stays pinned at the right."""
    s = rank_frame()
    s += btn(8, 22, 12, 11, "<", "") + t("t5 hint", 24, 25, "1-9 of 14")
    x = 58
    for label in EXTRA:
        w = chip_w(label)
        if x + w > W - 8 - 58:
            break
        s += rank_chip(x, 22, label, on=label == "RANKUP")
        x += w + 3
    s += btn(W - 8 - 58 - 14 + 3, 22, 12, 11, ">", "tq") + rank_chip(W - 8 - 52, 22, "AWARDS", gold=True)
    s += rank_body(36)
    s += t("t5 hint", 10, H - 10, "The rest of the screen never moves; the chosen ladder stays shown when you page.")
    return s + "</div>"


def option_lc_c():
    """A picker: one chip names the ladder shown; a click lists every ladder over the screen. AWARDS stays beside it."""
    s = rank_frame()
    s += chip(8, 22, 110, 11, "LADDER: RANKUP  v", "big on") + rank_chip(121, 22, "AWARDS", gold=True)
    s += t("t5 hint", 180, 25, "14 ladders")
    s += rank_body(36)
    # the open list
    s += f'<div class="abs" style="left:8px;top:35px;width:260px;height:{14 * 7 + 6}px;background:#0c0e12;border:1px solid var(--tq)"></div>'
    for i, label in enumerate(EXTRA):
        cx, cy = 12 + (i // 7) * 128, 39 + (i % 7) * 14
        on = label == "RANKUP"
        s += f'<div class="abs" style="left:{cx - 2}px;top:{cy - 2}px;width:124px;height:13px;background:{"rgba(25,211,176,.15)" if on else "transparent"}"></div>'
        s += t("t75 " + ("tq" if on else "text"), cx, cy, label) + t("t5 hint", cx + 118, cy + 2, "Warden" if on else "Duelist" if label == "PVP" else "-", "transform:translateX(-100%)")
    s += t("t5 hint", 10, H - 10, "Shown open. Each line names your rank on that ladder; a click picks it.")
    return s + "</div>"


# ------------------------------------------------------------------ the page

PARTS = [
    ("aw", "Give and take awards on PLAYERS", "D101 left awards display-only. These are three ways to give an award to the selected player, or take it away, from the award chips PL-1 drew.", [
        ("A", "Pick, then act", option_aw_a,
         "A click on an award chip rings it and opens a line under the chips: the award's points, GIVE IT or TAKE IT, and CANCEL. The same habit as SET on a ladder.",
         "Room to say what happens (no rewards); the same two steps as SET.", "Two clicks and a line that pushes the counts down while it is open."),
        ("B", "A button on each chip", option_aw_b,
         "Each chip gets a small corner button: + on an award not reached, - on one reached. The first click arms it (SURE?), the second does it, as RESET does.",
         "Fewest clicks; nothing opens or moves.", "Small targets; the meaning of + and - has to be learned (a hover says it)."),
        ("C", "An AWARDS table", option_aw_c,
         "The chips become a table under LADDERS: the award, its points, what it needs, reached or not, and GIVE or TAKE on each row.",
         "Matches the LADDERS table; the buttons say what they do.", "The counts lose a row; past three awards the table scrolls."),
    ]),
    ("lc", "The rank screen past one row of ladders", "Today the rank screen's chips stop at the screen's edge: past about 11 ladders the rest are cut off, AWARDS too, so a player can't reach their awards at all. BSP has one ladder today, so this is for later. Shown with 14 example ladders.", [
        ("A", "Wrap", option_lc_a,
         "The chips flow onto a second row, a third if need be, and the ladder card and the pane move down one row each time. AWARDS is always last.",
         "Every ladder in sight; nothing to learn; one row (today's look) until it is needed.", "Each extra row makes the ladder card a row shorter."),
        ("B", "Arrows", option_lc_b,
         "One row that pages with < and >, with \"1-9 of 14\"; AWARDS stays pinned at the right end.",
         "The screen never moves; AWARDS always in the same place.", "Ladders off the row are hidden until you page."),
        ("C", "A picker", option_lc_c,
         "One chip names the ladder shown; a click opens a list of every ladder with your rank on each. AWARDS sits beside it.",
         "Scales to any number; the list doubles as a summary of your ranks.", "One more click for every switch; least like today."),
    ]),
]

QUESTIONS = [
    ("Give and take awards (PLAYERS)", [
        ("Layout", "", "A: pick a chip, then GIVE IT or TAKE IT on the line under the chips, as SET does for ladders."),
        ("Rewards and announcement on GIVE", "SET RANK moves a rank without rewards or an announcement.",
         "The same: GIVE adds the LuckPerms group and marks it reached, with no rewards and no announcement. The player is told in chat."),
        ("TAKE while their points still reach it", "The next check would give it straight back.",
         "TAKE marks the award taken for that player: the check never gives it again until an admin gives it back. The chip says TAKEN."),
        ("What TAKE does to rewards already given", "",
         "Coins, items and pieces stay given. A title the award gave gives way to the next one kept, as at a season end (D75)."),
        ("Seasonal awards", "A seasonal award is taken back at each season's end and can be reached again.",
         "A taken award stays taken across seasons until given back."),
        ("Commands", "", "/rankup award give <player> <group> and /rankup award take <player> <group>, the same rules."),
        ("Network and version", "The player's row records the taken mark (shared, like awards reached); PLAYERS shows it.",
         "Ships in 0.5.0 (not yet out); protocol stays 4. Every backend updates together, as before."),
    ]),
    ("The rank screen's ladder chips", [
        ("Layout", "", "A: wrap onto more rows, AWARDS last."),
        ("The admin LADDER tab", "Its chips stop at the edge the same way (the rest only by /rankup ladders).",
         "The same rule there: the chips wrap, + LADDER last, and the strip and rungs move down."),
        ("Most rows", "", "Three rows (about 33 ladders); past that the last chip says +n and opens /rankup ladders' list in chat."),
    ]),
    ("Telling a player at login what they reached while away", [
        ("What it says", "No screen: lines in chat after the \"rewards kept for you\" line.",
         "One line per rank or award: \"While you were away you reached Captain on Pvp.\" / \"While you were away you earned Headhunter.\" More than five: the last line says \"and 3 more\"."),
        ("Who hears it", "", "Only the player. Nothing is announced at login (the rank-up was already quiet, D86)."),
        ("What counts", "", "What they earned (points, a totem lost). An admin's SET RANK and GIVE stay silent; a rung lost at a season end is not said."),
        ("On a network", "", "Kept in their shared row, so whichever server they join next tells them, once. A player online on another server is told live there (D106) and not again at login."),
    ]),
]


def page():
    parts, n = "", 0
    for key, title, lead, options in PARTS:
        parts += f'<section class="part" id="{key}"><h2 class="ph">{title}</h2><p class="pl">{lead}</p>'
        for k, name, fn, what, good, bad in options:
            parts += f"""
<section class="opt" id="{key}-{k.lower()}">
  <header><span class="tag">Option {k}</span><h3>{name}</h3><p>{what}</p>
  <div class="pc"><p><b>For</b> {good}</p><p><b>Against</b> {bad}</p></div></header>
  <div class="frame"><div class="stage">{fn()}</div></div>
</section>"""
        parts += "</section>"
    qs = ""
    for head, items in QUESTIONS:
        lis = ""
        for q, why, d in items:
            n += 1
            lis += f'<li><b>{n}. {q}</b>{f"<p>{why}</p>" if why else ""}<p class="def">Default: {d}</p></li>'
        qs += f"<h3>{head}</h3><ol>{lis}</ol>"
    return f"""<title>RankUp Awards and Ladder Chips</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=VT323&family=Atkinson+Hyperlegible:wght@400;700&display=swap">
<style>
/* Layout: two parts, each three options (a brief, then the screen at 2x its GUI size, 520 x 300 units, on a scrolling frame); the questions last. One dark look on purpose, the screens' own. */
:root {{
  --pg-bg: #0b0e13; --pg-panel: #10151c; --pg-line: #2a2f3a; --pg-fg: #e6eaf2; --pg-muted: #9aa3b5;
  --pg-accent: #19d3b0; --pg-vio: #b58cff; --pg-gold: #ffd23a;
  --pg-display: "VT323", "Courier New", monospace; --pg-body: "Atkinson Hyperlegible", system-ui, sans-serif;
  color-scheme: dark;
}}
body {{ background: var(--pg-bg); color: var(--pg-fg); font-family: var(--pg-body); font-size: 15px; line-height: 1.5; }}
.wrap {{ max-width: 1104px; margin: 0 auto; padding-block: 28px 64px; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); display: grid; gap: 40px; }}
h1, h2, h3 {{ font-family: var(--pg-display); font-weight: 400; margin: 0; text-wrap: balance; }}
h1 {{ font-size: 40px; color: var(--pg-accent); }}
h2.ph {{ font-size: 34px; color: var(--pg-gold); }}
h3 {{ font-size: 28px; }}
p {{ margin: 0; max-width: 75ch; }}
.lead, .pl {{ color: var(--pg-muted); }}
.lead {{ display: grid; gap: 8px; }}
.lead b {{ color: var(--pg-fg); }}
.part {{ display: grid; gap: 28px; }}
.opt {{ display: grid; gap: 12px; }}
.opt header {{ display: grid; gap: 6px; }}
.tag {{ font-family: var(--pg-display); font-size: 20px; color: var(--pg-gold); }}
.pc {{ display: flex; flex-wrap: wrap; gap: 6px 28px; color: var(--pg-muted); }}
.pc b {{ color: var(--pg-accent); }}
.pc p + p b {{ color: var(--pg-gold); }}
.frame {{ overflow-x: auto; border: 1px solid var(--pg-line); background: #06080c; }}
.stage {{ position: relative; width: 552px; height: 332px; zoom: 2; font-family: "VT323", monospace; color: var(--text); -webkit-font-smoothing: none; }}
.qs {{ background: var(--pg-panel); border: 1px solid var(--pg-line); padding: 20px; display: grid; gap: 14px; }}
.qs h3 {{ color: var(--pg-accent); font-size: 24px; }}
.qs ol {{ list-style: none; margin: 0; padding: 0; display: grid; gap: 14px; }}
.qs li p {{ color: var(--pg-muted); margin-top: 2px; }}
.qs .def {{ color: var(--pg-fg); }}
{holo_css()}
</style>
<div class="wrap">
  <div class="lead">
    <h1>RankUp Awards and Ladder Chips</h1>
    <p>Three follow-ups: <b>giving and taking awards</b> on PLAYERS, the <b>rank screen past one row of ladders</b>, and the <b>login line</b> about what a player reached while away (chat only, so questions without a screen).</p>
    <p>Each screen is drawn at twice its GUI size with your rank art. <b>PVP, the extra ladders and the three awards are example data.</b> Mrgregles is selected.</p>
  </div>
  {parts}
  <section class="qs" id="questions"><h2>Questions</h2>{qs}</section>
</div>
"""


def main():
    target = ROOT / "tools" / "preview" / "awards_chips_mockups.html"
    target.write_text(page(), encoding="utf-8")
    print("wrote", target.relative_to(ROOT))


if __name__ == "__main__":
    main()
