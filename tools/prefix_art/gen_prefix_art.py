#!/usr/bin/env python3
"""
Pixel-art prefix images for the BSP ranks (the groups in BSP-Luckperms/bsp-ranks): text tags for the twelve ladder
rungs and the three staff ranks, icons for the seven achievement ranks. Everything is drawn at 9 pixels tall, the
height RankUp draws a prefix image at (one chat line, D60), so it stays sharp at every GUI scale.

Three styles for the owner to choose from:
  A  plates     a framed plate in the rank's colour, the frame in the tier's metal; icons on a matching tile
  B  insignia   tall lettering with a drop shadow and tier pips; icons as full-colour pixel art
  C  banners    a swallowtail ribbon edged in the tier's metal; icons on a round medal
  BSP           the owner's pick (D87): C's banners, B's icons unchanged on a grey disc

  python3 tools/prefix_art/gen_prefix_art.py                  the mock-up page, tools/preview/prefix_art_mockups.html,
                                                              and the chosen style's PNGs in docs/prefix_images/
  python3 tools/prefix_art/gen_prefix_art.py --sheet FILE     every image of every style at 8x, one PNG, for checking
  python3 tools/prefix_art/gen_prefix_art.py --style BSP --out DIR
                                                              one style's PNGs, named after the groups (warden.png)

Standard library only. The pixel font is RankUp's own; no Minecraft asset is copied.
"""
import argparse
import base64
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
H = 9  # every image is one chat line tall

# ------------------------------------------------------------------ the ranks (from BSP-Luckperms/bsp-ranks)

# Minecraft's chat colours, by the code the LuckPerms prefixes use
MC = {"0": 0x000000, "2": 0x00AA00, "3": 0x00AAAA, "4": 0xAA0000, "5": 0xAA00AA, "6": 0xFFAA00, "7": 0xAAAAAA,
      "8": 0x555555, "9": 0x5555FF, "a": 0x55FF55, "b": 0x55FFFF, "c": 0xFF5555, "d": 0xFF55FF, "e": 0xFFFF55, "f": 0xFFFFFF}

# group, shown name, colour code, hours to reach it
LADDER = [("wanderer", "Wanderer", "7", 0), ("settler", "Settler", "f", 2), ("recruit", "Recruit", "a", 5),
          ("scout", "Scout", "2", 10), ("sentinel", "Sentinel", "b", 20), ("warden", "Warden", "3", 35),
          ("vanguard", "Vanguard", "9", 60), ("captain", "Captain", "d", 90), ("commander", "Commander", "5", 130),
          ("marshal", "Marshal", "e", 180), ("warlord", "Warlord", "6", 250), ("legend", "Legend", "c", 350)]
# group, shown name, colour code, symbol (style B)
STAFF = [("moderator", "Mod", "9", "gavel"), ("staff", "Staff", "b", "star"), ("admin", "Admin", "c", "crown")]
# group, suffix text, colour code, icon, how it is earned
ACHIEVEMENTS = [("season_champion", "Champion", "6", "cup", "Winning a BSP season"),
                ("totem_reaver", "Totem Reaver", "5", "crystal", "Most totems stolen in a season"),
                ("titan_slayer", "Titan Slayer", "4", "sword", "Most boss kills in a season"),
                ("headhunter", "Headhunter", "c", "skull", "Most player kills in a season"),
                ("bulwark", "Bulwark", "3", "shield", "Your totem survives 25 steal attempts"),
                ("founder", "Founder", "e", "flag", "Playing in the first season"),
                ("sanctuary", "Keeper", "a", "key", "Earning a plot on the Sanctuary server")]

# the ladder's four tiers of three rungs: copper, iron, gold, diamond (light, mid, dark)
METALS = [("Copper", 0xF0A070, 0xC0703C, 0x6E3A1E), ("Iron", 0xFFFFFF, 0xC8C8C8, 0x6A6A6A),
          ("Gold", 0xFFF07A, 0xF2BE2C, 0x8E5F10), ("Diamond", 0xC8FFF8, 0x4CDCD2, 0x1C7078)]


