#!/usr/bin/env python3
"""Writes the HTML pages behind the CurseForge pictures (tools/curseforge/<name>.html), drawn from the built screens'
layouts in client/AdminScreen.java and client/RankScreen.java (GUI units; render.sh draws them at 3x into
docs/curseforge/images/<name>.png). Edit this script, never the pages.

  python3 tools/curseforge/gen_pages.py && tools/curseforge/render.sh
"""
from pathlib import Path

HERE = Path(__file__).resolve().parent
W, H = 520, 300  # the screens' units (D29)
PAGE = (W + 32, H + 32)

LADDER = [("settler", "Settler", 0, False, 58), ("raider", "Raider", 500, False, 31), ("warden", "Warden", 1500, False, 12),
          ("vanguard", "Vanguard", 4000, True, 4), ("legend", "Legend", 10000, True, 1)]
SIGNALS = [("minutes played", True, 1, 10, "minutes", "RankUp", 1180, 118), ("player kills", True, 25, 1, "kill", "RankUp", 31, 775),
           ("mob kills", True, 1, 1, "kill", "RankUp", 212, 212), ("deaths", False, 0, 1, "death", "RankUp", 17, 0),
           ("totems stolen", True, 150, 1, "totem", "BSP Core", 6, 900), ("totems protected", True, 60, 1, "totem", "BSP Core", 4, 240),
           ("totems lost", False, 0, 1, "totem", "BSP Core", 3, 0), ("totems placed", False, 0, 1, "totem", "BSP Core", 9, 0),
           ("seasons survived", False, 0, 1, "season", "BSP Core", 2, 0), ("season score", False, 1, 100, "points", "BSP Core", 0, 0),
           ("pieces earned", False, 1, 1000, "pieces", "BSP Core", 0, 0), ("claims bought", True, 40, 1, "claim", "Region Guard", 2, 80)]


def page(name, size, body, bg=None):
    style = f' style="background:{bg}"' if bg else ""
    (HERE / f"{name}.html").write_text(
        f'<!doctype html>\n<html><head><meta charset="utf-8"><meta name="size" content="{size[0]}x{size[1]}">'
        f'<link rel="stylesheet" href="holo.css"></head>\n<body{style}>\n{body}\n</body></html>\n')


def t(cls, x, y, text, extra=""):
    return f'<div class="abs {cls}" style="left:{x}px;top:{y}px;{extra}">{text}</div>'


def inset(x, y, w, h, extra=""):
    return f'<div class="inset" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;{extra}">'


def chip(x, y, w, h, text, cls=""):
    return f'<div class="chip {cls}" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px">{text}</div>'


def btn(x, y, w, h, text, cls="tq"):
    return f'<div class="btn {cls}" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px">{text}</div>'


def field(x, y, w, label, value, muted=False):
    return (t("t5 hint", x, y, label) + inset(x, y + 7, w, 13) + "</div>"
            + t("t75 " + ("muted" if muted else "text"), x + 4, y + 10, value))


def screen_frame(title, status, tab=None):
    s = '<div class="world"></div>'
    s += f'<div class="screen" style="left:16px;top:16px;width:{W}px;height:{H}px">'
    s += t("t1 tq", 10, 8, title) + t("t6 hint", W - 10, 10, status, "transform:translateX(-100%)")
    if tab is not None:
        x = 8
        for i, name in enumerate(TABS):
            s += chip(x, 22, 54, 12, name, "big on" if i == tab else "big")
            x += 57
    return s


TABS = ["LADDER", "GROUPS", "SIGNALS", "PLAYERS", "PREFIXES", "OPTIONS"]
T_LADDER, T_GROUPS, T_SIGNALS, T_PLAYERS, T_PREFIXES, T_OPTIONS = range(6)


def badge(label, w, bg, edge, scale=1):
    """A prefix image as the PNG would look: one text line (9 units) high, w wide."""
    return (f'<span style="display:inline-flex;align-items:center;justify-content:center;width:{w * scale}px;height:{9 * scale}px;'
            f'background:{bg};box-shadow:inset 0 0 0 {0.6 * scale}px {edge};color:#fff;font-size:{5.2 * scale}px;letter-spacing:0.3px;'
            f'text-shadow:{0.6 * scale}px {0.6 * scale}px 0 rgba(0,0,0,0.55);vertical-align:middle">{label}</span>')


IMAGES = {
    "warden": ("WARDEN", 40, "linear-gradient(#2fd3e6,#127a8f)", "#bff6ff"),
    "legend": ("LEGEND", 44, "linear-gradient(#ffd75a,#b9741a)", "#fff1b8"),
    "reaver": ("REAVER", 40, "linear-gradient(#ff6a5a,#8f1d1d)", "#ffc2b8"),
    "admin": ("ADMIN", 36, "linear-gradient(#ff4d6d,#7a1030)", "#ffd0d9"),
    "mod": ("MOD", 28, "linear-gradient(#6d8bff,#22308f)", "#d0d9ff"),
}


def img(key, scale=1):
    label, w, bg, edge = IMAGES[key]
    return badge(label, w, bg, edge, scale)


def ladder_strip(chosen="PLAYTIME"):
    """LADDER's ladder chips and the chosen ladder's strip (D51)."""
    s = chip(8, 40, 64, 12, "PLAYTIME", "big on" if chosen == "PLAYTIME" else "big") + chip(75, 40, 56, 12, "RAIDER", "big") + chip(134, 40, 60, 12, "+ LADDER", "big")
    s += inset(8, 56, 504, 14) + "</div>"
    s += t("t5 hint", 13, 60, "TRACK") + chip(34, 57, 60, 12, "rankup", "big")
    s += t("t5 hint", 100, 60, "COUNTS") + chip(124, 57, 62, 12, "every signal", "on") + chip(189, 57, 46, 12, "+ signal")
    s += chip(380, 57, 72, 12, "titles at 1000") + chip(458, 57, 52, 12, "RENAME")
    return s


