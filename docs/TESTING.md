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
