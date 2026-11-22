#!/usr/bin/env python3
"""
Pixel-art prefix images for two supporter ranks, in the same 9-pixel-tall art as gen_prefix_art.py:
  VIP   server supporters; it needs to look fancy
  BUG   a red bug: the player paid to help fix a bug

Three options for each, for the owner to choose from:
  VIP  A  gilded banner    the house swallowtail banner in royal purple, edged and lettered in gold, sparkles at the tails
       B  jewelled plate   a double gold frame around a dark plate, a cut gem before gold lettering
       C  gold insignia    no box: tall gold lettering with a shadow, a sparkle either side
  BUG  A  disc             a red beetle on the grey disc, like the achievement icons (D87)
       B  bare             the same beetle with no disc
       C  banner           a red swallowtail banner: a white beetle and the word BUG

  python3 tools/prefix_art/gen_supporter_art.py                   the mock-up page, tools/preview/supporter_prefix_mockups.html
  python3 tools/prefix_art/gen_supporter_art.py --vip A --bug A --out DIR
                                                                  two PNGs (vip.png, bug_hunter.png); the picks by default

The owner picked VIP A and BUG A (D135). gen_prefix_art.py writes them into docs/prefix_images/ with the other ranks.

Standard library only.
"""
import argparse
import html
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import gen_prefix_art as art  # noqa: E402
from gen_prefix_art import H, Img, dark, data_uri, lettering, light, outlined  # noqa: E402

ROOT = art.ROOT

# ------------------------------------------------------------------ colours

GOLD_HI, GOLD, GOLD_LO, GOLD_DEEP = 0xFFF2A8, 0xF2BE2C, 0xB8861A, 0x6E4A0C
ROYAL = 0x4A1A7A
SPARK = 0xFFFFFF
RED_HI, RED, RED_LO, RED_DEEP = 0xFF8A80, 0xE53935, 0xA31515, 0x2A0A0A


def gold_at(y, top=1, bottom=7):
    """Lettering lit from above: a bright top row, gold below."""
    return GOLD_HI if y == top else GOLD


def sparkle(img, cx, cy, big=False):
    """A four-point twinkle: a white heart, gold arms."""
    img.set(cx, cy, SPARK)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        img.set(cx + dx, cy + dy, GOLD_HI)
    if big:
        for dx, dy in ((2, 0), (-2, 0), (0, 2), (0, -2)):
            img.set(cx + dx, cy + dy, GOLD)


# ------------------------------------------------------------------ VIP

def vip_banner():
    """A: the house banner (C's swallowtail) in royal purple, a gold edge and gold lettering, a glint in each tail."""
    pixels, tw = lettering("VIP")
    pad = 6
    w = tw + pad * 2
    notch = {0: range(3, 6), 1: range(4, 5)}

    def inside(x, y):
        d = min(x, w - 1 - x)
        return not (d in notch and y in notch[d])

    img = Img(w)
    for (x, y), part in outlined(w, inside).items():
        img.set(x, y, GOLD if part == "edge" else (light(ROYAL, 0.3) if y == 1 else ROYAL))
    for x, y in pixels:
        img.set(x + pad, y + 2, gold_at(y + 2, 2, 6))
    img.set(3, 2, SPARK)
    img.set(w - 4, 6, SPARK)
    return img


GEM = ["..#..", ".###.", "#####", ".###.", "..#.."]


def vip_plate():
    """B: a double gold frame round a dark plate; a cut gem leads gold lettering."""
    pixels, tw = lettering("VIP")
    w = 3 + 5 + 2 + tw + 3
    img = Img(w)
    for (x, y), part in outlined(w, lambda x, y: not ((x in (0, w - 1)) and (y in (0, H - 1)))).items():
        img.set(x, y, GOLD_LO if part == "edge" else 0x1C1024)
    for x in range(1, w - 1):  # the inner frame: bright on top, deep below
        img.set(x, 1, GOLD_HI if x % 3 else GOLD)
        img.set(x, H - 2, GOLD_DEEP)
    for y in range(2, H - 2):
        img.set(1, y, GOLD)
        img.set(w - 2, y, GOLD_DEEP)
    gem = {(x, y) for y, row in enumerate(GEM) for x, c in enumerate(row) if c == "#"}
    for x, y in gem:
        shade = 0xC8FFF8 if x + y < 3 else 0x4CDCD2 if x + y < 6 else 0x1C7078
        img.set(3 + x, 2 + y, shade)
    img.set(4, 3, SPARK)
    for x, y in pixels:
        img.set(10 + x, y + 2, gold_at(y + 2, 2, 6))
    return img


