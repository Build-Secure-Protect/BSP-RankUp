# BSP RankUp 0.2.0: kit rewards

For Minecraft 1.20.1 (Forge 47.4.10 and Mohist), made for the Build Secure Protect modpack. Requires **BSP-Core 1.1.0** or newer (**1.3.0** for kit rewards) and **LuckPerms**. Works with **BSP-Region Guard** and **BSP-Teleport**.

## Since 0.1.0
- **Kit rewards.** A sixth reward kind, KIT: a rung can give a BSP-Core kit by name. BSP-Core owes the kit to the player and delivers it when their inventory has room, at once or at their next login (or from the WAITING card in their kit browser), so a rank-up never loses a kit to a full inventory. In the admin screen, **+ KIT** shows BSP-Core's kits as chips; `/rankup reward add <rank> kit <id>` does the same with tab completion. A kit BSP-Core does not know is refused. On a BSP-Core older than 1.3.0 the kind is refused and the other five work as before.
- **Built against BSP-Core 1.3.0 and BSP-Region Guard 0.2.0.** No change in what RankUp needs: BSP-Core 1.1.0 or newer still runs it (without kit rewards), Region Guard 0.1.0 or newer still gives the claims-bought signal.
- The players' and the admin's screens show kit rewards by the kit's name.

## 0.1.0 (first release, 2026-10-18)
- Automatic LuckPerms ranks from what players do: playtime, player and mob kills, deaths, Shatter Totems stolen, protected, lost and placed, seasons survived, claims bought (Region Guard); the ladder as a LuckPerms track with points, seasonal rungs and rewards (coins, pieces, items, titles, commands); the anti-farming rules; seasons; the admin screen and `/rankup`; the player screen (`/rank`, the U key, the inventory button); shared MySQL and Redis storage for a network; the api with `RankChangedEvent` and the team hook.
