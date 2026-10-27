# Testing BSP RankUp in game

What to try after each build. Tick what works, note what does not. Claude cannot run the client, so this list is the hand-off between a build and the owner. Headless checks run by Claude: `./gradlew runGameTestServer` (25 tests: the LuckPerms bridge, every signal, the ladder engine, the shared storage, the commands, the api, seasons, both screens' server sides).

## Setup for a first pass

A server (Forge, or the Mohist test server from Region Guard's `tools/setup_test_server.sh`) with BSP Core 1.1.0, this jar, and LuckPerms. You as a BSP Core admin (`admin.admins` in BSP Core's config, or an operator with that list empty). A second account helps for kills, steals and `/rankup set`.

1. `/rankup info`: the mod is up, LuckPerms is reached.
2. `/rankup admin`: build a three-rung ladder (one rung at 0, one at 50, one at 100 marked seasonal) with a few rewards; MAKE TRACK IN LUCKPERMS.
3. `/rank`: the player screen shows the ladder.
4. Then the sections below in order: RU-3 signals, RU-4 ladder and rewards, RU-6 commands, RU-8 seasons, RU-9 and RU-10 screens, RU-5 on the network.

## Skeleton (build 0.1.0, 2026-10-17)

- [ ] The server log on start shows `BSP RankUp loaded (skeleton; no ranking rules yet)` after BSP Core's own start line.
- [ ] The mod list in the client shows "BSP RankUp".

## RU-1: LuckPerms bridge (2026-10-17)

Headless: `./gradlew runGameTestServer` runs the bridge against the real LuckPerms Forge mod (promote, demote, set group, prefixes, track creation). Nothing in game uses the bridge yet; the only live check is the connection to the Bukkit plugin on a Mohist backend.

- [ ] On a Mohist backend with the LuckPerms Bukkit plugin, the server log shows `LuckPerms reached through the Bukkit edition` once BSP RankUp has asked for it (nothing asks yet; this line appears from RU-3 onward when the first player logs in).
- [ ] The backend's `luckperms.conf` has `primary-group-calculation`: with `parents-by-weight` (the default) the ladder's groups need rising weights for a player's primary group to follow their rank; `ensureTrack` sets them on groups it creates, existing groups keep theirs.

## RU-3: progress ledger and signals (2026-10-17)

Headless: six GameTests cover a minute of playtime, kills with the ten-minute per-victim cooldown, the team hook, mob kills, deaths, the totem events and the ledger's save, load and reset. Nothing shows in game yet (no command or screen until RU-6 and RU-9), so the in-game checks wait; the counts live in `world/data/bsp_rankup_progress.dat`.

- [ ] The server log on start shows `BSP RankUp loaded` after BSP Core's own lines.
- [ ] (From RU-6 on) `/rank` after a minute online shows playtime 1; after a PvP kill shows player kills 1; a second kill of the same player within ten minutes does not raise it; a steal that succeeds raises the thief's totems stolen and the owner's totems lost; a steal that fails while the owner is on the same server raises the owner's totems protected.

## RU-4: settings and the ladder engine (2026-10-17)

Headless: five GameTests cover the points arithmetic, the settings' save and load, a promotion through the real LuckPerms with every reward kind (coins, pieces, a stick, a title, a scoreboard command) and the announcement, a jump over two rungs, a rank that stays when points are low, and rewards kept for a player who is away. In game nothing can be set up yet (the admin screen and commands come in RU-6 and RU-9), so these checks wait for them:

- [ ] (From RU-6 on) With a ladder set up and a track in LuckPerms: a player whose points pass a rung gets `You are now <rank>!`, the reward lines, and everyone sees `<name> reached the rank <rank>!`; `/lp user <name> info` shows the new group and, for a title, the prefix.
- [ ] A player given points by an admin while offline gets `Rewards you earned while away:` and the items on their next login.

## RU-6: commands (2026-10-17)

Headless: two GameTests run every `/rankup` subcommand and `/rank` through the dispatcher. In game, as a BSP Core admin (or from the console), with LuckPerms running and a track whose groups exist (`/lp createtrack rankup`, `/lp track rankup append <group>` for each rung, or let `/rankup ladder import` read an existing track):

- [ ] `/rankup info` shows the track, 0 rungs, and `LuckPerms: Bukkit plugin` on a Mohist backend (`Forge edition` on a Forge server).
- [ ] `/rankup ladder import` makes a rung per track group at 0 points; `/rankup ladder points <group> <n>` sets each threshold; `/rankup ladder list` shows them in order.
- [ ] `/rankup reward add <group> coins 50`, `... pieces 100`, `... item minecraft:diamond 2`, `... item hand` (holding something), `... title &b[Hero] `, `... command give %player% minecraft:apple`; `/rankup reward list <group>` shows them numbered.
- [ ] `/rank` shows "no rank yet" with 0 points before anything happens; after relogging you land on the first rung silently (if it is at 0 points).
- [ ] `/rankup add <you> <points past the next rung>`: you see `You are now <rank>!` and the reward lines, everyone sees the announcement, the coins, items and pieces arrive, `/lp user <you> info` shows the group (and the prefix for a title); `/rank` shows the new rank and the next one.
- [ ] `/rankup set <other> <group>` moves another player without rewards; `/rankup reset <other>` puts them back on the first rung with 0 points.
- [ ] `/rankup export mine` writes `config/bsp_rankup/mine.json`; edit a threshold in the file; `/rankup import mine` applies it and `/rankup ladder list` shows the change.
- [ ] Tab completion offers rung groups, signal keys and online names.

## RU-5: shared storage (2026-10-18)

Headless: three GameTests on an in-memory backend and a fake Redis (write-through as deltas, a row read on login, shared settings and their notice, notices through Redis and through the table during an outage). The MySQL and Redis connections themselves need the network: two backends, the shared database, Redis on the main machine (Region Guard's `docs/NETWORK.md` describes the layout).

- [ ] With `storage.mode = "mysql"` in `serverconfig/bsp_rankup-server.toml` on each backend (and `redis.enabled = true` if Redis runs), the start log shows `RankUp storage connected: mysql ... (this server is '<name>')` and, with Redis, `Redis connected`. `/rankup info` shows the same.
- [ ] Play 2 minutes on server A and 1 minute on server B: `/rank` on either shows 3 minutes.
- [ ] Set a rung's points on server A with `/rankup ladder points ...`: within a few seconds `/rankup ladder list` on server B shows it (at once with Redis, within `pollSeconds` without).
- [ ] With `/rankup option announce network`, a rank-up on server A is announced on server B too.
- [ ] `/rankup reset <player>` on server A while the player is on server B: their `/rank` on B shows the fresh start within a few seconds.
- [ ] Stop Redis: the log says it is retrying and the checks above still pass, just slower; start Redis again: `Redis connected again`.

## RU-7: api (2026-10-18)

Headless only: two GameTests cover every call and the event. Nothing to check in game until another mod calls it; `docs/API.md` is the contract.

## RU-8: seasons (2026-10-18)

Headless: two GameTests (a fall from the seasonal top to the highest permanent rung, the base and the seasons-survived count, climbing back with this season's points, rewards again or once, a player away handled on login; a ladder where every rung is seasonal).

- [ ] Mark the top rung seasonal (`/rankup ladder seasonal <group> true`), reach it, then end the season in BSP Core (`/bsp admin`, or its season command): you see `Season n is over: you are <rank> again. Seasonal ranks start afresh.`, `/lp user <you> info` shows the lower group, `/rank` shows `this season: 0 points` and the lifetime points unchanged.
- [ ] Earn the seasonal rung's points again: you rank up again and (with `rewardsagain` on) get its rewards again.
- [ ] A player who was offline when the season ended sees the same message on their next login.

## RU-9: admin screen (2026-10-18)

Headless: one GameTest applies every action the screen can send and checks the view and the packets. The screen's drawing, clicks and the item browser are untested until this pass. As a BSP Core admin:

- [ ] `/rankup admin` opens the screen, filling the window with a margin, turquoise border, RANKUP ADMIN top left, the status line top right, four tabs. A player who is not an admin gets a refusal in the action bar.
- [ ] LADDER: + ADD RANK, type `warden 1500 Warden`, Enter: the rung appears, selected, with its fields; click DISPLAY NAME or POINTS to edit (Enter applies, Esc cancels); keep/reset flips the seasonal flag; UP and DOWN reorder; IMPORT makes rungs from the LuckPerms track.
- [ ] Rewards: + COINS, + PIECES, + TITLE, + COMMAND open the input with a hint; + ITEM opens the item browser: type to search, click an item, type the count, Enter; FROM HAND adds the held stack; the x removes a row. The item's icon shows on its row.
- [ ] REMOVE RANK turns to CLICK AGAIN for five seconds and removes on the second click.
- [ ] SIGNALS: the on/off chip flips at once; clicking the worth box opens the input (`points per`, e.g. `25 1`).
- [ ] PLAYERS: typing in the search box filters within a second; online players show a turquoise dot; clicking one shows their counts; SET RANK shows the rungs as chips; ADD POINTS takes a number (negative takes); RESET wants a second click.
- [ ] OPTIONS: the track and the numbers edit through the input; announce and seasonal rewards are chips; MAKE TRACK IN LUCKPERMS (second click) creates the track and groups and the answer line says so; EXPORT and IMPORT take a file name; the info box shows LuckPerms, the track's groups, storage and the player count; RESET EVERYONE shows a code and the seconds left, the input takes it back, CANCEL or Esc drops it.
- [ ] The answer line at the bottom shows every reply, turquoise for success and red for a refusal; the screen redraws after each.
- [ ] `config/bsp_rankup-client.toml` `ui.scale = 2` draws it at a fixed size instead.

## RU-10: player screen (2026-10-18)

Headless: two GameTests cover the view the server builds and the fastest-way rows. The screen, the key and the inventory button are untested until this pass. As any player:

- [ ] `/rank` opens YOUR RANK filling the window: the ladder on the left (your rung in gold, the ones below lit turquoise, seasonal ones with an S and a violet node, holder counts), your rank large on the right with the title chip when you have one, the points line, the bar to the next rung with what it gives and the "Fastest:" line, and the WHAT COUNTS table.
- [ ] The key U (Controls, BSP RankUp) opens it in game; the inventory shows a small R button at the right of the crafting area that opens it; `/rank text` prints the chat lines.
- [ ] Hover a rung: the bottom line shows what it gives. Scroll the ladder when it has more than six rungs.
- [ ] Earn points while it is open (a kill, a minute): the numbers move within a second.
- [ ] After a season ended, the points line shows "this season" and the bar to a seasonal rung counts this season's points.
- [ ] With Curios or a backpack mod installed, the R button does not sit on theirs; if it does, move it in `config/bsp_rankup-client.toml`.

## RG-R1: claims bought (2026-10-18)

Headless: one GameTest on the plain-data path (Region Guard is not in the dev run). In game, with BSP Region Guard installed and the signal switched on (SIGNALS tab, or `/rankup signal claims_bought on`):

- [ ] Buy a plot at the Land Office: `/rank` shows "claims bought 1" and its points; enlarge it: the count stays.
- [ ] Without Region Guard installed the signal stays at 0 and the server log shows nothing about it.

## K-1: kit rewards (2026-10-19)

Headless: one GameTest (the kit kind round trip, an unknown kit refused, a kit made in BSP Core given on a rank-up). In game, with BSP Core 1.3.0 and a kit made in its admin panel's KITS tab:

- [ ] `/rankup admin`, LADDER, + KIT: the kits show as chips by name; click one: the reward row says "the kit <name>".
- [ ] Reach the rung: the chat says `+ the kit <name> (it comes when your inventory has room)`; with room it lands at once, with a full inventory BSP Core's WAITING card holds it.
- [ ] `/rankup reward add <group> kit <id>` tab-completes the kit ids; an unknown id is refused.

## MySQL driver lookup (2026-10-21)

Headless: one GameTest. In the dev run (BSP Core's full jar) the driver found is BSP Core's relocated one, and a connection to a closed port fails inside it. A real database has not been reached yet.

- [ ] On a plain Forge backend with BSP Core's **full** jar and `storage.mode = "mysql"`, the start log shows `MySQL driver: com.mrgregles.bsp_core.shaded.mysql.cj.jdbc.Driver`, then `RankUp storage connected: mysql ...`.
- [ ] On a Mohist backend (BSP Core's slim jar), the log shows `MySQL driver: com.mysql.cj.jdbc.Driver` and the storage connects.
- [ ] If neither shows and the log says `No MySQL driver found`, note which BSP Core jar and server type it was.

## UI and performance pass (2026-10-23)

Headless: seven GameTests (`PerformanceGameTests`: the screen scale at small and big windows, the refresh throttle, the database fail-fast, the ledger visitor, the cooldown pruning, over-long lines still encoding, a name looked up off the server thread), 35 in all. Nothing below has been seen in a client.

Window size:
- [ ] At 1920 x 1080 with GUI scale **Auto** (or 4), open `/rank` and `/rankup admin`: the whole frame shows, nothing cut off at any edge; the text is a little smaller than at GUI scale 3.
- [ ] At GUI scale 2 on the same window: both screens still fill the window as before.
- [ ] Resize the window smaller and larger with a screen open: it re-fits each time.

Rank screen (`/rank`):
- [ ] A seasonal rung with a threshold of 1000000 or more: the season mark sits just left of the number and does not touch it; a long rung name is cut with no overlap.
- [ ] A long title (`/rankup reward add <group> title &b[A Very Long Title For Testing]`, reach it): the title chip ends inside the frame.
- [ ] With rewards waiting and a big total, the "rewards wait" text stops before the total.
- [ ] With the screen open, the server log and network are quiet: one refresh a second, also at a high frame rate (unlock the frame rate in Video Settings).
- [ ] With twelve rungs and a player on the first one: the screen opens showing their gold rung and the next one, without scrolling.
- [ ] The hint line at the bottom ("Hover a rung...") sits below the ladder box, not across its border.
- [ ] A title made of a long `&#RRGGBB` gradient (over 64 characters): the screen still opens, the chip shows the letters without colour-code junk.

Inventory button:
- [ ] Open the inventory, toggle the recipe book on and off: the R button moves with the inventory each time.

Admin screen (`/rankup admin`):
- [ ] LADDER, + ITEM: with the browser open, click items in its bottom rows on the right (where REMOVE RANK sits underneath) twice: the item is picked and the rank is **not** removed. Click items in its top row (over the display name field): no rename box opens.
- [ ] PLAYERS: the search box is under the tabs; LADDER, SIGNALS and PLAYERS tabs all switch with one click.
- [ ] PLAYERS, pick a player, SET RANK: all twelve BSP ranks show (two rows) and each one sets the rank; with more than fit, a "+N more: /rankup set" line shows.
- [ ] PLAYERS: a player with 1000000 or more points: their rank name stops before the points.
- [ ] LADDER: the IMPORT button's label sits inside it; an ITEM reward with a long name stops before its icon.
- [ ] LADDER, + KIT with eight or more kits in BSP Core: the kits wrap onto up to three rows; any beyond that are named with the command.
- [ ] LADDER, + ITEM in a big modpack: typing a search does not hitch (the first open may take a moment).
- [ ] OPTIONS with MySQL down and a long host name: the screen still answers and the storage line is cut, not missing.
- [ ] Type a name nobody has in the PLAYERS search and leave the screen open for a minute: no server lag. On an online-mode server, the name of someone who has played elsewhere appears after a refresh or two.

Storage (network run):
- [ ] Stop MySQL while players are online for a minute, then start it: the server does not lag, `/rankup info` shows errors while it is down and connects again within about 10 seconds of it coming back. Counts made while it was down reach the shared table once it is back (see the storage reliability section).

## Storage reliability (2026-10-23)

Headless: four GameTests on an in-memory backend that can be switched to failing (changes kept through an outage and written in one go when it returns, a reset in the outage deleting the old row first; many changes merged into one write; a row read at login with the unsent changes added on top; notices older than a day deleted), 39 in all. MySQL itself has not been run: the transaction and the JDBC batch (`rewriteBatchedStatements=true`) are untested against a real database.

On the network (two backends, shared MySQL):
- [ ] Note a player's playtime with `/rank`. Stop MySQL, play 3 minutes and get a kill, start MySQL again. Within a minute, `/rankup info` shows no players waiting, and the player's `/rank` on the **other** server shows the 3 minutes and the kill.
- [ ] Stop MySQL, play 2 minutes, log out and back in on the same server while it is still down: the 2 minutes are still there. Start MySQL, wait a minute, switch servers: still there.
- [ ] While MySQL is down, `/rankup info` shows errors and "N players' changes waiting to be written"; the server does not lag.
- [ ] `/rankup reset <player>` while MySQL is down, then start it: the player starts fresh on both servers within a minute.
- [ ] In MySQL, `SELECT COUNT(*) FROM bsp_ru_notices WHERE created_at < (UNIX_TIMESTAMP() - 86400) * 1000;` is 0 an hour or so after a server starts.
- [ ] Known limit: a server stopped while MySQL is still down logs "closed with N players' changes unwritten"; those changes stay in that server's own save but are replaced by the database row at the players' next login.

## Score signal and BSP-Core 1.4.5 (2026-10-23)

Headless: three GameTests (`ScoreGameTests`: only points past the season's peak count and a new season starts the peak again, BSP-Core's event counts only for a player on this server, the peak shared through the storage), and the team tests now keep BSP-Core's team provider; 42 in all. Needs BSP-Core 1.4.5 on the server.

- [ ] Server log on start: `BSP-Core's teams registered with BSP-RankUp`.
- [ ] Two players on one BSP-Core team: a kill between them gives no player kill (`/rank` shows the same count).
- [ ] `/rankup signal score on` (or ADMIN > SIGNALS, score). Place a totem: after BSP-Core's next board (up to 30 s), `/rank` shows season score rising by your BSP-Core points.
- [ ] Take that totem down and place it again: the score count does not rise a second time.
- [ ] Have the totem stolen: the score count does not fall.
- [ ] Reach more BSP-Core points than before: only the difference is added.
- [ ] End a season in BSP-Core: the count stays, and the new season's points count again from 0.
- [ ] Network: with players moving between two backends, the score is not counted twice (the peak travels in `score_peak` of `bsp_ru_players`).

## Several ladders: settings (2026-10-27, RU-14a)

Headless: three GameTests (`LaddersGameTests`: settings from before load as one ladder, ladders and awards survive saving and the JSON files, the single-ladder edits change only the first ladder), 45 in all. Nothing new to see in game yet; this only checks that updating changes nothing.

- [ ] Update a server that has a ladder set up: `/rankup info` and the admin screen show the same track, rungs and title priority as before, and `/rank` the same rank.
- [ ] `/rankup export test` then `/rankup import test`: the ladder comes back unchanged (the file now also has a `Ladders` list).

## Several ladders: the engine (2026-10-27, RU-14b)

Headless: three GameTests against the real LuckPerms (`MultiLadderGameTests`: each ladder counts its own signals and moves along its own track with its own title priority; an award adds its group once reached and a group already held gives no rewards; a season's end works per ladder and takes seasonal awards back), 48 in all. Ladders and awards can only be set by a settings file until the commands and screens (RU-14d to RU-16), so in game:

- [ ] `/rankup export two`, add a second ladder and an award to `config/bsp_rankup/two.json` (copy the `Ladders` entry, give it a new `Name` and `Track`, `Counts: ["player_kills"]`; an `Awards` entry with `Group`, `Name`, `Counts`, `Points`), make the track and group in LuckPerms, `/rankup import two`.
- [ ] Earn points on each: each ladder moves on its own track (`/lp user <you> info`), the chat says "You are now ... on ...".
- [ ] Reach the award: "You earned ...", the group is on you in LuckPerms, its rewards arrive once.
- [ ] Give yourself an award's group by hand first, then reach it: no rewards.

## Several ladders: shared storage (2026-10-27, RU-14c)

Headless: three GameTests (`LadderStorageGameTests`: later ladders, bases and awards travel to another server; a row from before several ladders keeps the server's copy; an award reached is announced to the other servers), and the multi-ladder tests now pass six runs in a row with LuckPerms edits queued per player; 51 in all. Network run, two backends sharing MySQL, a second ladder and an award set up (see the engine section):

- [ ] On start, `bsp_ru_players` has the columns `ladder_ranks`, `season_bases`, `awards` (`SHOW COLUMNS FROM bsp_ru_players`).
- [ ] Climb the second ladder and reach the award on backend A, move to backend B: `/rank` there shows the same rung on both ladders; nothing is given twice.
- [ ] With announcements on `network`, reaching an award on A shows "... earned ..." on B as well.
- [ ] End a season on A while you are on A: on B afterwards the second ladder's seasonal rung and any seasonal award are gone there too.

## Several ladders: commands, /rank and the api (2026-10-27, RU-14d)

Headless: two GameTests (`LadderCommandGameTests`: ladders and awards made and changed by command; `/rank` gives a line per ladder and the awards, and the api lists them), 53 in all. In game, as a BSP Core admin:

- [ ] `/rankup ladders add raider raider`, then make the track in LuckPerms (`/lp createtrack raider`, groups appended), `/rankup ladders import raider`, `/rankup ladders counts raider player_kills,totems_stolen`, set points with `/rankup ladder points <group> <n>`.
- [ ] `/rankup ladders` shows both ladders with their tracks, counts and title priorities; `/rankup ladders rungs raider` the rungs.
- [ ] `/rankup award add apple_picker 5000 Apple Picker`, `/rankup award counts apple_picker coins_earned`, `/rankup reward add apple_picker item minecraft:golden_apple 16`, then `/rankup award`.
- [ ] `/rankup ladders remove <first ladder>` is refused; `/rankup ladders rename raider x` is refused; renaming the first ladder works.
- [ ] `/rank text` shows a line per ladder ("Playtime: ... - next ...") and "awards: ..." once one is reached.

## Several ladders: the admin screen (2026-10-27, RU-15)

Headless: two GameTests (`AdminLaddersGameTests`: every ladder and award action the screen sends is applied; the view carries LuckPerms' groups and every ladder's and award's holders through the packet), 55 in all, and the layout measured with the font widths (every string fits). The screen itself has not been seen. Server and client must both be this build (protocol 2). In game, `/rankup admin`:

- [ ] Tabs read LADDER, GROUPS, SIGNALS, PLAYERS, OPTIONS; each opens with one click.
- [ ] LADDER: a chip per ladder above the rungs; clicking one shows its rungs and its strip (TRACK, COUNTS chips, "titles at", RENAME on the first, REMOVE on the others). `+ LADDER`, type `raider raider`, Enter: a RAIDER chip appears.
- [ ] On RAIDER: `+ signal`, type `player_kills,totems_stolen`, Enter: two chips; click one: it goes; when none is left the strip says "every signal".
- [ ] On RAIDER: `+ ADD RANK`, type `cutpurse 150 Cutpurse`, Enter: the rung is on RAIDER, not on the first ladder. IMPORT reads RAIDER's own track.
- [ ] REMOVE on RAIDER asks CLICK AGAIN and then removes it; the first ladder shows RENAME instead and no REMOVE.
- [ ] GROUPS: LuckPerms' groups listed under ON A LADDER, AWARDS and NOT RANKUP'S; typing in the search box filters; REFRESH after `/lp creategroup apple_picker` shows it.
- [ ] Click a ladder group: its rung and points, OPEN ON LADDER jumps to it on LADDER.
- [ ] Click `apple_picker`: MAKE AWARD, type `5000`, Enter: it moves to AWARDS; set NEEDS, COUNTS (`coins_earned`), SEASON, add a reward with + ITEM (the browser) and + KIT; STOP AWARDING asks CLICK AGAIN.
- [ ] An award whose group is deleted in LuckPerms shows "missing" and the red line in its pane.
- [ ] Nothing overlaps at GUI scale Auto on 1920 x 1080 (the screen shrinks to fit).

## Several ladders: the rank screen (2026-10-27, RU-16)

Headless: one GameTest (`RankViewLaddersGameTests`: a standing per ladder with its own points and fastest ways, every award reached or with its points, holders on every ladder and award, through the packet), 56 in all; the layout measured. The screen itself has not been seen. Server and client both this build (protocol 2). With two ladders and two awards set up, `/rank` (or U):

- [ ] Under YOUR RANK, a chip per ladder and AWARDS; the screen opens on the first ladder the first time.
- [ ] Click the second ladder's chip: its rungs on the track, your rung there in gold, its points and next rung, WHAT COUNTS lists only its signals; close and open again: it opens on that chip.
- [ ] AWARDS: a card per award; one you reached in gold with "reached, ... ago", the others with "x / y points" and a bar; a seasonal one says "this season"; scroll with five or more.
- [ ] The title chip shows the title LuckPerms shows in chat (a ladder's title over an award's).
- [ ] With one ladder and no awards: one chip and the screen as before.

## Prefix images (2026-10-27, D55 to D74)

Headless: six GameTests (`PrefixImageGameTests`: the settings keep images and the switch; the folder's files checked (too big, too wide, animated, broken, not a PNG); picking an image keeps it here and in the shared table, a server without it fetches it, a player is sent it once; the admin view lists prefixes and files and the actions apply; colour codes; and against the real LuckPerms, the chat and tab list name carrying the title as text, then as its image key with the text for the console, the hover, a group prefix above the title winning, the switch off), 62 in all, three runs. **Nothing of the drawing has been seen in a client**: the images in chat and the tab list, the font reload, the PREFIXES tab, the image slot and the title chip. Server and client both this build (protocol 3).

Setup: put two or three PNGs in the server's `config/bsp_rankup/prefix_images/`, for example a 40 x 9 badge and a 24 x 9 one; give a rung a TITLE reward (`&b[Warden] `) and reach it; give a LuckPerms group a prefix (`/lp group admin meta setprefix 2000 "&c[Admin] "`).

- [ ] Chat: your message shows `<[Warden] Name> hello`, with [Warden] in aqua. The console shows the same text.
- [ ] Tab list: the prefix before your name.
- [ ] `/rankup admin`, PREFIXES: the rung's title under LADDER <name>, the group's prefix under LUCKPERMS GROUPS, each with "text"; the folder's files with their previews (a moment after the tab opens), bad files in red with the reason.
- [ ] Pick the title, pick a file, USE IMAGE: within a second or two the list, the IMAGE box (line size and x2) and IN CHAT show the image. Chat shows the image in place of [Warden], one pixel before the name; hovering it shows "[Warden] Warden". The console still shows `[Warden]`.
- [ ] Another player (or a second client) sees the image in your chat line and the tab list.
- [ ] The admin group: give it an image; a player in that group shows the group's image (its prefix is above the title).
- [ ] SHOW TEXT AGAIN: the text comes back in chat.
- [ ] LADDER: the rung's TITLE reward row has a small slot with the image; clicking it opens PREFIXES on that title.
- [ ] `/rank`: the title chip shows the image.
- [ ] Delete the PNG, REFRESH: the image still shows; the IMAGE box says "not in folder".
- [ ] Two servers on the shared MySQL: pick an image on one; on the other (without the file) players see it.
- [ ] OPTIONS, PREFIXES IN CHAT off: names in chat and the tab list without prefixes; on again: back.
- [ ] Log out and in: the images show again after a moment (they are sent at login); F3+T keeps them.
- [ ] The name above players' heads has no prefix.
- [ ] Watch for: a short hitch when images arrive (only the fonts reload), any mod that changes chat or fonts drawing the prefix wrongly.

## The full audit (2026-10-27, AU-1)

Headless: 68 GameTests on three runs against LuckPerms Forge 5.5.88-bsp.2 (the servers' build; also 62/62 earlier against bsp.4), new: `AuditGameTests` (5), the holder cache, dotted prefixes, the title after a season's fall. Not run against a real MySQL or Redis, nor on two backends.

- [ ] Two backends on the shared MySQL: on A, `/rankup add <player> <points>` while the player is offline and enough for a rung with an item reward; the player logs into B (gets the item), then into A: no second item.
- [ ] Two backends with Redis: pick a prefix image on A; on B a player's chat shows it within seconds, without another edit.
- [ ] A rung with a title, marked RESET (seasonal): reach it, end the season (BSP-Core's season end): the title in chat goes back to the rung below's title (or none). A seasonal award with a title: the same.
- [ ] A LuckPerms group with a dotted prefix (`/lp group x meta setprefix 2000 "&e[Lv.5] "`): PREFIXES shows it without a backslash; give it an image: chat shows the image.
- [ ] `/rankup prefix list`, then `/rankup prefix image title <group> <file>` and `/rankup prefix clear title <group>` as it prints them.
- [ ] Velocity Additions' tab list (live config: on, every 3 s) replaces RankUp's tab names with its own text prefix; until request RU-R3 is done, images show in chat but not in the tab list. Check what the tab list shows.
- [ ] The log at start: "MySQL driver: ... (from BSP-Core)" with BSP-Core 1.4.5 or newer.

## Seeing which signals count (2026-10-27, D81 to D85)

Headless: the build and 68 GameTests (nothing server-side changed: the matrix sends the existing counts commands); the new labels measured (9 of 9 fit). Not seen in a client.

- [ ] LADDER: under TRACK, a PROGRESS FROM line: your ladder shows "minutes played 1 pt / minute"; hover it: "minutes played (key playtime, from RankUp)".
- [ ] A rung's POINTS shows "= 120 minutes" under it on a ladder counting one signal; none when it counts several.
- [ ] The x on a chip takes the signal off (the ladder then counts every signal when it was the last one: "every signal" in gold).
- [ ] SIGNALS: columns RANKUP (and every other ladder and award). Click an empty cell: a tick, and LADDER's PROGRESS FROM gains it. Click a tick: gone. A ladder with no tick shows dim gold ticks everywhere (every signal).
- [ ] Switch a counted signal off on SIGNALS: its tick and chip dim, the chip says "off".
- [ ] With four or more ladders and awards, < and > page the columns.
- [ ] GROUPS: an award shows PROGRESS FROM chips and "= 5000 pieces" beside NEEDS.
- [ ] The rung list still shows all 12 rungs; a rung with six rewards scrolls its list.

## Rank-ups while away are quiet (2026-10-27, D86)

Headless: 69 GameTests, new `a_player_who_is_away_ranks_up_without_an_announcement` (an away player crosses a rung and reaches an award with announcements on network: nothing is announced here or sent to the other servers; after login the next rung is announced both ways).

- [ ] Restart a server with a player offline who is owed a rung: the log at start has no "reached the rank" line.
- [ ] `/rankup add <offline player> <points>` past a rung: no announcement in chat; the player logs in and gets the waiting rewards.
- [ ] The same for an online player: announced as before (and on the other servers with announce network).

## Suffixes and the stacked name line (2026-10-27, D87 to D97)

Headless: 73 GameTests on two runs against LuckPerms Forge 5.5.88-bsp.2; new `SuffixGameTests` (4): the split into known pieces (the longest wins, titles before groups, unknown text stays), spacing (images one pixel apart, real spaces around the name, the console reads every piece), hover notes (kept, shared, limits, the screen's action), a group's own suffix read and drawn after a player's name, the switch covering suffixes. Not seen in a client.

Set up first: copy `docs/prefix_images/*.png` into `config/bsp_rankup/prefix_images/` on one server, then paste `docs/prefix_images/commands.txt` into its console. For every achievement to show, the LuckPerms suffix format lists `highest_from_group_<group>` per achievement (request RU-R5).

- [ ] Chat as an admin on a rung: `<[Admin][Warden] Mrgregles> hi` with the two banners one pixel apart and a normal space before the name.
- [ ] A player holding two achievements: both icons after the name, one pixel apart; hovering one shows "✦ Totem Reaver totem_reaver" and its note.
- [ ] The tab list shows the same line.
- [ ] PREFIXES: the list ends with "AFTER THE NAME - SUFFIXES" (violet heading) and the seven achievements; picking one shows SUFFIX TEXT, its image, HOVER NOTE, and IN CHAT with the icon after the name.
- [ ] Click HOVER NOTE, type a note, Enter: the field shows it and hovering in chat shows it (within 5 seconds). Empty and Enter takes it away.
- [ ] OPTIONS: "PREFIXES AND SUFFIXES" off leaves names alone, both sides.
- [ ] The server console's chat lines read `[Admin] [Warden] Mrgregles ✦ Totem Reaver ✦ Founder`, no stand-in characters.
- [ ] A player on RankUp 0.4.x joins a 0.5.0 server: they connect and see the icons in chat.

## PLAYERS for every ladder (2026-10-27, D98 to D105)

Needs: two ladders (the second counting player kills, say) and an award on GROUPS. Server and client both on this build (protocol 4).

1. `/rankup admin`, PLAYERS, pick yourself. LADDERS shows a row for each ladder: your rung (its image if it has one) and "i/n", points, this season, the bar and "<next rung> at <points>" (or "top rung").
2. Kill a mob or wait a minute: the points on the ladders that count it go up within two seconds; the others stay.
3. SET on the second ladder: its rungs open under the table, with the row lit. Pick one: you move on that ladder only, with no rewards; the first ladder and the list's rank don't change. CANCEL or SET again closes it.
4. AWARDS: a reached award is lit with REACHED; one not reached shows "points/needed" and a bar. A seasonal award counts this season's points. No awards: "No awards yet: add them on GROUPS."
5. COUNTS: each signal shows the letters of the ladders that count it, with the key after COUNTS. Scroll over the counts when they don't all fit (SET open makes the box shorter).
6. ADD POINTS 10: every ladder's points and every award's go up by 10. RESET (click twice): every ladder's rank, every count and the bonus are forgotten.
7. With four ladders, scroll over LADDERS to see the fourth.
8. A 0.4.x client can't join this server (protocol 4); that is expected.

## The audit of 0.5.0 (2026-10-27, AU-2)

Headless: 78 GameTests, three runs clean. Not seen in a client; the network part not run on two real backends.

- [ ] Two backends, announcements SERVER: play on B; on A run `/rankup add <you> <enough points for the next rung>`. Within a few seconds B says "You are now ..." to you and announces it on B; a reward that needs you online (coins, items) arrives on B at once, not at your next login. Nothing is said on A.
- [ ] The same with announcements NETWORK: A and every other server announce it too, once each.
- [ ] Keep playing on B until your own points pass the rung A gave you: no second "You are now ...", no second rewards.
- [ ] SIGNALS: on a ladder counting one signal, click its only tick: the bottom line says a ladder counts at least one signal, and the ladder is unchanged. The same with the x on its PROGRESS FROM chip, and on an award on GROUPS. Typing `all` in + signal still makes it count every signal (gold ticks).
- [ ] SIGNALS with four or more ladders and awards: < > and "1 of n" sit under the matrix on the right, clear of the column names; the hint beside them is readable.
- [ ] PREFIXES: open HOVER NOTE on one prefix, click another in the list: the box closes. Set a note on a suffix, delete the group in LuckPerms, then `/rankup prefix note suffix <group>`: the note goes.
- [ ] PLAYERS: select an online player, then search for someone else: the pane still says "online". Open SET on a ladder with 12 rungs: three rows of rungs, "+n more: /rankup set <player> <group>" readable under them, no empty row before COUNTS.
