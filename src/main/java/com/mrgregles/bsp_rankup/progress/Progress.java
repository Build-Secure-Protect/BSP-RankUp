package com.mrgregles.bsp_rankup.progress;

import com.mrgregles.bsp_rankup.settings.Reward;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One player's counters: a raw count per {@link Signal}, bonus points an admin added, the rank group last given
 * by RankUp and when. Mutable; the ledger owns it and marks itself dirty when it changes.
 */
public final class Progress {
    private final EnumMap<Signal, Long> counts = new EnumMap<>(Signal.class);
    private long bonus;
    @Nullable
    private String rank;
    private long rankedAtMs;
    private final List<Reward> pending = new ArrayList<>();

    public long count(Signal signal) {
        return counts.getOrDefault(signal, 0L);
    }

    /** Every count, signals with a zero count left out. */
    public Map<Signal, Long> counts() {
        return Map.copyOf(counts);
    }

    void add(Signal signal, long amount) {
        counts.merge(signal, amount, Long::sum);
        if (counts.get(signal) <= 0) {
            counts.remove(signal);
        }
    }

    void set(Signal signal, long count) {
        if (count <= 0) {
            counts.remove(signal);
        } else {
            counts.put(signal, count);
        }
    }

    /** Points an admin added (or took, when negative) on top of what the signals give. */
    public long bonus() {
        return bonus;
    }

    void setBonus(long bonus) {
        this.bonus = bonus;
    }

    /** The LuckPerms group RankUp last put this player on, null when none yet. */
    @Nullable
    public String rank() {
        return rank;
    }

    public long rankedAtMs() {
        return rankedAtMs;
    }

    void setRank(@Nullable String rank, long atMs) {
        this.rank = rank;
        this.rankedAtMs = atMs;
    }

    /** Rewards earned while the player was away, handed over on their next login here. */
    public List<Reward> pending() {
        return List.copyOf(pending);
    }

    void addPending(List<Reward> rewards) {
        pending.addAll(rewards);
    }

    List<Reward> takePending() {
        List<Reward> out = List.copyOf(pending);
        pending.clear();
        return out;
    }

    public boolean isEmpty() {
        return counts.isEmpty() && bonus == 0 && rank == null && pending.isEmpty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag c = new CompoundTag();
        counts.forEach((s, n) -> c.putLong(s.key(), n));
        tag.put("Counts", c);
        if (bonus != 0) {
            tag.putLong("Bonus", bonus);
        }
        if (rank != null) {
            tag.putString("Rank", rank);
            tag.putLong("RankedAt", rankedAtMs);
        }
        if (!pending.isEmpty()) {
            ListTag list = new ListTag();
            for (Reward r : pending) {
                list.add(r.save());
            }
            tag.put("Pending", list);
        }
        return tag;
    }

    public static Progress load(CompoundTag tag) {
        Progress p = new Progress();
        CompoundTag c = tag.getCompound("Counts");
        for (String key : c.getAllKeys()) {
            Signal s = Signal.byKey(key);
            if (s != null) {
                p.set(s, c.getLong(key)); // an unknown key (a signal removed later) is dropped
            }
        }
        p.bonus = tag.getLong("Bonus");
        if (tag.contains("Rank")) {
            p.rank = tag.getString("Rank");
            p.rankedAtMs = tag.getLong("RankedAt");
        }
        ListTag list = tag.getList("Pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            p.pending.add(Reward.load(list.getCompound(i)));
        }
        return p;
    }

    @Override
    public String toString() {
        return "Progress" + counts + (bonus != 0 ? " bonus " + bonus : "") + (rank != null ? " rank " + rank : "") + (pending.isEmpty() ? "" : " pending " + pending.size());
    }
}
