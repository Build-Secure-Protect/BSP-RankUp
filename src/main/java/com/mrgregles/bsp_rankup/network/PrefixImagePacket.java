package com.mrgregles.bsp_rankup.network;

import com.mrgregles.bsp_rankup.prefix.PrefixImages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: one prefix image (D62), by its id (the hash of its bytes) and its PNG bytes. Sent at login for
 * every image in use, when an admin picks one, and to an admin for the PREFIXES tab's previews. The client draws it
 * as a glyph wherever the server's text names that id.
 */
public record PrefixImagePacket(String id, byte[] png) {
    public static void encode(PrefixImagePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.id, 40);
        buf.writeByteArray(msg.png);
    }

    public static PrefixImagePacket decode(FriendlyByteBuf buf) {
        return new PrefixImagePacket(buf.readUtf(40), buf.readByteArray(PrefixImages.MAX_BYTES));
    }

    public static void handle(PrefixImagePacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_rankup.client.PrefixFont.receive(msg.id(), msg.png()));
        ctx.get().setPacketHandled(true);
    }
}
