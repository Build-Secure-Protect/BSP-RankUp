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
 * (empty for none) and when, their title and its image's id (empty: drawn as text, D61), waiting rewards, the fastest ways to the next rung, and whether to open.
 * The single-ladder fields are the first ladder's; {@code ladders} has every ladder's standing and {@code awards} every
 * award's (D52), and the holders count every ladder's rungs and every award.
 */
public record RankViewPacket(CompoundTag settings, Map<String, Integer> holders, Map<String, Long> counts, long bonus, long points,
                             long seasonPoints, boolean seasonStarted, String rank, long rankedAt, String title, String titleImage, int pending,
                             List<Fastest> fastest, String server, int season, boolean open, Map<String, String> kits,
                             List<LadderView> ladders, List<AwardView> awards) {
    /** One way to the next rung: this many more units of a signal would do it alone. */
    public record Fastest(String signal, long units) {
    }

    /**
     * The player on one ladder: the rung held (empty for none) and when, its points and this season's, the fastest ways
     * on, and the ladder's own title (the prefix at its priority, empty for none) with its image's id (D76).
     */
    public record LadderView(String name, String rank, long rankedAt, long points, long seasonPoints, List<Fastest> fastest, String title, String titleImage) {
    }

    /** One award: reached or not (and when), and the points toward it (this season's for a seasonal award). */
    public record AwardView(String group, boolean reached, long reachedAt, long have) {
    }

    /** The most ladders and awards a view carries. */
    public static final int MAX_LADDERS = 32, MAX_AWARDS = 200;

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
        buf.writeUtf(msg.titleImage, 40);
        buf.writeVarInt(msg.pending);
        buf.writeCollection(msg.fastest, (b, f) -> {
            b.writeUtf(f.signal, 32);
            b.writeVarLong(f.units);
        });
        buf.writeUtf(msg.server, 64);
        buf.writeVarInt(msg.season);
        buf.writeBoolean(msg.open);
        buf.writeMap(msg.kits, (b, k) -> b.writeUtf(k, 64), (b, v) -> b.writeUtf(v, 64));
        buf.writeCollection(msg.ladders.size() > MAX_LADDERS ? msg.ladders.subList(0, MAX_LADDERS) : msg.ladders, (b, l) -> {
            b.writeUtf(l.name, 32);
            b.writeUtf(l.rank, 64);
            b.writeVarLong(l.rankedAt);
            b.writeVarLong(l.points);
            b.writeVarLong(l.seasonPoints);
            b.writeCollection(l.fastest, (bb, f) -> {
                bb.writeUtf(f.signal, 32);
                bb.writeVarLong(f.units);
            });
            b.writeUtf(l.title, 64);
            b.writeUtf(l.titleImage, 40);
        });
        buf.writeCollection(msg.awards.size() > MAX_AWARDS ? msg.awards.subList(0, MAX_AWARDS) : msg.awards, (b, a) -> {
            b.writeUtf(a.group, 64);
            b.writeBoolean(a.reached);
            b.writeVarLong(a.reachedAt);
            b.writeVarLong(a.have);
        });
    }

    public static RankViewPacket decode(FriendlyByteBuf buf) {
        CompoundTag settings = buf.readNbt();
        return new RankViewPacket(settings == null ? new CompoundTag() : settings,
                buf.readMap(b -> b.readUtf(64), FriendlyByteBuf::readVarInt),
                buf.readMap(b -> b.readUtf(32), FriendlyByteBuf::readVarLong),
                buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readBoolean(), buf.readUtf(64), buf.readVarLong(), buf.readUtf(64), buf.readUtf(40), buf.readVarInt(),
                buf.readList(b -> new Fastest(b.readUtf(32), b.readVarLong())), buf.readUtf(64), buf.readVarInt(), buf.readBoolean(),
                buf.readMap(b -> b.readUtf(64), b -> b.readUtf(64)),
                buf.readList(b -> new LadderView(b.readUtf(32), b.readUtf(64), b.readVarLong(), b.readVarLong(), b.readVarLong(),
                        b.readList(bb -> new Fastest(bb.readUtf(32), bb.readVarLong())), b.readUtf(64), b.readUtf(40))),
                buf.readList(b -> new AwardView(b.readUtf(64), b.readBoolean(), b.readVarLong(), b.readVarLong())));
    }

    public static void handle(RankViewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_rankup.client.RankUpClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
