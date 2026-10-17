package com.mrgregles.bsp_rankup.progress;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import com.mrgregles.bsp_rankup.settings.Reward;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Every player's {@link Progress} on this server, saved with the overworld. The one place counts change: the
 * signals call {@link #add}, admins {@link #addBonus}, {@link #setRank} and {@link #reset}. Each change tells the
 * {@link #onChange} listeners (the ladder engine checks thresholds, the shared storage writes through).
 *
 * <p>On a network the shared storage (RU-5) is the truth and this ledger is the local copy, read on login.
 */
public final class ProgressLedger extends SavedData {
    public static final String NAME = "bsp_rankup_progress";

    /** What changed: the server (null for a ledger loaded outside a server, as in tests), the player, the signal (null for a bonus or a reset) and the amount added. */
    public record Change(@Nullable MinecraftServer server, UUID player, @Nullable Signal signal, long amount, Progress progress) {
    }

    private static final List<Consumer<Change>> LISTENERS = new CopyOnWriteArrayList<>();

    private final Map<UUID, Progress> players = new HashMap<>();
    @Nullable
    private MinecraftServer server;

    public ProgressLedger() {
    }

    public static ProgressLedger get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("no overworld");
        }
        ProgressLedger ledger = overworld.getDataStorage().computeIfAbsent(ProgressLedger::load, ProgressLedger::new, NAME);
        ledger.server = server;
        return ledger;
    }

    /** Registers a listener for every change; listeners run on the server thread, in the call that changed the count. */
    public static void onChange(Consumer<Change> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<Change> listener) {
        LISTENERS.remove(listener);
    }

    /** The player's progress, empty when none was ever recorded (never null). The returned object is live. */
    public Progress progress(UUID player) {
        return players.computeIfAbsent(player, k -> new Progress());
    }

    /** The player's progress without creating an entry: null when none was ever recorded. */
    @Nullable
    public Progress peek(UUID player) {
        return players.get(player);
    }

    public boolean has(UUID player) {
        return players.containsKey(player);
    }

    /** Every player with progress, as a copy. */
    public Map<UUID, Progress> all() {
        return Map.copyOf(players);
    }

    /** Adds to a count (a negative amount takes away, never below zero). */
    public void add(UUID player, Signal signal, long amount) {
        if (amount == 0) {
            return;
        }
        Progress p = progress(player);
        p.add(signal, amount);
        setDirty();
        notify(new Change(server, player, signal, amount, p));
    }

    /** Replaces a count outright (the shared storage, or an admin). */
    public void set(UUID player, Signal signal, long count) {
        Progress p = progress(player);
        long before = p.count(signal);
        p.set(signal, count);
        setDirty();
        notify(new Change(server, player, signal, p.count(signal) - before, p));
    }

    public void addBonus(UUID player, long points) {
        Progress p = progress(player);
        p.setBonus(p.bonus() + points);
        setDirty();
        notify(new Change(server, player, null, points, p));
    }

    public void setRank(UUID player, @Nullable String rank, long atMs) {
        Progress p = progress(player);
        p.setRank(rank, atMs);
        setDirty();
    }

    /** Keeps rewards for a player who is not here to receive them; {@link #takePending} hands them out on login. */
    public void addPending(UUID player, List<Reward> rewards) {
        if (rewards.isEmpty()) {
            return;
        }
        progress(player).addPending(rewards);
        setDirty();
    }

    public List<Reward> takePending(UUID player) {
        if (!has(player)) {
            return List.of();
        }
        List<Reward> out = progress(player).takePending();
        if (!out.isEmpty()) {
            setDirty();
        }
        return out;
    }

    /** Forgets everything about the player: counts, bonus and rank. */
    public void reset(UUID player) {
        players.remove(player);
        setDirty();
        notify(new Change(server, player, null, 0, new Progress()));
    }

    private static void notify(Change change) {
        for (Consumer<Change> l : LISTENERS) {
            l.accept(change);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        players.forEach((uuid, p) -> {
            if (!p.isEmpty()) {
                CompoundTag entry = p.save();
                entry.putUUID("Player", uuid);
                list.add(entry);
            }
        });
        tag.put("Players", list);
        return tag;
    }

    public static ProgressLedger load(CompoundTag tag) {
        ProgressLedger ledger = new ProgressLedger();
        ListTag list = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("Player")) {
                ledger.players.put(entry.getUUID("Player"), Progress.load(entry));
            }
        }
        return ledger;
    }
}
