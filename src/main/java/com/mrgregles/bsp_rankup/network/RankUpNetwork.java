package com.mrgregles.bsp_rankup.network;

import com.mrgregles.bsp_rankup.BSPRankUp;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** The mod's channel: {@code bsp_rankup:main}. The server decides, the client displays. */
public final class RankUpNetwork {
    /** 3: prefix images (D55 to D74); 2: several ladders and awards (D49 to D52). A server and its players must be on the same RankUp. */
    private static final String PROTOCOL = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(BSPRankUp.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private RankUpNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(AdminViewPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminViewPacket::encode).decoder(AdminViewPacket::decode)
                .consumerMainThread(AdminViewPacket::handle).add();
        CHANNEL.messageBuilder(AdminActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdminActionPacket::encode).decoder(AdminActionPacket::decode)
                .consumerMainThread(AdminActionPacket::handle).add();
        CHANNEL.messageBuilder(RankViewPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RankViewPacket::encode).decoder(RankViewPacket::decode)
                .consumerMainThread(RankViewPacket::handle).add();
        CHANNEL.messageBuilder(RankActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RankActionPacket::encode).decoder(RankActionPacket::decode)
                .consumerMainThread(RankActionPacket::handle).add();
        CHANNEL.messageBuilder(PrefixImagePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PrefixImagePacket::encode).decoder(PrefixImagePacket::decode)
                .consumerMainThread(PrefixImagePacket::handle).add();
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
