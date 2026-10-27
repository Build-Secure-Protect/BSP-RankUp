"""The mock-up page for the prefix art: three styles in chat and the tab list, every image on a sheet, the questions."""
import html

from gen_prefix_art import ACHIEVEMENTS, LADDER, MC, STAFF, STYLES, data_uri, tier

# chat lines as decided (D88, D89, D90): staff tag, ladder tag, name, every achievement held
CHAT = [("admin", "legend", "Mrgregles", ("season_champion", "founder"), "restart in 5 minutes, find somewhere safe"),
        (None, "warden", "PieFarmer", ("totem_reaver", "headhunter"), "anyone up for a raid at Stormfall?"),
        ("moderator", "captain", "Kestrel", ("headhunter",), "gg on the totem"),
        (None, "marshal", "Brickwright", ("bulwark", "founder"), "my totem is still standing after all that"),
        (None, "scout", "Ferngully", ("sanctuary",), "got my plot on Sanctuary!"),
        (None, "wanderer", "NewFarmer22", (), "hi, how do I claim land?")]
TAB = [("admin", "legend", "Mrgregles", ("season_champion", "founder")), (None, "warden", "PieFarmer", ("totem_reaver", "headhunter")),
       ("moderator", "captain", "Kestrel", ("headhunter",)), (None, "marshal", "Brickwright", ("bulwark", "founder")),
       ("staff", "vanguard", "Quillon", ("titan_slayer",)), (None, "settler", "Ottoline", ("founder",)),
       (None, "scout", "Ferngully", ("sanctuary",)), (None, "wanderer", "NewFarmer22", ())]

SUFFIX = {g: (t, MC[c], how) for g, t, c, _, how in ACHIEVEMENTS}


def pic(img, group, k=None, title=None):
    k_css = f"calc({img.w}px * var(--k))" if k is None else f"{img.w * k}px"
    h_css = "calc(9px * var(--k))" if k is None else f"{9 * k}px"
    t = f' title="{html.escape(title)}"' if title else ""
    return f'<img class="px" src="{data_uri(img)}" alt="{group}" style="width:{k_css};height:{h_css}"{t}>'


def name_line(imgs, staff, rung, name, ach):
    parts = []
    if staff:
        parts.append(pic(imgs[staff], staff))
    parts.append(pic(imgs[rung], rung))
    parts.append(f'<span class="nm">{name}</span>')
    for a in ach:
        text, _, how = SUFFIX[a]
        parts.append(pic(imgs[a], a, title=f"✦ {text}: {how}"))
    return "".join(parts)


def option(key, imgs):
    name, blurb = STYLES[key]
    chat = "".join(f'<div class="cl"><span class="br">&lt;</span>{name_line(imgs, s, r, n, a)}<span class="br">&gt;</span><span class="msg">{m}</span></div>'
                   for s, r, n, a, m in CHAT)
    tab = "".join(f'<div class="tr">{name_line(imgs, s, r, n, a)}</div>' for s, r, n, a in TAB)
    ladder = "".join(
        f'<div class="cell"><div class="art">{pic(imgs[g], g, 4)}</div><div class="cap"><b>{i + 1}. {n}</b><span>{h} h · {tier(i)[0].lower()} · {imgs[g].w} px</span></div></div>'
        for i, (g, n, _, h) in enumerate(LADDER))
    staff = "".join(f'<div class="cell"><div class="art">{pic(imgs[g], g, 4)}</div><div class="cap"><b>{g}</b><span>{imgs[g].w} px</span></div></div>' for g, *_ in STAFF)
    ach = "".join(
        f'<div class="cell ic"><div class="art">{pic(imgs[g], g, 6)}</div><div class="cap"><b>✦ {t}</b><span>{g} · {how}</span></div></div>'
        for g, t, _, _, how in ACHIEVEMENTS)
    return f"""
<section class="opt" id="opt-{key.lower()}">
  <header class="oh"><span class="tag">{"Chosen" if key == "BSP" else "Option " + key}</span><h2>{name}</h2><p>{blurb}</p></header>
  <div class="scene">
    <div class="chat">{chat}</div>
    <div class="tab"><div class="tabhead">BSP Spawn · 8 online</div><div class="tabgrid">{tab}</div></div>
  </div>
  <h3>The ladder, rung 1 to 12 <small>4× zoom</small></h3>
  <div class="grid">{ladder}</div>
  <h3>Staff <small>4× zoom</small></h3>
  <div class="grid">{staff}</div>
  <h3>Achievements <small>6× zoom; hover an icon in chat for its text</small></h3>
  <div class="grid">{ach}</div>
</section>"""


