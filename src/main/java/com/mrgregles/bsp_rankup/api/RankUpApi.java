package com.mrgregles.bsp_rankup.api;

import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.signal.Teams;
import com.mrgregles.bsp_rankup.storage.Storage;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What other mods may ask of BSP RankUp (owner decision D12). Everything is read on the server thread from what this
 * server knows; on a network a player's row is read from the shared storage when they log in here, so for a player
 * who is elsewhere the answer may be behind by what they did since. Events: {@link com.mrgregles.bsp_rankup.api.event.RankChangedEvent}.
 *
 * <p>{@link #API_VERSION} is a compile-time constant: compare {@link #apiVersion()} at run time to detect an older
 * RankUp, and catch {@link LinkageError} around the first call for a RankUp without this class.
 */
public final class RankUpApi {
    /** 2: several ladders and awards (D49, D50): {@link #ladders}, the calls that take a ladder name, {@link #awards}, {@link #hasAward}. */
    public static final int API_VERSION = 2;

    private RankUpApi() {
    }

    public static int apiVersion() {
        return API_VERSION;
    }

    // ------------------------------------------------------------------ the first ladder (the only one before API 2)

    /** The LuckPerms track the first ladder follows. */
    public static String track(MinecraftServer server) {
        return SettingsLedger.settings(server).track();
    }

    /** Every rung of the first ladder, first (lowest) first. Empty until an admin sets the ladder up. */
    public static List<RankInfo> ladder(MinecraftServer server) {
        Settings settings = SettingsLedger.settings(server);
        List<RankInfo> out = new ArrayList<>();
        for (int i = 0; i < settings.ranks().size(); i++) {
            out.add(info(settings.ranks().get(i), i));
        }
        return out;
    }

    /** The rung with this LuckPerms group name, if it is on the first ladder. */
    public static Optional<RankInfo> rank(MinecraftServer server, String group) {
        Settings settings = SettingsLedger.settings(server);
        int i = settings.indexOf(group);
        return i < 0 ? Optional.empty() : Optional.of(info(settings.ranks().get(i), i));
    }

    // ------------------------------------------------------------------ players

    /** The rung RankUp last gave the player on the first ladder, if any. */
    public static Optional<RankInfo> rankOf(MinecraftServer server, UUID player) {
        Progress p = ProgressLedger.get(server).peek(player);
        return p == null ? Optional.empty() : rank(server, p.rank() == null ? "" : p.rank());
    }

    /** True when the player holds this rung of the first ladder or a higher one. */
    public static boolean hasRank(MinecraftServer server, UUID player, String group) {
        Optional<RankInfo> held = rankOf(server, player);
        Optional<RankInfo> asked = rank(server, group);
        return held.isPresent() && asked.isPresent() && held.get().index() >= asked.get().index();
    }

    /** Where the player stands on the first ladder: points, rung, next rung, counts. */
    public static ProgressInfo progressOf(MinecraftServer server, UUID player) {
        return progressOf(server, player, SettingsLedger.settings(server).first().name()).orElseThrow();
    }

    // ------------------------------------------------------------------ every ladder (API 2, D49)

    /** Every ladder, the first one first (never empty). */
    public static List<LadderInfo> ladders(MinecraftServer server) {
        List<LadderInfo> out = new ArrayList<>();
        for (Ladder l : SettingsLedger.settings(server).ladders()) {
            out.add(info(l));
        }
        return out;
    }

    /** The ladder with this name. */
    public static Optional<LadderInfo> ladderNamed(MinecraftServer server, String name) {
        Ladder l = SettingsLedger.settings(server).ladder(name);
        return l == null ? Optional.empty() : Optional.of(info(l));
    }

    /** The rung with this LuckPerms group on the named ladder. */
    public static Optional<RankInfo> rank(MinecraftServer server, String ladder, String group) {
        Ladder l = SettingsLedger.settings(server).ladder(ladder);
        int i = l == null ? -1 : l.indexOf(group);
        return i < 0 ? Optional.empty() : Optional.of(info(l.ranks().get(i), i));
    }

    /** The rung RankUp last gave the player on the named ladder, if any. */
    public static Optional<RankInfo> rankOf(MinecraftServer server, UUID player, String ladder) {
        Settings settings = SettingsLedger.settings(server);
        Ladder l = settings.ladder(ladder);
        Progress p = ProgressLedger.get(server).peek(player);
        if (l == null || p == null) {
            return Optional.empty();
        }
        int i = l.indexOf(RankEngine.rankOn(settings, l, p));
        return i < 0 ? Optional.empty() : Optional.of(info(l.ranks().get(i), i));
    }

    /** True when the player holds this rung of the named ladder or a higher one there. */
    public static boolean hasRank(MinecraftServer server, UUID player, String ladder, String group) {
        Optional<RankInfo> held = rankOf(server, player, ladder);
        Optional<RankInfo> asked = rank(server, ladder, group);
        return held.isPresent() && asked.isPresent() && held.get().index() >= asked.get().index();
    }

    /** Where the player stands on the named ladder (its own points: the signals it counts plus the bonus); empty for no such ladder. */
    public static Optional<ProgressInfo> progressOf(MinecraftServer server, UUID player, String ladder) {
        Settings settings = SettingsLedger.settings(server);
        Ladder l = settings.ladder(ladder);
        if (l == null) {
            return Optional.empty();
        }
        Progress p = ProgressLedger.get(server).peek(player);
        if (p == null) {
            p = new Progress();
        }
        long points = RankEngine.points(settings, l, p);
        int current = l.indexOf(RankEngine.rankOn(settings, l, p));
        RankInfo rank = current < 0 ? null : info(l.ranks().get(current), current);
        RankInfo next = current + 1 < l.ranks().size() ? info(l.ranks().get(current + 1), current + 1) : null;
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Signal s : Signal.values()) {
            long n = p.count(s);
            if (n != 0) {
                counts.put(s.key(), n);
            }
        }
        return Optional.of(new ProgressInfo(points, rank, next, next == null ? 0 : Math.max(0, next.points() - points), counts, p.bonus()));
    }

    // ------------------------------------------------------------------ awards (API 2, D50)

    /** Every award. */
    public static List<AwardInfo> awards(MinecraftServer server) {
        List<AwardInfo> out = new ArrayList<>();
        for (Award a : SettingsLedger.settings(server).awards()) {
            out.add(new AwardInfo(a.group(), a.displayName(), keys(a.counts()), a.points(), a.seasonal()));
        }
        return out;
    }

    /** True when the player reached this award (RankUp's record; LuckPerms holds the group). */
    public static boolean hasAward(MinecraftServer server, UUID player, String group) {
        Progress p = ProgressLedger.get(server).peek(player);
        return p != null && p.hasAward(group.toLowerCase(java.util.Locale.ROOT));
    }

    /** The groups of every award the player reached. */
    public static List<String> awardsOf(MinecraftServer server, UUID player) {
        Progress p = ProgressLedger.get(server).peek(player);
        return p == null ? List.of() : List.copyOf(p.awards().keySet());
    }

    /** The player's points under the current settings. */
    public static long pointsOf(MinecraftServer server, UUID player) {
        return progressOf(server, player).points();
    }

    /**
     * Gives (or with a negative number takes) points, as an admin would: they count toward the ladder at once
     * and may rank the player up, never down. {@code reason} is for the log.
     */
    public static void addPoints(MinecraftServer server, UUID player, long points, String reason) {
        if (points != 0) {
            com.mrgregles.bsp_rankup.BSPRankUp.LOGGER.debug("{} points for {}: {}", points, player, reason);
            ProgressLedger.get(server).addBonus(player, points);
        }
    }

    /** The signal keys RankUp counts (for showing a player's counts by name). */
    public static List<String> signalKeys() {
        List<String> out = new ArrayList<>();
        for (Signal s : Signal.values()) {
            out.add(s.key());
        }
        return out;
    }

    // ------------------------------------------------------------------ teams and storage

    /** Registers the teams mod's answer to "are these two players on one team"; null removes it. */
    public static void setTeamProvider(@Nullable TeamProvider provider) {
        Teams.setProvider(provider == null ? null : provider::sameTeam);
    }

    public static boolean hasTeamProvider() {
        return Teams.isRegistered();
    }

    /** True when this server shares counts and settings with the rest of the network through MySQL. */
    public static boolean isShared() {
        return Storage.enabled();
    }

    /** This server's name in the shared storage, or null when storage is off. */
    @Nullable
    public static String serverId() {
        Storage s = Storage.active();
        return s == null ? null : s.serverId();
    }

    static LadderInfo info(Ladder l) {
        List<RankInfo> rungs = new ArrayList<>();
        for (int i = 0; i < l.ranks().size(); i++) {
            rungs.add(info(l.ranks().get(i), i));
        }
        return new LadderInfo(l.name(), l.track(), keys(l.counts()), rungs, l.titlePriority());
    }

    private static List<String> keys(java.util.Set<Signal> signals) {
        List<String> out = new ArrayList<>();
        for (Signal s : Signal.values()) {
            if (signals.contains(s)) {
                out.add(s.key());
            }
        }
        return out;
    }

    static RankInfo info(Rank rank, int index) {
        return new RankInfo(rank.group(), rank.displayName(), index, rank.points(), rank.seasonal());
    }
}
