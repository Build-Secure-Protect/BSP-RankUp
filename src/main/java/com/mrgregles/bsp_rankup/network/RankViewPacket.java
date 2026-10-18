package com.mrgregles.bsp_rankup.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server to client: a player's standing for the rank screen (owner decisions D38 to D46): the ladder (the settings'
 * NBT), how many hold each rung, the player's counts by signal key, bonus, lifetime and season points, the rung held
 * (empty for none) and when, their title, waiting rewards, the fastest ways to the next rung, and whether to open.
 */
public record RankViewPacket(CompoundTag settings, Map<String, Integer> holders, Map<String, Long> counts, long bonus, long points,
                             long seasonPoints, boolean seasonStarted, String rank, long rankedAt, String title, int pending,
                             List<Fastest> fastest, String server, int season, boolean open) {
    /** One way to the next rung: this many more units of a signal would do it alone. */
    public record Fastest(String signal, long units) {
    }

    public static void encode(RankViewPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.settings);
        buf.writeMap(msg.holders, (b, k) -> b.writeUtf(k, 64), FriendlyByteBuf::writeVarInt);
        buf.writeMap(msg.counts, (b, k) -> b.writeUtf(k, 32), FriendlyByteBuf::writeVarLong);
        buf.writeVarLong(msg.bonus);
        buf.writeVarLong(msg.points);
        buf.writeVarLong(msg.seasonPoints);
        buf.writeBoolean(msg.seasonStarted);
        buf.writeUtf(msg.rank, 64);
        buf.writeVarLong(msg.rankedAt);
        buf.writeUtf(msg.title, 64);
        buf.writeVarInt(msg.pending);
        buf.writeCollection(msg.fastest, (b, f) -> {
            b.writeUtf(f.signal, 32);
            b.writeVarLong(f.units);
        });
        buf.writeUtf(msg.server, 64);
        buf.writeVarInt(msg.season);
        buf.writeBoolean(msg.open);
    }

    public static RankViewPacket decode(FriendlyByteBuf buf) {
        CompoundTag settings = buf.readNbt();
        return new RankViewPacket(settings == null ? new CompoundTag() : settings,
                buf.readMap(b -> b.readUtf(64), FriendlyByteBuf::readVarInt),
                buf.readMap(b -> b.readUtf(32), FriendlyByteBuf::readVarLong),
                buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readBoolean(), buf.readUtf(64), buf.readVarLong(), buf.readUtf(64), buf.readVarInt(),
                buf.readList(b -> new Fastest(b.readUtf(32), b.readVarLong())), buf.readUtf(64), buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(RankViewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_rankup.client.RankUpClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