DECIDED = [
    ("D87 Style", "C's swallowtail banners for the ladder and staff ranks; B's full-colour icons, unchanged, on a grey disc."),
    ("D88 Two tags", "The staff tag, then the ladder tag, as the LuckPerms format says."),
    ("D89 Achievement icons", "Drawn after the name, as images when one is set. PREFIXES gets a SUFFIXES section; hovering shows the text and how it's earned."),
    ("D90 Every achievement shows", "Set in LuckPerms' meta-formatting; RankUp draws whatever LuckPerms formats."),
    ("D91 Colours and tiers", "LuckPerms' colours, tier metals copper, iron, gold, diamond; dark colours lifted."),
    ("D92 Files", "docs/prefix_images/, named after the groups, with commands.txt to assign them."),
    ("D93 No image for default", "LuckPerms' built-in group every player has; wanderer is the first rung and has its banner."),
]


def page(styles):
    opts = "".join(option(k, imgs) for k, imgs in styles.items())
    qs = "".join(f'<li><b>{q}</b><p>{d}</p></li>' for q, d in DECIDED)
    nav = "".join(f'<a href="#opt-{k.lower()}"><span>{k}</span>{STYLES[k][0]}</a>' for k in styles)
    return f"""<title>BSP Rank Prefix Art</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Silkscreen&family=VT323&family=Atkinson+Hyperlegible:wght@400;700&display=swap">
<style>
/* Layout: one column of three option panels, each a game scene (chat + tab list) over its sprite sheet; a single dark look, like the admin screen. */
:root {{
  --bg: #0d1217; --panel: #141b22; --sunk: #0a0e12; --line: #24313d; --fg: #dde6ec; --muted: #8696a4;
  --accent: #2fd3c0; --gold: #f2be2c;
  --display: "Silkscreen", "Courier New", monospace; --body: "Atkinson Hyperlegible", system-ui, sans-serif; --game: "VT323", "Courier New", monospace;
  --k: 3; color-scheme: dark;
}}
body {{ background: var(--bg); color: var(--fg); font-family: var(--body); font-size: 15px; line-height: 1.5; }}
.wrap {{ max-width: 1120px; margin: 0 auto; padding-block: 28px 64px; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); display: grid; gap: 28px; }}
h1, h2 {{ font-family: var(--display); font-weight: 400; letter-spacing: .02em; margin: 0; text-wrap: balance; }}
h1 {{ font-size: 30px; color: var(--accent); }}
h2 {{ font-size: 22px; }}
h3 {{ font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: var(--accent); margin: 8px 0 0; font-weight: 700; }}
h3 small {{ text-transform: none; letter-spacing: 0; color: var(--muted); font-weight: 400; margin-left: 8px; }}
.intro {{ display: grid; gap: 10px; max-width: 72ch; }}
.intro p {{ margin: 0; color: var(--muted); }}
.intro b {{ color: var(--fg); }}
.bar {{ display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }}
.bar a {{ display: inline-flex; gap: 8px; align-items: center; padding: 6px 12px; border: 1px solid var(--line); color: var(--fg); text-decoration: none; font-family: var(--display); font-size: 13px; }}
.bar a span {{ color: var(--accent); }}
.bar a:hover, .bar a:focus-visible {{ border-color: var(--accent); outline: none; }}
.scale {{ margin-left: auto; display: inline-flex; gap: 4px; align-items: center; color: var(--muted); font-size: 13px; }}
.scale button {{ font: inherit; font-family: var(--display); background: var(--panel); color: var(--fg); border: 1px solid var(--line); padding: 4px 10px; cursor: pointer; }}
.scale button[aria-pressed="true"] {{ border-color: var(--accent); color: var(--accent); }}
.scale button:focus-visible {{ outline: 2px solid var(--accent); outline-offset: 1px; }}
.opt {{ background: var(--panel); border: 1px solid var(--line); padding: 20px; display: grid; gap: 14px; min-width: 0; }}
.oh {{ display: grid; gap: 4px; }}
.oh p {{ margin: 0; color: var(--muted); max-width: 75ch; }}
.tag {{ font-family: var(--display); font-size: 12px; color: var(--gold); }}
.scene {{ display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; background: linear-gradient(180deg, #2b4a35 0%, #1f3527 55%, #3b2f22 100%); padding: 14px; overflow-x: auto; }}
.chat {{ background: rgba(0, 0, 0, .5); padding: calc(2px * var(--k)); flex: none; }}
.cl, .tr {{ display: flex; align-items: center; gap: calc(1px * var(--k)); font-family: var(--game); font-size: calc(9.5px * var(--k)); line-height: calc(9px * var(--k)); height: calc(10px * var(--k)); color: #fff; white-space: nowrap; text-shadow: calc(1px * var(--k) / 3 * 2) calc(1px * var(--k) / 3 * 2) #3f3f3f; }}
.br {{ color: #fff; }}
.nm {{ padding-right: calc(1px * var(--k)); }}
.msg {{ margin-left: calc(3px * var(--k)); }}
.tab {{ background: rgba(0, 0, 0, .55); padding: calc(2px * var(--k)); flex: none; }}
.tabhead {{ font-family: var(--game); font-size: calc(8px * var(--k)); color: #aaa; text-align: center; padding-bottom: calc(2px * var(--k)); }}
.tabgrid {{ display: grid; gap: calc(1px * var(--k)); }}
.tr {{ background: rgba(255, 255, 255, .08); padding: 0 calc(1px * var(--k)); }}
.px {{ image-rendering: pixelated; image-rendering: crisp-edges; display: block; flex: none; }}
.grid {{ display: flex; flex-wrap: wrap; gap: 10px; }}
.cell {{ background: var(--sunk); border: 1px solid var(--line); padding: 10px; display: grid; gap: 8px; min-width: 0; }}
.cell.ic {{ grid-template-columns: auto 1fr; align-items: center; max-width: 360px; }}
.art {{ display: flex; align-items: center; min-height: 36px; }}
.cap {{ display: grid; font-size: 13px; }}
.cap b {{ font-weight: 700; }}
.cap span {{ color: var(--muted); font-size: 12px; }}
.qs {{ background: var(--panel); border: 1px solid var(--line); padding: 20px; display: grid; gap: 12px; }}
.qs ol {{ margin: 0; padding-left: 0; list-style: none; display: grid; gap: 10px; }}
.qs li p {{ margin: 2px 0 0; color: var(--muted); max-width: 80ch; }}
.qs .def {{ color: var(--fg); }}
.note {{ color: var(--muted); font-size: 13px; margin: 0; }}
@media (max-width: 820px) {{ .scale {{ margin-left: 0; }} }}
</style>
<div class="wrap">
  <div class="intro">
    <h1>BSP Rank Prefix Art</h1>
    <p><b>Every image is 9 pixels tall</b>, the height RankUp draws a prefix at (one chat line), so it stays sharp at any GUI scale. Ladder and staff ranks are lettering; the seven achievement ranks are icons. The ranks and colours come from <b>BSP-Luckperms/bsp-ranks</b>.</p>
    <p>The chosen style comes first; A, B and C stay below for comparison. Chat and the tab list show the name as decided: staff tag, ladder tag, name, then every achievement held. Two tags and the icons after the name need a RankUp change (task PA-3); the art is ready now.</p>
  </div>
  <div class="bar">{nav}<a href="#questions"><span>✓</span>Decided</a>
    <span class="scale" role="group" aria-label="GUI scale">GUI scale
      <button type="button" id="k2" data-k="2" aria-pressed="false">2</button><button type="button" id="k3" data-k="3" aria-pressed="true">3</button><button type="button" id="k4" data-k="4" aria-pressed="false">4</button>
    </span>
  </div>
  {opts}
  <section class="qs" id="questions">
    <h2>Decided</h2>
    <ol>{qs}</ol>
    <p class="note">Generated by tools/prefix_art/gen_prefix_art.py. The lettering is RankUp's own pixel font; the chat text in this page only stands in for Minecraft's.</p>
  </section>
</div>
<script>
document.querySelectorAll(".scale button").forEach(b => b.addEventListener("click", () => {{
  document.documentElement.style.setProperty("--k", b.dataset.k);
  document.querySelectorAll(".scale button").forEach(o => o.setAttribute("aria-pressed", String(o === b)));
}}));
</script>
"""
