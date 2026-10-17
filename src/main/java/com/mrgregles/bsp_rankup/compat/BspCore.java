package com.mrgregles.bsp_rankup.compat;

import com.mrgregles.bsp_core.api.BspBank;
import com.mrgregles.bsp_core.api.BspPlayers;
import com.mrgregles.bsp_core.api.event.TotemEvent;
import com.mrgregles.bsp_rankup.signal.TotemSignals;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only class that touches BSP-Core: every call goes through {@code com.mrgregles.bsp_core.api}, and every
 * BSP-Core event is turned into plain data for the signals here. BSP-Core is a required dependency, so nothing
 * is guarded.
 */
public final class BspCore {
    /** The name RankUp's bank entries carry. */
    public static final String BANK_PARTY = "BSP RankUp";

    private BspCore() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(BspCore::onStealEnded);
        MinecraftForge.EVENT_BUS.addListener(BspCore::onPlaced);
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
}
