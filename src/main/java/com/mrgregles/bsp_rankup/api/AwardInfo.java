package com.mrgregles.bsp_rankup.api;

import java.util.List;

/**
 * An award as other mods see it (owner decision D50): a LuckPerms group on no ladder that RankUp adds to a player
 * once their points from the counted signals (empty: every signal) reach {@code points}; a seasonal one is taken
 * back at each season's end.
 */
public record AwardInfo(String group, String displayName, List<String> counts, long points, boolean seasonal) {
}
