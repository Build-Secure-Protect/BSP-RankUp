package com.mrgregles.bsp_rankup.progress;

import com.mrgregles.bsp_rankup.settings.Reward;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private int season;
    private long seasonBase;
    private final Set<String> rewarded = new LinkedHashSet<>();

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

    /** The number of the last season whose end was applied to this player (0: none yet). */
    public int season() {
        return season;
    }

    /** The player's points when that season ended; seasonal rungs are judged on points above it. */
    public long seasonBase() {
        return seasonBase;
    }

    void setSeason(int season, long base) {
        this.season = season;
        this.seasonBase = base;
    }

    /** Groups whose rewards this player has received once (for ladders that reward a rung only once). */
    public Set<String> rewarded() {
        return Set.copyOf(rewarded);
    }

    void setRewarded(Set<String> groups) {
        rewarded.clear();
        rewarded.addAll(groups);
    }

    public boolean isEmpty() {
        return counts.isEmpty() && bonus == 0 && rank == null && pending.isEmpty() && season == 0 && rewarded.isEmpty();
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
        if (season != 0) {
            tag.putInt("Season", season);
            tag.putLong("SeasonBase", seasonBase);
        }
        if (!rewarded.isEmpty()) {
            ListTag list = new ListTag();
            for (String g : rewarded) {
                list.add(net.minecraft.nbt.StringTag.valueOf(g));
            }
            tag.put("Rewarded", list);
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
        p.season = tag.getInt("Season");
        p.seasonBase = tag.getLong("SeasonBase");
        ListTag rewarded = tag.getList("Rewarded", Tag.TAG_STRING);
        for (int i = 0; i < rewarded.size(); i++) {
            p.rewarded.add(rewarded.getString(i));
        }
        return p;
    }

    @Override
    public String toString() {
        return "Progress" + counts + (bonus != 0 ? " bonus " + bonus : "") + (rank != null ? " rank " + rank : "") + (pending.isEmpty() ? "" : " pending " + pending.size()) + (season == 0 ? "" : " season " + season + " base " + seasonBase);
    }
}
