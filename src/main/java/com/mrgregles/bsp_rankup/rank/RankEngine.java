package com.mrgregles.bsp_rankup.rank;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.api.RankInfo;
import com.mrgregles.bsp_rankup.api.event.RankChangedEvent;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The ladder: counts become points (owner decision D5), points become the rank a player has earned, and when
 * that is above the rank RankUp last gave them the player is moved there through LuckPerms, given every passed
 * rung's rewards and announced (D9, D10). Never downward by itself (D6).
 *
 * <p>Runs on every ledger change and on login. With LuckPerms present the group change must succeed before the
 * rank-up counts; without LuckPerms (a dev server) the rank is recorded in the ledger alone, so the ladder still
 * works for testing.
 */
public final class RankEngine {
    /** A rank-up that happened: the rung left (null for the first) and the rung reached. */
    public record RankUp(MinecraftServer server, UUID player, @Nullable Rank from, Rank to) {
    }

    private static final List<Consumer<RankUp>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Set<UUID> IN_FLIGHT = new HashSet<>();
    private static long lastWarnMs;

    private RankEngine() {
    }

    public static void init() {
        ProgressLedger.onChange(RankEngine::onChange);
        SettingsLedger.onChange(RankEngine::onSettingsChanged);
    }

    public static void onRankUp(Consumer<RankUp> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<RankUp> listener) {
        LISTENERS.remove(listener);
    }

    // ------------------------------------------------------------------ arithmetic

    /** The points a player's progress is worth under the settings: every signal that is on, plus the admin bonus. */
    public static long points(Settings settings, Progress progress) {
        long total = progress.bonus();
        for (Signal s : Signal.values()) {
            total += settings.signal(s).pointsFor(progress.count(s));
        }
        return total;
    }

    /**
     * The highest rung these points reach, walking the ladder from the first rung and stopping at the first one
     * not reached (so a rung with a lower threshold than the one below it is never skipped to). -1 for none.
     * Permanent rungs are judged on the lifetime points, seasonal rungs on the points earned this season.
     */
    public static int rankIndex(Settings settings, long points, long seasonPoints) {
        int best = -1;
        List<Rank> ranks = settings.ranks();
        for (int i = 0; i < ranks.size(); i++) {
            Rank r = ranks.get(i);
            if ((r.seasonal() ? seasonPoints : points) >= r.points()) {
                best = i;
            } else {
                break;
            }
        }
        return best;
    }

    /** The rung lifetime points reach when no season has ended (season points equal lifetime points). */
    public static int rankIndex(Settings settings, long points) {
        return rankIndex(settings, points, points);
    }

    /** The points earned since the last season ended: lifetime points above the base recorded then. */
    public static long seasonPoints(Settings settings, Progress progress) {
        return Math.max(0, points(settings, progress) - progress.seasonBase());
    }

    // ------------------------------------------------------------------ triggers

    private static void onChange(ProgressLedger.Change change) {
        // rank, pending, season and rewarded writes come from the engine itself: reacting to them would rank a player twice
        if (change.server() != null && change.kind() != ProgressLedger.Kind.RANK && change.kind() != ProgressLedger.Kind.PENDING
                && change.kind() != ProgressLedger.Kind.SEASON && change.kind() != ProgressLedger.Kind.REWARDED) {
            check(change.server(), change.player());
        }
    }

    private static void onSettingsChanged(Settings settings) {
        // thresholds or weights may have changed: every player online is checked on the next tick of each server that has them
        for (MinecraftServer server : SERVERS) {
            server.execute(() -> checkOnline(server));
        }
    }

    private static final Set<MinecraftServer> SERVERS = new HashSet<>();

