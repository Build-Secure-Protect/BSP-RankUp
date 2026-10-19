![BSP RankUp](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/banner.png)

**BSP RankUp** ranks your players automatically. The more they play, fight and raid, the higher they climb a ladder of LuckPerms ranks, and every rung pays out: coins, pieces into the bank, items, a title, anything a command can give. Made for the Build Secure Protect server, where Shatter Totems are stolen and defended; works on any Forge 1.20.1 or Mohist server with BSP-Core and LuckPerms.

**Requires:** Minecraft 1.20.1, Forge 47 or newer, Java 17, [BSP-Core](https://www.curseforge.com/minecraft/mc-mods/bsp-core) 1.1.0 or newer (1.3.0 for kit rewards), and LuckPerms on the server (the Bukkit plugin on Mohist, or the Forge mod). Needed on the client and the server. **Optional:** BSP-Region Guard, BSP-Teleport.

## How it works

![From what players do to a rank](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/rankup_flow.png)

1. **Signals** are what RankUp counts: minutes online, players defeated, mobs killed, Shatter Totems stolen from others, totems defended when someone tried to steal yours, claims bought, and more. Each is on or off and worth a number of points per so many units.
2. **Points** add up. Permanent rungs look at lifetime points; seasonal rungs at the points earned since the last season ended.
3. **The ladder** is a LuckPerms track: each rung is a group, a display name, the points it needs, and what it gives.
4. **Rank up:** when your points pass a rung, you are moved onto its group, told what you got, and everyone hears about it. Ranks are never taken away because points fell.

## For players: your rank screen

![The player's rank screen](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/player_screen.png)

Press **U**, type **/rank**, or click the **R** button in your inventory. The ladder stands on the left with your rung in gold and the ones below lit; hover a rung to see what it gives. On the right: your rank and title, your points (lifetime and this season), the bar to the next rung, what it gives, the fastest ways to get there, and every signal that counted with its count, worth, share and points. It refreshes every second while open.

![The button in the inventory](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/inventory_button.png)

When you reach a rung you see it in chat, with each reward on its own line, and the server sees the announcement:

![Chat on a rank-up](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/chat.png)

Rewards earned while you were away (an admin gave you points, or you ranked up on another server) wait for your next login.

## For admins: setting up the ladder

Make the track and its groups in LuckPerms, or let RankUp make them from its ladder with one button. Then, as a BSP-Core admin, type **/rankup admin**.

![The LADDER tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_ladder.png)

**LADDER:** the rungs on the left in track order. Add one with its group, points and name (or IMPORT the groups of the LuckPerms track), reorder with UP and DOWN. The chosen rung's display name and points edit in place; **keep** or **reset** says whether a season's end takes it away. Its rewards sit below: **+ COINS** (Shatter Coins into the hand), **+ PIECES** (into the bank), **+ ITEM**, **+ TITLE** (a LuckPerms prefix, colour codes welcome), **+ COMMAND** (run by the console, `%player%` is the player), **+ KIT** (a BSP-Core kit, delivered when the inventory has room), and **FROM HAND** for whatever you hold. Any number of rewards per rung.

![The item browser](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/item_browser.png)

**+ ITEM** opens a browser over every item in the game, including every other mod's: type to search by name or id, click one, type the count.

![Picking a kit as a reward](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/kit_picker.png)

**+ KIT** shows the kits made in BSP-Core's admin panel; one click makes the kit the rung's reward. BSP-Core owes the kit to the player and hands it over when their inventory has room, at once or at their next login, so a full inventory never loses it.

![The SIGNALS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_signals.png)

**SIGNALS:** what counts and how much. Switch each on or off and set its worth ("25 points per 1 kill", "1 point per 10 minutes"). Counts are kept even while a signal is off, so switching one on later credits what players already did.

![The PLAYERS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_players.png)

**PLAYERS:** find anyone, online or not; see their rank, points, this season's points and every count; **SET RANK** (no rewards), **ADD POINTS** (a negative number takes), **RESET**.

![The OPTIONS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_options.png)

**OPTIONS:** the LuckPerms track, where rank-ups are announced (off, this server, the whole network), the kill cooldown (a repeat kill of the same player counts again only after it), the prefix priority titles use, whether seasonal rungs reward again each season or once, **MAKE TRACK IN LUCKPERMS** (creates the track and any missing groups from the ladder, with rising weights), **EXPORT** and **IMPORT** of the whole setup as a file, the state of LuckPerms and the storage, and **RESET EVERYONE** behind a code typed back within a minute.

Every change saves at once and reaches every server. Buttons that remove things want a second click. Everything the screen does, `/rankup` can do from chat or the console too (`/rankup ladder add warden 1500 Warden`, `/rankup reward add warden item minecraft:netherite_ingot 2`, `/rankup signal player_kills points 25 per 1` ...).

## Seasons

A rung marked **reset** is seasonal. When BSP-Core ends a season, players on a seasonal rung fall back to the highest permanent rung below it, keep their lifetime points, and from then on seasonal rungs count only the points earned since, so the climb starts afresh every season while permanent ranks stay forever. Players online are handled at once, everyone else on their next login. Season winners are BSP-Core's business.

## Anti-farming

A kill of the same player within the cooldown counts nothing; killing yourself is a death, not a kill; a steal of an unclaimed totem is still a steal, but a totem is "protected" only when its owner was online on that server when the steal failed. Once a teams mod registers RankUp's team hook, kills between teammates count for nothing.

## A network of servers

![Several servers, one ladder](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/network.png)

With `storage.mode = "mysql"` in `serverconfig/bsp_rankup-server.toml` on each backend, every count is written through to RankUp's own tables as it happens, so playtime on several servers adds up; a player's row is read when they log in anywhere; the ladder and its settings are shared; and with Redis enabled, settings changes and network-wide announcements reach every server at once (the table carries them when Redis is down). LuckPerms' own shared database carries the ranks. No database driver is bundled: BSP-Core's full jar or Mohist provides one. That config file holds nothing else; the ladder lives in the shared storage and is edited in game.

## With the other BSP mods

![What the companions add](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/companions.png)

- **BSP-Core (required):** its totem events are the raiding signals (a successful steal is a totem stolen for the thief and lost for the owner; a failed one while the owner is online is a totem protected; totems placed), its bank and coins pay the rewards, its kits can be rewards (BSP-Core 1.3.0), its admin list is the admin list, and its season ends drive the seasonal rungs.
- **BSP-Region Guard (optional):** buying a plot at the Land Office counts a claim bought; Region Guard's rank-gated regions and mines open for the ranks RankUp hands out, since they are the same LuckPerms groups; on a network RankUp takes Region Guard's server name.
- **BSP-Teleport (optional):** no link is needed; both run side by side.
- **LuckPerms:** RankUp promotes along a track and sets prefix titles; it can create the track and its groups from the ladder. Give the groups rising weights and LuckPerms reports the highest as the primary group.

## For other mods

A small API (`docs/API.md` in the repository): the ladder, a player's rank and standing, `hasRank` for gates ("Warden or better"), points from other mods, the team hook, and a `RankChangedEvent` on the Forge bus after every rank change.

## Links

Source and docs: https://github.com/Build-Secure-Protect/BSP-RankUp. Issues and ideas welcome there. MIT licensed.
