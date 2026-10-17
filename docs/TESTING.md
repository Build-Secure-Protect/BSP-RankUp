# Testing BSP RankUp in game

What to try after each build. Tick what works, note what does not. Claude cannot run the client, so this list is the hand-off between a build and the owner. Headless checks run by Claude: `./gradlew runGameTestServer`.

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