def vip_insignia():
    """C: no box. Tall gold lettering with a dark shadow, a sparkle either side."""
    pixels, tw = lettering("VIP", big=True)
    lead = 4
    w = lead + tw + 1 + 4
    img = Img(w)
    for x, y in pixels:
        img.set(lead + x + 1, y + 2, GOLD_DEEP)
    for x, y in pixels:
        img.set(lead + x, y + 1, gold_at(y + 1, 1, 7))
    sparkle(img, 1, 2)
    sparkle(img, w - 2, 6)
    return img


VIP = {"A": ("Gilded banner", "The house banner, like Legend and Admin, in royal purple: a gold edge, gold lettering lit from above, a lighter band along the top and a white glint in each tail.", vip_banner),
       "B": ("Jewelled plate", "A plate with a double gold frame (bright on top, deep below) round a near-black ground, a cut diamond before gold lettering. The most ornate, and wider than the others.", vip_plate),
       "C": ("Gold insignia", "No box at all: tall gold lettering with a dark drop shadow, a twinkle on either side. Stands apart from every other tag, which are all banners.", vip_insignia)}

# ------------------------------------------------------------------ BUG

# nine-by-nine ladybird from above: k black (head, legs, seam, spots), o the shell's dark red rim, h highlight,
# m red, d shaded red, e eye
BEETLE = ["..k...k..",
          "...k.k...",
          "..kekek..",
          ".ohhkmmo.",
          "kohkkmkok",
          ".ohhkmmo.",
          "komkkmkok",
          ".omdkddo.",
          "..ooooo.."]
BEETLE_PAL = {"k": 0x16080A, "o": 0x8E1212, "h": RED_HI, "m": RED, "d": RED_LO, "e": 0xFFFFFF}


def bug_bare():
    """B: the red beetle with no background."""
    img = Img(H)
    for y, row in enumerate(BEETLE):
        for x, c in enumerate(row):
            if c != ".":
                img.set(x, y, BEETLE_PAL[c])
    return img


def bug_disc():
    """A: the beetle on the grey disc, the way the achievement icons sit (D87)."""
    img = Img(H)
    for (x, y), part in outlined(H, lambda x, y: art.DISC[y][x] == "#").items():
        img.set(x, y, art.GREY[2] if part == "edge" else (art.GREY[0] if x + y < 6 else art.GREY[1]))
    for y, row in enumerate(BEETLE):
        for x, c in enumerate(row):
            if c != ".":
                img.set(x, y, BEETLE_PAL[c])
    return img


SMALL_BUG = ["#...#", ".###.", "##.##", ".#.#.", "#.#.#"]


def bug_banner():
    """C: a red swallowtail banner, edged dark, with a white beetle and the word BUG."""
    pixels, tw = lettering("BUG")
    w = 4 + 5 + 2 + tw + 4
    notch = {0: range(3, 6), 1: range(4, 5)}

    def inside(x, y):
        d = min(x, w - 1 - x)
        return not (d in notch and y in notch[d])

    img = Img(w)
    for (x, y), part in outlined(w, inside).items():
        img.set(x, y, RED_DEEP if part == "edge" else (RED_HI if y == 1 else RED))
    for y, row in enumerate(SMALL_BUG):
        for x, c in enumerate(row):
            if c == "#":
                img.set(4 + x, 2 + y, 0xFFFFFF)
    for x, y in pixels:
        img.set(11 + x, y + 2, 0xFFFFFF)
    return img


BUG = {"A": ("Beetle on a disc", "A red beetle with a dark seam, black spots and white eyes, on the same grey disc as the achievement icons, so it sits with them after the name.", bug_disc),
       "B": ("Bare beetle", "The same beetle with no disc: just the bug, like a coloured emoji. Smallest and plainest.", bug_bare),
       "C": ("Red banner", "A red swallowtail banner with a white beetle and the word BUG. Reads as a tag before the name, like the ranks, rather than a badge.", bug_banner)}

