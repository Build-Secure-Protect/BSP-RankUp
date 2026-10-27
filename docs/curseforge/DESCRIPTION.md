![BSP RankUp](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/banner.png)

**BSP RankUp** ranks your players automatically. The more they play, fight and raid, the higher they climb ladders of LuckPerms ranks, and every rung pays out: coins, pieces into the bank, items, kits, a title, anything a command can give. Their rank shows before their name in chat and the tab list, as a picture if you give it one. Made for the Build Secure Protect server, where Shatter Totems are stolen and defended; works on any Forge 1.20.1 server (or Mohist) with BSP-Core and LuckPerms.

**Requires:** Minecraft 1.20.1, Forge 47 or newer, Java 17, [BSP-Core](https://www.curseforge.com/minecraft/mc-mods/bsp-core) 1.4.5 or newer, and LuckPerms on the server (the Forge mod, or the Bukkit plugin on Mohist). Needed on the client and the server, in the same version. **Optional:** BSP-Region Guard (0.6.0 needs BSP-Core 1.4.6 or newer), BSP-Teleport.

## Ranks you can see

![Prefix images in chat and the tab list](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/prefix_chat.png)

RankUp puts every player's LuckPerms prefix in front of their name in chat and the tab list, in its colours. Give a prefix an image (a PNG up to 256 x 64 pixels in the server's `config/bsp_rankup/prefix_images/` folder) and the picture is drawn instead, one line of text high. It works for RankUp's own titles and for any LuckPerms group's prefix, like Admin or Mod. Hover an image to read it. Players get the images when they join; there is nothing to install, and on a network the image only needs to be on one server.

## How it works

![From what players do to a rank](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/rankup_flow.png)

1. **Signals** are what RankUp counts: minutes online, players defeated, mobs killed, Shatter Totems stolen from others, totems defended when someone tried to steal yours, BSP-Core's season score, claims bought, and more. Each is on or off and worth a number of points per so many units.
2. **Points** add up. Permanent rungs look at lifetime points; seasonal rungs at the points earned since the last season ended.
3. **Ladders** are LuckPerms tracks: each rung is a group, a display name, the points it needs, and what it gives. Run one ladder, or several that each count their own signals (a Playtime ladder for minutes played, a Raider ladder for kills and totems); players climb each one separately.
4. **Awards** are single LuckPerms groups given once their points are reached ("Apple Picker" at 5000 pieces earned), with their own rewards.
5. **Rank up:** when your points pass a rung, you are moved onto its group, told what you got, and everyone hears about it. Ranks are never taken away because points fell.

## For players: your rank screen

