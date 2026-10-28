# BSP RankUp

A Forge mod for Minecraft 1.20.1 and the Build Secure Protect (BSP) modpack: automatic LuckPerms ranks, raised by what players do on the server. Players earn points for time played, players defeated, Shatter Totems stolen and defended, and more; when their points pass a rung of the ladder they are moved onto that LuckPerms group, told, rewarded and announced. Everything about the ladder is set up in game.

Requires [BSP Core](https://github.com/Build-Secure-Protect/BSP-Core) 1.4.5 or newer and LuckPerms on the server (the Bukkit plugin on Mohist or the Forge mod; without it ranks are only recorded). [BSP Region Guard](https://github.com/Build-Secure-Protect/BSP-Region-Guard) and BSP Teleport are optional.

## How it works

- **Signals** are what RankUp counts: minutes played, player kills, mob kills, deaths, totems stolen, protected, lost and placed, seasons survived, and (when BSP Core offers them) season score, pieces earned and claims bought. Each is on or off and worth a number of points per so many units.
- **The ladder** is a LuckPerms track: each rung is a group, a display name, the points it needs, whether it resets each season, and what it gives (Shatter Coins, pieces into the bank, items, a title shown as a LuckPerms prefix, console commands). RankUp shows each player's LuckPerms prefix before their name and suffix after it in chat and the tab list, as LuckPerms formats them (a staff tag and a ladder tag, every achievement), each piece as an image when an admin gave it one, with a hover note such as how it's earned. Reaching a rung's points moves the player onto its group through LuckPerms. Ranks are never taken away by falling points; an admin can reset a player.
- **Seasons** (BSP Core's): rungs marked seasonal fall back at a season's end to the highest permanent rung below, and are then judged on points earned since; the title goes back to that rung's. Permanent rungs stay.
- **Ranks in other mods**: a rank-up takes the player off the rung below (a LuckPerms track holds one group at a time), and BSP Region Guard's regions and BSP Core's kits check the exact group. So a region for "Warden and up" lists every rung from Warden to the top, on each ladder that should count.
- **A network**: with `storage.mode = "mysql"` every count is written through to shared tables as it happens, so playtime on several servers adds up, a player's row is read when they log in, the settings are shared, and announcements can reach every server (through Redis when enabled).

## Setting it up

1. In LuckPerms, make a track and its groups (`/lp createtrack rankup`, `/lp creategroup warden`, `/lp track rankup append warden` ...), or let RankUp make them from its ladder (OPTIONS, MAKE TRACK IN LUCKPERMS). Give the groups rising weights so LuckPerms reports the highest as the primary group.
2. As a BSP Core admin, `/rankup admin` opens the admin screen: add rungs on LADDER (or IMPORT from the track), set each rung's points and rewards, switch signals and their worth on SIGNALS, pick announcements and the rest on OPTIONS. Everything saves at once. The same can be done with `/rankup` commands.
3. Players open their rank screen with `/rank`, the U key, or the R button in their inventory.
4. For several servers, on each backend: set `storage.mode = "mysql"` (and `storage.redis.enabled = true` with Redis) in `serverconfig/bsp_rankup-server.toml`, and the database and Redis hosts, users and passwords in `config/bsp_rankup-network.toml`. The passwords never go in the server config: Forge sends that file to every player who joins. A value left there by an older RankUp is moved to the network file at the next start. Never ship `config/bsp_rankup-network.toml` in a client pack.

## Commands

`/rank` (your screen), `/rank text` (the same as chat lines), `/rank <player>` (admins). `/rankup admin|info|reload`, `set|add|reset <player> ...`, `ladder add|remove|list|points|name|seasonal|move|import` (rungs; add, list and import on the first ladder), `ladders` and `ladders add|remove|rename|track|counts|titlepriority|rungs|addrung|import <ladder>` (several ladders, each on its own track counting its own signals), `award` and `award add|remove|points|name|counts|seasonal` (LuckPerms groups on no ladder, given when their points are reached), `award give|take <player> <award>` (by hand, without rewards; a taken award is not given again until given back), `reward add|list|remove|clear` (of any rung or award), `signal ...`, `option track|announce|cooldown|titlepriority|rewardsagain|showprefixes`, `prefix list|files`, `prefix image title|group|suffix <group> <file>`, `prefix clear title|group|suffix <group>` (images in place of prefixes and suffixes, from PNGs in `config/bsp_rankup/prefix_images/`), `prefix note title|group|suffix <group> [text]` (what hovering it shows), `export|import <name>` (files in `config/bsp_rankup/`).

## For other mods

`docs/API.md`: the ladder and a player's standing, points from other mods, a team hook (kills between teammates count for nothing once a teams mod registers it), and `RankChangedEvent`.

## Build

BSP Core must be built first (its jar is read from `../BSP-Core/build/libs`). Then:

```
./gradlew build
```

The jar lands in `build/libs/`. `./gradlew runGameTestServer` runs the headless tests; the build checks the screen text against the language file (`tools/check_lang.py`), which `tools/gen_lang.py` writes.

## Docs

- `docs/API.md`: what other mods may call.
- `docs/TESTING.md`: the in-game checklist, one section per feature.
- `CHANGELOG.md`.

MIT licensed.
