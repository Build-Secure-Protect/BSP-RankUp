package com.mrgregles.bsp_rankup.network;

import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The server side of the player's rank screen: builds the view. Nothing here changes anything. */
public final class RankSync {
    private RankSync() {
    }

    /** Opens the screen for a player. */
    public static void open(ServerPlayer player) {
        send(player, true);
    }

    static void send(ServerPlayer player, boolean open) {
        RankUpNetwork.sendTo(player, view(player.server, player.getUUID(), open));
    }

    public static RankViewPacket view(MinecraftServer server, UUID player, boolean open) {
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        Progress p = ledger.peek(player);
        if (p == null) {
            p = new Progress();
        }
        Map<String, Integer> holders = new LinkedHashMap<>();
        for (Rank r : settings.ranks()) {
            holders.put(r.group(), 0);
        }
        for (Progress other : ledger.all().values()) {
            if (other.rank() != null) {
                holders.merge(other.rank(), 1, Integer::sum);
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Signal s : Signal.values()) {
            counts.put(s.key(), p.count(s));
        }
        long points = RankEngine.points(settings, p);
        long seasonPoints = RankEngine.seasonPoints(settings, p);
        int current = settings.indexOf(p.rank());
        List<RankViewPacket.Fastest> fastest = new ArrayList<>();
        if (current + 1 < settings.ranks().size()) {
            Rank next = settings.ranks().get(current + 1);
            long toGo = next.points() - (next.seasonal() ? seasonPoints : points);
            fastest = fastest(settings, Math.max(0, toGo));
        }
        String title = LuckPermsBridge.isPresent() ? LuckPermsBridge.prefixOf(player, settings.titlePriority()).orElse("") : "";
        String serverId = Storage.active() == null ? "" : Storage.active().serverId();
        return new RankViewPacket(settings.save(), holders, counts, p.bonus(), points, seasonPoints, p.seasonBase() != 0 || p.season() != 0,
                p.rank() == null ? "" : p.rank(), p.rankedAtMs(), title, p.pending().size(), fastest, serverId, ledger.lastEndedSeason() + 1, open);
    }

    /**
     * The three heaviest signals that are on, each with how many more units would make up {@code toGo} points
     * alone (owner decision D43). Empty when nothing is needed.
     */
    public static List<RankViewPacket.Fastest> fastest(Settings settings, long toGo) {
        List<RankViewPacket.Fastest> out = new ArrayList<>();
        if (toGo <= 0) {
            return out;
        }
        List<Signal> on = new ArrayList<>();
        for (Signal s : Signal.values()) {
            SignalSetting v = settings.signal(s);
            if (v.on() && v.points() > 0 && v.per() > 0) {
                on.add(s);
            }
        }
        on.sort(Comparator.comparingDouble((Signal s) -> (double) settings.signal(s).points() / settings.signal(s).per()).reversed());
        for (Signal s : on.subList(0, Math.min(3, on.size()))) {
            SignalSetting v = settings.signal(s);
            long steps = (toGo + v.points() - 1) / v.points(); // whole "per" blocks needed
            out.add(new RankViewPacket.Fastest(s.key(), steps * v.per()));
        }
        return out;
    }
}
