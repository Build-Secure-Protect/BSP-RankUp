package com.mrgregles.bsp_rankup.settings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * One rung of the ladder: the LuckPerms group, the name shown to players, the points needed, whether a season
 * end takes it away (owner decision D15) and what reaching it gives.
 */
public record Rank(String group, String displayName, long points, boolean seasonal, List<Reward> rewards) {
    public Rank {
        rewards = List.copyOf(rewards);
    }

    public static Rank of(String group, String displayName, long points) {
        return new Rank(group, displayName, points, false, List.of());
    }

    public Rank withRewards(List<Reward> rewards) {
        return new Rank(group, displayName, points, seasonal, rewards);
    }

    public Rank withSeasonal(boolean seasonal) {
        return new Rank(group, displayName, points, seasonal, rewards);
    }

    public Rank withPoints(long points) {
        return new Rank(group, displayName, points, seasonal, rewards);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Group", group);
        tag.putString("Name", displayName);
        tag.putLong("Points", points);
        tag.putBoolean("Seasonal", seasonal);
        ListTag list = new ListTag();
        for (Reward r : rewards) {
            list.add(r.save());
        }
        tag.put("Rewards", list);
        return tag;
    }

    public static Rank load(CompoundTag tag) {
        List<Reward> rewards = new ArrayList<>();
        ListTag list = tag.getList("Rewards", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            rewards.add(Reward.load(list.getCompound(i)));
        }
        return new Rank(tag.getString("Group"), tag.getString("Name"), tag.getLong("Points"), tag.getBoolean("Seasonal"), rewards);
    }
}
