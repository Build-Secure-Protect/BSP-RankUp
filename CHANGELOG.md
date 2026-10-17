# Changelog

All notable changes to BSP RankUp. Format follows Keep a Changelog; versions follow SemVer.

## [Unreleased]

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
