package com.mrgregles.bsp_rankup.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: a player asks for their rank screen ({@code open}) or a fresh view while it is open ({@code refresh}). */
public record RankActionPacket(String action) {
    public static final String OPEN = "open", REFRESH = "refresh";

    public static void encode(RankActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.action, 16);
    }

    public static RankActionPacket decode(FriendlyByteBuf buf) {
        return new RankActionPacket(buf.readUtf(16));
    }

    public static void handle(RankActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            RankSync.send(player, msg.action.equals(OPEN));
        }
        ctx.get().setPacketHandled(true);
    }
}
