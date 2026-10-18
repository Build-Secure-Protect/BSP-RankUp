package com.mrgregles.bsp_rankup.api;

import java.util.UUID;

/**
 * Answers whether two players are on one team. A teams mod registers one through
 * {@link RankUpApi#setTeamProvider}; from then on kills between teammates count for nothing (owner decision D13).
 */
@FunctionalInterface
public interface TeamProvider {
    boolean sameTeam(UUID a, UUID b);
}
