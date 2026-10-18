package com.mrgregles.bsp_rankup.network;

import com.mrgregles.bsp_rankup.admin.AdminEdits;
import com.mrgregles.bsp_rankup.admin.Reply;
import com.mrgregles.bsp_rankup.admin.ResetAll;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.storage.Storage;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The server side of the admin screen: builds the view and applies the actions the client sends. Every action is
 * checked against the same rule as the commands: the sender must be a BSP Core admin. Per admin it remembers the
 * last player search and the selected player, so a fresh view after any edit shows the same things.
 */
public final class AdminSync {
    private static final int MAX_ROWS = 100;
    private static final Map<UUID, String> SEARCH = new HashMap<>();
    private static final Map<UUID, UUID> SELECTED = new HashMap<>();

    private AdminSync() {
    }

    public static boolean mayEdit(ServerPlayer player) {
        return BspCore.isAdmin(player) || player.hasPermissions(2);
    }

    /** Opens the screen for an admin. */
    public static void open(ServerPlayer player) {
        if (!mayEdit(player)) {
            player.displayClientMessage(Component.literal("Only BSP Core admins can open the RankUp admin screen."), true);
            return;
        }
        send(player, "", true, true);
    }

    public static void handle(ServerPlayer player, AdminActionPacket msg) {
        if (!mayEdit(player)) {
            return;
        }
        if (msg.action().equals(AdminActionPacket.OPEN)) {
            send(player, "", true, true);
            return;
        }
        Reply reply = apply(player, msg);
        if (reply != null) {
            send(player, reply.text(), reply.ok(), false);
        }
    }

