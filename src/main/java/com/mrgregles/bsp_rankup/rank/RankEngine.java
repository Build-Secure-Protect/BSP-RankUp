package com.mrgregles.bsp_rankup.rank;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.api.RankInfo;
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
 * The ladders and the awards (owner decisions D5, D9, D10, D49 to D51): counts become points, on each ladder from the
 * signals that ladder counts plus the admin bonus; points become the rung a player has earned on each ladder, and
 * when that is above the rung RankUp last gave them there, the player is moved along that ladder's LuckPerms track,
 * given every passed rung's rewards and announced. An award (a group on no ladder) is added to the player in
 * LuckPerms once its points are reached. Never downward by itself (D6).
 *
 * <p>Runs on every ledger change and on login. With LuckPerms present the group change must succeed before the
 * rank-up or award counts; without LuckPerms (a dev server) it is recorded in the ledger alone, so the ladders still
 * work for testing.
 */
public final class RankEngine {
    /** A rank-up that happened: the ladder, the rung left (null for the first) and the rung reached. */
    public record RankUp(MinecraftServer server, UUID player, Ladder ladder, @Nullable Rank from, Rank to) {
    }

    /** An award a player reached here (D50): the group was added and its rewards given. */
    public record AwardReached(MinecraftServer server, UUID player, Award award) {
    }

    private static final List<Consumer<RankUp>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<AwardReached>> AWARD_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Component>> ANNOUNCE_LISTENERS = new CopyOnWriteArrayList<>();
    /** Moves waiting for LuckPerms, by player and ladder ({@code uuid/ladder}) or award ({@code uuid/award:group}). */
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static long lastWarnMs;

    private RankEngine() {
    }

    public static void init() {
        ProgressLedger.onChange(RankEngine::onChange);
        SettingsLedger.onChange(RankEngine::onSettingsChanged);
        com.mrgregles.bsp_rankup.storage.Storage.onPlayerRead((server, player) -> {
            if (AWAITING_ROW.remove(player)) {
                ServerPlayer online = Players.online(server, player);
                if (online != null) {
                    onLogin(online);
                }
            }
        });
    }

    public static void onRankUp(Consumer<RankUp> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<RankUp> listener) {
        LISTENERS.remove(listener);
    }

    public static void onAward(Consumer<AwardReached> listener) {
        AWARD_LISTENERS.add(listener);
    }

    public static void removeAwardListener(Consumer<AwardReached> listener) {
        AWARD_LISTENERS.remove(listener);
    }

    /** Hears every rank-up and award announced to the whole server here. For tests. */
    public static void onAnnounce(Consumer<Component> listener) {
        ANNOUNCE_LISTENERS.add(listener);
    }

    public static void removeAnnounceListener(Consumer<Component> listener) {
        ANNOUNCE_LISTENERS.remove(listener);
    }

    /**
     * Whether a rank-up or award of {@code player} is announced: only while they are online here (D86). One reached
     * while they are away (a totem lost, an admin's points, a row read as the server starts) is still given, quietly.
     */
    public static boolean announces(MinecraftServer server, Settings settings, UUID player) {
        return settings.announce() != Settings.Announce.OFF && Players.isOnline(server, player);
    }

    private static void announce(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
        for (Consumer<Component> l : ANNOUNCE_LISTENERS) {
            l.accept(message);
        }
    }

    // ------------------------------------------------------------------ arithmetic

    /** The points a player's progress is worth on a ladder: every signal that is on and that the ladder counts, plus the admin bonus. */
    public static long points(Settings settings, Ladder ladder, Progress progress) {
        long total = progress.bonus();
        for (Signal s : Signal.values()) {
            if (ladder.counts(s)) {
                total += settings.signal(s).pointsFor(progress.count(s));
            }
        }
        return total;
    }