# ------------------------------------------------------------------ the picks (D135 to D139)

CHOSEN_VIP, CHOSEN_BUG = "A", "A"
# group, how it shows (prefix or suffix), its LuckPerms text for anyone without the image, the hover note
SUPPORTERS = [("vip", "group", "&8[&6&lVIP&8]", "Supports the BSP server"),
              ("bug_hunter", "suffix", "&c✦ Bug Hunter", "Paid to help fix a bug")]


def chosen():
    """The picked images by group: {"vip": Img, "bug_hunter": Img}."""
    return {"vip": VIP[CHOSEN_VIP][2](), "bug_hunter": BUG[CHOSEN_BUG][2]()}


# ------------------------------------------------------------------ the page


def pic(img, alt, k=None, title=None):
    w = f"calc({img.w}px * var(--k))" if k is None else f"{img.w * k}px"
    h = "calc(9px * var(--k))" if k is None else f"{9 * k}px"
    t = f' title="{html.escape(title)}"' if title else ""
    return f'<img class="px" src="{data_uri(img)}" alt="{alt}" style="width:{w};height:{h}"{t}>'


def page():
    house = art.render("BSP")
    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    import mockup
    css = mockup.page({})
    css = css[css.index("<style>"):css.index("</style>") + 8]
    sections = []
    for key in "ABC":
        vname, vblurb, vfn = VIP[key]
        bname, bblurb, bfn = BUG[key]
        vip, bug = vfn(), bfn()
        bug_as_prefix = key == "C"

        def line(staff, rung, name, ach, msg, with_vip=True, with_bug=True):
            parts = []
            if staff:
                parts.append(pic(house[staff], staff))
            if with_vip:
                parts.append(pic(vip, "vip", title="VIP: supports the BSP server"))
            if with_bug and bug_as_prefix:
                parts.append(pic(bug, "bug", title="Bug hunter: paid to help fix a bug"))
            parts.append(pic(house[rung], rung))
            parts.append(f'<span class="nm">{name}</span>')
            for a in ach:
                parts.append(pic(house[a], a))
            if with_bug and not bug_as_prefix:
                parts.append(pic(bug, "bug", title="Bug hunter: paid to help fix a bug"))
            inner = "".join(parts)
            return inner if msg is None else f'<div class="cl"><span class="br">&lt;</span>{inner}<span class="br">&gt;</span><span class="msg">{msg}</span></div>'

        chat = (line("admin", "legend", "Mrgregles", ("season_champion",), "thanks for the support everyone")
                + line(None, "warden", "PieFarmer", ("founder",), "found another one by the shop", with_bug=True)
                + line(None, "captain", "Kestrel", (), "just went VIP!", with_bug=False)
                + line(None, "scout", "Ferngully", ("sanctuary",), "reported the dupe, paid the bounty", with_vip=False))
        tab = "".join(f'<div class="tr">{line(*row, None, **kw)}</div>' for row, kw in [
            (("admin", "legend", "Mrgregles", ("season_champion",)), {}),
            ((None, "warden", "PieFarmer", ("founder",)), {}),
            ((None, "captain", "Kestrel", ()), {"with_bug": False}),
            ((None, "scout", "Ferngully", ("sanctuary",)), {"with_vip": False}),
            ((None, "wanderer", "NewFarmer22", ()), {"with_vip": False, "with_bug": False})])
        cells = (f'<div class="cell"><div class="art">{pic(vip, "vip", 6)}</div><div class="cap"><b>VIP {key}: {vname}</b><span>{vip.w} × 9 px</span></div></div>'
                 f'<div class="cell"><div class="art">{pic(bug, "bug", 6)}</div><div class="cap"><b>BUG {key}: {bname}</b><span>{bug.w} × 9 px</span></div></div>')
        sections.append(f"""
<section class="opt" id="opt-{key.lower()}">
  <header class="oh"><span class="tag">Option {key}</span><h2>VIP: {vname} · BUG: {bname}</h2>
  <p><b>VIP.</b> {vblurb}</p><p><b>BUG.</b> {bblurb}</p></header>
  <div class="scene">
    <div class="chat">{chat}</div>
    <div class="tab"><div class="tabhead">BSP Spawn · 5 online</div><div class="tabgrid">{tab}</div></div>
  </div>
  <h3>Close up <small>6× zoom</small></h3>
  <div class="grid">{cells}</div>
</section>""")
    questions = [
        ("1. Which VIP", "Default: A, the gilded banner. It matches every other tag in shape, and the purple and gold make it the fanciest-looking of the banners. Any VIP can go with any BUG."),
        ("2. Which BUG", "Default: A, the beetle on a disc. It sits after the name with the achievement icons, as a badge. C is the one to pick if you want it before the name as a tag."),
        ("3. Where they sit", "Default: VIP is a prefix, after the staff tag and before the ladder rank ([Admin] [VIP] [Legend] name); BUG (A or B) is a suffix after the name, last after the achievements. That order is set in LuckPerms' prefix and suffix stacks (BSP-Luckperms/bsp-ranks), so it goes there as a request."),
        ("4. The LuckPerms groups", "Default: vip and bug_hunter, both outside the ladder (given by hand or by the store), each with a prefix or suffix text for anyone without the images: \"[VIP] \" and \" ✦ Bug Hunter\"."),
        ("5. Hover notes", "Default: VIP \"Supports the BSP server\"; BUG \"Paid to help fix a bug\". Shown when someone hovers the tag in chat."),
    ]
    qs = "".join(f"<li><b>{q}</b><p>{d}</p></li>" for q, d in questions)
    nav = "".join(f'<a href="#opt-{k.lower()}"><span>{k}</span>VIP {VIP[k][0]} · BUG {BUG[k][0]}</a>' for k in "ABC")
    return f"""<title>BSP Supporter Prefix Art</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Silkscreen&family=VT323&family=Atkinson+Hyperlegible:wght@400;700&display=swap">
{css}
<div class="wrap">
  <div class="intro">
    <h1>VIP and BUG: three options each</h1>
    <p><b>Every image is 9 pixels tall</b>, like the other rank art, so it stays sharp at any GUI scale. The other tags in the scenes are the real BSP banners and icons. Hover a VIP or BUG image in chat for its note. Mix and match: any VIP option with any BUG option.</p>
  </div>
  <div class="bar">{nav}<a href="#questions"><span>?</span>Decisions</a>
    <span class="scale" role="group" aria-label="GUI scale">GUI scale
      <button type="button" data-k="2" aria-pressed="false">2</button><button type="button" data-k="3" aria-pressed="true">3</button><button type="button" data-k="4" aria-pressed="false">4</button>
    </span>
  </div>
  {"".join(sections)}
  <section class="qs" id="questions">
    <h2>Decisions</h2>
    <ol>{qs}</ol>
    <p class="note">Generated by tools/prefix_art/gen_supporter_art.py. The chat text only stands in for Minecraft's font.</p>
  </section>
</div>
<script>
document.querySelectorAll(".scale button").forEach(b => b.addEventListener("click", () => {{
  document.documentElement.style.setProperty("--k", b.dataset.k);
  document.querySelectorAll(".scale button").forEach(o => o.setAttribute("aria-pressed", String(o === b)));
}}));
</script>
"""


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--vip", choices=list(VIP))
    p.add_argument("--bug", choices=list(BUG))
    p.add_argument("--out", type=pathlib.Path)
    a = p.parse_args()
    if a.out:
        a.out.mkdir(parents=True, exist_ok=True)
        (a.out / "vip.png").write_bytes(VIP[a.vip or CHOSEN_VIP][2]().png())
        (a.out / "bug_hunter.png").write_bytes(BUG[a.bug or CHOSEN_BUG][2]().png())
        print("wrote", a.out / "vip.png", a.out / "bug_hunter.png")
    else:
        target = ROOT / "tools" / "preview" / "supporter_prefix_mockups.html"
        target.write_text(page(), encoding="utf-8")
        print("wrote", target.relative_to(ROOT))


if __name__ == "__main__":
    main()