def rung_list(selected, y=74, h=182):
    s = inset(8, y, 164, h)
    for i, (g, name, pts, seasonal, holders) in enumerate(LADDER):
        ry = 1 + i * 14
        sel = g == selected
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:14px">'
        s += f'<span class="t75 hint" style="width:10px">{i + 1}.</span><span class="t75 {"text" if sel else "muted"}" style="flex:1">{name}</span>'
        if seasonal:
            s += '<span class="t6 vio" style="width:12px">S</span>'
        s += f'<span class="t75 gold" style="width:34px;text-align:right">{pts:,}</span></div>'.replace(",", " ")
    s += "</div>"
    by = y + h + 6
    s += btn(8, by, 72, 13, "+ ADD RANK") + btn(84, by, 26, 13, "UP", "") + btn(114, by, 26, 13, "DOWN", "") + btn(144, by, 28, 13, "IMPORT", "")
    return s


STATUS = "track rankup - 5 rungs - LuckPerms: Forge edition"


def admin_ladder(picking=False):
    s = screen_frame("RANKUP ADMIN", STATUS, T_LADDER) + ladder_strip() + rung_list("warden")
    px, py = 180, 76
    s += t("t1 text", px, py, "WARDEN") + t("t6 hint", W - 10, py + 2, "rung 3 of 5 - 12 players hold it", "transform:translateX(-100%)")
    s += field(px, py + 14, 100, "GROUP", "warden", True) + field(px + 106, py + 14, 110, "DISPLAY NAME", "Warden") + field(px + 222, py + 14, 50, "POINTS", "1500")
    s += t("t5 hint", px + 278, py + 14, "SEASON") + chip(px + 278, py + 21, 26, 13, "keep", "on") + chip(px + 305, py + 21, 27, 13, "reset")
    s += t("t5 tq", px, py + 42, "REWARDS WHEN REACHED") + inset(px, py + 50, 332, 76)
    rows = [("COINS", "coins worth 64 Copper"), ("PIECES", "250 pieces into the bank"), ("ITEM", "2 x Netherite Ingot"), ("TITLE", "&amp;b[Warden]"), ("COMMAND", "/give %player% minecraft:totem_of_undying")]
    if picking:
        rows.append(("KIT", "the kit Warden's Armour"))
    for i, (k, v) in enumerate(rows):
        ry = 1 + i * 12
        if k == "TITLE":
            v = '<span style="color:#55ffff">[Warden]</span> <span class="hint">&amp;b[Warden]</span>'
            # the read-only image slot beside a title (D65): a click opens PREFIXES on it
            s += f'<div class="abs" style="left:{332 - 64}px;top:{ry + 1}px;width:48px;height:10px;border:1px solid var(--vio);overflow:hidden;display:flex;align-items:center;justify-content:center;line-height:0">{img("warden")}</div>'
        s += t("t5 vio", 4, ry + 3, k) + t("t6 text", 50, ry + 2, v) + chip(332 - 14, ry + 1, 11, 10, "x", "bad")
    s += "</div>"
    ay = py + 50 + 76 + 5
    s += kind_chips(px, ay, picking)
    if picking:
        s += t("t5 hint", px, ay + 20, "pick a kit:")
        bx = px + 50
        for name in ["Starter Toolkit", "Warden's Armour", "Raider Rations"]:
            w = len(name) * 4 + 10
            s += chip(bx, ay + 17, w, 13, name, "vio")
            bx += w + 3
        s += btn(px + 332 - 40, ay + 17, 40, 13, "CANCEL", "")
        s += t("t6 tq", 10, H - 12, "Warden now also gives the kit Warden's Armour")
    else:
        s += t("t5 hint", px, ay + 21, "Removing a rung keeps the players' points.") + btn(W - 8 - 76, ay + 18, 76, 13, "REMOVE RANK", "bad")
        s += t("t6 tq", 10, H - 12, "Warden now also gives 2 x Netherite Ingot")
    return s + "</div>"


def kind_chips(px, ay, picking):
    s = ""
    for i, k in enumerate(["COINS", "PIECES", "ITEM", "TITLE", "COMMAND", "KIT"]):
        s += chip(px + i * 47, ay, 44, 13, "+ " + k, "on" if picking and k == "KIT" else "")
    return s + btn(px + 282, ay, 50, 13, "FROM HAND", "gold")


