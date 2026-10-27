package com.mrgregles.bsp_rankup.progress;

import com.mrgregles.bsp_rankup.settings.Reward;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One player's counters: a raw count per {@link Signal}, bonus points an admin added, the rung RankUp last gave them
 * on each ladder and when, the awards they reached (D50) and the season bases. Mutable; the ledger owns it and marks
 * itself dirty when it changes.
 *
 * <p>The first ladder's rung and season base live in {@link #rank()}, {@link #rankedAtMs()} and {@link #seasonBase()},
 * as they did when RankUp had one ladder (and as the shared storage holds them); every other ladder's are kept by
 * the ladder's name. So the first ladder is a fixed role: it can be renamed but not removed or moved.
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
    /** The highest BSP-Core points seen this season (owner decision D48); the score signal counts what passes it. */
    private long scorePeak;
    private final Set<String> rewarded = new LinkedHashSet<>();
    /** The rung held on each ladder after the first, by ladder name. */
    private final Map<String, Held> otherRanks = new LinkedHashMap<>();
    /** Season bases of the ladders after the first and of seasonal awards ({@code award:<group>}), by that key. */
    private final Map<String, Long> otherBases = new LinkedHashMap<>();
    /** Awards reached (D50): the group and when. */
    private final Map<String, Long> awards = new LinkedHashMap<>();

    /** A rung held: the LuckPerms group and when RankUp gave it. */
    public record Held(String group, long atMs) {
    }

    /** The key a seasonal award's base is kept under. */
    public static String awardKey(String group) {
        return "award:" + group;
    }

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

    /** The rung held on a ladder after the first, by its name; null when none. */
    @Nullable
    public Held otherRank(String ladder) {
        return otherRanks.get(ladder);
    }

    public Map<String, Held> otherRanks() {
        return Map.copyOf(otherRanks);
    }

    void setOtherRank(String ladder, @Nullable String group, long atMs) {
        if (group == null) {
            otherRanks.remove(ladder);
        } else {
            otherRanks.put(ladder, new Held(group, atMs));
        }
    }

    /** The season base kept under a key (a later ladder's name, or {@link #awardKey}); 0 when none. */
    public long otherBase(String key) {
        return otherBases.getOrDefault(key, 0L);
    }

    public Map<String, Long> otherBases() {
        return Map.copyOf(otherBases);
    }

    void setOtherBases(Map<String, Long> bases) {
        otherBases.clear();
        bases.forEach((k, v) -> {
            if (v != 0) {
                otherBases.put(k, v);
            }
        });
    }

    /** True when the player reached this award (D50). */
    public boolean hasAward(String group) {
        return awards.containsKey(group);
    }

    /** Awards reached: group and when. */
    public Map<String, Long> awards() {
        return Map.copyOf(awards);
    }

    void setAward(String group, boolean reached, long atMs) {
        if (reached) {
            awards.put(group, atMs);
        } else {
            awards.remove(group);
        }
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

    /** The highest BSP-Core points seen this season (D48); 0 at the start of each season. */
    public long scorePeak() {
        return scorePeak;
    }

    void setScorePeak(long peak) {
        this.scorePeak = Math.max(0, peak);
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
        return counts.isEmpty() && bonus == 0 && rank == null && pending.isEmpty() && season == 0 && scorePeak == 0 && rewarded.isEmpty()
                && otherRanks.isEmpty() && otherBases.isEmpty() && awards.isEmpty();
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
        if (scorePeak != 0) {
            tag.putLong("ScorePeak", scorePeak);
        }
        if (!rewarded.isEmpty()) {
            ListTag list = new ListTag();
            for (String g : rewarded) {
                list.add(net.minecraft.nbt.StringTag.valueOf(g));
            }
            tag.put("Rewarded", list);
        }
        if (!otherRanks.isEmpty()) {
            CompoundTag r = new CompoundTag();
            otherRanks.forEach((ladder, held) -> {
                CompoundTag e = new CompoundTag();
                e.putString("Group", held.group());
                e.putLong("At", held.atMs());
                r.put(ladder, e);
            });
            tag.put("LadderRanks", r);
        }
        if (!otherBases.isEmpty()) {
            CompoundTag b = new CompoundTag();
            otherBases.forEach(b::putLong);
            tag.put("SeasonBases", b);
        }
        if (!awards.isEmpty()) {
            CompoundTag a = new CompoundTag();
            awards.forEach(a::putLong);
            tag.put("Awards", a);
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
        p.scorePeak = tag.getLong("ScorePeak");
        ListTag rewarded = tag.getList("Rewarded", Tag.TAG_STRING);
        for (int i = 0; i < rewarded.size(); i++) {
            p.rewarded.add(rewarded.getString(i));
        }
        CompoundTag r = tag.getCompound("LadderRanks");
        for (String ladder : r.getAllKeys()) {
            CompoundTag e = r.getCompound(ladder);
            p.otherRanks.put(ladder, new Held(e.getString("Group"), e.getLong("At")));
        }
        CompoundTag b = tag.getCompound("SeasonBases");
        for (String key : b.getAllKeys()) {
            p.otherBases.put(key, b.getLong(key));
        }
        CompoundTag a = tag.getCompound("Awards");
        for (String group : a.getAllKeys()) {
            p.awards.put(group, a.getLong(group));
        }
        return p;
    }

    @Override
    public String toString() {
        return "Progress" + counts + (bonus != 0 ? " bonus " + bonus : "") + (rank != null ? " rank " + rank : "") + (pending.isEmpty() ? "" : " pending " + pending.size()) + (season == 0 ? "" : " season " + season + " base " + seasonBase) + (scorePeak == 0 ? "" : " score peak " + scorePeak)
                + (otherRanks.isEmpty() ? "" : " ladders " + otherRanks) + (otherBases.isEmpty() ? "" : " bases " + otherBases) + (awards.isEmpty() ? "" : " awards " + awards.keySet());
    }
}
