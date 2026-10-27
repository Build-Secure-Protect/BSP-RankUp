# Changelog

All notable changes to BSP RankUp. Format follows Keep a Changelog; versions follow SemVer.

## [Unreleased]

## [0.4.0] - 2026-10-27

Server and modpack must update together (protocol 3). Requires BSP Core 1.4.5 or newer; BSP Region Guard 0.6.0 needs BSP Core 1.4.6 or newer, so deploy BSP Core 1.6.2, Region Guard 0.6.0 and this RankUp together.

### Changed (2026-10-27, the owner's answers after the audit)
- BSP Core 1.4.5 or newer is required (`bsp_core_version_range=[1.4.5,)`, D77): the score signal, the team hook and the database driver lookup need it.
- The rank screen's title chip shows each ladder's own title (the prefix at that ladder's priority), as its image when it has one, instead of the one title LuckPerms shows on every ladder's pane (D76). The rank view carries a title per ladder.
- Titles follow the rung at a season's end (D75, confirmed).
- README: how ranks gate regions and kits (exact groups; a region for "X and up" lists every rung from X, D78).

### Fixed (2026-10-27, the full audit, AU-1)
- On a network the login check (rewards kept while away, a season's end missed) waited for nothing: it ran on this server's old copy before the player's shared row arrived, so rewards already given on another server could be given again and a season handled there handled again. It now waits for the row (or for the read to find none or fail); nothing is checked for the player meanwhile.
- A settings change was announced to the other servers before it was written, so with Redis a server could read the old settings and miss the change (a newly picked prefix image included) until the next edit. The notice now goes out after the write.
- Notices read from the MySQL table when Redis is off: one committed after a later id could be skipped; the last few are read again (each acts once).
- Prefixes with a dot (`[Lv.5]`) were read from LuckPerms with its key escape (`[Lv\.5]`): group prefixes with a dot never got their image, and the PREFIXES tab and the title chip showed the backslash.
- Falling off a seasonal rung, or losing a seasonal award at a season's end, left its title on the player (now shown in chat). The title now follows the rung: the one the rung fallen to gives (or the nearest rung below that gives one), or none; a taken-back award's title gives way to the latest award kept that has one (D75).
- `/rankup prefix image|clear` could not read the `title:<group>` keys `prefix list` printed: they now take `title <group>` or `group <group>`, as `prefix list` prints them.
- A PNG whose chunk claims more bytes than the file could stop the images folder being read; it is now listed as broken.
- A rung with two TITLE rewards matched its image against the first; LuckPerms shows the last.
- The tab list name kept an old team colour after a team change until the prefix changed; a team change draws it again.
- The PREFIXES chat preview is drawn with chat's shadow.

### Changed (2026-10-27, the full audit, AU-1)
- Holder counts for the rank and admin views are counted once and kept until a rank, award, season, load or reset changes them; the settings sent with each view are made once per change; the admin search looks up offline names without walking every world; LuckPerms' groups are asked once per check rather than once per ladder.
- The images folder is read again only for files whose size or time changed; names are drawn again only when the switch, an image or a title's text changed, not on every settings edit.
- The MySQL driver comes from BSP-Core (`BspStorage.jdbcDriver()`, BSP-Core 1.4.5 and newer, which also covers its slim jar and MariaDB); the old lookup stays for older BSP-Core.
- Dev runs and GameTests use the LuckPerms Forge build the servers run (`luckperms_dev_version=5.5.88-bsp.2` in `gradle.properties`; `tools/make_luckperms_dev_jar.py` reads it).

### Added (2026-10-27, prefix images, D55 to D74)
- Prefixes before names: RankUp puts each player's LuckPerms prefix (the one LuckPerms ranks highest) before their name in chat and the tab list, inside vanilla's `<name>`, in its colours (`&` and `§` codes, `&#RRGGBB`). Nothing on a Forge network did this before. OPTIONS has PREFIXES IN CHAT on/off, and `/rankup option showprefixes true|false`, for a chat mod added later.
- Prefix images: PNG files in `config/bsp_rankup/prefix_images/` (at most 256 x 64 pixels and 256 KB, not animated, no wider than 64 pixels once drawn one text line high) can be drawn in place of a rank's prefix. An image is tied to the rank: a rung's or an award's title (`title:<group>`), or a LuckPerms group's own prefix (`group:<group>`), so editing the prefix text keeps it. One image may serve several ranks. Hovering an image in chat shows the prefix text and the rank's name; the console and logs show the prefix text.
- The picked image is kept on the server and in the shared MySQL storage (a new `<prefix>images` table), so the other servers draw it without the file, and deleting the file changes nothing (the screen marks it "not in folder"). Players are sent each image in use at login and when one is picked; the client draws it as a character of a font RankUp adds, reloading only the fonts.
- A PREFIXES tab on the admin screen (mock-up A, D65): every prefix by where it comes from (each ladder's titles, the awards' titles, LuckPerms groups), the chosen one's text, image at line size and twice that, a chat preview, and the folder's files with previews and what is wrong with any that cannot be used; USE IMAGE, SHOW TEXT AGAIN, REFRESH, "only without image". A TITLE reward on LADDER and GROUPS shows its image in a small slot that opens PREFIXES on it.
- The rank screen's title chip shows the title's image. The "Your title is now ..." line shows the title in its colours.
- `/rankup prefix list`, `prefix files` (the folder read again, each file checked), `prefix image title|group <group> <file>`, `prefix clear title|group <group>`.
- Protocol 3: the server and the players' modpack must update together.

## [0.3.0] - 2026-10-27

### Added (2026-10-27, the rank screen for several ladders and awards, RU-16)
- The player's rank screen (mock-up A, D52) has a chip per ladder under the title, and AWARDS when there are awards. A ladder chip shows that ladder: its rungs on the track, the player's rung, points and next rung there, its fastest ways, and under WHAT COUNTS only the signals that ladder counts. AWARDS lists every award as a card: reached (and when) in gold, or the points toward it with a bar, and what it gives. The screen opens on the chip looked at last this game, else the first ladder.
- The title chip shows the RankUp title LuckPerms displays: the prefix at the highest of the ladders' and the awards' priorities.
- The rank view (protocol 2) carries each ladder's standing (rung, when, points, this season's points, fastest ways among its own signals) and each award's (reached and when, or the points toward it), and counts holders on every ladder and award.

### Added (2026-10-27, the admin screen for several ladders and awards, RU-15)
- LADDER (mock-up A, D51): a row of ladder chips at the top chooses the ladder, `+ LADDER` adds one ("name track"). Under the chips, the chosen ladder's strip: its TRACK, what it COUNTS as chips (a click takes a signal off, `+ signal` sets the list, "every signal" when none is chosen), its title priority, and RENAME (the first ladder) or REMOVE (the others, a second click). The rung list, `+ ADD RANK` and IMPORT work on the chosen ladder.
- GROUPS, a new second tab (D50, D51): every LuckPerms group, read from LuckPerms each time the view comes, in three parts: on a ladder (with the ladder and rung), awards (with what they count, or "missing" when LuckPerms has no such group), and not RankUp's. A search box filters the list; REFRESH reads LuckPerms again. A ladder group shows its rule and OPEN ON LADDER; an unused group has MAKE AWARD; an award has its name, points, counts, season, rewards (the same reward editor as a rung, with the item browser and kit picker) and STOP AWARDING.
- Protocol 2: the admin view carries LuckPerms' groups and the holders of every ladder's rungs and every award, so the server and the players' modpack must update together.

### Added (2026-10-27, several ladders: commands, /rank, api, RU-14d)
- `/rankup ladders` lists the ladders; `/rankup ladders add <name> <track>` adds one (counting every signal, its titles one priority below the lowest so far), and `remove`, `rename`, `track`, `counts <ladder> all|<signal,signal>`, `titlepriority`, `rungs`, `addrung <ladder> <group> <points> [name]` and `import <ladder>` look after them. The first ladder cannot be removed, and only it can be renamed (what players hold on the others is kept under their names).
- `/rankup award` lists the awards; `award add <group> <points> [name]`, `remove`, `points`, `name`, `counts`, `seasonal` look after them. A group is a rung on one ladder at most, or an award, never both.
- The rung commands (`ladder remove|points|name|seasonal|move`), `rankup set` and the reward commands now reach a rung on any ladder, and the reward commands an award's rewards too.
- `/rank` with several ladders shows one line per ladder (its rung, points and next rung), then the signals, the awards reached, the bonus and what waits. With one ladder it reads as before, plus the awards reached.
- API version 2: `ladders(server)` (`LadderInfo`), `ladderNamed`, `rank`, `rankOf`, `hasRank`, `progressOf` with a ladder name, `awards(server)` (`AwardInfo`), `hasAward`, `awardsOf`. The calls without a ladder name answer for the first ladder, so `ladder(server)` (read by BSP-Core's kit rank rules) is unchanged. `docs/API.md` updated.

### Added (2026-10-27, several ladders: shared storage, RU-14c)
- The shared MySQL storage carries every ladder: each later ladder's rung, the season bases of the later ladders and the seasonal awards, and the awards reached, in three new columns of the players table (`ladder_ranks`, `season_bases`, `awards`, added to an existing table on start), set together with the latest winning. A row written by RankUp 0.2 (no such columns) leaves the server's own copy alone; a row that has them is the truth, even when empty.
- Awards are announced on the other servers too when announcements are network-wide (D53).

### Fixed (2026-10-27, LuckPerms edits)
- RankUp's LuckPerms edits for one player now run one at a time. LuckPerms loads, edits and saves the whole user, so two edits at once (rank-ups on two ladders, a rank-up and an award, a title and a rung) could each save over the other's change.
- Adding an award's group decides "the player already had it" inside the same LuckPerms edit, instead of from LuckPerms' cached copy, which could be out of date or unloaded.

### Added (2026-10-27, several ladders: the engine, RU-14b)
- The ladder engine works on every ladder: each ladder's points are the signals it counts plus the admin bonus, and a player climbs each ladder on its own LuckPerms track, holding one rung on each. A later ladder's TITLE rewards go to that ladder's title priority. When there is more than one ladder, the rank-up message and the announcement name the ladder ("You are now Warden on Playtime!").
- Awards: once a player's points from an award's signals (plus the bonus) reach its points, RankUp adds its group in LuckPerms, gives its rewards once and tells the player ("You earned Apple Picker!"). A player who already has the group (by hand, or from another server) is recorded as having it, with no rewards. A group on a ladder is never an award. Award titles go one priority below the lowest ladder's.
- A season's end works per ladder: each ladder's seasonal rung falls back on its own track, each ladder's and each seasonal award's points become that season's base, and seasonal awards are taken back (their group removed) to be earned again.
- `RankChangedEvent` names the ladder (`getLadder()`).
-

### Added (2026-10-27, several ladders: settings, RU-14a)
- The settings hold several ladders (each with a name, its LuckPerms track, the signals it counts, its rungs and its title priority) and awards (LuckPerms groups on no ladder, each with the signals it counts, the points it needs, a season flag and rewards), owner decisions D49 to D51. Settings from before load as one ladder named after its track, counting every signal, so nothing changes. Saved settings still carry the first ladder in the old form, so a backend that has not updated reads it. Not used by the ladder engine, commands or screens yet (RU-14b to RU-16).

### Changed (2026-10-27, sibling versions)
- Compiled against BSP Region Guard 0.6.0 (its request RG-R6; the 0.5.0 jar is gone). Still accepts `[0.1.0,)`.

## [0.2.2] - 2026-10-23

### Added (2026-10-23, score signal)
- The `score` signal counts BSP-Core's points (BSP-Core 1.4.5's `BspScores` and `ScoreEvent.Changed`, RankUp's request RU-R1): each season's peak, added up (owner decision D48). The count rises only when a player's points pass their highest this season, by the amount they pass it; a season's end starts the peak again. Taking a totem down and putting it back gains nothing, and a stolen totem takes nothing already counted. Counted on the server the player is on, and checked again on login and after a season's end. Off by default like the other optional signals. With an older BSP-Core it counts nothing.
- The peak is shared through MySQL with the season values (new column `score_peak` in the players table, added to an existing table on start).

### Changed (2026-10-23, BSP-Core 1.4.5)
- Compiled against BSP-Core 1.4.5 (still accepts `[1.1.0,)`). BSP-Core now registers its teams with RankUp's team hook itself, so kills between teammates count for nothing; no RankUp change was needed.

### Fixed (2026-10-23, UI and performance pass)
- Both screens shrink to fit a window too small for them instead of being cut off at the edges (1920 x 1080 at GUI scale 4, Minecraft's automatic choice, is 480 x 270 units; the screens are 520 x 300). Larger windows still fill as before (D29).
- Admin screen: with the item browser open, a click on an item no longer reaches what lies under the browser (the rung's fields, the reward chips, or REMOVE RANK, which two clicks there could arm and fire). Clicks now go to whatever was drawn last.
- Admin screen, PLAYERS: the search box no longer covers the LADDER, SIGNALS and PLAYERS tabs (it sat on top of them and took their clicks); it is now the top of the list column. SET RANK lays the rungs out on up to three rows, so all twelve BSP ranks can be picked (only six fitted before); any beyond that are named with the command to use.
- Text that could run into its neighbour: a rung's points and its season mark on the rank screen, the title chip and the NEXT line beside a long rank name, the header line with a long server id, rewards waiting beside a big total, a player's rank beside big points, the rank line beside a long player name, signal names beside big counts, reward descriptions beside the item icon, and IMPORT in its button.
- The rank screen asked the server for a fresh view every 20 frames (seven times a second at 144 fps) and the admin screen every 40; both now count game ticks (once a second and once every two seconds). The server answers a player's refresh at most every 10 ticks.
- The screens read the settings once when a view arrives instead of every frame (the admin screen read them several times a frame); reward texts and the item browser's stacks are built once, not every frame.
- Views count rank holders and players without copying every player's progress; the admin view did that three times per refresh.
- MySQL: while the database is unreachable, writes fail at once for 10 seconds after a failed connect instead of each waiting out the 5-second connect timeout, so the storage queue does not back up; a connection used in the last 30 seconds is no longer pinged before every statement.
- LuckPerms bridge: reflective method lookups are cached (the title lookup scanned every node's whole method table every second for each open rank screen).
- The kill cooldown forgets victims whose cooldown has passed once a killer has 64 remembered.
- The rank screen opens with the player's own rung and the next one in view; with nine or more rungs it used to open on the top rungs, so a new player saw neither.
- A title longer than 64 characters (a colour gradient passes that quickly) made the rank screen's packet fail, so the screen never opened; the title, server id and kit names are cut to what the packet holds. `&#RRGGBB` colour codes are stripped from the title chip too.
- Admin info lines and replies are cut to one line of 500 characters, under the packet's 512 (a storage error with a long host name could pass it and stop the admin screen answering).
- The kit picker lays kits out on up to three rows (about four fitted before) and names the rest with the command to use.
- The inventory button follows the inventory when the recipe book opens or closes (it stayed where it was, over the player preview or outside the panel). Its default place in code now matches the config default (128).
- The item browser builds every item's stack and search text once when it first opens, not again for every search.
- The rank screen's ladder box ends with the right pane, so the hint line under it no longer crosses its border.
- Kit names for the screens are read from BSP-Core at most once a second (BSP-Core copies every kit's items to list them).
- A player search that matches nobody here asks the profile cache off the server thread; the answer shows on the next refresh.
- The LuckPerms check made on every count change asks for the player's groups once instead of twice.

### Fixed (2026-10-23, storage reliability)
- Shared MySQL storage keeps players' progress through a database outage. Count and bonus changes are merged per player and signal in memory and written every 5 seconds as one transaction (one JDBC batch per statement); a write that fails is kept and tried again after 10, 20, 40, then every 60 seconds. Before, each kill or playtime minute was its own write, and one made while MySQL was down was dropped, then overwritten from the database at the player's next login. Rank, waiting rewards, season and rewarded rungs are kept the same way, the latest value winning. A row read at login gets the changes not yet written added on top. A reset deletes the row before any later change and tells the other servers once the delete is written. Notices older than a day are deleted every hour. `/rankup info` shows how many players' changes are waiting. Four GameTests (39 in all).

### Changed (2026-10-23, sibling versions)
- Compiled against BSP Region Guard 0.5.0 (its request RG-R5; the 0.4.0 jar is gone). Still accepts `[0.1.0,)`.

### Changed (2026-10-23, sibling versions)
- Compiled against BSP Region Guard 0.4.0 (its request RG-R4; API 5 unchanged, still accepts `[0.1.0,)`). No code change; 28 GameTests pass.

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