    public static void onServerStarted(MinecraftServer server) {
        SERVERS.add(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        SERVERS.remove(server);
        IN_FLIGHT.clear();
    }

    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            onLogin(player);
        }
    }

    /** On login: rewards kept while away, then the usual check (which also puts a new player on the first rung). */
    public static void onLogin(ServerPlayer player) {
        Settings settings = SettingsLedger.settings(player.server);
        Rewards.givePending(player, settings);
        check(player.server, player.getUUID());
    }

    // ------------------------------------------------------------------ admin moves

    /**
     * Puts the player on a rung by an admin's hand (D26 SET RANK): LuckPerms first when present, then the
     * ledger; no rewards, no announcement. The future says whether it happened.
     */
    public static CompletableFuture<Boolean> assign(MinecraftServer server, UUID player, Rank rank) {
        Settings settings = SettingsLedger.settings(server);
        CompletableFuture<Boolean> apply = LuckPermsBridge.isPresent()
                ? LuckPermsBridge.setGroup(player, settings.track(), rank.group())
                : CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        apply.whenComplete((ok, err) -> server.execute(() -> {
            boolean success = err == null && ok != null && ok;
            if (success) {
                ProgressLedger ledger = ProgressLedger.get(server);
                Progress before = ledger.peek(player);
                int from = settings.indexOf(before == null ? null : before.rank());
                ledger.setRank(player, rank.group(), System.currentTimeMillis());
                post(server, player, settings, from, settings.indexOf(rank.group()), RankChangedEvent.Cause.ADMIN);
            }
            done.complete(success);
        }));
        return done;
    }

    /** Re-checks every player online on this server (after a settings import or a reload). */
    public static void checkOnline(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : new ArrayList<>(level.players())) {
                check(server, p.getUUID());
            }
        }
    }

    // ------------------------------------------------------------------ the check

    /** Moves the player up to the rung their points reach, if that is above the rung they hold. */
    public static void check(MinecraftServer server, UUID player) {
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        Progress progress = ledger.peek(player); // never creates an entry: a reset must leave nothing behind
        if (progress == null) {
            progress = new Progress();
        }
        if (Seasons.due(server, progress) && Players.isOnline(server, player)) {
            Seasons.apply(server, player); // ends with this check again; a player who is away is handled on login
            return;
        }
        int current = settings.indexOf(progress.rank());
        int target = rankIndex(settings, points(settings, progress), seasonPoints(settings, progress));
        if (target <= current) {
            reapply(server, settings, player, current);
            return;
        }
        if (!IN_FLIGHT.add(player)) {
            return;
        }
        Rank to = settings.ranks().get(target);
        CompletableFuture<Boolean> apply = LuckPermsBridge.isPresent()
                ? LuckPermsBridge.setGroup(player, settings.track(), to.group())
                : CompletableFuture.completedFuture(true);
        apply.whenComplete((ok, err) -> server.execute(() -> {
            IN_FLIGHT.remove(player);
            if (err != null || ok == null || !ok) {
                warn("LuckPerms did not put " + Players.name(server, player) + " on group '" + to.group() + "' of track '" + settings.track() + "'; the rank-up waits. Does the track hold that group?");
                return;
            }
            complete(server, player, current, target);
        }));
    }

    private static void complete(MinecraftServer server, UUID player, int from, int target) {
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        List<Rank> ranks = settings.ranks();
        Progress held = ledger.peek(player);
        int heldIndex = settings.indexOf(held == null ? null : held.rank());
        if (heldIndex >= target) {
            return; // an admin or a season moved the player while LuckPerms was answering
        }
        from = Math.max(from, heldIndex);
        Rank previous = from < 0 || from >= ranks.size() ? null : ranks.get(from);
        for (int i = from + 1; i <= target && i < ranks.size(); i++) {
            Rank rank = ranks.get(i);
            ledger.setRank(player, rank.group(), System.currentTimeMillis());
            ServerPlayer online = Players.online(server, player);
            boolean quiet = previous == null && i == 0; // the starting rung: no fanfare
            if (online != null && !quiet) {
                online.sendSystemMessage(Component.translatable("message.bsp_rankup.reached", rank.displayName()));
            }
            Progress now = ledger.progress(player);
            if (settings.rewardsAgainEachSeason() || !now.rewarded().contains(rank.group())) {
                Rewards.give(server, player, rank, settings);
                ledger.addRewarded(player, rank.group());
            }
            if (!quiet && settings.announce() != Settings.Announce.OFF) {
                server.getPlayerList().broadcastSystemMessage(Component.translatable("message.bsp_rankup.announce", Players.name(server, player), rank.displayName()), false);
            }
            RankUp up = new RankUp(server, player, previous, rank);
            for (Consumer<RankUp> l : LISTENERS) {
                l.accept(up);
            }
            post(server, player, settings, i - 1, i, RankChangedEvent.Cause.EARNED);
            previous = rank;
        }
        check(server, player); // counts may have moved while LuckPerms was answering
    }

    /** A player who holds a rung but is on none of the track's groups in LuckPerms (someone cleared it) is put back. */
    private static void reapply(MinecraftServer server, Settings settings, UUID player, int current) {
        if (current < 0 || !LuckPermsBridge.isPresent() || !Players.isOnline(server, player) || IN_FLIGHT.contains(player)) {
            return;
        }
        if (LuckPermsBridge.groupsOf(player).isEmpty() || LuckPermsBridge.rankOn(player, settings.track()).isPresent()) {
            return;
        }
        Rank rank = settings.ranks().get(current);
        IN_FLIGHT.add(player);
        LuckPermsBridge.setGroup(player, settings.track(), rank.group()).whenComplete((ok, err) -> server.execute(() -> IN_FLIGHT.remove(player)));
    }

    /** Posts the api's event for a move from rung {@code from} to rung {@code to} (-1 for none). */
    public static void post(MinecraftServer server, UUID player, Settings settings, int from, int to, RankChangedEvent.Cause cause) {
        MinecraftForge.EVENT_BUS.post(new RankChangedEvent(server, player, info(settings, from), info(settings, to), cause));
    }

    @Nullable
    private static RankInfo info(Settings settings, int index) {
        if (index < 0 || index >= settings.ranks().size()) {
            return null;
        }
        Rank r = settings.ranks().get(index);
        return new RankInfo(r.group(), r.displayName(), index, r.points(), r.seasonal());
    }

    private static void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > 60_000L) {
            lastWarnMs = now;
            BSPRankUp.LOGGER.warn(message);
        }
    }
}
