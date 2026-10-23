package com.mrgregles.bsp_rankup.signal;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The team hook (owner decision D13): the owner's own teams mod registers an answer to "are these two players on
 * one team", and kills between teammates then do not count. Until something is registered, nobody is on a team.
 * The api package (RU-7) exposes {@link #setProvider}; this holder is the one place the signals ask.
 */
public final class Teams {
    @Nullable
    private static volatile BiPredicate<UUID, UUID> provider;

    private Teams() {
    }

    public static void setProvider(@Nullable BiPredicate<UUID, UUID> sameTeam) {
        provider = sameTeam;
    }

    public static boolean isRegistered() {
        return provider != null;
    }

    /** The registered answer, or null (tests put back what was there). */
    @Nullable
    public static BiPredicate<UUID, UUID> provider() {
        return provider;
    }

    public static boolean sameTeam(UUID a, UUID b) {
        BiPredicate<UUID, UUID> p = provider;
        return p != null && p.test(a, b);
    }
}
