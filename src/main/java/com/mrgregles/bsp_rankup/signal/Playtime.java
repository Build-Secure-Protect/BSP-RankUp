package com.mrgregles.bsp_rankup.signal;

import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RankUp's own playtime counter (owner decision D14): every online player gathers ticks; each full minute is
 * written to the ledger as one {@link Signal#PLAYTIME}. Players are taken from the worlds' player lists, so a
 * player counts wherever they are. The part of a minute left when a player logs out is dropped.
 */
public final class Playtime {
    public static final int TICKS_PER_MINUTE = 20 * 60;

    private static final Map<UUID, Integer> TICKS = new HashMap<>();

    private Playtime() {
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tick(event.getServer());
        }
    }

    /** One server tick: a tick for every online player, a minute to the ledger when one is full. */
    public static void tick(MinecraftServer server) {
        ProgressLedger ledger = null;
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) {
                    continue;
                }
                int ticks = TICKS.merge(player.getUUID(), 1, Integer::sum);
                if (ticks >= TICKS_PER_MINUTE) {
                    TICKS.put(player.getUUID(), ticks - TICKS_PER_MINUTE);
                    if (ledger == null) {
                        ledger = ProgressLedger.get(server);
                    }
                    ledger.add(player.getUUID(), Signal.PLAYTIME, 1);
                }
            }
        }
    }

    /** Ticks gathered toward the player's next minute. */
    public static int pendingTicks(UUID player) {
        return TICKS.getOrDefault(player, 0);
    }

    public static void forget(UUID player) {
        TICKS.remove(player);
    }

    public static void clear() {
        TICKS.clear();
    }
}
