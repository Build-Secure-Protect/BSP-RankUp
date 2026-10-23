package com.mrgregles.bsp_rankup.signal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-farming for player kills (owner decision D13): a killer's kill of the same victim counts again only after
 * the cooldown. Kept in memory per server; a restart forgets it, which is harmless.
 */
public final class KillCooldowns {
    public static final long DEFAULT_MS = 10 * 60 * 1000L;

    private static long cooldownMs = DEFAULT_MS;
    private static final Map<UUID, Map<UUID, Long>> LAST = new HashMap<>();

    private KillCooldowns() {
    }

    /** Set from the settings (RU-4); 0 switches the rule off. */
    public static void setCooldownMs(long ms) {
        cooldownMs = Math.max(0, ms);
    }

    public static long cooldownMs() {
        return cooldownMs;
    }

    /** Records the kill and says whether it counts: true unless the same pair killed within the cooldown. */
    public static boolean record(UUID killer, UUID victim, long nowMs) {
        Map<UUID, Long> victims = LAST.computeIfAbsent(killer, k -> new HashMap<>());
        if (victims.size() >= 64 && cooldownMs > 0) {
            victims.values().removeIf(at -> nowMs - at >= cooldownMs); // kills past the cooldown no longer matter
        }
        Long last = victims.get(victim);
        victims.put(victim, nowMs);
        return cooldownMs == 0 || last == null || nowMs - last >= cooldownMs;
    }

    public static void clear() {
        LAST.clear();
    }

    /** How many victims are remembered for a killer (tests). */
    public static int tracked(UUID killer) {
        Map<UUID, Long> victims = LAST.get(killer);
        return victims == null ? 0 : victims.size();
    }

    public static void forget(UUID player) {
        LAST.remove(player);
        LAST.values().forEach(m -> m.remove(player));
    }
}
