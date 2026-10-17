# BSP RankUp

A Forge mod for Minecraft 1.20.1 and the Build Secure Protect (BSP) modpack: automatic LuckPerms ranks, raised by what players do on the server (playtime, kills, Shatter Totems stolen and protected, and more).

Requires [BSP Core](https://github.com/Build-Secure-Protect/BSP-Core). BSP Region Guard and BSP Teleport are optional. LuckPerms must be installed on the server for ranks to change.

## Build

BSP Core must be built first (its jar is read from `../BSP-Core/build/libs`). Then:

```
./gradlew build
```

The jar lands in `build/libs/`. `./gradlew runGameTestServer` runs the headless tests.

## Docs

- `docs/API.md`: what other mods may call.
- `docs/TESTING.md`: the in-game checklist.
- `CHANGELOG.md`.

MIT licensed.
