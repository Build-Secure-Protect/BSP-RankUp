package com.mrgregles.bsp_rankup.rank;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.signal.Playtime;
import com.mrgregles.bsp_rankup.storage.Storage;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BSP-Core's season reset ({@code /bsp season reset}, owner decision SR12): every player starts the new season at the
 * bottom of every ladder. What each player keeps is {@link Progress#startedOver}: the seasons survived (one more),
 * the permanent awards and the awards an admin took; everything else goes, the seasonal awards with it, and every
 * rung rewards again when it is reached again.
 *
 * <p>On every server that hears it ({@code SeasonEvent.Ended} with {@code isSeasonReset()}), this server's copies are
 * started over at once and the shared storage forgets the changes it had not written, so nothing from before the
 * reset is written or read back from here; a shared row read later that is still from before the reset is started
 * over as it is read. Only where the reset was typed ({@code isOrigin()}) are the shared rows rewritten and LuckPerms
 * moved: every player the shared rows know, offline players too, plus this server's own copies. LuckPerms is shared by
 * the network, so once is enough; any server puts a player right at their next login there ({@link #atLogin}) if a
 * move failed or never reached them. Groups off the ladders are never touched.
 */
public final class SeasonReset {
    /** One player's progress before and after the reset. */
    public record Restart(UUID player, Progress before, Progress after) {
    }

    /** Counts season resets heard in this run, so a rank-up or award LuckPerms was still answering for is dropped. */
    private static volatile int generation;
    /** This server's copies at the reset, kept until the shared pass says which players the rows did not know. */
    private static final Map<Integer, Map<UUID, Restart>> LOCAL_AT_RESET = new HashMap<>();

    private SeasonReset() {
    }

    /** Changes with every season reset heard (in-memory). */
    public static int generation() {
        return generation;
    }

    /** The groups of these settings' seasonal awards. */
    public static Set<String> seasonalAwards(Settings settings) {
        Set<String> out = new LinkedHashSet<>();
        for (Award a : settings.awards()) {
            if (a.seasonal()) {
                out.add(a.group());
            }
        }
        return out;
    }

    /**
     * BSP-Core's season reset reached this server: season {@code endedSeason} ended with it. {@code origin} says it was
     * typed here. A reset already heard does nothing.
     */
    public static void apply(MinecraftServer server, int endedSeason, boolean origin) {
        apply(server, endedSeason, origin, Storage.active());
    }

    /** {@link #apply(MinecraftServer, int, boolean)} with a given shared storage (null: none), for tests. */
    public static void apply(MinecraftServer server, int endedSeason, boolean origin, @Nullable Storage storage) {
        ProgressLedger ledger = ProgressLedger.get(server);
        if (endedSeason <= ledger.lastResetSeason()) {
            BSPRankUp.LOGGER.info("Season reset (season {}) heard again: already done here", endedSeason);
            return;
        }
        Settings settings = SettingsLedger.settings(server);
        Set<String> seasonal = seasonalAwards(settings);
        generation++;
        Playtime.clear(); // minutes begun before the reset count for nothing
        boolean shared = storage != null && origin;
        // LuckPerms is shared by the network: only the server the reset was typed on moves it. Every other server puts a
        // player right at their next login there (atLogin), which also covers an edit that failed or was lost.
        boolean moves = origin;
        if (shared) {
            ledger.setPendingSharedReset(endedSeason); // first, so a failure below still leaves the pass to be run
        }
        if (storage != null) {
            storage.startOver(endedSeason); // nothing from before the reset is written or read back from here
        }
        ledger.setLastEndedSeason(endedSeason); // a season's end is not applied on top at the next login
        ledger.setLastResetSeason(endedSeason);
        Map<UUID, Restart> local = new LinkedHashMap<>();
        for (Map.Entry<UUID, Progress> e : ledger.all().entrySet()) {
            try {
                Progress after = e.getValue().startedOver(endedSeason, seasonal);
                local.put(e.getKey(), new Restart(e.getKey(), e.getValue(), after));
                ledger.startOver(e.getKey(), after);
            } catch (RuntimeException ex) {
                BSPRankUp.LOGGER.error("Season reset: {}'s RankUp progress could not be started over here", e.getKey(), ex);
            }
        }
        BSPRankUp.LOGGER.info("Season reset (season {} ended{}): {} players' RankUp progress started over here{}", endedSeason, origin ? ", typed here" : "",
                local.size(), shared ? "; the shared rows and LuckPerms follow" : moves ? "; LuckPerms follows" : "; the server it was typed on moves the shared rows and LuckPerms");
        if (shared) {
            synchronized (LOCAL_AT_RESET) {
                LOCAL_AT_RESET.clear(); // an earlier reset's copies, if its pass never ended: this reset's pass covers them
                LOCAL_AT_RESET.put(endedSeason, local);
            }
            storage.startOverShared(endedSeason, settings);
        } else if (moves) {
            moveLuckPerms(server, endedSeason, settings, local.values(), "this server's players");
        }
    }

    /**
     * The shared pass wrote one batch of rows (worker thread's result, on the server thread): their players are
     * moved in LuckPerms from what the rows held.
     */
    public static void sharedWritten(MinecraftServer server, int endedSeason, Settings settings, List<Restart> restarts) {
        moveLuckPerms(server, endedSeason, settings, restarts, "the shared rows' players");
    }

    /**
     * The shared pass is over: every row is from after the reset. Players this server had copies of that no row
     * knew are moved in LuckPerms from those copies.
     */
    public static void sharedDone(MinecraftServer server, int endedSeason, Settings settings, Set<UUID> inRows, int started) {
        ProgressLedger ledger = ProgressLedger.get(server);
        if (ledger.pendingSharedReset() == endedSeason) {
            ledger.setPendingSharedReset(0);
        }
        Map<UUID, Restart> local;
        synchronized (LOCAL_AT_RESET) {
            local = LOCAL_AT_RESET.remove(endedSeason);
        }
        List<Restart> rest = new ArrayList<>();
        if (local != null) {
            local.forEach((id, r) -> {
                if (!inRows.contains(id)) {
                    rest.add(r);
                }
            });
        }
        BSPRankUp.LOGGER.info("Season reset (season {}): {} shared rows started over ({} already were){}", endedSeason, started, inRows.size() - started,
                rest.isEmpty() ? "" : "; " + rest.size() + " players known only here follow in LuckPerms");
        if (!rest.isEmpty()) {
            moveLuckPerms(server, endedSeason, settings, rest, "players known only here");
        }
    }

    /**
     * Moves each player in LuckPerms as the reset says ({@link #plan}): one edit each, all queued at once, so an edit
     * this server makes for the same player later (a login's rank-up) comes after it.
     */
    public static void moveLuckPerms(MinecraftServer server, int endedSeason, Settings settings, Collection<Restart> restarts, String who) {
        if (restarts.isEmpty()) {
            return;
        }
        if (!LuckPermsBridge.isPresent()) {
            BSPRankUp.LOGGER.info("Season reset (season {}): no LuckPerms here, so no group moved for {} ({})", endedSeason, who, restarts.size());
            return;
        }
        AtomicInteger failed = new AtomicInteger();
        List<CompletableFuture<?>> edits = new ArrayList<>(restarts.size());
        for (Restart r : restarts) {
            edits.add(LuckPermsBridge.startOver(r.player(), plan(settings, r.after())).handle((ok, err) -> {
                if (err != null || ok == null || !ok) {
                    failed.incrementAndGet();
                }
                return null;
            }));
        }
        CompletableFuture.allOf(edits.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) -> server.execute(() -> {
            if (failed.get() == 0) {
                BSPRankUp.LOGGER.info("Season reset (season {}): LuckPerms moved {} ({})", endedSeason, who, restarts.size());
            } else {
                BSPRankUp.LOGGER.warn("Season reset (season {}): LuckPerms did not move {} of {} ({}); each is put right on their ladders at their next login", endedSeason,
                        failed.get(), restarts.size(), who);
            }
        }));
    }

    /**
     * Puts a player right in LuckPerms at their login on any server, when the ledger shows they have not been checked
     * since the last season reset (no rung on any ladder, nothing rewarded yet): the same edit the server it was
     * typed on makes, queued before this login's rank-up, so a move that failed, was lost to a restart, or was never
     * made (this server only heard of the reset) is made now. Nothing happens for anyone else.
     */
    public static void atLogin(MinecraftServer server, UUID player) {
        ProgressLedger ledger = ProgressLedger.get(server);
        int season = ledger.lastResetSeason();
        Progress p = ledger.peek(player);
        if (season <= 0 || p == null || p.season() != season || p.rank() != null || !p.otherRanks().isEmpty() || !p.rewarded().isEmpty()
                || !LuckPermsBridge.isPresent()) {
            return;
        }
        LuckPermsBridge.startOver(player, plan(SettingsLedger.settings(server), p));
    }

    /**
     * What the reset does to a player in LuckPerms: on each ladder, every rung taken off but the one the kept points
     * reach (none, usually, or the first rung), which is put on with its title, the one the ladder's climb would
     * give; every seasonal award the player does not hold now taken off (all of them, just after a reset; a change
     * the ledger never wrote may have given one), and their titles with them when one shows. The rung's rewards
     * still come when the player's next check climbs to it, since the ledger holds no rung for them.
     */
    public static LuckPermsBridge.StartOver plan(Settings settings, Progress after) {
        Set<String> off = new LinkedHashSet<>();
        List<String> on = new ArrayList<>();
        Map<Integer, Optional<String>> titles = new LinkedHashMap<>();
        for (Ladder ladder : settings.ladders()) {
            int target = RankEngine.rankIndex(ladder, RankEngine.points(settings, ladder, after), RankEngine.seasonPoints(settings, ladder, after));
            for (int i = 0; i < ladder.ranks().size(); i++) {
                Rank rank = ladder.ranks().get(i);
                if (i == target) {
                    on.add(rank.group());
                } else {
                    off.add(rank.group());
                }
            }
            titles.put(ladder.titlePriority(), Optional.ofNullable(Seasons.titleAt(ladder, target)));
        }
        Set<String> titlesGone = new LinkedHashSet<>();
        for (Award award : settings.awards()) {
            if (award.seasonal() && !after.hasAward(award.group()) && settings.ladderOf(award.group()) == null) {
                off.add(award.group());
                String title = PrefixDisplay.titleOf(award.rewards());
                if (title != null) {
                    titlesGone.add(title);
                }
            }
        }
        off.removeAll(on);
        return new LuckPermsBridge.StartOver(off, on, titles, RankEngine.awardTitlePriority(settings), titlesGone, latestAwardTitle(settings, after));
    }

    /** The title of the award reached most recently among those kept, or null. */
    @Nullable
    private static String latestAwardTitle(Settings settings, Progress after) {
        String next = null;
        long latest = Long.MIN_VALUE;
        Map<String, Long> held = after.awards();
        for (Award a : settings.awards()) {
            Long at = held.get(a.group());
            String t = PrefixDisplay.titleOf(a.rewards());
            if (at != null && t != null && at > latest) {
                latest = at;
                next = t;
            }
        }
        return next;
    }
}
