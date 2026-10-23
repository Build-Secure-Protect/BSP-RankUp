package com.mrgregles.bsp_rankup.signal;

import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.Seasons;
import com.mrgregles.bsp_rankup.storage.Storage;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.UUID;

/**
 * BSP-Core's score as a signal (owner decision D48, BSP-Core 1.4.5's {@code BspScores}): each season's peak of a
 * player's BSP-Core points, added up. The count rises only when their points pass their highest this season, by
 * the amount they pass it; the peak starts again at each season's end. Taking a totem down and putting it back
 * gains nothing, and a stolen totem takes nothing already counted.
 *
 * <p>BSP-Core's board covers the whole network and its change event comes on every backend, so only the server
 * the player is on counts it. Their points are asked again on login (once the shared row has arrived, when there
 * is one) and right after a season's end is applied to them.
 */
public final class ScoreSignal {
    private ScoreSignal() {
    }

    public static void init() {
        ProgressLedger.onChange(c -> {
            if (c.kind() == ProgressLedger.Kind.LOAD && c.server() != null) {
                MinecraftServer server = c.server();
                server.execute(() -> check(server, c.player())); // after the shared row is in place
            }
        });
    }

    /** On login without shared storage; with it, the row's arrival asks instead (see {@link #init}). */
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (Storage.active() == null && event.getEntity() instanceof ServerPlayer player) {
            check(player.server, player.getUUID());
        }
    }

    /** BSP-Core says the player's points are now {@code points}; counted where the player is online. */
    public static void changed(MinecraftServer server, UUID player, long points) {
        if (Players.isOnline(server, player)) {
            count(server, player, points);
        }
    }

    /** Asks BSP-Core for the player's points now and counts what passes their peak (nothing without BSP-Core scores). */
    public static void check(MinecraftServer server, UUID player) {
        BspCore.scorePoints(server, player).ifPresent(points -> changed(server, player, points));
    }

    /**
     * The rule itself, with plain data: adds what {@code points} passes the player's peak this season and raises the
     * peak; returns what was added. When a season's end is still to be applied to the player, it is applied first
     * (for a player here) and nothing counts now: the season's end starts the peak at 0 and asks again, so the same
     * points are never counted under both seasons' peaks.
     */
    public static long count(MinecraftServer server, UUID player, long points) {
        ProgressLedger ledger = ProgressLedger.get(server);
        Progress p = ledger.peek(player);
        if (Seasons.due(server, p == null ? new Progress() : p)) {
            if (Players.isOnline(server, player)) {
                Seasons.apply(server, player);
            }
            return 0;
        }
        long peak = p == null ? 0 : p.scorePeak();
        if (points <= peak) {
            return 0;
        }
        ledger.setScorePeak(player, points);
        ledger.add(player, Signal.SCORE, points - peak);
        return points - peak;
    }
}