    /**
     * Applies one action and returns the reply, or null when the reply comes later (the view is sent then). Package
     * and test entry point: every screen action goes through here.
     */
    @Nullable
    public static Reply apply(ServerPlayer admin, AdminActionPacket msg) {
        MinecraftServer server = admin.server;
        String a = msg.a(), b = msg.b(), c = msg.c();
        try {
            switch (msg.action()) {
                case AdminActionPacket.REFRESH, AdminActionPacket.LADDER_SELECT -> {
                    return Reply.ok("");
                }
                case AdminActionPacket.LADDER_ADD -> {
                    return AdminEdits.ladderAdd(server, a, Long.parseLong(b.isBlank() ? "0" : b.trim()), c);
                }
                case AdminActionPacket.LADDER_REMOVE -> {
                    return AdminEdits.ladderRemove(server, a);
                }
                case AdminActionPacket.LADDER_POINTS -> {
                    return AdminEdits.ladderPoints(server, a, Long.parseLong(b.trim()));
                }
                case AdminActionPacket.LADDER_NAME -> {
                    return AdminEdits.ladderName(server, a, b);
                }
                case AdminActionPacket.LADDER_SEASONAL -> {
                    return AdminEdits.ladderSeasonal(server, a, Boolean.parseBoolean(b));
                }
                case AdminActionPacket.LADDER_MOVE -> {
                    return AdminEdits.ladderMove(server, a, Integer.parseInt(b.trim()));
                }
                case AdminActionPacket.LADDER_IMPORT -> {
                    return AdminEdits.ladderImport(server);
                }
                case AdminActionPacket.REWARD_ADD -> {
                    return AdminEdits.rewardAdd(server, a, reward(b, c));
                }
                case AdminActionPacket.REWARD_HAND -> {
                    return AdminEdits.rewardFromHand(admin, a);
                }
                case AdminActionPacket.REWARD_REMOVE -> {
                    return AdminEdits.rewardRemove(server, a, Integer.parseInt(b.trim()));
                }
                case AdminActionPacket.SIGNAL_ON -> {
                    boolean on = Boolean.parseBoolean(b);
                    return AdminEdits.signal(server, a, s -> new SignalSetting(on, s.points(), s.per()));
                }
                case AdminActionPacket.SIGNAL_POINTS -> {
                    long points = Long.parseLong(b.trim());
                    long per = c.isBlank() ? -1 : Long.parseLong(c.trim());
                    return AdminEdits.signal(server, a, s -> new SignalSetting(s.on(), points, per < 0 ? s.per() : per));
                }
                case AdminActionPacket.OPTION -> {
                    return option(server, a, b);
                }
                case AdminActionPacket.PLAYER_SEARCH -> {
                    SEARCH.put(admin.getUUID(), a.trim());
                    return Reply.ok("");
                }
                case AdminActionPacket.PLAYER_SELECT -> {
                    SELECTED.put(admin.getUUID(), a.isBlank() ? null : UUID.fromString(a));
                    return Reply.ok("");
                }
                case AdminActionPacket.PLAYER_SET -> {
                    UUID who = UUID.fromString(a);
                    AdminEdits.playerSet(server, who, b, reply -> send(admin, reply.text(), reply.ok(), false));
                    return null;
                }
                case AdminActionPacket.PLAYER_ADD -> {
                    return AdminEdits.playerAdd(server, UUID.fromString(a), Long.parseLong(b.trim()));
                }
                case AdminActionPacket.PLAYER_RESET -> {
                    return AdminEdits.playerReset(server, UUID.fromString(a));
                }
                case AdminActionPacket.RESET_ALL_REQUEST -> {
                    ResetAll.request(admin.getUUID());
                    return Reply.ok("Type the code back within a minute to reset every player");
                }
                case AdminActionPacket.RESET_ALL_CONFIRM -> {
                    return ResetAll.confirm(server, admin.getUUID(), a);
                }
                case AdminActionPacket.RESET_ALL_CANCEL -> {
                    ResetAll.cancel(admin.getUUID());
                    return Reply.ok("Reset cancelled");
                }
                case AdminActionPacket.MAKE_TRACK -> {
                    AdminEdits.makeTrack(server).thenAccept(reply -> server.execute(() -> send(admin, reply.text(), reply.ok(), false)));
                    return null;
                }
                case AdminActionPacket.EXPORT -> {
                    return AdminEdits.export(server, a);
                }
                case AdminActionPacket.IMPORT -> {
                    return AdminEdits.importFile(server, a);
                }
                default -> {
                    return Reply.no("Unknown action " + msg.action());
                }
            }
        } catch (NumberFormatException e) {
            return Reply.no("That is not a number: " + e.getMessage().replace("For input string: ", ""));
        } catch (IllegalArgumentException e) {
            return Reply.no(e.getMessage());
        }
    }

