# BSP RankUp API for other mods

Package `com.mrgregles.bsp_rankup.api`, API version 1. Compile against the RankUp jar (`build/libs/bsp-rankup-<version>.jar` through `fg.deobf`, as the BSP mods do with each other); keep every call in one adapter class on your side. Only this package and `api.event` are meant for other mods; anything else in the jar may change without notice. `RankUpApi.API_VERSION` is a compile-time constant: compare `RankUpApi.apiVersion()` at run time to detect an older RankUp, and catch `LinkageError` around the first call for a RankUp without the class.

Everything is read on the server thread from what this server knows. On a network a player's row is read from the shared storage when they log in here, so for a player who is elsewhere the answer may be behind by what they did since.

## `RankUpApi`

| Call | What it gives |
|---|---|
| `track(server)` | the LuckPerms track the ladder follows |
| `ladder(server)` | every rung as `RankInfo(group, displayName, index, points, seasonal)`, first (lowest) first; empty until an admin set the ladder up |
| `rank(server, group)` | the rung with that LuckPerms group, if on the ladder |
| `rankOf(server, uuid)` | the rung RankUp last gave the player |
| `hasRank(server, uuid, group)` | true when the player holds that rung or a higher one (the natural gate: "Warden or better") |
| `progressOf(server, uuid)` | `ProgressInfo(points, rank, next, pointsToNext, counts, bonus)`: the points under the current settings, the rung held (null for none), the next rung (null at the top), the points still needed, the raw counts by signal key, the admin bonus |
| `pointsOf(server, uuid)` | the points alone |
| `addPoints(server, uuid, points, reason)` | gives (or with a negative number takes) points, as an admin's bonus would; the player may rank up at once, never down |
| `signalKeys()` | the signal keys (`playtime` in minutes, `player_kills`, `mob_kills`, `deaths`, `totems_stolen`, `totems_protected`, `totems_lost`, `totems_placed`, `seasons_survived`, `score`, `coins_earned`, `claims_bought`) |
| `setTeamProvider(TeamProvider)` | registers a teams mod's answer to "are these two players on one team"; kills between teammates then count for nothing; null removes it. `hasTeamProvider()` says whether one is registered |
| `isShared()`, `serverId()` | whether this server shares counts and settings through MySQL, and its name there (null when storage is off) |

## Events

Package `com.mrgregles.bsp_rankup.api.event`, posted on `MinecraftForge.EVENT_BUS` on the server thread; notifications only, never cancelable.

| Event | When |
|---|---|
| `RankChangedEvent` | a player's rank changed on this server: `getPlayer()`, `getFrom()` (null for none), `getTo()` (null when the player now holds none), `getCause()` (`EARNED` through points, `ADMIN` through `/rankup set` or the admin screen, `SEASON` at a season's end), `isPromotion()`. A jump over several rungs fires one event per rung, in order. The LuckPerms group has already changed when it fires. |

## Ranks in LuckPerms

RankUp moves players along a LuckPerms track (`track(server)`); the rung's `group` is the LuckPerms group name, the same name BSP Region Guard's regions use for their rank gates. A mod that only needs "which groups is this player in" can keep asking LuckPerms; `rankOf` is for mods that want the ladder's own view (display names, order, thresholds) without LuckPerms.

Requests for changes to this api live in `BSP-RankUp/Claude Notes/RankUp - Requests for Other Mods.md` in the vault, or come the other way through your own mod's requests note.
