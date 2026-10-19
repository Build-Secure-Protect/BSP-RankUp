# BSP RankUp 0.1.0: first release

The first public release of BSP RankUp for Minecraft 1.20.1 (Forge 47.4.10 and Mohist), made for the Build Secure Protect modpack. Requires **BSP-Core 1.1.0** or newer and **LuckPerms** on the server. Works with **BSP-Region Guard** and **BSP-Teleport** when they are installed.

## Ranks from what players do
- **Signals:** minutes played, player kills, mob kills, deaths, Shatter Totems stolen, protected, lost and placed, seasons survived, claims bought (with Region Guard), and season score and pieces earned once BSP-Core offers them. Each is on or off and worth a number of points per so many units; the switch governs the points, the count is always kept.
- **The ladder:** a LuckPerms track. Each rung is a group, a display name, the points it needs, a seasonal flag, and what it gives. Pass a rung's points and you are moved onto its group, told, rewarded and announced. A jump over several rungs gives each one's rewards.
- **Never down by points alone:** falling points take no rank away; an admin can reset a player.
- **Anti-farming:** a repeat kill of the same player counts again only after a cooldown (ten minutes by default); kills of yourself never count; once a teams mod registers the team hook, kills between teammates count for nothing.
- **Rewards:** Shatter Coins into the hand, Shatter Pieces into the bank, items, a title (a LuckPerms prefix), console commands with `%player%`, and BSP-Core kits (delivered when the inventory has room). Rewards for a player who is away wait for their next login.
- **Announcements:** off, this server, or the whole network.

## Seasons
- Rungs marked seasonal fall back at the end of a BSP-Core season to the highest permanent rung below, and are then judged on points earned since. Permanent rungs stay. Rewards on a seasonal rung come again each season, or once.
- Players online are handled at once, everyone else on their next login.

## The admin screen
- `/rankup admin` for BSP-Core admins. Tabs: **LADDER** (rungs, their fields and rewards, an item browser over every item, FROM HAND), **SIGNALS** (on/off and worth), **PLAYERS** (search, a player's counts, SET RANK, ADD POINTS, RESET), **OPTIONS** (track, announcements, kill cooldown, title priority, seasonal rewards, MAKE TRACK IN LUCKPERMS, EXPORT and IMPORT, RESET EVERYONE behind a code).
- Every change saves at once and reaches every server. Destructive buttons want a second click.
- The screen fills the window (client setting `ui.scale`).

## The player screen
- `/rank`, the **U** key, or the **R** button in the inventory: the ladder with your rung lit, your rank and title, points (lifetime and this season), the bar to the next rung, what it gives, the fastest ways there, and every signal that counted. Refreshed every second.

## Commands
- `/rank`, `/rank text`, `/rank <player>` (admins).
- `/rankup admin|info|reload`, `set|add|reset`, `ladder ...`, `reward ...`, `signal ...`, `option ...`, `export|import`.

## A network of servers
- With `storage.mode = "mysql"`: counts written through as deltas (playtime adds up across servers), a player's row read on login, the settings shared, notices through Redis with the table as the safety net. No driver is bundled.

## For other mods
- API version 1: the ladder, a player's standing, points from other mods, the team hook, and `RankChangedEvent`.