    /** The points toward an award: the signals it counts, plus the admin bonus. */
    public static long points(Settings settings, Award award, Progress progress) {
        long total = progress.bonus();
        for (Signal s : Signal.values()) {
            if (award.counts(s)) {
                total += settings.signal(s).pointsFor(progress.count(s));
            }
        }
        return total;
    }

    /** The points on the first ladder (the only one before D49). */
    public static long points(Settings settings, Progress progress) {
        return points(settings, settings.first(), progress);
    }

    /** True for the first ladder, whose rung and season base are kept where RankUp kept them before D49. */
    public static boolean isFirst(Settings settings, Ladder ladder) {
        return settings.first().name().equals(ladder.name());
    }

    /** The group RankUp last put the player on, on this ladder; null when none. */
    @Nullable
    public static String rankOn(Settings settings, Ladder ladder, Progress progress) {
        if (isFirst(settings, ladder)) {
            return progress.rank();
        }
        Progress.Held held = progress.otherRank(ladder.name());
        return held == null ? null : held.group();
    }

    public static long rankedAtOn(Settings settings, Ladder ladder, Progress progress) {
        if (isFirst(settings, ladder)) {
            return progress.rankedAtMs();
        }
        Progress.Held held = progress.otherRank(ladder.name());
        return held == null ? 0 : held.atMs();
    }

    /** The ladder's points when the last season ended; its seasonal rungs are judged on points above it. */
    public static long seasonBase(Settings settings, Ladder ladder, Progress progress) {
        return isFirst(settings, ladder) ? progress.seasonBase() : progress.otherBase(ladder.name());
    }

    /** The points earned on a ladder since the last season ended. */
    public static long seasonPoints(Settings settings, Ladder ladder, Progress progress) {
        return Math.max(0, points(settings, ladder, progress) - seasonBase(settings, ladder, progress));
    }

    /** The points earned toward an award since the last season ended (its base is 0 until a season ends). */
    public static long seasonPoints(Settings settings, Award award, Progress progress) {
        return Math.max(0, points(settings, award, progress) - progress.otherBase(Progress.awardKey(award.group())));
    }

    /** The points earned on the first ladder since the last season ended. */
    public static long seasonPoints(Settings settings, Progress progress) {
        return seasonPoints(settings, settings.first(), progress);
    }

