# BSP RankUp API for other mods

Package `com.mrgregles.bsp_rankup.api`, API version 2 (version 2 added several ladders and awards). Compile against the RankUp jar (`build/libs/bsp-rankup-<version>.jar` through `fg.deobf`, as the BSP mods do with each other); keep every call in one adapter class on your side. Only this package and `api.event` are meant for other mods; anything else in the jar may change without notice. `RankUpApi.API_VERSION` is a compile-time constant: compare `RankUpApi.apiVersion()` at run time to detect an older RankUp, and catch `LinkageError` around the first call for a RankUp without the class.

Everything is read on the server thread from what this server knows. On a network a player's row is read from the shared storage when they log in here, so for a player who is elsewhere the answer may be behind by what they did since.

## `RankUpApi`

RankUp has one or more ladders, each on its own LuckPerms track and counting its own signals; a player holds one rung on each. There is always a first ladder, and the calls without a ladder name answer for it, as they did when RankUp had only one (BSP-Core's kit rank rules read `ladder(server)`).

### The first ladder

| Call | What it gives |
|---|---|
| `track(server)` | the LuckPerms track the first ladder follows |
| `ladder(server)` | every rung of the first ladder as `RankInfo(group, displayName, index, points, seasonal)`, first (lowest) first; empty until an admin set it up |
| `rank(server, group)` | the rung with that LuckPerms group, if on the first ladder |
| `rankOf(server, uuid)` | the rung RankUp last gave the player there |
| `hasRank(server, uuid, group)` | true when the player holds that rung or a higher one (the natural gate: "Warden or better") |
| `progressOf(server, uuid)` | `ProgressInfo(points, rank, next, pointsToNext, counts, bonus)`: the first ladder's points under the current settings, the rung held (null for none), the next rung (null at the top), the points still needed, the raw counts by signal key, the admin bonus |
| `pointsOf(server, uuid)` | the first ladder's points alone |

### Every ladder (version 2)

| Call | What it gives |
|---|---|
| `ladders(server)` | every ladder as `LadderInfo(name, track, counts, rungs, titlePriority)`, the first ladder first; `counts` is the signal keys it counts, empty for every signal |
| `ladderNamed(server, name)` | one ladder by name |
| `rank(server, ladder, group)` | the rung with that group on the named ladder |
| `rankOf(server, uuid, ladder)` | the rung RankUp last gave the player on the named ladder |
| `hasRank(server, uuid, ladder, group)` | that rung or a higher one, on the named ladder |
| `progressOf(server, uuid, ladder)` | the `ProgressInfo` on the named ladder: its points are the signals it counts plus the admin bonus; empty for no such ladder |

### Awards (version 2)

An award is a LuckPerms group on no ladder that RankUp adds to a player once their points from the award's signals (plus the admin bonus) reach its points. The group stays; a seasonal award is taken back at each season's end and can be earned again.

| Call | What it gives |
|---|---|
| `awards(server)` | every award as `AwardInfo(group, displayName, counts, points, seasonal)` |
| `hasAward(server, uuid, group)` | true when the player reached the award (RankUp's record; LuckPerms holds the group) |
| `awardsOf(server, uuid)` | the groups of every award the player reached |

### Points, signals, teams, storage

| Call | What it gives |
|---|---|
| `addPoints(server, uuid, points, reason)` | gives (or with a negative number takes) points, as an admin's bonus would; the player may rank up at once, never down |
| `signalKeys()` | the signal keys (`playtime` in minutes, `player_kills`, `mob_kills`, `deaths`, `totems_stolen`, `totems_protected`, `totems_lost`, `totems_placed`, `seasons_survived`, `score`, `coins_earned`, `claims_bought`) |
| `setTeamProvider(TeamProvider)` | registers a teams mod's answer to "are these two players on one team"; kills between teammates then count for nothing; null removes it. `hasTeamProvider()` says whether one is registered |
| `isShared()`, `serverId()` | whether this server shares counts and settings through MySQL, and its name there (null when storage is off) |

## Events

Package `com.mrgregles.bsp_rankup.api.event`, posted on `MinecraftForge.EVENT_BUS` on the server thread; notifications only, never cancelable.

| Event | When |
|---|---|
| `RankChangedEvent` | a player's rank changed on this server, on one ladder: `getPlayer()`, `getLadder()` (the ladder's name, version 2), `getFrom()` (null for none), `getTo()` (null when the player now holds none), `getCause()` (`EARNED` through points, `ADMIN` through `/rankup set` or the admin screen, `SEASON` at a season's end), `isPromotion()`. A jump over several rungs fires one event per rung, in order. The LuckPerms group has already changed when it fires. |

## Ranks in LuckPerms

RankUp moves players along each ladder's LuckPerms track (`track(server)` for the first, `LadderInfo.track()` for each); the rung's `group` is the LuckPerms group name, the same name BSP Region Guard's regions use for their rank gates. A mod that only needs "which groups is this player in" can keep asking LuckPerms; `rankOf` is for mods that want the ladder's own view (display names, order, thresholds) without LuckPerms.

Requests for changes to this api live in `BSP-RankUp/Claude Notes/RankUp - Requests for Other Mods.md` in the vault, or come the other way through your own mod's requests note.
