package com.mrgregles.bsp_rankup.settings;

import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which signals a ladder or an award counts (owner decisions D49, D50): a chosen few, or every signal when none is
 * chosen. Saved as a list of signal keys; a key from a newer RankUp is skipped.
 */
public final class Counts {
    private Counts() {
    }

    /** True when {@code chosen} counts {@code signal}: an empty set counts every signal. */
    public static boolean counts(Set<Signal> chosen, Signal signal) {
        return chosen.isEmpty() || chosen.contains(signal);
    }

    public static Set<Signal> copyOf(Set<Signal> chosen) {
        return chosen.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(chosen));
    }

    public static ListTag save(Set<Signal> chosen) {
        ListTag list = new ListTag();
        for (Signal s : Signal.values()) {
            if (chosen.contains(s)) {
                list.add(StringTag.valueOf(s.key()));
            }
        }
        return list;
    }

    public static Set<Signal> load(ListTag list) {
        EnumSet<Signal> out = EnumSet.noneOf(Signal.class);
        for (int i = 0; i < list.size(); i++) {
            Signal s = Signal.byKey(list.getString(i));
            if (s != null) {
                out.add(s);
            }
        }
        return copyOf(out);
    }

    public static Set<Signal> load(net.minecraft.nbt.CompoundTag tag, String key) {
        return load(tag.getList(key, Tag.TAG_STRING));
    }
}
