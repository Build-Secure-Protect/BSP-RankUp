# BSP RankUp 0.5.0: suffixes, every ladder on PLAYERS, awards by hand

For Minecraft 1.20.1 (Forge 47.4.10; Mohist works too), made for the Build Secure Protect modpack. Requires **BSP-Core 1.4.5** or newer and **LuckPerms** (the Forge mod, or the Bukkit plugin on Mohist). Works with **BSP-Region Guard** (0.6.0 needs BSP-Core 1.4.6 or newer) and **BSP-Teleport**. **Update the server and every player's modpack together**: 0.5.0 does not talk to 0.4.0 or older. On a network, update every server at once.

## 0.5.0 (2026-10-27)

### The whole name line: prefixes, suffixes and their pictures
![Staff tag, ladder rank, name and achievement icons in chat and the tab list](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/prefix_chat.png)

- **Suffixes after the name.** RankUp now shows each player's LuckPerms suffix after their name in chat and the tab list, as a picture when you give it one. On BSP these are the achievements: Champion, Totem Reaver, Titan Slayer, Headhunter, Bulwark, Founder and Keeper.
- **Every tag LuckPerms puts together.** When LuckPerms is set to show several tags (a staff tag, then the ladder rank, then every achievement), RankUp shows all of them and draws each one it knows as its picture: `[Admin] [Warden] name ✦ ✦`. Text it doesn't know stays as text. Pictures side by side sit close together, with a normal space either side of the name.
- **Hover notes.** Give any prefix or suffix a short note (up to 120 characters), such as how an achievement is earned. Players see it when they hover the tag in chat.
- **Art for every BSP rank** in the repository's `docs/prefix_images/`: banners for the twelve ladder ranks and the staff tags, icons for the achievements, and a `commands.txt` that assigns them all and fills in their notes.

![The PREFIXES tab with the suffixes and a hover note](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_prefixes.png)

- The PREFIXES tab gains an "AFTER THE NAME - SUFFIXES" section and a HOVER NOTE field, and its chat preview shows suffixes after the name. Commands: `/rankup prefix image|clear suffix <group> <file>` and `/rankup prefix note title|group|suffix <group> [text]`.

### See what each ladder counts
![The SIGNALS tab with a column per ladder and award](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_signals.png)

- **PROGRESS FROM** on the LADDER tab: a chip for every signal the ladder counts and what it is worth there ("minutes played 1 pt / minute"). The same chips show on an award on GROUPS. When a ladder counts one signal, a rung's points are translated ("= 120 minutes").
- **The SIGNALS tab has a column for every ladder and award**: a tick where that signal counts, and one click switches it. Hover a signal to see its command name and where it comes from.
- A ladder or award always counts at least one signal: the screen won't remove the last one by accident (that used to make it count every signal and could promote many players at once). Type `all` to count every signal on purpose.

### PLAYERS: every ladder, and awards by hand
![The PLAYERS tab: every ladder, the awards, GIVE IT](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-RankUp/main/docs/curseforge/images/admin_players.png)

- **Every ladder at once.** A selected player's rank on each ladder (with its picture), how far up it is, their points and this season's points, a bar to the next rung, and **SET** on each ladder to move them on that one.
- **Their awards** as chips: reached, or the points so far out of the points needed.
- **Give and take awards.** Click an award, then **GIVE IT** or **TAKE IT**. Giving adds the LuckPerms group without rewards or an announcement. Taking removes it and keeps it off: RankUp won't give it back, even when their points reach it or a new season starts, until an admin gives it again. The player is told either way. Commands: `/rankup award give <player> <award>` and `/rankup award take <player> <award>`.
- Each count is marked with the ladders that count it, and ADD POINTS and RESET say they apply to every ladder.

### Rank-ups while you're away, and on a network
- **Nothing is announced for a player who isn't there.** Before, a server could start up announcing "Mrgregles reached the rank Settler!" with nobody online.
- **"While you were away..."** A rank or award you earned while offline (an admin's points, a totem of yours lost) is told to you at your next login, one line each.
- **On a network, a rank-up reaches you on the server you're playing on.** If you rank up on another server (an admin there gave you points, say), the server you're on tells you, announces it, and hands over your rewards straight away. It also can no longer give you the same rank and rewards a second time.

### The rank screen with many ladders
- Ladder chips that don't fit on one row go onto a second and third row, with AWARDS always last. Before, past about 11 ladders the rest were cut off, AWARDS included. The admin LADDER tab does the same.

### Fixes
- A title and a group's prefix with the same text show the group's picture when only the group has one.
- Name lines are redrawn when a LuckPerms group's prefix changes, however the change was noticed.
- A hover note can be removed after its group is deleted; choosing another prefix while typing a note no longer saves it on the wrong one.
- PLAYERS: "online" is always right, SET's rung list has no gap, and long ladder or group names no longer break the admin screen.
- The SIGNALS page buttons no longer cover a column's name.

## 0.4.0 (2026-10-27): prefix images
- RankUp shows each player's LuckPerms prefix before their name in chat and the tab list, and any prefix can be drawn as a picture (PNGs in `config/bsp_rankup/prefix_images/`), shared across a network. A PREFIXES tab manages them.
- On a network, logging in waits for your progress from the shared database, so rewards and seasons are never handled twice.

## 0.3.0 (2026-10-27): several ladders and awards
- Several ladders, each on its own LuckPerms track counting its own signals; awards (single groups given at a points total); a GROUPS tab; a chip per ladder on the rank screen.

## 0.2.2 (2026-10-23)
- The score signal (BSP-Core's season points), screens that fit small windows, progress kept through a database outage, teammates' kills worth nothing.
