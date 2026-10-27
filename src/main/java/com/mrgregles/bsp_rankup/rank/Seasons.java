package com.mrgregles.bsp_rankup.rank;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.api.event.RankChangedEvent;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What a season's end does to the ladders and awards (owner decisions D7, D15, D16, D18, D51): on each ladder a
 * player on a seasonal rung falls back to the highest permanent rung below it (or off the ladder when there is none),
 * each ladder's points at that moment become the base above which its seasonal rungs are judged from then on,
 * seasonal awards are taken back to be earned again on this season's points, and every player with progress has
 * survived one more season. Permanent rungs and other awards stay. Winners are BSP-Core's business.
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
     * Applies the last season's end to one player: the season bases (every ladder's points and every seasonal award's,
     * at this moment), the seasons-survived count, the fall from a seasonal rung on each ladder, and the seasonal
     * awards taken back so they can be earned again (D51). Ends with the ladders' usual check. Does nothing while a
     * move for this player is under way.
     */
    public static void apply(MinecraftServer server, UUID player) {
        ProgressLedger ledger = ProgressLedger.get(server);
        int season = ledger.lastEndedSeason();
        Settings settings = SettingsLedger.settings(server);
        Progress progress = ledger.peek(player);
        if (progress == null) {
            ledger.setSeason(player, season, 0, Map.of()); // nothing to fall from: just mark the season as seen
            RankEngine.check(server, player);
            server.execute(() -> ScoreSignal.check(server, player)); // the new season's peak starts at 0 (D48)
            return;
        }
        if (progress.season() >= season || !IN_FLIGHT.add(player)) {
            return;
        }
        // the rungs held now, before anything moves
        Map<String, Integer> held = new LinkedHashMap<>();
        for (Ladder l : settings.ladders()) {
            held.put(l.name(), l.indexOf(RankEngine.rankOn(settings, l, progress)));
        }
        Map<String, Long> bases = new LinkedHashMap<>();
        for (Ladder l : settings.ladders()) {
            if (!RankEngine.isFirst(settings, l)) {
                bases.put(l.name(), RankEngine.points(settings, l, progress));
            }
        }
        List<Award> takenBack = new ArrayList<>();
        for (Award a : settings.awards()) {
            if (a.seasonal()) {
                bases.put(Progress.awardKey(a.group()), RankEngine.points(settings, a, progress));
                if (progress.hasAward(a.group())) {
                    takenBack.add(a);
                }
            }
        }
        boolean active = !progress.counts().isEmpty() || progress.bonus() != 0 || progress.rank() != null || !progress.otherRanks().isEmpty();
        ledger.setSeason(player, season, RankEngine.points(settings, settings.first(), progress), bases);
        server.execute(() -> ScoreSignal.check(server, player)); // the new season's peak starts at 0 (D48)
        if (active) {
            ledger.add(player, Signal.SEASONS_SURVIVED, 1);
        }
        List<CompletableFuture<?>> moves = new ArrayList<>();
        for (Ladder ladder : settings.ladders()) {
            int current = held.get(ladder.name());
            if (current >= 0 && ladder.ranks().get(current).seasonal()) {
                moves.add(fall(server, player, season, settings, ladder, current));
            }
        }
        for (Award award : takenBack) {
            CompletableFuture<Boolean> remove = LuckPermsBridge.isPresent()
                    ? LuckPermsBridge.removeGroup(player, award.group())
                    : CompletableFuture.completedFuture(true);
            moves.add(remove.handle((ok, err) -> {
                server.execute(() -> {
                    if (err == null && ok != null && ok) {
                        ledger.setAward(player, award.group(), false, 0);
                    } else {
                        BSPRankUp.LOGGER.warn("Season {}: LuckPerms did not take the award '{}' back from {}", season, award.group(), Players.name(server, player));
                    }
                });
                return null;
            }));
        }
        // after every move: the usual check, which may give back what this season's points already reach
        CompletableFuture.allOf(moves.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) -> server.execute(() -> {
            IN_FLIGHT.remove(player);
            RankEngine.check(server, player);
        }));
    }

    /** Moves the player off a seasonal rung to the highest permanent rung below it on the same ladder (or off it). */
    private static CompletableFuture<?> fall(MinecraftServer server, UUID player, int season, Settings settings, Ladder ladder, int current) {
        int target = -1;
        for (int i = current - 1; i >= 0; i--) {
            if (!ladder.ranks().get(i).seasonal()) {
                target = i;
                break;
            }
        }
        int to = target;
        CompletableFuture<Boolean> move;
        if (!LuckPermsBridge.isPresent()) {
            move = CompletableFuture.completedFuture(true);
        } else if (to < 0) {
            move = LuckPermsBridge.clearTrack(player, ladder.track());
        } else {
            move = LuckPermsBridge.setGroup(player, ladder.track(), ladder.ranks().get(to).group());
        }
        return move.handle((ok, err) -> {
            server.execute(() -> {
                if (err != null || ok == null || !ok) {
                    BSPRankUp.LOGGER.warn("Season {}: LuckPerms did not move {} off the seasonal rung of {}; they keep it until the next login", season, Players.name(server, player), ladder.name());
                    return;
                }
                Rank reached = to < 0 ? null : ladder.ranks().get(to);
                RankEngine.setRung(server, settings, ladder, player, reached == null ? null : reached.group());
                ServerPlayer online = Players.online(server, player);
                if (online != null) {
                    online.sendSystemMessage(reached == null
                            ? Component.translatable("message.bsp_rankup.season.reset_off", season)
                            : Component.translatable("message.bsp_rankup.season.reset", season, reached.displayName()));
                }
                RankEngine.post(server, player, settings, ladder, current, to, RankChangedEvent.Cause.SEASON);
            });
            return null;
        });
    }
}
