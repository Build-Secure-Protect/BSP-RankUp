package com.mrgregles.bsp_rankup.api;

import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
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
    public static final int API_VERSION = 1;

    private RankUpApi() {
    }

    public static int apiVersion() {
        return API_VERSION;
    }

    // ------------------------------------------------------------------ the ladder

    /** The LuckPerms track the ladder follows. */
    public static String track(MinecraftServer server) {
        return SettingsLedger.settings(server).track();
    }

    /** Every rung, first (lowest) first. Empty until an admin sets the ladder up. */
    public static List<RankInfo> ladder(MinecraftServer server) {
        Settings settings = SettingsLedger.settings(server);
        List<RankInfo> out = new ArrayList<>();
        for (int i = 0; i < settings.ranks().size(); i++) {
            out.add(info(settings.ranks().get(i), i));
        }
        return out;
    }

    /** The rung with this LuckPerms group name, if it is on the ladder. */
    public static Optional<RankInfo> rank(MinecraftServer server, String group) {
        Settings settings = SettingsLedger.settings(server);
        int i = settings.indexOf(group);
        return i < 0 ? Optional.empty() : Optional.of(info(settings.ranks().get(i), i));
    }

    // ------------------------------------------------------------------ players

    /** The rung RankUp last gave the player, if any. */
    public static Optional<RankInfo> rankOf(MinecraftServer server, UUID player) {
        Progress p = ProgressLedger.get(server).peek(player);
        return p == null ? Optional.empty() : rank(server, p.rank() == null ? "" : p.rank());
    }

    /** True when the player holds this rung or a higher one. */
    public static boolean hasRank(MinecraftServer server, UUID player, String group) {
        Optional<RankInfo> held = rankOf(server, player);
        Optional<RankInfo> asked = rank(server, group);
        return held.isPresent() && asked.isPresent() && held.get().index() >= asked.get().index();
    }

    /** Where the player stands: points, rung, next rung, counts. */
    public static ProgressInfo progressOf(MinecraftServer server, UUID player) {
        Settings settings = SettingsLedger.settings(server);
        Progress p = ProgressLedger.get(server).peek(player);
        if (p == null) {
            p = new Progress();
        }
        long points = RankEngine.points(settings, p);
        int current = settings.indexOf(p.rank());
        RankInfo rank = current < 0 ? null : info(settings.ranks().get(current), current);
        RankInfo next = current + 1 < settings.ranks().size() ? info(settings.ranks().get(current + 1), current + 1) : null;
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Signal s : Signal.values()) {
            long n = p.count(s);
            if (n != 0) {
                counts.put(s.key(), n);
            }
        }
        return new ProgressInfo(points, rank, next, next == null ? 0 : Math.max(0, next.points() - points), counts, p.bonus());
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

    static RankInfo info(Rank rank, int index) {
        return new RankInfo(rank.group(), rank.displayName(), index, rank.points(), rank.seasonal());
    }
}
