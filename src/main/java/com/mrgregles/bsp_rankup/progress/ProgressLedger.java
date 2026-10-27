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
import java.util.function.BiConsumer;
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

    /** What kind of change happened. */
    public enum Kind { COUNT, BONUS, RANK, PENDING, SEASON, REWARDED, AWARD, RESET, LOAD }

    /**
     * What changed: the server (null for a ledger loaded outside a server, as in tests), the player, the kind, the
     * signal (for a COUNT) and the amount added (a COUNT's delta, a BONUS's points), and the progress afterwards.
     */
    public record Change(@Nullable MinecraftServer server, UUID player, Kind kind, @Nullable Signal signal, long amount, Progress progress) {
    }

    private static final List<Consumer<Change>> LISTENERS = new CopyOnWriteArrayList<>();

    private final Map<UUID, Progress> players = new HashMap<>();
    /** The number of the last season that ended on this server (0: none since this ledger began). */
    private int lastEndedSeason;
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

    /** Visits every player with progress without copying the map (views built every second use this); no changes from the visitor. */
    public void forEach(BiConsumer<UUID, Progress> visitor) {
        players.forEach(visitor);
    }

    /** How many players have progress. */
    public int size() {
        return players.size();
    }

    /** Adds to a count (a negative amount takes away, never below zero). */
    public void add(UUID player, Signal signal, long amount) {
        if (amount == 0) {
            return;
        }
        Progress p = progress(player);
        p.add(signal, amount);
        setDirty();
        notify(new Change(server, player, Kind.COUNT, signal, amount, p));
    }

    /** Replaces a count outright (the shared storage, or an admin). */
    public void set(UUID player, Signal signal, long count) {
        Progress p = progress(player);
        long before = p.count(signal);
        p.set(signal, count);
        setDirty();
        notify(new Change(server, player, Kind.COUNT, signal, p.count(signal) - before, p));
    }

    public void addBonus(UUID player, long points) {
        Progress p = progress(player);
        p.setBonus(p.bonus() + points);
        setDirty();
        notify(new Change(server, player, Kind.BONUS, null, points, p));
    }

    public void setRank(UUID player, @Nullable String rank, long atMs) {
        Progress p = progress(player);
        p.setRank(rank, atMs);
        setDirty();
        notify(new Change(server, player, Kind.RANK, null, 0, p));
    }

    /**
     * Replaces everything known about a player with what the shared storage holds (read on login). One LOAD change
     * tells the listeners; the storage itself ignores it.
     */
    public void load(UUID player, Map<Signal, Long> counts, long bonus, @Nullable String rank, long rankedAtMs, List<Reward> pending, int season, long seasonBase, long scorePeak, java.util.Set<String> rewarded,
                     @Nullable Map<String, Progress.Held> otherRanks, @Nullable Map<String, Long> otherBases, @Nullable Map<String, Long> awards) {
        Progress p = new Progress();
        counts.forEach(p::set);
        p.setBonus(bonus);
        p.setRank(rank, rankedAtMs);
        p.addPending(pending);
        p.setSeason(season, seasonBase);
        p.setScorePeak(scorePeak);
        p.setRewarded(rewarded);
        Progress local = players.get(player);
        if (otherRanks != null && otherBases != null && awards != null) {
            otherRanks.forEach((ladder, held) -> p.setOtherRank(ladder, held.group(), held.atMs()));
            p.setOtherBases(otherBases);
            awards.forEach((group, at) -> p.setAward(group, true, at));
        } else if (local != null) {
            // a row written before several ladders (RankUp 0.2) has none of them: this server's copy stays
            local.otherRanks().forEach((ladder, held) -> p.setOtherRank(ladder, held.group(), held.atMs()));
            p.setOtherBases(local.otherBases());
            local.awardMarks().forEach((group, at) -> p.setAward(group, true, at));
        }
        if (p.isEmpty()) {
            players.remove(player);
        } else {
            players.put(player, p);
        }
        setDirty();
        notify(new Change(server, player, Kind.LOAD, null, 0, p));
    }

    /**
     * Records that a season's end was applied to the player, with their points at that moment; the score peak starts
     * again (D48).
     */
    public void setSeason(UUID player, int season, long base) {
        Progress p = progress(player);
        p.setSeason(season, base);
        p.setScorePeak(0);
        setDirty();
        notify(new Change(server, player, Kind.SEASON, null, 0, p));
    }

    /** The rung RankUp put the player on, on a ladder after the first (the first ladder's is {@link #setRank}). */
    public void setOtherRank(UUID player, String ladder, @Nullable String group, long atMs) {
        Progress p = progress(player);
        p.setOtherRank(ladder, group, atMs);
        setDirty();
        notify(new Change(server, player, Kind.RANK, null, 0, p));
    }

    /**
     * Records a season's end applied to the player with every base at once: the first ladder's in {@code base}, the
     * later ladders' and seasonal awards' in {@code others} (by ladder name or {@link Progress#awardKey}).
     */
    public void setSeason(UUID player, int season, long base, Map<String, Long> others) {
        Progress p = progress(player);
        p.setSeason(season, base);
        p.setOtherBases(others);
        p.setScorePeak(0);
        setDirty();
        notify(new Change(server, player, Kind.SEASON, null, 0, p));
    }

    /** Records an award reached, or taken back (a seasonal award at a season's end). */
    public void setAward(UUID player, String group, boolean reached, long atMs) {
        Progress p = progress(player);
        if (p.hasAward(group) == reached) {
            return;
        }
        p.setAward(group, reached, atMs);
        setDirty();
        notify(new Change(server, player, Kind.AWARD, null, 0, p));
    }

    /** Marks an award taken from the player by an admin (D111), or clears the mark; a reached award taken is no longer held. */
    public void setAwardTaken(UUID player, String group, boolean taken) {
        Progress p = progress(player);
        if (p.isAwardTaken(group) == taken) {
            return;
        }
        p.setAwardTaken(group, taken);
        setDirty();
        notify(new Change(server, player, Kind.AWARD, null, 0, p));
    }

    /** Records the player's highest BSP-Core points this season (D48); shared with the season values. */
    public void setScorePeak(UUID player, long peak) {
        Progress p = progress(player);
        p.setScorePeak(peak);
        setDirty();
        notify(new Change(server, player, Kind.SEASON, null, 0, p));
    }

    /** Records a rung whose rewards the player has now had once. */
    public void addRewarded(UUID player, String group) {
        Progress p = progress(player);
        java.util.Set<String> set = new java.util.LinkedHashSet<>(p.rewarded());
        if (set.add(group)) {
            p.setRewarded(set);
            setDirty();
            notify(new Change(server, player, Kind.REWARDED, null, 0, p));
        }
    }

    public int lastEndedSeason() {
        return lastEndedSeason;
    }

    public void setLastEndedSeason(int season) {
        if (season > lastEndedSeason) {
            lastEndedSeason = season;
            setDirty();
        }
    }

    /** Keeps rewards for a player who is not here to receive them; {@link #takePending} hands them out on login. */
    public void addPending(UUID player, List<Reward> rewards) {
        if (rewards.isEmpty()) {
            return;
        }
        Progress p = progress(player);
        p.addPending(rewards);
        setDirty();
        notify(new Change(server, player, Kind.PENDING, null, 0, p));
    }

    public List<Reward> takePending(UUID player) {
        if (!has(player)) {
            return List.of();
        }
        Progress p = progress(player);
        List<Reward> out = p.takePending();
        if (!out.isEmpty()) {
            setDirty();
            notify(new Change(server, player, Kind.PENDING, null, 0, p));
        }
        return out;
    }

    /** Forgets everything about the player: counts, bonus and rank. */
    public void reset(UUID player) {
        players.remove(player);
        setDirty();
        notify(new Change(server, player, Kind.RESET, null, 0, new Progress()));
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
        tag.putInt("LastEndedSeason", lastEndedSeason);
        return tag;
    }

    public static ProgressLedger load(CompoundTag tag) {
        ProgressLedger ledger = new ProgressLedger();
        ledger.lastEndedSeason = tag.getInt("LastEndedSeason");
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
