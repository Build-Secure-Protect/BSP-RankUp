package com.mrgregles.bsp_rankup.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server: one edit or request from the admin screen. The server checks that the sender is a BSP Core
 * admin, applies it through the same {@code AdminEdits} the commands use, and answers with a fresh view.
 * Arguments are plain text.
 */
public record AdminActionPacket(String action, String a, String b, String c) {
    public static final String OPEN = "open", REFRESH = "refresh",
            LADDER_ADD = "ladder-add", LADDER_REMOVE = "ladder-remove", LADDER_POINTS = "ladder-points", LADDER_NAME = "ladder-name",
            LADDER_SEASONAL = "ladder-seasonal", LADDER_MOVE = "ladder-move", LADDER_IMPORT = "ladder-import", LADDER_SELECT = "ladder-select",
            REWARD_ADD = "reward-add", REWARD_HAND = "reward-hand", REWARD_REMOVE = "reward-remove",
            SIGNAL_ON = "signal-on", SIGNAL_POINTS = "signal-points",
            OPTION = "option",
            PLAYER_SEARCH = "player-search", PLAYER_SELECT = "player-select", PLAYER_SET = "player-set", PLAYER_ADD = "player-add", PLAYER_RESET = "player-reset",
            RESET_ALL_REQUEST = "reset-all-request", RESET_ALL_CONFIRM = "reset-all-confirm", RESET_ALL_CANCEL = "reset-all-cancel",
            MAKE_TRACK = "make-track", EXPORT = "export", IMPORT = "import",
            // several ladders and awards (D49 to D51): a = the ladder's name or the award's group
            LADDERS_ADD = "ladders-add", LADDERS_REMOVE = "ladders-remove", LADDERS_RENAME = "ladders-rename", LADDERS_TRACK = "ladders-track",
            LADDERS_COUNTS = "ladders-counts", LADDERS_PRIORITY = "ladders-priority", LADDERS_IMPORT = "ladders-import", RUNG_ADD = "rung-add",
            AWARD_ADD = "award-add", AWARD_REMOVE = "award-remove", AWARD_POINTS = "award-points", AWARD_NAME = "award-name",
            AWARD_COUNTS = "award-counts", AWARD_SEASONAL = "award-seasonal";

    public static AdminActionPacket of(String action) {
        return new AdminActionPacket(action, "", "", "");
    }

    public static AdminActionPacket of(String action, String a) {
        return new AdminActionPacket(action, a, "", "");
    }

    public static AdminActionPacket of(String action, String a, String b) {
        return new AdminActionPacket(action, a, b, "");
    }

    public static void encode(AdminActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.action, 32);
        buf.writeUtf(msg.a, 256);
        buf.writeUtf(msg.b, 1024);
        buf.writeUtf(msg.c, 256);
    }

    public static AdminActionPacket decode(FriendlyByteBuf buf) {
        return new AdminActionPacket(buf.readUtf(32), buf.readUtf(256), buf.readUtf(1024), buf.readUtf(256));
    }

    public static void handle(AdminActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            AdminSync.handle(player, msg);
        }
        ctx.get().setPacketHandled(true);
    }
}
