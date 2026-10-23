package com.mrgregles.bsp_rankup.rank;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.api.event.RankChangedEvent;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.signal.ScoreSignal;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What a season's end does to the ladder (owner decisions D7, D15, D16, D18): a player on a seasonal rung falls
 * back to the highest permanent rung below it (or off the ladder when there is none), their points at that moment
 * become the base above which seasonal rungs are judged from then on, and every player with progress has survived
 * one more season. Permanent rungs stay. Winners are BSP-Core's business.
 *
 * <p>Players online here are handled when BSP-Core's {@code SeasonEvent.Ended} fires; everyone else on their next
 * login (the ledger remembers the last season that ended), so on a network each player is handled once, by the
 * server they are on.
 */
public final class Seasons {
    private static final Set<UUID> IN_FLIGHT = new HashSet<>();

    private Seasons() {
    }

    /** BSP-Core says a season ended on this server. */
    public static void endSeason(MinecraftServer server, int endedNumber) {
        ProgressLedger ledger = ProgressLedger.get(server);
        if (endedNumber <= ledger.lastEndedSeason()) {
            return;
        }
        ledger.setLastEndedSeason(endedNumber);
        List<UUID> online = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                online.add(p.getUUID());
            }
        }
        BSPRankUp.LOGGER.info("Season {} ended: seasonal ranks fall back for {} players online, the rest on their next login", endedNumber, online.size());
        for (UUID player : online) {
            apply(server, player);
        }
    }

    /** True when a season ended since this player was last handled. */
    public static boolean due(MinecraftServer server, Progress progress) {
        return progress.season() < ProgressLedger.get(server).lastEndedSeason();
    }

    /**
     * Applies the last season's end to one player: the season base, the seasons-survived count, and the fall from a
     * seasonal rung. Ends with the ladder's usual check. Does nothing while a move for this player is under way.
     */
    public static void apply(MinecraftServer server, UUID player) {
        ProgressLedger ledger = ProgressLedger.get(server);
        int season = ledger.lastEndedSeason();
        Progress progress = ledger.peek(player);
        if (progress == null) {
            ledger.setSeason(player, season, 0); // nothing to fall from: just mark the season as seen
            RankEngine.check(server, player);
            server.execute(() -> ScoreSignal.check(server, player)); // the new season's peak starts at 0 (D48)
            return;
        }
        if (progress.season() >= season || !IN_FLIGHT.add(player)) {
            return;
        }
        Settings settings = SettingsLedger.settings(server);
        long points = RankEngine.points(settings, progress);
        ledger.setSeason(player, season, points);
        server.execute(() -> ScoreSignal.check(server, player)); // the new season's peak starts at 0 (D48)
        if (!progress.counts().isEmpty() || progress.bonus() != 0 || progress.rank() != null) {
            ledger.add(player, Signal.SEASONS_SURVIVED, 1);
        }
        int current = settings.indexOf(progress.rank());
        if (current < 0 || !settings.ranks().get(current).seasonal()) {
            IN_FLIGHT.remove(player);
            RankEngine.check(server, player);
            return;
        }
        int target = -1;
        for (int i = current - 1; i >= 0; i--) {
            if (!settings.ranks().get(i).seasonal()) {
                target = i;
                break;
            }
        }
        int to = target;
        CompletableFuture<Boolean> move;
        if (!LuckPermsBridge.isPresent()) {
            move = CompletableFuture.completedFuture(true);
        } else if (to < 0) {
            move = LuckPermsBridge.clearTrack(player, settings.track());
        } else {
            move = LuckPermsBridge.setGroup(player, settings.track(), settings.ranks().get(to).group());
        }
        move.whenComplete((ok, err) -> server.execute(() -> {
            IN_FLIGHT.remove(player);
            if (err != null || ok == null || !ok) {
                BSPRankUp.LOGGER.warn("Season {}: LuckPerms did not move {} off the seasonal rung; they keep it until the next login", season, Players.name(server, player));
                return;
            }
            Rank reached = to < 0 ? null : settings.ranks().get(to);
            ledger.setRank(player, reached == null ? null : reached.group(), System.currentTimeMillis());
            ServerPlayer online = Players.online(server, player);
            if (online != null) {
                online.sendSystemMessage(reached == null
                        ? Component.translatable("message.bsp_rankup.season.reset_off", season)
                        : Component.translatable("message.bsp_rankup.season.reset", season, reached.displayName()));
            }
            RankEngine.post(server, player, settings, current, to, RankChangedEvent.Cause.SEASON);
            RankEngine.check(server, player);
        }));
    }
}
