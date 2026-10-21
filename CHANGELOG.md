# Changelog

All notable changes to BSP RankUp. Format follows Keep a Changelog; versions follow SemVer.

## [Unreleased]

## [0.2.1] - 2026-10-21

### Changed (2026-10-21, MySQL driver)
- The MySQL connection creates its driver directly, as BSP Core does, instead of asking `java.sql.DriverManager`: BSP Core's relocated driver (`com.mrgregles.bsp_core.shaded.mysql.cj.jdbc.Driver`, in its full jar on Forge servers) first, then the plain `com.mysql.cj.jdbc.Driver` (Mohist's), then `DriverManager` as before. The start log names the driver used. `DriverManager` did find BSP Core's driver in the Forge dev run, but only through its one-time service scan, which depends on which thread first touches it. One GameTest (28 in all): a connection to a closed port fails inside BSP Core's driver, not with "No suitable driver".

### Changed (2026-10-20, sibling versions)
- Compiled against BSP Region Guard 0.3.0 (its request RG-R3; API 5 unchanged, still accepts `[0.1.0,)`). No code change; 27 GameTests pass.

## [0.2.0] - 2026-10-19

### Added (2026-10-19, kit rewards)
- A sixth reward kind, **KIT**: a rung can give a BSP Core kit by id (BSP Core 1.3.0 or newer; its request K-1). BSP Core owes the kit and delivers it when the inventory has room, at once or at the next login, so it needs no online check. The admin screen's + KIT picks from BSP Core's kits by name; `/rankup reward add <group> kit <id>` with tab completion; a kit that BSP Core does not know is refused. On an older BSP Core the kind is refused and does nothing.