def item_browser():
    s = screen_frame("RANKUP ADMIN", STATUS, T_LADDER) + ladder_strip() + rung_list("warden")
    px, py = 180, 76
    s += f'<div class="screen gold" style="left:{px}px;top:{py}px;width:332px;height:{244 - 76}px">'
    s += t("t75 gold", 6, 5, "PICK AN ITEM") + btn(332 - 50, 3, 46, 11, "CLOSE", "")
    items = ["#c9a44c", "#b0b0b0", "#5fb4e0", "#2f2f2f", "#8b5a2b", "#e0d05f", "#4caf50", "#d9d9d9", "#7a4a2a", "#bfe0ff", "#a0522d", "#777", "#b8860b", "#9acd32", "#f5f5dc", "#4682b4", "#3a3a3a", "#cd853f"]
    cols = (332 - 12) // 18
    for i in range(cols * 8):  # eight rows fill the browser's 168 units
        c = items[i % len(items)]
        ix, iy = 6 + (i % cols) * 18, 18 + (i // cols) * 18
        hover = i == 21
        s += f'<div class="abs" style="left:{ix}px;top:{iy}px;width:18px;height:18px;{"background:rgba(25,211,176,0.2);" if hover else ""}"><div class="abs" style="left:3px;top:3px;width:12px;height:12px;background:{c};border:1px solid rgba(0,0,0,0.4)"></div></div>'
    s += t("t6 hint", 332 - 54, 6, "Netherite Ingot  minecraft:netherite_ingot", "transform:translateX(-100%)")
    s += "</div>"
    s += inset(px, H - 34, 332, 16, "border-color:var(--gold)") + "</div>" + t("t75 text", px + 4, H - 30, "nether") + t("t6 hint", px + 40, H - 30, "type to search every item, click one")
    s += t("t6 hint", 10, H - 12, "Enter applies, Esc cancels.")
    return s + "</div>"


def admin_signals():
    s = screen_frame("RANKUP ADMIN", STATUS, T_SIGNALS)
    lx, ly = 8, 40
    s += inset(lx, ly, 504, 230)
    s += t("t5 hint", 6, 4, "SIGNAL") + t("t5 hint", 150, 4, "ON") + t("t5 hint", 200, 4, "WORTH") + t("t5 hint", 380, 4, "FROM")
    for i, (name, on, pts, per, unit, src, _, _) in enumerate(SIGNALS):
        ry = 14 + i * 17
        if i % 2 == 0:
            s += f'<div class="abs" style="left:1px;top:{ry}px;width:502px;height:17px;background:rgba(255,255,255,0.04)"></div>'
        s += t("t75 " + ("text" if on else "muted"), 6, ry + 5, name) + chip(148, ry + 2, 22, 12, "on" if on else "off", "on" if on else "")
        s += inset(198, ry + 2, 176, 12) + "</div>" + t("t6 " + ("gold" if on else "muted"), 202, ry + 4, f"{pts} points per {per} {unit}{'' if per == 1 else 's'}")
        s += t("t5 hint", 380, ry + 5, src)
    s += "</div>" + t("t6 hint", 10, H - 12, "Changes save at once and reach every server. Esc closes.")
    return s + "</div>"


def admin_players():
    s = screen_frame("RANKUP ADMIN", STATUS, T_PLAYERS)
    s += inset(8, 40, 164, 14) + "</div>" + t("t75 hint", 12, 43, "find player")
    s += inset(8, 56, 164, 198)
    players = [("Mrgregles", "Warden", 2140, True, True), ("Tyler_S", "Raider", 640, True, False), ("Kestrel", "Vanguard", 4210, True, False), ("Bramble", "Settler", 95, False, False), ("Alex_Builds", "Raider", 712, False, False), ("Sam_W", "Settler", 230, False, False)]
    for i, (name, rank, pts, online, sel) in enumerate(players):
        ry = 1 + i * 14
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:14px">'
        s += f'<span style="width:3px;height:3px;background:{"var(--tq)" if online else "var(--line)"};margin-right:4px"></span>'
        s += f'<span class="t75 {"text" if sel else "muted"}" style="flex:1">{name}</span><span class="t6 muted" style="width:40px">{rank}</span><span class="t6 gold" style="width:30px;text-align:right">{pts}</span></div>'
    s += "</div>" + t("t6 hint", 8, 262, "6 players")
    px, py = 180, 40
    s += t("t1 text", px, py, "Mrgregles") + t("t6 hint", px + 54, py + 2, "rank 3 of 5 - Warden") + t("t75 gold", px, py + 14, "2140 points - 640 this season")
    s += inset(px, py + 28, 332, 120)
    col, row = 0, 0
    for name, on, pts, per, unit, src, count, got in SIGNALS:
        if not on and count == 0:
            continue
        cx, ry = 6 + col * 164, 4 + row * 11
        s += t("t6 " + ("text" if on else "muted"), cx, ry, name) + t("t6 " + ("gold" if on else "muted"), cx + 156, ry, f"{count}  ({got})", "transform:translateX(-100%)")
        row += 1
        if row >= 10:
            row, col = 0, col + 1
    s += "</div>" + t("t6 hint", px, py + 152, "bonus from admins: -105")
    by = py + 164
    s += btn(px, by, 70, 13, "SET RANK") + btn(px + 74, by, 76, 13, "ADD POINTS", "gold") + btn(px + 332 - 70, by, 70, 13, "RESET", "bad")
    s += t("t6 hint", 10, H - 12, "Changes save at once and reach every server. Esc closes.")
    return s + "</div>"


def admin_options():
    s = screen_frame("RANKUP ADMIN", STATUS, T_OPTIONS)
    px, py = 8, 40
    s += field(px, py, 150, "LUCKPERMS TRACK", "rankup")
    s += t("t5 hint", px + 160, py, "ANNOUNCE RANK-UPS") + chip(px + 160, py + 7, 50, 13, "off") + chip(px + 213, py + 7, 50, 13, "server") + chip(px + 266, py + 7, 50, 13, "network", "vio")
    s += field(px, py + 28, 150, "KILL COOLDOWN (MIN)", "10") + field(px + 160, py + 28, 100, "TITLE PRIORITY", "1000")
    s += t("t5 hint", px + 270, py + 28, "SEASONAL REWARDS") + chip(px + 270, py + 35, 60, 13, "each season", "on") + chip(px + 333, py + 35, 40, 13, "once")
    s += t("t5 hint", px + 384, py + 28, "PREFIXES IN CHAT") + chip(px + 384, py + 35, 30, 13, "on", "on") + chip(px + 417, py + 35, 30, 13, "off")
    by = py + 60
    s += btn(px, by, 150, 13, "MAKE TRACK IN LUCKPERMS", "bad") + t("t5 hint", px + 156, by + 3, "makes the track and any missing groups from this ladder")
    s += btn(px, by + 18, 72, 13, "EXPORT") + btn(px + 76, by + 18, 72, 13, "IMPORT") + t("t5 hint", px + 156, by + 21, "files live in config/bsp_rankup/")
    iy = by + 42
    s += inset(px, iy, 504, 60)
    lines = ["LuckPerms: Forge edition", "Track rankup: settler > raider > warden > vanguard > legend",
             "Storage: mysql localhost:3306/bsp as bsp, prefix bsp_ru_, this server 'survival-2', no errors, 14 notices handled; redis 10.0.0.5:6379, channel bsp_ru:messages: connected; sent 9, received 14",
             "Players with progress here: 106"]
    for i, line in enumerate(lines):
        s += t("t5 muted", 6, 4 + i * 9, line[:150])
    s += "</div>"
    ry = iy + 70
    s += btn(px, ry, 120, 13, "RESET EVERYONE", "bad") + t("t5 hint", px + 126, ry + 3, "asks for a code first; the code must be typed back within a minute")
    s += t("t6 tq", 10, H - 12, "Rank-ups are announced: network")
    return s + "</div>"


def player_screen():
    s = screen_frame("YOUR RANK", "Mrgregles - survival-2 - season 4")
    s += chip(8, 22, 64, 11, "PLAYTIME", "big on") + chip(75, 22, 50, 11, "RAIDER", "big") + chip(128, 22, 50, 11, "AWARDS", "big")
    lx, ly, lw, lh = 8, 36, 172, 248
    s += inset(lx, ly, lw, lh)
    n = len(LADDER)
    step = max(30, min(44, (lh - 12) // n))
    trackx = 16
    top, bottom = 6 + step // 2, 6 + (n - 1) * step + step // 2
    s += f'<div class="abs" style="left:{trackx - 1}px;top:{top}px;width:2px;height:{bottom - top}px;background:var(--line)"></div>'
    current = 2
    lit_top = 6 + (n - 1 - current) * step + step // 2
    s += f'<div class="abs" style="left:{trackx - 1}px;top:{lit_top}px;width:2px;height:{bottom - lit_top}px;background:var(--tq)"></div>'
    for row in range(n):
        i = n - 1 - row
        g, name, pts, seasonal, holders = LADDER[i]
        cy = 6 + row * step
        mine, below = i == current, i < current
        node = "var(--gold)" if mine else "var(--tq)" if below else "var(--vio)" if seasonal else "var(--line)"
        s += f'<div class="abs" style="left:{trackx - 3}px;top:{cy + step // 2 - 3}px;width:6px;height:6px;background:{node}"></div>'
        cx, cw, ch = trackx + 10, lw - 34, step - 6
        border = "var(--gold)" if mine else "var(--line)"
        s += f'<div class="abs" style="left:{cx}px;top:{cy}px;width:{cw}px;height:{ch}px;background:{"rgba(255,210,58,0.13)" if mine else "var(--inset)"};border:1px solid {border}"></div>'
        s += t("t75 " + ("gold" if mine else "text" if below else "muted"), cx + 4, cy + 3, name)
        if seasonal:
            s += t("t6 vio", cx + cw - 30, cy + 4, "S", "transform:translateX(-100%)")
        s += t("t6 gold", cx + cw - 4, cy + 4, f"{pts:,}".replace(",", " "), "transform:translateX(-100%)")
        line = "you, since 11 days" if mine else "next: 3360 points to go" if i == current + 1 else f"{holders} player{'s' if holders != 1 else ''} hold{'s' if holders == 1 else ''} it"
        s += t("t5 " + ("gold" if mine else "hint"), cx + 4, cy + 14, line)
    s += "</div>"
    px, py = 190, 38
    s += f'<div class="abs" style="left:{px}px;top:{py}px;font-size:15.75px;line-height:1;color:var(--text)">WARDEN</div>'
    s += t("t5 hint", px + 70, py + 6, "rank 3 of 5") + f'<div class="abs" style="left:{px + 112}px;top:{py + 3}px">{img("warden")}</div>'
    s += t("t6 gold", px, py + 18, "2140 points - 640 this season")
    ny = py + 34
    s += t("t5 tq", px, ny, "NEXT: VANGUARD AT 4000 POINTS  (this season's points count)")
    s += inset(px, ny + 9, 322, 12) + "</div>" + f'<div class="abs" style="left:{px + 1}px;top:{ny + 10}px;width:{int(320 * 0.16)}px;height:10px;background:rgba(25,211,176,0.8)"></div>'
    s += t("t6 text", px + 135, ny + 12, "640 / 4000")
    s += t("t5 text", px, ny + 24, "Vanguard gives coins worth 200 Copper - 1000 pieces into the bank - 2 x Netherite Ingot - the title [Vanguard]")
    s += t("t5 hint", px, ny + 33, "Fastest: 23 totems, or 56 totems, or 134 kills.")
    cy = ny + 46
    s += t("t5 tq", px, cy, "WHAT COUNTS")
    ly2, lh2 = cy + 8, H - 16 - (cy + 8)
    s += inset(px, ly2, 322, lh2)
    s += t("t5 hint", 6, 3, "signal") + t("t5 hint", 150, 3, "count", "transform:translateX(-100%)") + t("t5 hint", 214, 3, "worth", "transform:translateX(-100%)") + t("t5 hint", 316, 3, "points", "transform:translateX(-100%)")
    ry = 11
    total = 2245
    for name, on, pts, per, unit, src, count, got in SIGNALS:
        if not on and count == 0:
            continue
        if ry + 10 > lh2 - 22:
            break
        s += t("t6 " + ("text" if on else "muted"), 6, ry + 1, name) + t("t6 " + ("muted" if on else "dim"), 150, ry + 1, str(count), "transform:translateX(-100%)")
        s += t("t6 muted", 214, ry + 1, f"{pts} / {per}" if on else "off", "transform:translateX(-100%)")
        s += f'<div class="abs" style="left:222px;top:{ry + 2}px;width:60px;height:4px;background:rgba(25,211,176,0.1)"></div>'
        if on and got:
            s += f'<div class="abs" style="left:222px;top:{ry + 2}px;width:{int(60 * got / total)}px;height:4px;background:var(--tq)"></div>'
        s += t("t6 " + ("gold" if on else "dim"), 316, ry + 1, str(got), "transform:translateX(-100%)")
        ry += 10
    fy = lh2 - 20
    s += f'<div class="abs" style="left:1px;top:{fy - 2}px;width:320px;height:1px;background:var(--line)"></div>'
    s += t("t6 hint", 6, fy + 1, "bonus from admins") + t("t6 bad", 316, fy + 1, "-105", "transform:translateX(-100%)")
    s += t("t6 tq", 6, fy + 10, "total") + t("t6 gold", 316, fy + 10, "2140", "transform:translateX(-100%)")
    s += "</div>" + t("t5 hint", 10, H - 10, "Hover a rung for what it gives. Esc closes.")
    return s + "</div>"


def inventory_button():
    s = '<div class="world"></div>'
    s += '<div class="abs" style="left:16px;top:16px;width:176px;height:166px;background:#c6c6c6;border:2px solid;border-color:#fff #555 #555 #fff;border-radius:2px"></div>'
    s += t("abs", 24, 22, '<span style="font-family:\'VT323\';font-size:9px;color:#3f3f3f">Crafting</span>')
    # player box
    s += '<div class="abs" style="left:42px;top:24px;width:50px;height:70px;background:#000;border:1px solid #8b8b8b"></div>'
    s += '<div class="abs" style="left:58px;top:34px;width:18px;height:18px;background:#8a5a3b"></div><div class="abs" style="left:56px;top:52px;width:22px;height:30px;background:#2d8f3a"></div>'
    def slot(x, y):
        return f'<div class="abs" style="left:{x}px;top:{y}px;width:18px;height:18px;background:#8b8b8b;border:1px solid;border-color:#373737 #fff #fff #373737"></div>'
    for i in range(4):
        s += slot(24, 24 + i * 18)
    s += slot(93, 78)
    for r in range(2):
        for c in range(2):
            s += slot(114 + c * 18, 34 + r * 18)
    s += slot(168, 44)
    for r in range(3):
        for c in range(9):
            s += slot(24 + c * 18, 100 + r * 18)
    for c in range(9):
        s += slot(24 + c * 18, 158)
    s += '<div class="abs" style="left:120px;top:38px;width:20px;height:18px;background:#8b8b8b;border:1px solid;border-color:#373737 #fff #fff #373737"></div>'
    # the RankUp button at (132, 61) from the gui's corner
    bx, by = 16 + 132, 16 + 61
    s += f'<div class="abs" style="left:{bx}px;top:{by}px;width:20px;height:14px;background:#6f6f6f;border:1px solid;border-color:#fff #2a2a2a #2a2a2a #fff;display:flex;align-items:center;justify-content:center;font-size:9px;color:#fff">R</div>'
    s += f'<div class="abs" style="left:{bx + 24}px;top:{by - 2}px;background:rgba(16,0,16,0.94);border:1px solid #5000ff;padding:2px 4px;font-size:8px;color:#fff">Your rank</div>'
    s += '<div class="abs cap" style="left:208px;top:30px;width:130px;font-size:6.5px;line-height:1.5;color:var(--text)">The <b>R</b> button in the inventory opens your rank screen. So do the <b>U</b> key and <b>/rank</b>. Its spot is a client setting, so it can move off other mods\' buttons.</div>'
    return s


def card(x, y, w, h, title, body, cls="tq"):
    return f'<div class="card" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px"><h3 class="{cls}">{title}</h3><p>{body}</p></div>'


def flow():
    s = '<div class="world"></div>'
    s += card(12, 18, 130, 96, "WHAT PLAYERS DO", "Minutes online, players defeated, mobs killed, Shatter Totems stolen and defended, claims bought... Every one is a <b>signal</b>, on or off, worth so many points per unit.")
    s += '<div class="arrow" style="left:146px;top:52px">&#8594;</div>'
    s += card(162, 18, 120, 96, "POINTS", "Each signal's count times its worth, plus any bonus an admin gave. Lifetime points for permanent rungs; points since the last season end for seasonal ones.", "gold")
    s += '<div class="arrow" style="left:286px;top:52px">&#8594;</div>'
    s += card(302, 18, 130, 96, "THE LADDERS", "Each a LuckPerms track counting its own signals: each rung a group, the points it needs, a seasonal flag and what it gives. Awards are single groups given at their points.", "vio")
    s += '<div class="arrow" style="left:436px;top:52px">&#8594;</div>'
    s += card(452, 18, 130, 96, "RANK UP", "LuckPerms gets the group; you get the rewards (coins, pieces, items, a title, commands, kits), a message, and the server hears it. Your title shows before your name, as an image if the rank has one.", "tq")
    s += '<div class="abs cap" style="left:12px;top:124px;font-size:7px;color:var(--muted)">All of it set in game by an admin; the server config holds only the database connection for a network.</div>'
    return s


def network():
    s = '<div class="world"></div>'
    for i, (name, x) in enumerate([("survival-1", 20), ("survival-2", 190), ("creative", 360)]):
        s += card(x, 18, 150, 70, name.upper(), "Forge 1.20.1 with BSP Core, RankUp and LuckPerms. Counts are written through as they happen; a player's row is read when they log in here, before anything is decided for them.")
    s += '<div class="abs" style="left:95px;top:88px;width:2px;height:30px;background:var(--line)"></div><div class="abs" style="left:265px;top:88px;width:2px;height:30px;background:var(--line)"></div><div class="abs" style="left:435px;top:88px;width:2px;height:30px;background:var(--line)"></div>'
    s += '<div class="abs" style="left:95px;top:118px;width:342px;height:2px;background:var(--line)"></div>'
    s += card(60, 128, 160, 58, "MYSQL", "bsp_ru_counts, bsp_ru_players, bsp_ru_settings, bsp_ru_images, bsp_ru_notices: one set of tables for the whole network, so playtime on every server adds up, the ladders are the same everywhere, and a prefix image picked on one server shows on all.", "gold")
    s += card(240, 128, 130, 58, "REDIS", "Settings changes, prefix images and announcements reach every server at once; the notice table carries them when Redis is down.", "bad")
    s += card(390, 128, 180, 58, "LUCKPERMS", "Its own shared database: a group given on one server holds on all of them, and Region Guard's rank-gated regions see it too.", "vio")
    return s


def companions():
    s = '<div class="world"></div>'
    s += card(12, 16, 180, 150, "BSP CORE (required)", "Totem events: steals that succeed count as stolen for the thief and lost for the owner, steals that fail while you are online as protected, totems placed. The bank, the coins and the kits for rewards, the season score as a signal, the teams. The same admin list as every BSP mod. Season ends drive the seasonal rungs. Needs 1.4.5 or newer.")
    s += card(206, 16, 180, 150, "BSP REGION GUARD (optional)", "Buying a plot at the Land Office counts a claim bought. Region Guard's regions and mines are gated by LuckPerms groups: the ranks and awards RankUp hands out open them (list every rung from the lowest that should get in). The server id is shared.", "vio")
    s += card(400, 16, 180, 150, "LUCKPERMS and BSP TELEPORT", "LuckPerms holds the ranks (the Forge mod, or the Bukkit plugin on Mohist): RankUp moves players along each ladder's track, adds award groups, sets titles, and shows every player's prefix before their name, as an image when the rank has one. BSP Teleport needs no link.", "gold")
    return s


def chat():
    s = '<div class="world"></div>'
    s += '<div class="abs" style="left:12px;top:12px;width:376px;height:96px;background:rgba(0,0,0,0.5)"></div>'
    lines = [("#ffffff", "&lt;" + img("mod") + " Tyler_S&gt; who is at the arena"), ("#ffff55", "Mrgregles reached the rank Warden!"), ("#55ffff", "You are now Warden!"),
             ("#ffffff", "+ Shatter Coins worth 64 Copper"), ("#ffffff", "+ 250 pieces into your bank"), ("#ffffff", "+ 2 x Netherite Ingot"), ("#ffffff", 'Your title is now <span style="color:#55ffff">[Warden]</span>'),
             ("#aaaaaa", "[Season 4 is over: you are Warden again. Seasonal ranks start afresh.]")]
    for i, (c, line) in enumerate(lines):
        s += f'<div class="abs" style="left:16px;top:{16 + i * 11}px;font-size:9px;color:{c};text-shadow:1px 1px #000">{line}</div>'
    return s


def banner():
    s = '''<svg class="abs" style="left:0;top:0" width="600" height="150" viewBox="0 0 600 150">
  <defs><linearGradient id="fade" x1="0" x2="1"><stop offset="0" stop-color="#0c1016" stop-opacity="1"/><stop offset="0.55" stop-color="#0c1016" stop-opacity="0.2"/><stop offset="1" stop-color="#0c1016" stop-opacity="0"/></linearGradient></defs>
  <g opacity="0.95">
    <line x1="470" y1="130" x2="470" y2="22" stroke="#2a2f3a" stroke-width="3"/>
    <line x1="470" y1="130" x2="470" y2="74" stroke="#19d3b0" stroke-width="3"/>
    <rect x="464" y="124" width="12" height="12" fill="#19d3b0"/><rect x="464" y="98" width="12" height="12" fill="#19d3b0"/>
    <rect x="463" y="71" width="14" height="14" fill="#ffd23a"/><circle cx="470" cy="78" r="12" fill="none" stroke="#ffd23a" stroke-opacity="0.5"/>
    <rect x="464" y="46" width="12" height="12" fill="none" stroke="#b58cff" stroke-width="2"/><rect x="464" y="20" width="12" height="12" fill="none" stroke="#b58cff" stroke-width="2"/>
    <text x="490" y="134" fill="#9aa3b5" font-family="VT323" font-size="14">SETTLER</text>
    <text x="490" y="108" fill="#e6eaf2" font-family="VT323" font-size="14">RAIDER</text>
    <text x="490" y="82" fill="#ffd23a" font-family="VT323" font-size="16">WARDEN  - you</text>
    <text x="490" y="56" fill="#b58cff" font-family="VT323" font-size="14">VANGUARD</text>
    <text x="490" y="30" fill="#b58cff" font-family="VT323" font-size="14">LEGEND</text>
  </g>
  <rect x="0" y="0" width="600" height="150" fill="url(#fade)"/>
</svg>'''
    s += '<div class="abs" style="left:28px;top:30px;font-size:44px;line-height:1;color:var(--tq);letter-spacing:1px">BSP RANKUP</div>'
    s += '<div class="abs cap" style="left:30px;top:80px;font-size:11px;color:var(--text)">Play. Fight. Raid. Rank up. Automatic LuckPerms ranks for the BSP server.</div>'
    s += '<div class="abs t75 muted" style="left:30px;top:104px">Forge 1.20.1 - for the Build Secure Protect modpack - needs BSP-Core and LuckPerms</div>'
    s += '<div class="abs t75 muted" style="left:30px;top:116px">works with BSP-Region Guard and BSP-Teleport</div>'
    return s


def admin_prefixes():
    """PREFIXES (D65): every prefix by where it comes from, the chosen one's image and the folder's files."""
    s = screen_frame("RANKUP ADMIN", "7 prefixes - 5 with an image - 6 files", T_PREFIXES)
    s += inset(8, 40, 200, 216)
    rows = [("LADDER PLAYTIME", None, None), ("Warden", "warden", True), ("Legend", "legend", False), ("LADDER RAIDER", None, None), ("Reaver", "reaver", False),
            ("AWARDS", None, None), ("apple_picker", '<span style="color:#55ff55">[Apple Picker]</span>', False),
            ("LUCKPERMS GROUPS", None, None), ("admin", "admin", False), ("moderator", "mod", False), ("founder", '<span style="color:#ffaa00">[Founder]</span>', False)]
    for i, (name, shown, sel) in enumerate(rows):
        ry = 1 + i * 14
        if shown is None:
            s += t("t5 tq", 6, ry + 5, name)
            continue
        text_prefix = shown.startswith("<span")
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:14px">'
        s += f'<span class="t75 {"text" if sel else "muted"}" style="flex:1">{name}</span>'
        s += f'<span class="t6" style="margin-right:3px">{shown}</span>' if text_prefix else f'<span style="margin-right:2px">{img(shown)}</span>'
        if text_prefix:
            s += '<span class="t5 muted">text</span>'
        s += "</div>"
    s += "</div>" + btn(8, 262, 60, 13, "REFRESH", "") + chip(71, 262, 92, 13, "only without image")
    px, py = 216, 40
    s += t("t1 text", px, py, "WARDEN") + t("t6 hint", W - 8, py + 2, "title of rung 3 on Playtime", "transform:translateX(-100%)")
    s += t("t5 hint", px, py + 14, "PREFIX TEXT") + inset(px, py + 21, 150, 13) + "</div>" + t("t75", px + 4, py + 24, '<span style="color:#55ffff">[Warden]</span>')
    s += btn(px + 156, py + 21, 74, 13, "EDIT ON LADDER", "")
    s += t("t5 hint", px, py + 42, "IMAGE") + inset(px, py + 49, 296, 34) + "</div>"
    s += f'<div class="abs" style="left:{px + 6}px;top:{py + 61}px">{img("warden")}</div>' + t("t5 hint", px + 50, py + 63, "line size")
    s += f'<div class="abs" style="left:{px + 78}px;top:{py + 57}px">{img("warden", 2)}</div>' + t("t5 hint", px + 162, py + 63, "x2")
    s += t("t6 hint", px + 290, py + 56, "warden.png - 40 x 9", "transform:translateX(-100%)")
    s += t("t5 hint", px, py + 90, "IN CHAT")
    s += f'<div class="abs" style="left:{px}px;top:{py + 97}px;width:296px;height:13px;background:rgba(0,0,0,0.5)"></div>'
    s += f'<div class="abs" style="left:{px + 4}px;top:{py + 99}px;font-size:8px;color:#fff;text-shadow:1px 1px #000">&lt;{img("warden")} Mrgregles&gt; hello</div>'
    s += t("t5 tq", px, py + 118, "FILES IN prefix_images/") + inset(px, py + 126, 296, 74)
    files = [("warden", "warden.png", "40 x 9", "in use here", "tq"), ("legend", "legend.png", "44 x 9", "on legend", "muted"), ("reaver", "reaver.png", "40 x 9", "on reaver", "muted"),
             ("admin", "admin.png", "36 x 9", "on admin", "muted"), ("mod", "mod_badge.png", "28 x 9", "on moderator", "muted"), (None, "founder_banner.png", "", "300 x 80: over 256 x 64", "bad")]
    for i, (key, name, size, tag, cls) in enumerate(files):
        ry = 1 + i * 12
        s += f'<div class="row{" sel" if i == 0 else ""}" style="top:{ry}px;height:12px">'
        s += f'<span style="width:70px">{img(key) if key else ""}</span><span class="t6 {"bad" if key is None else "text" if i == 0 else "muted"}" style="width:76px">{name}</span>'
        s += f'<span class="t5 hint" style="flex:1">{size}</span><span class="t5 {cls}">{tag}</span></div>'
    s += "</div>"
    s += btn(px, py + 205, 70, 13, "USE IMAGE", "") + btn(px + 73, py + 205, 84, 13, "SHOW TEXT AGAIN", "bad") + t("t5 hint", px + 164, py + 208, "saved for every server")
    s += t("t6 tq", 10, H - 12, "warden's title now shows warden.png in chat and the tab list")
    return s + "</div>"


def admin_groups():
    """GROUPS (D50, D51): LuckPerms' groups, an award chosen."""
    s = screen_frame("RANKUP ADMIN", "27 LuckPerms groups - 10 on ladders - 2 awards", T_GROUPS)
    s += inset(8, 40, 164, 14) + "</div>" + t("t75 hint", 13, 43, "find group")
    s += inset(8, 56, 164, 198)
    rows = [("ON A LADDER", None, ""), ("settler", "Playtime 1", "tq"), ("warden", "Playtime 3", "tq"), ("cutpurse", "Raider 1", "tq"), ("reaver", "Raider 3", "tq"),
            ("AWARDS", None, ""), ("apple_picker", "pieces_earned", "gold"), ("bulwark", "totems_protected", "gold"),
            ("NOT RANKUP'S", None, ""), ("admin", "not used", "muted"), ("builder", "not used", "muted"), ("moderator", "not used", "muted")]
    for i, (name, tag, cls) in enumerate(rows):
        ry = 1 + i * 14
        if tag is None:
            s += t("t5 tq", 6, ry + 5, name)
            continue
        sel = name == "apple_picker"
        s += f'<div class="row{" sel" if sel else ""}" style="top:{ry}px;height:14px"><span class="t75 {"text" if sel else "muted"}" style="flex:1">{name}</span><span class="t5 {cls}">{tag}</span></div>'
    s += "</div>" + t("t5 hint", 8, 262, "27 groups") + btn(122, 259, 50, 13, "REFRESH", "")
    px, py = 180, 40
    s += t("t1 text", px, py, "APPLE_PICKER") + chip(px + 78, py - 1, 40, 11, "AWARD", "gold") + t("t5 hint", W - 8, py + 2, "14 players hold it", "transform:translateX(-100%)")
    s += t("t5 hint", px, py + 12, "Given once a player reaches it, and kept. Not on any ladder.")
    s += field(px, py + 22, 120, "DISPLAY NAME", "Apple Picker") + field(px + 126, py + 22, 70, "NEEDS (POINTS)", "5000")
    s += t("t5 hint", px + 278, py + 22, "SEASON") + chip(px + 278, py + 29, 26, 13, "keep", "on") + chip(px + 305, py + 29, 27, 13, "reset")
    s += t("t5 hint", px, py + 47, "COUNTS") + chip(px + 24, py + 44, 66, 12, "pieces earned", "on") + chip(px + 93, py + 44, 46, 12, "+ signal")
    s += t("t5 tq", px, py + 62, "REWARDS WHEN REACHED") + inset(px, py + 70, 332, 76)
    rewards = [("ITEM", "16 x Golden Apple"), ("TITLE", '<span style="color:#55ff55">[Apple Picker]</span> <span class="hint">&amp;a[Apple Picker]</span>')]
    for i, (k, v) in enumerate(rewards):
        ry = 1 + i * 12
        if k == "TITLE":
            s += f'<div class="abs" style="left:{332 - 64}px;top:{ry + 1}px;width:48px;height:10px;border:1px solid var(--line);display:flex;align-items:center;justify-content:center"><span class="t5 muted">image</span></div>'
        s += t("t5 vio", 4, ry + 3, k) + t("t6 text", 50, ry + 2, v) + chip(332 - 14, ry + 1, 11, 10, "x", "bad")
    s += "</div>"
    ay = py + 70 + 76 + 5
    s += kind_chips(px, ay, False)
    s += t("t5 hint", px, ay + 21, "Stopping keeps the group on players who have it.") + btn(W - 8 - 80, ay + 18, 80, 13, "STOP AWARDING", "bad")
    s += t("t6 hint", 10, H - 12, "Changes save at once and reach every server. Esc closes.")
    return s + "</div>"


def prefix_chat():
    """Prefixes before names in chat and the tab list: images where the rank has one, coloured text where not."""
    s = '<div class="world"></div>'
    s += '<div class="abs" style="left:110px;top:8px;width:180px;height:44px;background:rgba(0,0,0,0.5)"></div>'
    s += '<div class="abs" style="left:110px;top:10px;width:180px;text-align:center;font-size:7px;color:#aaa">BSP Spawn - 23 online</div>'
    tab = [(img("admin"), "Steve_Admin"), (img("warden"), "Mrgregles"), ('<span style="color:#55ff55">[Apple Picker]</span>', "PieFarmer"), (img("mod"), "Kestrel")]
    for i, (p, n) in enumerate(tab):
        x, y = 112 + (i % 2) * 89, 20 + (i // 2) * 12
        s += f'<div class="abs" style="left:{x}px;top:{y}px;width:86px;height:10px;background:rgba(255,255,255,0.08);display:flex;align-items:center;gap:2px;font-size:7px;color:#fff;white-space:nowrap;overflow:hidden">{p}<span>{n}</span></div>'
    s += '<div class="abs" style="left:12px;top:64px;width:376px;height:58px;background:rgba(0,0,0,0.5)"></div>'
    lines = [(img("warden"), "Mrgregles", "anyone up for a raid at Stormfall?"), (img("admin"), "Steve_Admin", "restart in 5 minutes, find somewhere safe"),
             ('<span style="color:#55ff55">[Apple Picker]</span> ', "PieFarmer", "5000 pieces of apple. never again"), (img("mod"), "Kestrel", "gg on the totem")]
    for i, (p, n, m) in enumerate(lines):
        s += f'<div class="abs" style="left:16px;top:{67 + i * 13}px;font-size:9px;color:#fff;text-shadow:1px 1px #000;white-space:nowrap">&lt;{p} {n}&gt; {m}</div>'
    s += '<div class="abs" style="left:60px;top:52px;background:rgba(16,0,16,0.94);border:1px solid #5000ff;padding:1px 3px;font-size:7px;color:#fff;white-space:nowrap"><span style="color:#55ffff">[Warden]</span> <span style="color:#aaa">Playtime: Warden</span></div>'
    return s


def main():
    page("banner", (600, 150), banner(), "#0c1016")
    page("player_screen", PAGE, player_screen())
    page("admin_ladder", PAGE, admin_ladder())
    page("admin_signals", PAGE, admin_signals())
    page("admin_players", PAGE, admin_players())
    page("admin_options", PAGE, admin_options())
    page("item_browser", PAGE, item_browser())
    page("kit_picker", PAGE, admin_ladder(True))
    page("inventory_button", (350, 200), inventory_button())
    page("rankup_flow", (596, 140), flow())
    page("network", (590, 196), network())
    page("companions", (592, 176), companions())
    page("chat", (400, 120), chat())
    page("admin_prefixes", PAGE, admin_prefixes())
    page("admin_groups", PAGE, admin_groups())
    page("prefix_chat", (400, 130), prefix_chat())
    print("16 pages written to", HERE)


if __name__ == "__main__":
    main()
