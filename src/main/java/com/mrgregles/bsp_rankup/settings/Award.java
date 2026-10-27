package com.mrgregles.bsp_rankup.settings;

import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A LuckPerms group on no ladder that RankUp gives on its own (owner decisions D50, D51): reached when a player's
 * points from the counted signals (none chosen: all of them), plus their admin bonus, come to {@code points}. The
 * group is then added to the player in LuckPerms and kept, unless the award is seasonal; its rewards are given once.
 */
public record Award(String group, String displayName, Set<Signal> counts, long points, boolean seasonal, List<Reward> rewards) {
    public Award {
        group = group.toLowerCase(Locale.ROOT);
        counts = Counts.copyOf(counts);
        rewards = List.copyOf(rewards);
    }

    public static Award of(String group, String displayName, Set<Signal> counts, long points) {
        return new Award(group, displayName, counts, points, false, List.of());
    }

    public boolean counts(Signal signal) {
        return Counts.counts(counts, signal);
    }

    public Award withDisplayName(String name) {
        return new Award(group, name, counts, points, seasonal, rewards);
    }

    public Award withCounts(Set<Signal> counts) {
        return new Award(group, displayName, counts, points, seasonal, rewards);
    }

    public Award withPoints(long points) {
        return new Award(group, displayName, counts, points, seasonal, rewards);
    }

    public Award withSeasonal(boolean seasonal) {
        return new Award(group, displayName, counts, points, seasonal, rewards);
    }

    public Award withRewards(List<Reward> rewards) {
        return new Award(group, displayName, counts, points, seasonal, rewards);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Group", group);
        tag.putString("Name", displayName);
        tag.put("Counts", Counts.save(counts));
        tag.putLong("Points", points);
        tag.putBoolean("Seasonal", seasonal);
        ListTag list = new ListTag();
        for (Reward r : rewards) {
            list.add(r.save());
        }
        tag.put("Rewards", list);
        return tag;
    }

    public static Award load(CompoundTag tag) {
        List<Reward> rewards = new ArrayList<>();
        ListTag list = tag.getList("Rewards", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            rewards.add(Reward.load(list.getCompound(i)));
        }
        String group = tag.getString("Group");
        return new Award(group, tag.contains("Name") ? tag.getString("Name") : group, Counts.load(tag, "Counts"), tag.getLong("Points"), tag.getBoolean("Seasonal"), rewards);
    }
}