### Changed (2026-10-19, sibling versions)
- Compiled against BSP Core 1.3.0 (its 1.1.0 jar is gone; still requires `[1.1.0,)`) and BSP Region Guard 0.2.0 (Region Guard's request RG-R2; still accepts `[0.1.0,)`). No code change; 26 GameTests pass.

## [0.1.0] - 2026-10-18

### Added (2026-10-18, claims bought)
- The `claims_bought` signal counts: with BSP Region Guard installed, every new claim a player buys (not an enlargement) counts one, through Region Guard's `ClaimPurchasedEvent` (its request RG-R1). Off by default like the other optional signals; switching it on gives points for claims already counted. One GameTest (26 in all).

### Changed (2026-10-18, docs)
- `README.md` describes the mod, its setup, commands and build; `docs/TESTING.md` opens with the setup for a first pass; the build runs `tools/check_lang.py` (`checkLang`, part of `check`).

### Added (2026-10-18, player screen)
- The rank screen (owner decisions D38 to D46): the ladder on a track down the left with your rung in gold, the rungs below lit, seasonal ones marked, how many hold each and how far the next is; on the right your rank, title, points (lifetime and this season), the bar to the next rung, what it gives, the fastest ways there (the three heaviest signals), and every signal that counts with its count, worth, share and points; the bonus and the total; rewards waiting. Hovering a rung shows what it gives. Refreshed every second while open.
- Opens with `/rank`, with a key (default U, "Open your rank" under BSP RankUp in Controls) and with a button in the survival inventory (client config `ui.inventoryButton`, `inventoryButtonX`, `inventoryButtonY` to move it off other mods' buttons). `/rank text` prints the chat lines instead.
- Two GameTests on the server side (25 in all).

### Added (2026-10-18, admin screen)
- `/rankup admin` opens the admin screen for BSP Core admins (owner decisions D22 to D31): tabs LADDER (rungs on the left; the chosen rung's group, display name, points and keep/reset on the right with its rewards, + COINS / PIECES / ITEM / TITLE / COMMAND, FROM HAND and an item browser over every item with a search), SIGNALS (on/off and points per units for each), PLAYERS (search; online players and everyone with progress here; a player's counts, points, bonus and waiting rewards; SET RANK, ADD POINTS, RESET) and OPTIONS (track, announcements, kill cooldown, title priority, seasonal rewards each season or once, MAKE TRACK IN LUCKPERMS, EXPORT and IMPORT, the storage and LuckPerms state, RESET EVERYONE with a code typed back within a minute). Every change saves at once and reaches every server; REMOVE RANK, RESET and MAKE TRACK want a second click within five seconds.
- The screen fills the window (client config `ui.scale`, 0 fills, 1 to 4 fixed), minimum 520 by 300 units; BSP Core's Holo HUD look; text from the language file.
- The commands and the screen share one set of edits (`admin/AdminEdits`). Channel `bsp_rankup:main`, protocol 1. One GameTest on the server side (23 in all); the screen itself needs the client.

### Added (2026-10-18, seasons)
- When BSP Core ends a season, a player on a seasonal rung falls back to the highest permanent rung below it (off the ladder when there is none), is told, and a `RankChangedEvent` with cause `SEASON` fires; permanent rungs stay. From then on seasonal rungs are judged on the points earned since (the player's points at the season's end are their base), so the lifetime points that earned the rung do not climb them straight back. Every player with progress has survived one more season (the `seasons_survived` signal, off by default). Players online are handled at once, everyone else on their next login here, so on a network each player is handled once.
- Rewards on a seasonal rung come again each season, or once only with `/rankup option rewardsagain false`. `/rank` shows this season's points once a season has ended.
- Shared storage: `season`, `season_base` and `rewarded` on the players row. Two GameTests (22 in all).

### Added (2026-10-18, api)
- Package `api` (version 1, `docs/API.md`): `RankUpApi` with the ladder (`track`, `ladder`, `rank`), a player's standing (`rankOf`, `hasRank`, `progressOf`, `pointsOf`), `addPoints` for other mods, `signalKeys`, the team hook (`setTeamProvider`, `hasTeamProvider`) and the storage state (`isShared`, `serverId`); records `RankInfo`, `ProgressInfo`; `TeamProvider`. `api.event.RankChangedEvent` on the Forge bus after every rank change (`EARNED`, `ADMIN`, `SEASON`), one per rung passed. Two GameTests (20 in all).

### Added (2026-10-18, shared storage)
- Server config `bsp_rankup-server.toml`: `storage` (mode none or mysql, host, port, database, user, password, tablePrefix `bsp_ru_`, serverId, pollSeconds) and `storage.redis` (enabled, host, port, username, password, channel, safetyPollSeconds). Nothing else is in the file: the ladder and the rest live in the shared storage and are edited in game.
- With `storage.mode = mysql`: every change to a player's counts, bonus, rank and waiting rewards is written through as it happens (counts and bonus as additive deltas, so playtime on several servers adds up); a player's row is read on login and replaces the local copy; the settings are one shared document written on every edit and re-read when another server changes them. Tables `bsp_ru_counts`, `bsp_ru_players`, `bsp_ru_settings`, `bsp_ru_notices`. No driver is bundled (BSP Core's full jar or Mohist provides one). `serverId` empty takes BSP Region Guard's server id when it is installed.
- Notices between servers (a settings change, a player reset elsewhere, a rank-up to announce network-wide when announcements are set to `network`) go through Redis when enabled (RankUp's own small client, as Region Guard's) and are always written to the notice table, which every server reads every `pollSeconds` (every `safetyPollSeconds` while Redis is connected); a notice acts once.
- `/rankup info` shows the storage's state. Three GameTests on an in-memory backend and a fake Redis (18 in all). The MySQL path itself is untested until a network run.

### Added (2026-10-17, commands)
- `/rank`: your rank and where it stands (rank n of m, points, the next rung and the points to go, every counted signal, the bonus, rewards waiting). `/rank <player>` for admins, by name (online, known to the server, or a uuid).
- `/rankup` for BSP Core admins and the console: `set <player> <group>` (no rewards), `add <player> <points>` (negative takes away; never a demotion), `reset <player>` (starts over on the first rung); `ladder list|add <group> <points> [name]|remove|points|name|seasonal|move <rung>|import` (import makes rungs from the LuckPerms track's groups); `reward list|add <group> coins|pieces|item [count]|item hand|title|command ...|remove <n>|clear`; `signal` (list) and `signal <signal> on|off|points <n> [per <n>]`; `option track|announce off/server/network|cooldown <min>|titlepriority <n>|rewardsagain <bool>`; `export <name>` and `import <name>` as `config/bsp_rankup/<name>.json`; `info`; `reload` (LuckPerms looked up again, everyone online re-checked). Every edit saves at once.
- Two GameTests (15 in all).

### Added (2026-10-17, settings and the ladder engine)
- `settings/Settings` (immutable, with a builder): the LuckPerms track, the ladder of `Rank`s (group, display name, points needed, seasonal flag, rewards), a `SignalSetting` per signal (on, points per units), the kill cooldown, where rank-ups are announced (off, server, network), the title priority, whether seasonal rewards are given again. `settings/SettingsLedger` saves it with the overworld (`bsp_rankup_settings`) and tells listeners of every change; the config file is not used for any of it.
- `settings/Reward`: coins (Shatter Coins into the hand), pieces (into the bank), item (id and count), title (a LuckPerms prefix at RankUp's priority), command (console, `%player%` replaced). `rank/Rewards` hands them over; coins, items and commands for a player who is away are kept in the ledger and given on their next login here.
- `rank/RankEngine`: points = every signal that is on plus the admin bonus; the rung reached is the highest whose threshold the points pass, walking from the first; on every ledger change and on login a player above their current rung is put on the new one through LuckPerms (`setGroup`), given every passed rung's rewards, told, and announced to the server; the first rung at 0 points is given silently. Never downward. A player who lost the track's groups in LuckPerms is put back on their rung. Without LuckPerms the rank is recorded locally.
- `tools/gen_lang.py` writes the language file (rank-up and reward lines). Five GameTests (13 in all).

### Added (2026-10-17, progress ledger and signals)
- `progress/Signal`: what RankUp counts (playtime in minutes, player kills, mob kills, deaths, totems stolen, protected, lost and placed, seasons survived, score, coins earned, claims bought), each with the default on/off, points and "per" that seed the settings. Counts are raw; points come from the settings, so a changed weight loses nothing.
- `progress/Progress` and `progress/ProgressLedger`: every player's counts, admin bonus points and the rank RankUp last gave, saved with the overworld (`bsp_rankup_progress`); `add`, `set`, `addBonus`, `setRank`, `reset`; listeners hear every change (the ladder engine and the shared storage hook in here).
- Signals: `signal/Playtime` (one minute per 1 200 ticks online, per player, in any world; spectators excluded; the part minute is dropped on logout), `signal/KillSignals` (player kills credited to the damage source's player, mob kills, deaths; a kill of yourself is a death only), `signal/KillCooldowns` (a repeat kill of the same victim within 10 minutes does not count; the value is a setting later), `signal/Teams` (the team hook: once a teams mod registers it, kills of teammates do not count), `signal/TotemSignals` fed by `compat/BspCore` from BSP Core's totem events (a successful steal: stolen for the thief, lost for the owner; a failed steal: protected for the owner when online on this server; a placed totem).
- Six GameTests (8 in all).

### Added (2026-10-17, LuckPerms bridge)
- `compat/LuckPermsBridge`: the one class that talks to LuckPerms. Reaches the Forge edition directly or the Bukkit plugin on Mohist through Bukkit's plugin manager, then uses one reflective implementation on both routes. Reads: a player's groups and primary group, every group, a track's groups in order, `rankOn` (the furthest group of a track the player holds), `prefixOf`. Writes (futures that complete on a LuckPerms thread): `promote` and `demote` along a track, `setGroup` (exactly one group of the track, made primary), `setPrefix` at RankUp's own priority (titles), `ensureTrack` (creates missing groups with rising weights and the track in the given order; the "RankUp makes the track" option, off until an admin asks).
- The LuckPerms Forge dev jar for the GameTests (`libs/`, not shipped; recipe `tools/make_luckperms_dev_jar.py`) and one GameTest covering every read and write against the real LuckPerms 5.4.102; it skips itself when the jar is missing. 2 GameTests in all.

### Added (2026-10-17, project skeleton)
- Forge 1.20.1 project (Forge 47.4.10, Java 17, official mappings) with the mod entry class `BSPRankUp` (mod id `bsp_rankup`). Requires BSP Core 1.1.0 or newer; BSP Region Guard and BSP Teleport are optional. LuckPerms API 5.4 compile-only.
- GameTest wiring (`./gradlew runGameTestServer`): the empty structure template from `tools/gen_gametest_structures.py`, a mock server player helper, and one setup test proving the mods load.
- No ranking rules yet: the design is being decided with the owner.
