#!/usr/bin/env python3
"""Writes BSP RankUp's language file. Edit this script, never the output.

  python3 tools/gen_lang.py
"""
import json
from pathlib import Path

LANG = Path(__file__).resolve().parent.parent / "src/main/resources/assets/bsp_rankup/lang/en_us.json"

KEYS = {
    "bsp_rankup.name": "BSP RankUp",
    # Rank-ups (%s = the rank's display name, or the player's name then the rank)
    "message.bsp_rankup.reached": "You are now %s!",
    "message.bsp_rankup.announce": "%s reached the rank %s!",
    # Rewards, one line each
    "message.bsp_rankup.reward.coins": "+ Shatter Coins worth %s Copper",
    "message.bsp_rankup.reward.pieces": "+ %s pieces into your bank",
    "message.bsp_rankup.reward.item": "+ %s x %s",
    "message.bsp_rankup.reward.title": "Your title is now %s",
    "message.bsp_rankup.reward.command": "+ a reward from the server",
    "message.bsp_rankup.reward.kept": "Rewards you earned while away:",
    # /rank
    "message.bsp_rankup.rank.header": "%s: %s (rank %s of %s), %s points",
    "message.bsp_rankup.rank.none": "%s has no rank yet, %s points",
    "message.bsp_rankup.rank.next": "Next: %s at %s points (%s more)",
    "message.bsp_rankup.rank.top": "Top of the ladder",
    "message.bsp_rankup.rank.signal": "  %s: %s (%s points)",
    "message.bsp_rankup.rank.bonus": "  bonus: %s points",
    "message.bsp_rankup.rank.pending": "  %s rewards wait for your next login",
}
# Signal names, one per Signal enum key
SIGNALS = {
    "playtime": "minutes played",
    "player_kills": "player kills",
    "mob_kills": "mob kills",
    "deaths": "deaths",
    "totems_stolen": "totems stolen",
    "totems_protected": "totems protected",
    "totems_lost": "totems lost",
    "totems_placed": "totems placed",
    "seasons_survived": "seasons survived",
    "score": "season score",
    "coins_earned": "pieces earned",
    "claims_bought": "claims bought",
}
for key, text in SIGNALS.items():
    KEYS["signal.bsp_rankup." + key] = text


def main():
    LANG.parent.mkdir(parents=True, exist_ok=True)
    LANG.write_text(json.dumps(KEYS, indent=2, ensure_ascii=False) + "\n")
    print(f"{LANG.relative_to(LANG.parents[5])}: {len(KEYS)} lines")


if __name__ == "__main__":
    main()
