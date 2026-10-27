package com.mrgregles.bsp_rankup.rank;

import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * How many players hold each rung of every ladder and each award (D42), for the rank and admin views. Every open rank
 * screen asks once a second and every open admin screen every two, and counting walks every player with progress, so
 * the count is kept until a change that can move it (a rank, an award, a season, a load or a reset) or new settings.
 */
public final class Holders {
    private record Counted(Settings settings, Map<String, Integer> counts) {
    }

    private static final Map<MinecraftServer, Counted> COUNTED = new WeakHashMap<>();

    private Holders() {
    }

    public static void init() {
        ProgressLedger.onChange(c -> {
            switch (c.kind()) {
                case RANK, AWARD, SEASON, LOAD, RESET -> {
                    synchronized (COUNTED) {
                        COUNTED.clear();
                    }
                }
                default -> {
                    // counts, bonus, waiting rewards: nobody's rung changes
                }
            }
        });
    }

    /** Holders by group: every rung and award listed (0 when nobody holds it), then any group a player holds that the settings no longer have. */
    public static Map<String, Integer> of(MinecraftServer server) {
        Settings settings = SettingsLedger.settings(server);
        synchronized (COUNTED) {
            Counted c = COUNTED.get(server);
            if (c != null && c.settings() == settings) {
                return c.counts();
            }
        }
        Map<String, Integer> holders = new LinkedHashMap<>();
        for (Ladder l : settings.ladders()) {
            for (Rank r : l.ranks()) {
                holders.put(r.group(), 0);
            }
        }
        for (Award a : settings.awards()) {
            holders.put(a.group(), 0);
        }
        ProgressLedger.get(server).forEach((id, p) -> {
            if (p.rank() != null) {
                holders.merge(p.rank(), 1, Integer::sum);
            }
            p.otherRanks().values().forEach(held -> holders.merge(held.group(), 1, Integer::sum));
            p.awards().keySet().forEach(g -> holders.merge(g, 1, Integer::sum));
        });
        Map<String, Integer> counts = Collections.unmodifiableMap(holders);
        synchronized (COUNTED) {
            COUNTED.put(server, new Counted(settings, counts));
        }
        return counts;
    }
}
