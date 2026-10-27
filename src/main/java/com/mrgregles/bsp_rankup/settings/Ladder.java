package com.mrgregles.bsp_rankup.settings;

import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One ladder (owner decision D49): its name, the LuckPerms track it moves players along, the signals whose points
 * count on it (none chosen: all of them), its rungs first to last, and the priority its TITLE rewards are set at,
 * so two ladders' titles do not overwrite each other. A player holds one rung on each ladder.
 */
public record Ladder(String name, String track, Set<Signal> counts, List<Rank> ranks, int titlePriority) {
    /** Ladder names: lower-case letters, digits and underscores. */
    public static final String NAME_PATTERN = "[a-z0-9_]{1,24}";

    public Ladder {
        name = name.toLowerCase(Locale.ROOT);
        counts = Counts.copyOf(counts);
        ranks = List.copyOf(ranks);
    }

    public static Ladder of(String name, String track) {
        return new Ladder(name, track, Set.of(), List.of(), Settings.DEFAULT_TITLE_PRIORITY);
    }

    /** A name made from a track for the first ladder (D51): its lower-case letters, digits and underscores. */
    public static String nameFor(String track) {
        String n = track.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        n = n.length() > 24 ? n.substring(0, 24) : n;
        return n.isEmpty() ? "ladder" : n;
    }

    public boolean counts(Signal signal) {
        return Counts.counts(counts, signal);
    }

    /** The rung with this group name, or -1. */
    public int indexOf(@Nullable String group) {
        if (group == null) {
            return -1;
        }
        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i).group().equalsIgnoreCase(group)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    public Rank rank(String group) {
        int i = indexOf(group);
        return i < 0 ? null : ranks.get(i);
    }

    public Ladder withTrack(String track) {
        return new Ladder(name, track, counts, ranks, titlePriority);
    }

    public Ladder withCounts(Set<Signal> counts) {
        return new Ladder(name, track, counts, ranks, titlePriority);
    }

    public Ladder withRanks(List<Rank> ranks) {
        return new Ladder(name, track, counts, ranks, titlePriority);
    }

    public Ladder withTitlePriority(int priority) {
        return new Ladder(name, track, counts, ranks, priority);
    }

    public Ladder withName(String name) {
        return new Ladder(name, track, counts, ranks, titlePriority);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putString("Track", track);
        tag.put("Counts", Counts.save(counts));
        tag.putInt("TitlePriority", titlePriority);
        ListTag list = new ListTag();
        for (Rank r : ranks) {
            list.add(r.save());
        }
        tag.put("Ranks", list);
        return tag;
    }

    public static Ladder load(CompoundTag tag) {
        List<Rank> ranks = new ArrayList<>();
        ListTag list = tag.getList("Ranks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ranks.add(Rank.load(list.getCompound(i)));
        }
        String track = tag.contains("Track") ? tag.getString("Track") : Settings.DEFAULT_TRACK;
        String name = tag.contains("Name") ? tag.getString("Name") : nameFor(track);
        int priority = tag.contains("TitlePriority") ? tag.getInt("TitlePriority") : Settings.DEFAULT_TITLE_PRIORITY;
        return new Ladder(name, track, Counts.load(tag, "Counts"), ranks, priority);
    }
}
