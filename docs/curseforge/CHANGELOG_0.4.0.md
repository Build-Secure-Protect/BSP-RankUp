# BSP RankUp 0.4.0: prefix images, several ladders, awards

For Minecraft 1.20.1 (Forge 47.4.10; Mohist works too), made for the Build Secure Protect modpack. Requires **BSP-Core 1.4.5** or newer and **LuckPerms** (the Forge mod, or the Bukkit plugin on Mohist). Works with **BSP-Region Guard** (0.6.0 needs BSP-Core 1.4.6 or newer) and **BSP-Teleport**. **Update the server and every player's modpack together**: 0.4.0 does not talk to 0.3.0 or older.

## 0.4.0 (2026-10-27)

### Prefixes in chat, and images in place of them
![Prefix images in chat and the tab list](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/prefix_chat.png)

- **Prefixes before names.** RankUp shows each player's LuckPerms prefix (the one LuckPerms ranks highest) before their name in chat and the tab list, in its colours (`&` codes and `&#RRGGBB` hex). LuckPerms Forge stores prefixes but never showed them on a Forge server; now ranks and titles are visible. Hovering an image shows its text and the rank. A switch in OPTIONS turns this off if a chat mod should do it instead.
- **Prefix images.** Put PNG files in the server's `config/bsp_rankup/prefix_images/` (up to 256 x 64 pixels and 256 KB, not animated). Choose an image for any rank's prefix: a rung's or an award's title, or a LuckPerms group's own prefix like Admin. It is drawn one text line high in chat, the tab list and the rank screen. Players get the images when they join; nothing to install.
- **Shared across a network.** A chosen image is saved in the shared database, so every server shows it without copying the file.

![The PREFIXES tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_prefixes.png)

- **A PREFIXES tab** in `/rankup admin`: every prefix by where it comes from, its image at line size and twice that, a chat preview, and the folder's files with previews (a file that cannot be used says why). USE IMAGE, SHOW TEXT AGAIN, REFRESH.
- A TITLE reward on a rung or award shows its image in a small slot that opens PREFIXES on it.
- **The rank screen's title** shows each ladder's own title, as its image when it has one.
- Commands: `/rankup prefix list`, `prefix files`, `prefix image title|group <group> <file>`, `prefix clear title|group <group>`, `option showprefixes true|false`.

### Fixes and speed
- On a network, logging in now waits for your progress from the shared database before anything is decided, so rewards given on another server are never given twice and a season is never handled twice.
- A settings change (a new image included) always reaches the other servers; before, a server could miss one until the next change.
- Falling off a seasonal rung, or losing a seasonal award, at a season's end now takes its title away too (back to the title of the rung you keep, or none).
- Prefixes with a dot (`[Lv.5]`) read correctly.
- The rank and admin screens cost the server less: rank holders, settings and LuckPerms lookups are worked out once instead of on every refresh.
- Requires BSP-Core 1.4.5 or newer: its score signal, its teams and its database driver are used.

## 0.3.0 (2026-10-27): several ladders and awards

![The LADDER tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_ladder.png)

- **Several ladders**, each on its own LuckPerms track and counting its own signals (a Playtime ladder that counts minutes only, a Raider ladder that counts kills and totems). Players hold one rung on each. Admin bonus points count on every ladder.
- **Awards**: a LuckPerms group on no ladder, given once its points are reached (for example "Apple Picker" at 5000 pieces earned), with its own rewards; can reset each season.
- **A GROUPS tab** listing every LuckPerms group: rungs, awards, and the rest, where any group can be made an award.
- The rank screen has a chip per ladder and an AWARDS chip.
- `/rankup ladders ...` and `/rankup award ...`; `/rank` shows a line per ladder.
- LuckPerms edits for one player run one at a time, so two rank-ups at once never undo each other.

## 0.2.2 (2026-10-23)
- **The score signal**: BSP-Core's season points, each season's best added up.
- Both screens shrink to fit small windows instead of being cut off; many layout fixes; much less work per refresh.
- The shared database keeps progress through an outage and writes it when the database is back.
- Teammates' kills count for nothing (BSP-Core's teams).

## 0.2.1 (2026-10-21)
- The MySQL connection uses BSP-Core's bundled driver directly.