    private static Reward reward(String kind, String value) {
        Reward.Kind k = Reward.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        return switch (k) {
            case COINS -> Reward.coins(Long.parseLong(value.trim()));
            case PIECES -> Reward.pieces(Long.parseLong(value.trim()));
            case ITEM -> {
                String[] parts = value.trim().split("\\s+");
                yield Reward.item(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
            }
            case TITLE -> Reward.title(value);
            case COMMAND -> Reward.command(value.startsWith("/") ? value.substring(1) : value);
        };
    }

    private static Reply option(MinecraftServer server, String name, String value) {
        return switch (name) {
            case "track" -> AdminEdits.optionTrack(server, value);
            case "announce" -> AdminEdits.optionAnnounce(server, value);
            case "cooldown" -> AdminEdits.optionCooldown(server, Integer.parseInt(value.trim()));
            case "titlepriority" -> AdminEdits.optionTitlePriority(server, Integer.parseInt(value.trim()));
            case "rewardsagain" -> AdminEdits.optionRewardsAgain(server, Boolean.parseBoolean(value));
            default -> Reply.no("No option called " + name);
        };
    }

    // ------------------------------------------------------------------ the view

    static void send(ServerPlayer player, String message, boolean ok, boolean open) {
        RankUpNetwork.sendTo(player, view(player, message, ok, open));
    }

    /** The view for an admin: settings, holders, the search's rows, the selected player, info, the reset code. */
    public static AdminViewPacket view(ServerPlayer admin, String message, boolean ok, boolean open) {
        MinecraftServer server = admin.server;
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        Map<String, Integer> holders = new LinkedHashMap<>();
        for (var r : settings.ranks()) {
            holders.put(r.group(), 0);
        }
        for (Progress p : ledger.all().values()) {
            if (p.rank() != null) {
                holders.merge(p.rank(), 1, Integer::sum);
            }
        }
        List<AdminViewPacket.PlayerRow> rows = search(server, SEARCH.getOrDefault(admin.getUUID(), ""));
        UUID sel = SELECTED.get(admin.getUUID());
        AdminViewPacket.PlayerDetail detail = sel == null ? AdminViewPacket.PlayerDetail.none() : detail(server, sel);
        List<String> info = new ArrayList<>();
        info.add("LuckPerms: " + switch (LuckPermsBridge.route()) { case FORGE -> "Forge edition"; case BUKKIT -> "Bukkit plugin"; default -> "not reachable (ranks are recorded here only)"; });
        if (LuckPermsBridge.isPresent()) {
            List<String> groups = LuckPermsBridge.trackGroups(settings.track());
            info.add("Track " + settings.track() + (groups.isEmpty() ? ": missing or empty in LuckPerms" : ": " + String.join(" > ", groups)));
        }
        info.add("Storage: " + (Storage.active() == null ? "this server only" : Storage.active().status()));
        info.add("Players with progress here: " + ledger.all().size());
        return new AdminViewPacket(settings.save(), holders, rows, detail, info, ResetAll.pending(admin.getUUID()), ResetAll.secondsLeft(admin.getUUID()), message, ok, open);
    }

    /** Players online here plus every player with progress whose name matches; online first, then by points. */
    public static List<AdminViewPacket.PlayerRow> search(MinecraftServer server, String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        Map<UUID, AdminViewPacket.PlayerRow> rows = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                String name = p.getGameProfile().getName();
                if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) {
                    Progress pr = ledger.peek(p.getUUID());
                    rows.put(p.getUUID(), new AdminViewPacket.PlayerRow(p.getUUID(), name, pr == null || pr.rank() == null ? "" : pr.rank(), pr == null ? 0 : RankEngine.points(settings, pr), true));
                }
            }
        }
        for (Map.Entry<UUID, Progress> e : ledger.all().entrySet()) {
            if (rows.containsKey(e.getKey())) {
                continue;
            }
            String name = Players.name(server, e.getKey());
            if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) {
                rows.put(e.getKey(), new AdminViewPacket.PlayerRow(e.getKey(), name, e.getValue().rank() == null ? "" : e.getValue().rank(), RankEngine.points(settings, e.getValue()), false));
            }
        }
        if (rows.isEmpty() && !q.isEmpty()) {
            Optional<UUID> found = Players.find(server, q);
            found.ifPresent(id -> rows.put(id, new AdminViewPacket.PlayerRow(id, Players.name(server, id), "", 0, false)));
        }
        List<AdminViewPacket.PlayerRow> out = new ArrayList<>(rows.values());
        out.sort((x, y) -> x.online() != y.online() ? (x.online() ? -1 : 1) : Long.compare(y.points(), x.points()));
        return out.size() > MAX_ROWS ? out.subList(0, MAX_ROWS) : out;
    }

    public static AdminViewPacket.PlayerDetail detail(MinecraftServer server, UUID player) {
        Settings settings = SettingsLedger.settings(server);
        Progress p = ProgressLedger.get(server).peek(player);
        if (p == null) {
            p = new Progress();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Signal s : Signal.values()) {
            counts.put(s.key(), p.count(s));
        }
        return new AdminViewPacket.PlayerDetail(player, Players.name(server, player), counts, p.bonus(), p.rank() == null ? "" : p.rank(), p.rankedAtMs(),
                p.pending().size(), RankEngine.points(settings, p), RankEngine.seasonPoints(settings, p));
    }

    /** Forgets an admin's search and selection (on logout). */
    public static void forget(UUID admin) {
        SEARCH.remove(admin);
        SELECTED.remove(admin);
        ResetAll.cancel(admin);
    }
}
