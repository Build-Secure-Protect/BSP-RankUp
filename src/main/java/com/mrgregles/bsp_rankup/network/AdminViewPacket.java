package com.mrgregles.bsp_rankup.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: everything the admin screen shows. The settings as their NBT, how many players hold each rung,
 * the players matching the last search, the selected player's standing, the lines of the OPTIONS tab's info, the
 * reset-everyone code waiting (empty when none) and its seconds, the server's last answer, and whether to open the screen.
 */
public record AdminViewPacket(CompoundTag settings, Map<String, Integer> holders, List<PlayerRow> players, PlayerDetail selected,
                              List<String> info, String resetCode, int resetSeconds, String message, boolean ok, boolean open) {
    /** One row of the PLAYERS tab. */
    public record PlayerRow(UUID uuid, String name, String rank, long points, boolean online) {
    }

    /** The selected player's standing: counts by signal key, bonus, rank, when ranked, waiting rewards, this season's points. */
    public record PlayerDetail(UUID uuid, String name, Map<String, Long> counts, long bonus, String rank, long rankedAt, int pending, long points, long seasonPoints) {
        public static PlayerDetail none() {
            return new PlayerDetail(new UUID(0, 0), "", Map.of(), 0, "", 0, 0, 0, 0);
        }

        public boolean isEmpty() {
            return name.isEmpty();
        }
    }

    public static void encode(AdminViewPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.settings);
        buf.writeMap(msg.holders, (b, k) -> b.writeUtf(k, 64), FriendlyByteBuf::writeVarInt);
        buf.writeCollection(msg.players.size() > 100 ? msg.players.subList(0, 100) : msg.players, (b, p) -> {
            b.writeUUID(p.uuid);
            b.writeUtf(p.name, 48);
            b.writeUtf(p.rank, 64);
            b.writeVarLong(p.points);
            b.writeBoolean(p.online);
        });
        PlayerDetail d = msg.selected;
        buf.writeUUID(d.uuid);
        buf.writeUtf(d.name, 48);
        buf.writeMap(d.counts, (b, k) -> b.writeUtf(k, 32), FriendlyByteBuf::writeVarLong);
        buf.writeVarLong(d.bonus);
        buf.writeUtf(d.rank, 64);
        buf.writeVarLong(d.rankedAt);
        buf.writeVarInt(d.pending);
        buf.writeVarLong(d.points);
        buf.writeVarLong(d.seasonPoints);
        buf.writeCollection(msg.info, (b, s) -> b.writeUtf(s, 512));
        buf.writeUtf(msg.resetCode, 8);
        buf.writeVarInt(msg.resetSeconds);
        buf.writeUtf(msg.message, 512);
        buf.writeBoolean(msg.ok);
        buf.writeBoolean(msg.open);
    }

    public static AdminViewPacket decode(FriendlyByteBuf buf) {
        CompoundTag settings = buf.readNbt();
        Map<String, Integer> holders = buf.readMap(b -> b.readUtf(64), FriendlyByteBuf::readVarInt);
        List<PlayerRow> players = buf.readList(b -> new PlayerRow(b.readUUID(), b.readUtf(48), b.readUtf(64), b.readVarLong(), b.readBoolean()));
        PlayerDetail d = new PlayerDetail(buf.readUUID(), buf.readUtf(48), buf.readMap(b -> b.readUtf(32), FriendlyByteBuf::readVarLong),
                buf.readVarLong(), buf.readUtf(64), buf.readVarLong(), buf.readVarInt(), buf.readVarLong(), buf.readVarLong());
        List<String> info = buf.readList(b -> b.readUtf(512));
        return new AdminViewPacket(settings == null ? new CompoundTag() : settings, holders, players, d, info, buf.readUtf(8), buf.readVarInt(), buf.readUtf(512), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(AdminViewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_rankup.client.RankUpClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