![The player's rank screen](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/player_screen.png)

Press **U**, type **/rank**, or click the **R** button in your inventory. A chip for each ladder sits at the top, and **AWARDS** lists every award with your progress toward it. The chosen ladder stands on the left with your rung in gold and the ones below lit; hover a rung to see what it gives. On the right: your rank and that ladder's title (as its image when it has one), your points (lifetime and this season), the bar to the next rung, what it gives, the fastest ways to get there, and every signal that ladder counts with its count, worth, share and points. It refreshes every second while open.

![The button in the inventory](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/inventory_button.png)

When you reach a rung you see it in chat, with each reward on its own line, and the server sees the announcement:

![Chat on a rank-up](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/chat.png)

Rewards earned while you were away (an admin gave you points, or you ranked up on another server) wait for your next login.

## For admins: setting up the ladder

Make the track and its groups in LuckPerms, or let RankUp make them from its ladder with one button. Then, as a BSP-Core admin, type **/rankup admin**.

![The LADDER tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_ladder.png)

**LADDER:** the ladder chips at the top choose a ladder (**+ LADDER** adds one); under them its LuckPerms track, the signals it counts and the priority its titles use. The rungs sit on the left in track order. Add one with its group, points and name (or IMPORT the groups of the LuckPerms track), reorder with UP and DOWN. The chosen rung's display name and points edit in place; **keep** or **reset** says whether a season's end takes it away. Its rewards sit below: **+ COINS** (Shatter Coins into the hand), **+ PIECES** (into the bank), **+ ITEM**, **+ TITLE** (a LuckPerms prefix, colour codes welcome; its image shows in a small slot beside it), **+ COMMAND** (run by the console, `%player%` is the player), **+ KIT** (a BSP-Core kit, delivered when the inventory has room), and **FROM HAND** for whatever you hold. Any number of rewards per rung.

![The item browser](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/item_browser.png)

**+ ITEM** opens a browser over every item in the game, including every other mod's: type to search by name or id, click one, type the count.

![Picking a kit as a reward](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/kit_picker.png)

**+ KIT** shows the kits made in BSP-Core's admin panel; one click makes the kit the rung's reward. BSP-Core owes the kit to the player and hands it over when their inventory has room, at once or at their next login, so a full inventory never loses it.

![The GROUPS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_groups.png)

**GROUPS:** every group in LuckPerms, sorted into rungs (with their ladder), awards, and the rest. Make any group an award: give it the points it needs, the signals that count toward it, whether it resets each season, and its rewards. **STOP AWARDING** keeps the group on players who already have it.

![The PREFIXES tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_prefixes.png)

**PREFIXES:** every prefix RankUp can show, by where it comes from: each ladder's titles, the awards' titles, and the LuckPerms groups' own prefixes. Pick one, pick a file from the folder (each shown as it will look, with any problem in red), **USE IMAGE**. **SHOW TEXT AGAIN** brings the text back. **REFRESH** reads the folder and LuckPerms again. The image stays even if the file is later deleted.

![The SIGNALS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_signals.png)

**SIGNALS:** what counts and how much. Switch each on or off and set its worth ("25 points per 1 kill", "1 point per 10 minutes"). Counts are kept even while a signal is off, so switching one on later credits what players already did.

![The PLAYERS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_players.png)

**PLAYERS:** find anyone, online or not; see their rank, points, this season's points and every count; **SET RANK** (no rewards), **ADD POINTS** (a negative number takes), **RESET**.

![The OPTIONS tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_options.png)

**OPTIONS:** the LuckPerms track, where rank-ups are announced (off, this server, the whole network), the kill cooldown (a repeat kill of the same player counts again only after it), the prefix priority titles use, whether seasonal rungs reward again each season or once, **PREFIXES IN CHAT** on or off, **MAKE TRACK IN LUCKPERMS** (creates the track and any missing groups from the ladder, with rising weights), **EXPORT** and **IMPORT** of the whole setup as a file, the state of LuckPerms and the storage, and **RESET EVERYONE** behind a code typed back within a minute.

Every change saves at once and reaches every server. Buttons that remove things want a second click. Everything the screen does, `/rankup` can do from chat or the console too (`/rankup ladder add warden 1500 Warden`, `/rankup reward add warden item minecraft:netherite_ingot 2`, `/rankup signal player_kills points 25 per 1`, `/rankup ladders add raider raider`, `/rankup award add apple_picker 5000`, `/rankup prefix image title warden warden.png` ...).

## Seasons

A rung marked **reset** is seasonal. When BSP-Core ends a season, players on a seasonal rung fall back to the highest permanent rung below it (and to that rung's title), keep their lifetime points, and from then on seasonal rungs count only the points earned since, so the climb starts afresh every season while permanent ranks stay forever. Awards marked **reset** are taken back and can be earned again. Players online are handled at once, everyone else on their next login. Season winners are BSP-Core's business.

## Anti-farming

A kill of the same player within the cooldown counts nothing; killing yourself is a death, not a kill; a steal of an unclaimed totem is still a steal, but a totem is "protected" only when its owner was online on that server when the steal failed. Once a teams mod registers RankUp's team hook, kills between teammates count for nothing.

## A network of servers

![Several servers, one ladder](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/network.png)

With `storage.mode = "mysql"` in `serverconfig/bsp_rankup-server.toml` on each backend, every count is written through to RankUp's own tables as it happens, so playtime on several servers adds up; a player's progress is read when they log in anywhere, before anything is decided for them; the ladders, awards, settings and prefix images are shared; and with Redis enabled, changes and network-wide announcements reach every server at once (the table carries them when Redis is down). LuckPerms' own shared database carries the ranks. No database driver is bundled: RankUp uses BSP-Core's. That config file holds nothing else; the ladder lives in the shared storage and is edited in game.

## With the other BSP mods

![What the companions add](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/companions.png)

- **BSP-Core (required, 1.4.5 or newer):** its totem events are the raiding signals (a successful steal is a totem stolen for the thief and lost for the owner; a failed one while the owner is online is a totem protected; totems placed), its season score is a signal, its bank and coins pay the rewards, its kits can be rewards, its teams stop teammates' kills counting, its admin list is the admin list, and its season ends drive the seasonal rungs.
- **BSP-Region Guard (optional):** buying a plot at the Land Office counts a claim bought; Region Guard's rank-gated regions and mines open for the ranks and awards RankUp hands out, since they are the same LuckPerms groups. A rank-up moves a player off the rung below, so a region for "Warden and up" lists every rung from Warden to the top. On a network RankUp takes Region Guard's server name.
- **BSP-Teleport (optional):** no link is needed; both run side by side.
- **LuckPerms:** RankUp moves players along each ladder's track, adds award groups, sets titles, and shows prefixes before names; it can create a track and its groups from a ladder. Give the groups rising weights and LuckPerms reports the highest as the primary group.

## For other mods

A small API (`docs/API.md` in the repository): the ladders and awards, a player's rank on each and their standing, `hasRank` and `hasAward` for gates ("Warden or better"), points from other mods, the team hook, and a `RankChangedEvent` on the Forge bus after every rank change, naming the ladder.

## Links

Source and docs: https://github.com/Build-Secure-Protect/BSP-RankUp. Issues and ideas welcome there. MIT licensed.
