package com.mrgregles.bsp_rankup.compat;

import com.mrgregles.bsp_core.api.BspBank;
import com.mrgregles.bsp_core.api.BspPlayers;
import com.mrgregles.bsp_core.api.event.SeasonEvent;
import com.mrgregles.bsp_core.api.event.TotemEvent;
import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.rank.Seasons;
import com.mrgregles.bsp_rankup.signal.ScoreSignal;
import com.mrgregles.bsp_rankup.signal.TotemSignals;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only class that touches BSP-Core: every call goes through {@code com.mrgregles.bsp_core.api}, and every
 * BSP-Core event is turned into plain data for the signals here. BSP-Core is a required dependency, so nothing
 * is guarded, except what newer BSP-Core versions added (kits, scores), which an older jar may lack.
 */
public final class BspCore {
    /** The name RankUp's bank entries carry. */
    public static final String BANK_PARTY = "BSP RankUp";

    private BspCore() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(BspCore::onStealEnded);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onPlaced);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onSeasonEnded);
        if (hasScores()) {
            Scores.listen();
        }
    }

    private static void onSeasonEnded(SeasonEvent.Ended event) {
        Seasons.endSeason(event.getServer(), event.getEndedNumber());
    }

    private static void onStealEnded(TotemEvent.StealEnded event) {
        TotemSignals.stealEnded(event.getServer(), event.getOwner(), event.getThief(), event.isSuccess());
    }

    private static void onPlaced(TotemEvent.Placed event) {
        if (event.getOwner() != null) {
            TotemSignals.placed(event.getServer(), event.getOwner());
        }
    }

    // ------------------------------------------------------------------ players and money

    public static boolean isAdmin(Player player) {
        return BspPlayers.isAdmin(player);
    }

    /** For GameTests only: names BSP-Core's admin list, since mock players are not operators. Returns the previous list. */
    public static List<? extends String> setAdminNames(List<String> names) {
        List<? extends String> previous = com.mrgregles.bsp_core.BSPConfig.ADMINS.get();
        com.mrgregles.bsp_core.BSPConfig.ADMINS.set(names);
        return previous;
    }

    /** Gives Shatter Coins worth {@code copperValue} Copper into the player's inventory. */
    public static void giveCoins(ServerPlayer player, long copperValue) {
        if (copperValue > 0) {
            BspPlayers.giveCoins(player, (int) Math.min(Integer.MAX_VALUE, copperValue));
        }
    }

    public static int coinValue(Player player) {
        return BspPlayers.coinValue(player);
    }

    /** Pays Shatter Pieces into a player's bank; works while they are offline. 0 is a success and does nothing. */
    public static void creditPieces(MinecraftServer server, UUID player, long pieces, String reason, Consumer<Boolean> then) {
        if (pieces <= 0) {
            then.accept(true);
            return;
        }
        BspBank.credit(server, player, pieces, BANK_PARTY, reason, then);
    }

    public static long balance(MinecraftServer server, UUID player) {
        return BspBank.balance(server, player);
    }

    public static String formatPieces(long pieces) {
        return BspBank.format(pieces);
    }

    // ------------------------------------------------------------------ kits (BSP-Core 1.3.0, its request K-1)

    /** A kit as the reward editor shows it. */
    public record KitInfo(String id, String name) {
    }

    private static boolean kitsChecked, kitsPresent;

    /** True when this BSP-Core has kits (1.3.0 and newer); an older jar is used without them. */
    public static boolean hasKits() {
        if (!kitsChecked) {
            kitsChecked = true;
            try {
                Kits.probe();
                kitsPresent = true;
            } catch (LinkageError e) {
                BSPRankUp.LOGGER.info("This BSP-Core has no kits (needs 1.3.0 or newer): KIT rewards do nothing");
                kitsPresent = false;
            }
        }
        return kitsPresent;
    }

    /** Every kit this server knows, in the order they were made; empty without kit support. */
    public static List<KitInfo> kits(MinecraftServer server) {
        return hasKits() ? Kits.list(server) : List.of();
    }

    /**
     * {@link #kits} for the screens' views, read at most once a second: BSP-Core copies every kit's items to list
     * them, and each open rank screen asks for a view every second. Edits and rewards use {@link #kits}.
     */
    public static List<KitInfo> kitsForViews(MinecraftServer server) {
        int now = server.getTickCount();
        if (kitCache == null || now - kitCacheTick >= 20 || now < kitCacheTick) {
            kitCache = kits(server);
            kitCacheTick = now;
        }
        return kitCache;
    }

    @org.jetbrains.annotations.Nullable
    private static List<KitInfo> kitCache;
    private static int kitCacheTick;

    // ------------------------------------------------------------------ scores (BSP-Core 1.4.5, request RU-R1)

    private static boolean scoresChecked, scoresPresent;

    /** True when this BSP-Core has the scores api (1.4.5 and newer); an older jar gives no score signal. */
    public static boolean hasScores() {
        if (!scoresChecked) {
            scoresChecked = true;
            try {
                Scores.probe();
                scoresPresent = true;
            } catch (LinkageError e) {
                BSPRankUp.LOGGER.info("This BSP-Core has no scores api (needs 1.4.5 or newer): the score signal counts nothing");
                scoresPresent = false;
            }
        }
        return scoresPresent;
    }

    /** The player's BSP-Core points on the network's board now; empty without the scores api. */
    public static java.util.OptionalLong scorePoints(MinecraftServer server, UUID player) {
        return hasScores() ? java.util.OptionalLong.of(Scores.points(server, player)) : java.util.OptionalLong.empty();
    }

    /** Holds the scores api's types, so a BSP-Core without them only fails here. */
    private static final class Scores {
        static void probe() {
            com.mrgregles.bsp_core.api.BspScores.class.getName();
            com.mrgregles.bsp_core.api.event.ScoreEvent.Changed.class.getName();
        }

        static void listen() {
            MinecraftForge.EVENT_BUS.addListener(Scores::onChanged);
        }

        static int points(MinecraftServer server, UUID player) {
            return com.mrgregles.bsp_core.api.BspScores.points(server, player);
        }

        private static void onChanged(com.mrgregles.bsp_core.api.event.ScoreEvent.Changed event) {
            ScoreSignal.changed(event.getServer(), event.getPlayer(), event.getPoints());
        }
    }

    /** Gives a kit as a reward: BSP-Core owes it and delivers it when it fits. False for an unknown kit or no kit support. */
    public static boolean giveKit(MinecraftServer server, UUID player, String kitId) {
        return hasKits() && Kits.give(server, player, kitId);
    }

    /** Holds the kit api's types, so a BSP-Core without them only fails here. */
    private static final class Kits {
        static void probe() {
            com.mrgregles.bsp_core.api.BspKits.class.getName();
        }

        static List<KitInfo> list(MinecraftServer server) {
            List<KitInfo> out = new java.util.ArrayList<>();
            for (com.mrgregles.bsp_core.api.BspKits.Info k : com.mrgregles.bsp_core.api.BspKits.kits(server)) {
                out.add(new KitInfo(k.id(), k.name()));
            }
            return out;
        }

        static boolean give(MinecraftServer server, UUID player, String kitId) {
            return com.mrgregles.bsp_core.api.BspKits.give(server, player, kitId);
        }
    }
}
