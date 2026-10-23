package com.mrgregles.bsp_rankup.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/** Finding players on this server: the player list first, then every world's own list (mock players in tests). */
public final class Players {
    private Players() {
    }

    @Nullable
    public static ServerPlayer online(MinecraftServer server, UUID player) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) {
            return p;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Player inLevel = level.getPlayerByUUID(player);
            if (inLevel instanceof ServerPlayer sp) {
                return sp;
            }
        }
        return null;
    }

    public static boolean isOnline(MinecraftServer server, UUID player) {
        return online(server, player) != null;
    }

    /**
     * A player by name or uuid text: online here (any world), else the profile cache (anyone who ever joined),
     * else a uuid written out. Empty when nothing matches.
     */
    public static Optional<UUID> find(MinecraftServer server, String nameOrUuid) {
        Optional<UUID> here = findHere(server, nameOrUuid);
        if (here.isPresent()) {
            return here;
        }
        if (server.getProfileCache() != null) {
            var cached = server.getProfileCache().get(nameOrUuid);
            if (cached.isPresent()) {
                return Optional.of(cached.get().getId());
            }
        }
        return Optional.empty();
    }

    /**
     * As {@link #find} without the profile cache, which may ask Mojang and wait: online here, or a uuid written out.
     */
    public static Optional<UUID> findHere(MinecraftServer server, String nameOrUuid) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer p : level.players()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(nameOrUuid)) {
                    return Optional.of(p.getUUID());
                }
            }
        }
        ServerPlayer listed = server.getPlayerList().getPlayerByName(nameOrUuid);
        if (listed != null) {
            return Optional.of(listed.getUUID());
        }
        try {
            return Optional.of(UUID.fromString(nameOrUuid));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The player's name: from the online player, else the profile cache, else the uuid's first eight characters. */
    public static String name(MinecraftServer server, UUID player) {
        ServerPlayer p = online(server, player);
        if (p != null) {
            return p.getGameProfile().getName();
        }
        if (server.getProfileCache() != null) {
            var cached = server.getProfileCache().get(player);
            if (cached.isPresent()) {
                return cached.get().getName();
            }
        }
        return player.toString().substring(0, 8);
    }
}