def tier(index):
    return METALS[index // 3]


# ------------------------------------------------------------------ colour

def readable(c):
    """Dark chat colours (dark blue, purple, green, red) lifted so they read on the chat's dark ground."""
    return split(c) if luminance(c) > 130 else light(c, 0.35)


def mix(a, b, t):
    """a moved toward b by t (0..1)."""
    return tuple(round(x + (y - x) * t) for x, y in zip(split(a), split(b)))


def split(c):
    return c if isinstance(c, tuple) else ((c >> 16) & 255, (c >> 8) & 255, c & 255)


def dark(c, t):
    return mix(c, 0x000000, t)


def light(c, t):
    return mix(c, 0xFFFFFF, t)


def luminance(c):
    r, g, b = split(c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


# ------------------------------------------------------------------ images

class Img:
    def __init__(self, w, h=H):
        self.w, self.h = w, h
        self.px = [[None] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h and c is not None:
            self.px[y][x] = split(c)

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < self.w and 0 <= y < self.h else None

    def png(self):
        raw = b"".join(b"\0" + b"".join(bytes((*p, 255)) if p else b"\0\0\0\0" for p in row) for row in self.px)
        chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

    def scaled(self, k, bg=None):
        out = Img(self.w * k, self.h * k)
        for y in range(self.h):
            for x in range(self.w):
                c = self.px[y][x] or bg
                for dy in range(k):
                    for dx in range(k):
                        out.set(x * k + dx, y * k + dy, c)
        return out


def outlined(mask_w, inside):
    """Pixels of a shape: 'edge' where a side neighbour is outside it, 'fill' otherwise."""
    cells = {}
    for y in range(H):
        for x in range(mask_w):
            if inside(x, y):
                edge = any(not (0 <= x + dx < mask_w and 0 <= y + dy < H and inside(x + dx, y + dy))
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                cells[(x, y)] = "edge" if edge else "fill"
    return cells


# ------------------------------------------------------------------ the pixel font (capitals, five rows)

FONT = {
    "A": [".#.", "#.#", "###", "#.#", "#.#"], "B": ["##.", "#.#", "##.", "#.#", "##."],
    "C": [".##", "#..", "#..", "#..", ".##"], "D": ["##.", "#.#", "#.#", "#.#", "##."],
    "E": ["###", "#..", "##.", "#..", "###"], "F": ["###", "#..", "##.", "#..", "#.."],
    "G": [".##", "#..", "#.#", "#.#", ".##"], "H": ["#.#", "#.#", "###", "#.#", "#.#"],
    "I": ["###", ".#.", ".#.", ".#.", "###"], "J": ["..#", "..#", "..#", "#.#", ".#."],
    "K": ["#.#", "#.#", "##.", "#.#", "#.#"], "L": ["#..", "#..", "#..", "#..", "###"],
    "M": ["#...#", "##.##", "#.#.#", "#...#", "#...#"], "N": ["#..#", "##.#", "#.##", "#..#", "#..#"],
    "O": [".#.", "#.#", "#.#", "#.#", ".#."], "P": ["##.", "#.#", "##.", "#..", "#.."],
    "Q": [".#.", "#.#", "#.#", "##.", ".##"], "R": ["##.", "#.#", "##.", "#.#", "#.#"],
    "S": [".##", "#..", ".#.", "..#", "##."], "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "U": ["#.#", "#.#", "#.#", "#.#", "###"], "V": ["#.#", "#.#", "#.#", "#.#", ".#."],
    "W": ["#...#", "#...#", "#.#.#", "##.##", "#...#"], "X": ["#.#", "#.#", ".#.", "#.#", "#.#"],
    "Y": ["#.#", "#.#", ".#.", ".#.", ".#."], "Z": ["###", "..#", ".#.", "#..", "###"],
    " ": ["..", "..", "..", "..", ".."],
}


def tall(glyph):
    """The seven-row face: three-wide letters gain a middle column, rows 2 and 4 are doubled."""
    rows = [r[:2] + r[1:] if len(r) == 3 else r for r in glyph]
    return [rows[0], rows[1], rows[1], rows[2], rows[3], rows[3], rows[4]]


def lettering(text, big=False):
    """Pixels (x, y) of a word in the font and its width; one blank column between letters."""
    pixels, x = [], 0
    for ch in text.upper():
        g = FONT[ch]
        g = tall(g) if big else g
        for y, row in enumerate(g):
            for dx, cell in enumerate(row):
                if cell == "#":
                    pixels.append((x + dx, y))
        x += len(g[0]) + 1
    return pixels, x - 1


# ------------------------------------------------------------------ icons

# five-by-five silhouettes, for the tiles and medals (A, C) and the staff symbols (B)
GLYPHS = {
    "cup": ["#####", "#####", ".###.", "..#..", ".###."],
    "skull": [".###.", "#####", "#.#.#", ".###.", ".#.#."],
    "crystal": ["..#..", ".#.#.", "##.##", ".##..", "..#.."],
    "sword": ["....#", "...#.", "#.#..", ".#...", "#.#.."],
    "shield": ["#####", "#####", "#####", ".###.", "..#.."],
    "flag": ["#....", "####.", "#####", "####.", "#...."],
    "key": [".....", "###..", "#.###", "###.#", "....."],
    "crown": [".....", "#.#.#", "#####", "#####", "....."],
    "gavel": ["###..", "###..", ".#...", "..#..", "...#."],
    "star": ["..#..", ".###.", "#####", ".###.", ".#.#."],
}

# nine-by-nine full-colour icons (B). k outline, h light, m mid, d dark of the rank's colour; other letters fixed
ICONS = {
    "cup": ["kkkkkkkkk", "khhmmmmdk", "khmmmmmdk", ".khmmmdk.", "..kmmdk..", "...kmk...", "...kdk...", "..kmmdk..", ".kkkkkkk."],
    "skull": ["..kkkkk..", ".kbbbbbk.", "kbbbbbbBk", "kbmmbmmBk", "kbmdbmdBk", ".kbbkbBk.", "..kbbbk..", "..kbkbk..", "...kkk..."],
    "crystal": ["....k....", "...khk...", "..khkmk..", ".khhkmdk.", "khmmmkmdk", ".khmkmdk.", "..kmkdk..", "...kdk...", "....k...."],
    "sword": [".......kk", "......kwk", ".....kwsk", "....kwsk.", ".k.kwsk..", ".kgksk...", "..kgk....", ".kmkgk...", "kmk.kk..."],
    "shield": ["kkkkkkkkk", "khhhhmmdk", "khhmhmmdk", "khmmhmmdk", "khmmhmmdk", ".kmmhmdk.", ".kmmhmdk.", "..kmhdk..", "...kkk..."],
    "flag": ["kk.......", "kpkkkkkk.", "kphhhhmk.", "kphmmmmdk", "kphmmmdk.", "kpkkkkk..", "kpk......", "kpk......", "kkk......"],
    "key": [".........", ".kkkk....", "khhmdk...", "kh..dkkkk", "km..dhmdk", ".kddkkmdk", "..kk..kkk", ".........", "........."],
}
FIXED = {"b": 0xE8E2CC, "B": 0xA9A28A, "w": 0xFFFFFF, "s": 0xB8C0C8, "g": 0xF2BE2C, "p": 0x8A5A2E}


def icon_palette(colour):
    base = colour if luminance(colour) > 70 else light(colour, 0.25)  # dark reds and purples lifted to read on chat
    return {"k": dark(colour, 0.82), "h": light(base, 0.45), "m": split(base), "d": dark(base, 0.35), **{k: split(v) for k, v in FIXED.items()}}


def bare_icon(name, colour):
    art = ICONS[name]
    pal = icon_palette(colour)
    used = [x for row in art for x, c in enumerate(row) if c != "."]
    x0, x1 = min(used), max(used)
    img = Img(x1 - x0 + 1)
    for y, row in enumerate(art):
        for x in range(x0, x1 + 1):
            if row[x] != ".":
                img.set(x - x0, y, pal[row[x]])
    return img


def glyph_into(img, name, ox, oy, colour, shadow=None):
    for y, row in enumerate(GLYPHS[name]):
        for x, c in enumerate(row):
            if c == "#":
                if shadow is not None and img.get(ox + x + 1, oy + y + 1) is None:
                    img.set(ox + x + 1, oy + y + 1, shadow)
                img.set(ox + x, oy + y, colour)


# ------------------------------------------------------------------ style A: plates

def plate_text(text, colour, frame):
    pixels, tw = lettering(text)
    w = tw + 6
    shape = outlined(w, lambda x, y: not ((x in (0, w - 1)) and (y in (0, H - 1))))
    img = Img(w)
    for (x, y), part in shape.items():
        img.set(x, y, frame if part == "edge" else (dark(colour, 0.62) if y == 1 else dark(colour, 0.78)))
    for x, y in pixels:
        img.set(x + 3, y + 2, readable(colour))
    return img


def plate_icon(name, colour):
    colour = readable(colour)
    shape = outlined(H, lambda x, y: not ((x in (0, H - 1)) and (y in (0, H - 1))))
    img = Img(H)
    for (x, y), part in shape.items():
        img.set(x, y, colour if part == "edge" else dark(colour, 0.75))
    glyph_into(img, name, 2, 2, light(colour, 0.35))
    return img


# ------------------------------------------------------------------ style B: insignia

def pips(img, count, metal, x0=0):
    _, hi, mid, lo = metal
    rows = {1: [3], 2: [1, 5], 3: [0, 3, 6]}[count]
    for top in rows:
        img.set(x0 + 1, top, hi)
        img.set(x0, top + 1, hi)
        img.set(x0 + 1, top + 1, mid)
        img.set(x0 + 2, top + 1, lo)
        img.set(x0 + 1, top + 2, lo)


def insignia_text(text, colour, mark):
    """mark: ('pips', count, metal) for a rung, ('glyph', name) for a staff rank."""
    pixels, tw = lettering(text, big=True)
    colour = readable(colour)
    lead = 4 if mark[0] == "pips" else 7
    img = Img(lead + tw + 1)
    if mark[0] == "pips":
        pips(img, mark[1], mark[2])
    else:
        glyph_into(img, mark[1], 0, 2, colour, dark(colour, 0.75))
    shade = dark(colour, 0.75)
    for x, y in pixels:
        img.set(lead + x + 1, y + 2, shade)
    for x, y in pixels:
        img.set(lead + x, y + 1, colour)
    return img


# ------------------------------------------------------------------ style C: banners

def banner_text(text, colour, edge):
    pixels, tw = lettering(text)
    w = tw + 8
    notch = {0: range(3, 6), 1: range(4, 5)}  # the swallowtail cut into each end

    def inside(x, y):
        d = min(x, w - 1 - x)
        return not (d in notch and y in notch[d])

    img = Img(w)
    fill = dark(colour, 0.08)
    for (x, y), part in outlined(w, inside).items():
        img.set(x, y, edge if part == "edge" else (light(fill, 0.18) if y == 1 else fill))
    ink = (0x14, 0x14, 0x18) if luminance(fill) > 150 else (0xFF, 0xFF, 0xFF)
    for x, y in pixels:
        img.set(x + 4, y + 2, ink)
    return img


DISC = ["..#####..", ".#######.", "#########", "#########", "#########", "#########", "#########", ".#######.", "..#####.."]
GREY = (0xA4A8AE, 0x7E838A, 0x4B4F55)  # light, mid, edge


def disc_icon(name, colour):
    """The full-colour icon, unchanged, on a grey disc (the owner's pick, D87)."""
    img = Img(H)
    for (x, y), part in outlined(H, lambda x, y: DISC[y][x] == "#").items():
        img.set(x, y, GREY[2] if part == "edge" else (GREY[0] if x + y < 6 else GREY[1]))
    pal = icon_palette(colour)
    for y, row in enumerate(ICONS[name]):
        for x, c in enumerate(row):
            if c != ".":
                img.set(x, y, pal[c])
    return img


def medal_icon(name, colour):
    rows = DISC
    base = colour if luminance(colour) > 70 else light(colour, 0.3)
    img = Img(H)
    for (x, y), part in outlined(H, lambda x, y: rows[y][x] == "#").items():
        img.set(x, y, dark(base, 0.55) if part == "edge" else (light(base, 0.35) if x + y < 7 else base))
    glyph_into(img, name, 2, 2, dark(base, 0.7))
    return img


# ------------------------------------------------------------------ the three styles

STAFF_FRAME = 0x2A2A30


def render(style):
    """Every image of one style: {group: Img}."""
    out = {}
    for i, (group, name, code, _) in enumerate(LADDER):
        colour = MC[code]
        if style == "A":
            out[group] = plate_text(name, colour, tier(i)[2])
        elif style == "B":
            out[group] = insignia_text(name, colour, ("pips", i % 3 + 1, tier(i)))
        else:
            out[group] = banner_text(name, colour, tier(i)[2])  # C and the chosen style
    for group, name, code, symbol in STAFF:
        colour = MC[code]
        if style == "A":
            out[group] = plate_text(name, colour, colour)
        elif style == "B":
            out[group] = insignia_text(name, colour, ("glyph", symbol))
        else:
            out[group] = banner_text(name, colour, light(colour, 0.3))
    for group, _, code, icon, _ in ACHIEVEMENTS:
        colour = MC[code]
        out[group] = {"A": plate_icon, "B": bare_icon, "C": medal_icon, "BSP": disc_icon}[style](icon, colour)
    return out


STYLES = {
    "BSP": ("Banners with disc icons", "The pick (D87): C's swallowtail banners for the ladder and staff, and B's full-colour icons, unchanged, on a grey disc."),
    "A": ("Plates", "A framed plate in the rank's colour. The frame is the tier's metal: copper for rungs 1 to 3, iron, gold, then diamond for 10 to 12. Icons sit on a tile of the same make."),
    "B": ("Insignia", "No box: taller lettering with a drop shadow, like chat text, led by one to three pips in the tier's metal. Staff ranks lead with a symbol. Icons are full-colour pixel art, the size of Minecraft's hearts."),
    "C": ("Banners", "A swallowtail ribbon in the rank's colour, edged in the tier's metal. Icons are struck on a round medal in the achievement's colour."),
}


# ------------------------------------------------------------------ outputs

def data_uri(img):
    return "data:image/png;base64," + base64.b64encode(img.png()).decode()


def write_sheet(path, wrap=150):
    """Every image of every style at 8x on a chat-dark ground, wrapped into rows, one block per style."""
    rows = []
    for s in STYLES:
        row = []
        for img in render(s).values():
            if row and sum(i.w + 2 for i in row) + img.w > wrap:
                rows.append(row)
                row = []
            row.append(img)
        rows.append(row)
    k, gap = 8, 2
    sheet = Img(wrap + gap * 2, len(rows) * (H + gap) + gap)
    for y in range(sheet.h):
        for x in range(sheet.w):
            sheet.set(x, y, (0x20, 0x24, 0x2A))
    for r, imgs in enumerate(rows):
        x = gap
        for img in imgs:
            for yy in range(H):
                for xx in range(img.w):
                    if img.px[yy][xx]:
                        sheet.set(x + xx, gap + r * (H + gap) + yy, img.px[yy][xx])
            x += img.w + gap
    path.write_bytes(sheet.scaled(k).png())


IMAGES = ROOT / "docs" / "prefix_images"


def write_style(style, out_dir):
    """One style's PNGs, named after the groups, and the commands that assign them once they are on a server."""
    out_dir.mkdir(parents=True, exist_ok=True)
    for group, img in render(style).items():
        (out_dir / f"{group}.png").write_bytes(img.png())
    lines = ["# Copy the PNGs into config/bsp_rankup/prefix_images/ on one server, run /rankup prefix files to check",
             "# them, then paste these lines into that server's console (or pick each image on the PREFIXES tab).",
             "# Made by tools/prefix_art/gen_prefix_art.py; run it again after changing the art.", ""]
    lines += [f"rankup prefix image group {g} {g}.png" for g, *_ in LADDER + STAFF]
    lines += ["", "# The achievement icons are LuckPerms suffixes, drawn after the name (RankUp 0.5.0 or newer)."]
    lines += [f"rankup prefix image suffix {g} {g}.png" for g, *_ in ACHIEVEMENTS]
    lines += ["", "# What hovering each icon in chat shows."]
    lines += [f"rankup prefix note suffix {g} {how}" for g, _, _, _, how in ACHIEVEMENTS]
    (out_dir / "commands.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sheet", type=pathlib.Path)
    p.add_argument("--style", choices=list(STYLES))
    p.add_argument("--out", type=pathlib.Path)
    a = p.parse_args()
    if a.sheet:
        write_sheet(a.sheet)
    elif a.style and a.out:
        write_style(a.style, a.out)
    else:
        import sys
        sys.path.insert(0, str(pathlib.Path(__file__).parent))
        import mockup  # the page lives beside this file
        target = ROOT / "tools" / "preview" / "prefix_art_mockups.html"
        target.write_text(mockup.page({s: render(s) for s in STYLES}), encoding="utf-8")
        print("wrote", target.relative_to(ROOT))
        write_style("BSP", IMAGES)
        print("wrote", IMAGES.relative_to(ROOT))


if __name__ == "__main__":
    main()
