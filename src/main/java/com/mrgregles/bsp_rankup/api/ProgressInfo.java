package com.mrgregles.bsp_rankup.api;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Where a player stands: their points, the rung they hold (null for none), the next rung (null at the top or with no
 * ladder), the points still needed for it, and their raw counts by signal key (minutes played, kills, totems ...).
 */
public record ProgressInfo(long points, @Nullable RankInfo rank, @Nullable RankInfo next, long pointsToNext, Map<String, Long> counts, long bonus) {
}