    /**
     * The highest rung these points reach on a ladder, walking it from the first rung and stopping at the first one
     * not reached (so a rung with a lower threshold than the one below it is never skipped to). -1 for none.
     * Permanent rungs are judged on the lifetime points, seasonal rungs on the points earned this season.
     */
    public static int rankIndex(Ladder ladder, long points, long seasonPoints) {
        int best = -1;
        List<Rank> ranks = ladder.ranks();
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

    /** {@link #rankIndex(Ladder, long, long)} on the first ladder. */
    public static int rankIndex(Settings settings, long points, long seasonPoints) {
        return rankIndex(settings.first(), points, seasonPoints);
    }

    /** The rung lifetime points reach on the first ladder when no season has ended. */
    public static int rankIndex(Settings settings, long points) {
        return rankIndex(settings, points, points);
    }

    /** The ladder's name as players read it: "playtime" is "Playtime". */
    public static String label(Ladder ladder) {
        String n = ladder.name().replace('_', ' ');
        return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** The priority awards' TITLE rewards are set at: one below the lowest ladder's, so a ladder's title shows first. */
    public static int awardTitlePriority(Settings settings) {
        int lowest = Integer.MAX_VALUE;
        for (Ladder l : settings.ladders()) {
            lowest = Math.min(lowest, l.titlePriority());
        }
        return lowest - 1;
    }

    // ------------------------------------------------------------------ triggers

    private static void onChange(ProgressLedger.Change change) {
        // rank, pending, season, rewarded and award writes come from the engine itself: reacting to them would rank a player twice
        ProgressLedger.Kind k = change.kind();
        if (change.server() != null && k != ProgressLedger.Kind.RANK && k != ProgressLedger.Kind.PENDING && k != ProgressLedger.Kind.SEASON
                && k != ProgressLedger.Kind.REWARDED && k != ProgressLedger.Kind.AWARD) {
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
            if (com.mrgregles.bsp_rankup.storage.Storage.active() != null) {
                awaitRow(player.getUUID()); // the shared row decides: rewards given on another server, a season handled there
            } else {
                onLogin(player);
            }
        }
    }

    /** Players whose shared row is being read at login: nothing is checked for them until it is in place. */
    private static final Set<UUID> AWAITING_ROW = new HashSet<>();

    /** Holds the login check for a player until the shared storage has read their row (see {@link #init}). */
    public static void awaitRow(UUID player) {
        AWAITING_ROW.add(player);
    }

    public static void forget(UUID player) {
        AWAITING_ROW.remove(player);
    }

    /** On login: rewards kept while away, then the usual check (which also puts a new player on each ladder's first rung). */
    public static void onLogin(ServerPlayer player) {
        Settings settings = SettingsLedger.settings(player.server);
        Rewards.givePending(player, settings);
        check(player.server, player.getUUID());
    }

    // ------------------------------------------------------------------ admin moves

    /**
     * Puts the player on a rung by an admin's hand (D26 SET RANK), on the ladder that has the rung: LuckPerms first
     * when present, then the ledger; no rewards, no announcement. The future says whether it happened.
     */
    public static CompletableFuture<Boolean> assign(MinecraftServer server, UUID player, Rank rank) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladderOf(rank.group());
        if (ladder == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> apply = LuckPermsBridge.isPresent()
                ? LuckPermsBridge.setGroup(player, ladder.track(), rank.group())
                : CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        apply.whenComplete((ok, err) -> server.execute(() -> {
            boolean success = err == null && ok != null && ok;
            if (success) {
                ProgressLedger ledger = ProgressLedger.get(server);
                Progress before = ledger.peek(player);
                int from = ladder.indexOf(before == null ? null : rankOn(settings, ladder, before));
                setRung(server, settings, ladder, player, rank.group());
                post(server, player, settings, ladder, from, ladder.indexOf(rank.group()), RankChangedEvent.Cause.ADMIN);
            }
            done.complete(success);
        }));
        return done;
    }

    /** Records the rung RankUp put the player on, on one ladder. */
    static void setRung(MinecraftServer server, Settings settings, Ladder ladder, UUID player, @Nullable String group) {
        ProgressLedger ledger = ProgressLedger.get(server);
        if (isFirst(settings, ladder)) {
            ledger.setRank(player, group, System.currentTimeMillis());
        } else {
            ledger.setOtherRank(player, ladder.name(), group, System.currentTimeMillis());
        }
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

    /** Moves the player up every ladder to the rung their points reach there, and gives the awards they reached. */
    public static void check(MinecraftServer server, UUID player) {
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        if (AWAITING_ROW.contains(player)) {
            return; // this server's copy may be old until the shared row is read; the read checks again
        }
        Progress progress = ledger.peek(player); // never creates an entry: a reset must leave nothing behind
        if (progress == null) {
            progress = new Progress();
        }
        if (Seasons.due(server, progress) && Players.isOnline(server, player)) {
            Seasons.apply(server, player); // ends with this check again; a player who is away is handled on login
            return;
        }
        Set<String>[] groups = new Set[1]; // LuckPerms' groups for the player, asked at most once per check (reapply)
        for (Ladder ladder : settings.ladders()) {
            checkLadder(server, settings, ladder, player, progress, groups);
        }
        for (Award award : settings.awards()) {
            checkAward(server, settings, award, player, progress);
        }
    }

    private static void checkLadder(MinecraftServer server, Settings settings, Ladder ladder, UUID player, Progress progress, Set<String>[] groups) {
        int current = ladder.indexOf(rankOn(settings, ladder, progress));
        int target = rankIndex(ladder, points(settings, ladder, progress), seasonPoints(settings, ladder, progress));
        if (target <= current) {
            reapply(server, settings, ladder, player, current, groups);
            return;
        }
        String key = player + "/" + ladder.name();
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        Rank to = ladder.ranks().get(target);
        CompletableFuture<Boolean> apply = LuckPermsBridge.isPresent()
                ? LuckPermsBridge.setGroup(player, ladder.track(), to.group())
                : CompletableFuture.completedFuture(true);
        apply.whenComplete((ok, err) -> server.execute(() -> {
            IN_FLIGHT.remove(key);
            if (err != null || ok == null || !ok) {
                warn("LuckPerms did not put " + Players.name(server, player) + " on group '" + to.group() + "' of track '" + ladder.track() + "'; the rank-up waits. Does the track hold that group?");
                return;
            }
            complete(server, player, ladder.name(), current, target);
        }));
    }

    private static void complete(MinecraftServer server, UUID player, String ladderName, int from, int target) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(ladderName);
        if (ladder == null) {
            return; // the ladder was removed while LuckPerms was answering
        }
        ProgressLedger ledger = ProgressLedger.get(server);
        List<Rank> ranks = ladder.ranks();
        Progress held = ledger.peek(player);
        int heldIndex = ladder.indexOf(held == null ? null : rankOn(settings, ladder, held));
        if (heldIndex >= target) {
            return; // an admin or a season moved the player while LuckPerms was answering
        }
        from = Math.max(from, heldIndex);
        boolean several = settings.ladders().size() > 1;
        Rank previous = from < 0 || from >= ranks.size() ? null : ranks.get(from);
        for (int i = from + 1; i <= target && i < ranks.size(); i++) {
            Rank rank = ranks.get(i);
            setRung(server, settings, ladder, player, rank.group());
            ServerPlayer online = Players.online(server, player);
            boolean quiet = previous == null && i == 0; // the starting rung: no fanfare
            if (online != null && !quiet) {
                online.sendSystemMessage(several
                        ? Component.translatable("message.bsp_rankup.reached_on", rank.displayName(), label(ladder))
                        : Component.translatable("message.bsp_rankup.reached", rank.displayName()));
            }
            Progress now = ledger.progress(player);
            if (settings.rewardsAgainEachSeason() || !now.rewarded().contains(rank.group())) {
                Rewards.give(server, player, rank.displayName(), rank.rewards(), settings, ladder.titlePriority());
                ledger.addRewarded(player, rank.group());
            }
            if (!quiet && announces(server, settings, player)) {
                announce(server, several
                        ? Component.translatable("message.bsp_rankup.announce_on", Players.name(server, player), rank.displayName(), label(ladder))
                        : Component.translatable("message.bsp_rankup.announce", Players.name(server, player), rank.displayName()));
            }
            RankUp up = new RankUp(server, player, ladder, previous, rank);
            for (Consumer<RankUp> l : LISTENERS) {
                l.accept(up);
            }
            post(server, player, settings, ladder, i - 1, i, RankChangedEvent.Cause.EARNED);
            previous = rank;
        }
        check(server, player); // counts may have moved while LuckPerms was answering
    }

    /** A player who holds a rung but is on none of the track's groups in LuckPerms (someone cleared it) is put back. */
    private static void reapply(MinecraftServer server, Settings settings, Ladder ladder, UUID player, int current, Set<String>[] lookedUp) {
        String key = player + "/" + ladder.name();
        if (current < 0 || !LuckPermsBridge.isPresent() || !Players.isOnline(server, player) || IN_FLIGHT.contains(key)) {
            return;
        }
        if (lookedUp[0] == null) {
            lookedUp[0] = LuckPermsBridge.groupsOf(player); // once for every ladder of this check: rankOn would ask again
        }
        Set<String> groups = lookedUp[0];
        if (groups.isEmpty() || LuckPermsBridge.rankOn(groups, ladder.track()).isPresent()) {
            return;
        }
        Rank rank = ladder.ranks().get(current);
        IN_FLIGHT.add(key);
        LuckPermsBridge.setGroup(player, ladder.track(), rank.group()).whenComplete((ok, err) -> server.execute(() -> IN_FLIGHT.remove(key)));
    }

    // ------------------------------------------------------------------ awards

    /**
     * Gives an award whose points the player reached (D50, D51): the group is added in LuckPerms, then the award is
     * recorded, its rewards given once and the reach announced. A group on a ladder is the ladder's, not an award. A
     * player who already has the group (given by hand, or by another server) is recorded as having reached it, with
     * no rewards.
     */
    private static void checkAward(MinecraftServer server, Settings settings, Award award, UUID player, Progress progress) {
        if (progress.hasAward(award.group()) || settings.ladderOf(award.group()) != null) {
            return;
        }
        long have = award.seasonal() ? seasonPoints(settings, award, progress) : points(settings, award, progress);
        if (have < award.points()) {
            return;
        }
        String key = player + "/award:" + award.group();
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        ProgressLedger ledger = ProgressLedger.get(server);
        CompletableFuture<LuckPermsBridge.Added> apply = LuckPermsBridge.isPresent()
                ? LuckPermsBridge.addGroup(player, award.group())
                : CompletableFuture.completedFuture(LuckPermsBridge.Added.ADDED);
        apply.whenComplete((added, err) -> server.execute(() -> {
            IN_FLIGHT.remove(key);
            if (err != null || added == null || added == LuckPermsBridge.Added.FAILED) {
                warn("LuckPerms did not add group '" + award.group() + "' to " + Players.name(server, player) + "; the award waits. Does the group exist?");
                return;
            }
            Settings now = SettingsLedger.settings(server);
            Award current = now.award(award.group());
            if (current == null || ledger.progress(player).hasAward(award.group())) {
                return; // the award was removed, or given, while LuckPerms was answering
            }
            ledger.setAward(player, current.group(), true, System.currentTimeMillis());
            if (added == LuckPermsBridge.Added.ALREADY) {
                ledger.addRewarded(player, current.group()); // theirs already, by hand or from another server: no rewards (D51)
                return;
            }
            if (now.rewardsAgainEachSeason() || !ledger.progress(player).rewarded().contains(current.group())) {
                Rewards.give(server, player, current.displayName(), current.rewards(), now, awardTitlePriority(now));
                ledger.addRewarded(player, current.group());
            }
            ServerPlayer online = Players.online(server, player);
            if (online != null) {
                online.sendSystemMessage(Component.translatable("message.bsp_rankup.award", current.displayName()));
            }
            if (announces(server, now, player)) {
                announce(server, Component.translatable("message.bsp_rankup.award_announce", Players.name(server, player), current.displayName()));
            }
            AwardReached reached = new AwardReached(server, player, current);
            for (Consumer<AwardReached> l : AWARD_LISTENERS) {
                l.accept(reached);
            }
        }));
    }

    // ------------------------------------------------------------------ events

    /** Posts the api's event for a move from rung {@code from} to rung {@code to} (-1 for none) on a ladder. */
    public static void post(MinecraftServer server, UUID player, Settings settings, Ladder ladder, int from, int to, RankChangedEvent.Cause cause) {
        MinecraftForge.EVENT_BUS.post(new RankChangedEvent(server, player, ladder.name(), info(ladder, from), info(ladder, to), cause));
    }

    /** {@link #post(MinecraftServer, UUID, Settings, Ladder, int, int, RankChangedEvent.Cause)} on the first ladder. */
    public static void post(MinecraftServer server, UUID player, Settings settings, int from, int to, RankChangedEvent.Cause cause) {
        post(server, player, settings, settings.first(), from, to, cause);
    }

    @Nullable
    private static RankInfo info(Ladder ladder, int index) {
        if (index < 0 || index >= ladder.ranks().size()) {
            return null;
        }
        Rank r = ladder.ranks().get(index);
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
