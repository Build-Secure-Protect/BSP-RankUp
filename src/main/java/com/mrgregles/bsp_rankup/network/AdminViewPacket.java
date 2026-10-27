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
 * reset-everyone code waiting (empty when none) and its seconds, the server's last answer, whether to open the screen,
 * BSP-Core's kits (id to name) for the reward editor, LuckPerms' groups for the GROUPS tab (D50), and for the
 * PREFIXES tab (D65) every prefix RankUp lists and the files in the images folder. The holders count every ladder's
 * rungs and every award; the prefixes' images are in the settings.
 */
public record AdminViewPacket(CompoundTag settings, Map<String, Integer> holders, List<PlayerRow> players, PlayerDetail selected,
                              List<String> info, String resetCode, int resetSeconds, String message, boolean ok, boolean open, Map<String, String> kits,
                              List<String> groups, List<PrefixRow> prefixes, List<FileRow> files) {
    /** The most LuckPerms groups one view carries. */
    public static final int MAX_GROUPS = 500;
    /** The most prefixes and image files one view carries. */
    public static final int MAX_PREFIXES = 500, MAX_FILES = 100;

    /** One prefix (D56): its rank key ({@code title:<group>} or {@code group:<group>}), its text and the rank's name. */
    public record PrefixRow(String key, String text, String rankName) {
    }

    /** One file of the images folder: name, size in pixels and bytes, id (empty when unusable) and what is wrong (empty when fine). */
    public record FileRow(String name, int width, int height, int bytes, String id, String problem) {
    }

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
        buf.writeMap(msg.kits, (b, k) -> b.writeUtf(k, 64), (b, v) -> b.writeUtf(v, 64));
        buf.writeCollection(msg.groups.size() > MAX_GROUPS ? msg.groups.subList(0, MAX_GROUPS) : msg.groups, (b, g) -> b.writeUtf(g, 64));
        buf.writeCollection(msg.prefixes.size() > MAX_PREFIXES ? msg.prefixes.subList(0, MAX_PREFIXES) : msg.prefixes, (b, p) -> {
            b.writeUtf(p.key, 72);
            b.writeUtf(p.text, 128);
            b.writeUtf(p.rankName, 128);
        });
        buf.writeCollection(msg.files.size() > MAX_FILES ? msg.files.subList(0, MAX_FILES) : msg.files, (b, f) -> {
            b.writeUtf(f.name, 128);
            b.writeVarInt(f.width);
            b.writeVarInt(f.height);
            b.writeVarInt(f.bytes);
            b.writeUtf(f.id, 40);
            b.writeUtf(f.problem, 128);
        });
    }

    public static AdminViewPacket decode(FriendlyByteBuf buf) {
        CompoundTag settings = buf.readNbt();
        Map<String, Integer> holders = buf.readMap(b -> b.readUtf(64), FriendlyByteBuf::readVarInt);
        List<PlayerRow> players = buf.readList(b -> new PlayerRow(b.readUUID(), b.readUtf(48), b.readUtf(64), b.readVarLong(), b.readBoolean()));
        PlayerDetail d = new PlayerDetail(buf.readUUID(), buf.readUtf(48), buf.readMap(b -> b.readUtf(32), FriendlyByteBuf::readVarLong),
                buf.readVarLong(), buf.readUtf(64), buf.readVarLong(), buf.readVarInt(), buf.readVarLong(), buf.readVarLong());
        List<String> info = buf.readList(b -> b.readUtf(512));
        String code = buf.readUtf(8);
        int seconds = buf.readVarInt();
        String message = buf.readUtf(512);
        boolean ok = buf.readBoolean(), open = buf.readBoolean();
        Map<String, String> kits = buf.readMap(b -> b.readUtf(64), b -> b.readUtf(64));
        List<String> groups = buf.readList(b -> b.readUtf(64));
        List<PrefixRow> prefixes = buf.readList(b -> new PrefixRow(b.readUtf(72), b.readUtf(128), b.readUtf(128)));
        List<FileRow> files = buf.readList(b -> new FileRow(b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(40), b.readUtf(128)));
        return new AdminViewPacket(settings == null ? new CompoundTag() : settings, holders, players, d, info, code, seconds, message, ok, open, kits, groups, prefixes, files);
    }

    public static void handle(AdminViewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_rankup.client.RankUpClient.receive(msg));
        ctx.get().setPacketHandled(true);
    }
}
