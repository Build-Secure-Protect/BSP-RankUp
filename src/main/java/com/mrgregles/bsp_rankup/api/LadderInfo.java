package com.mrgregles.bsp_rankup.api;

import java.util.List;

/**
 * One ladder as other mods see it (owner decision D49): its name, the LuckPerms track it moves players along, the
 * signal keys it counts (empty: every signal), its rungs first (lowest) first, and the LuckPerms priority of its
 * titles. The first ladder of {@link RankUpApi#ladders} is the one {@link RankUpApi#ladder} gives.
 */
public record LadderInfo(String name, String track, List<String> counts, List<RankInfo> rungs, int titlePriority) {
}
