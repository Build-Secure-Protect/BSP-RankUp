package com.mrgregles.bsp_rankup.api.event;

import com.mrgregles.bsp_rankup.api.RankInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Posted on the Forge bus ({@code MinecraftForge.EVENT_BUS}) on the server thread after a player's rank changed on
 * this server: earned through points, set by an admin, or taken away at a season's end. A notification only: it
 * cannot be cancelled, and the LuckPerms group has already changed when it fires.
 */
public class RankChangedEvent extends Event {
    public enum Cause { EARNED, ADMIN, SEASON }

    private final MinecraftServer server;
    private final UUID player;
    private final String ladder;
    @Nullable
    private final RankInfo from;
    @Nullable
    private final RankInfo to;
    private final Cause cause;

    public RankChangedEvent(MinecraftServer server, UUID player, String ladder, @Nullable RankInfo from, @Nullable RankInfo to, Cause cause) {
        this.server = server;
        this.player = player;
        this.ladder = ladder;
        this.from = from;
        this.to = to;
        this.cause = cause;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public UUID getPlayer() {
        return player;
    }

    /** The name of the ladder the rank changed on (D49); {@code getFrom} and {@code getTo} are rungs of that ladder. */
    public String getLadder() {
        return ladder;
    }

    /** The rung left; null when the player held none. */
    @Nullable
    public RankInfo getFrom() {
        return from;
    }

    /** The rung reached; null when the player now holds none (a season reset below the first rung). */
    @Nullable
    public RankInfo getTo() {
        return to;
    }

    public Cause getCause() {
        return cause;
    }

    /** True for a move to a higher rung. */
    public boolean isPromotion() {
        return to != null && (from == null || to.index() > from.index());
    }
}
