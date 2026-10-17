package com.mrgregles.bsp_rankup.settings;

import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the admin screen edits (owner decisions D11, D19): the track, the ladder, the signals' worth, the
 * options. Immutable; {@link SettingsLedger} holds the current one and hands out changed copies through
 * {@link #toBuilder()}.
 */
public final class Settings {
    public static final String DEFAULT_TRACK = "rankup";
    public static final int DEFAULT_TITLE_PRIORITY = 1000;
    public static final int DEFAULT_KILL_COOLDOWN_MINUTES = 10;

    /** Where rank-ups are announced (owner decision D8). NETWORK needs the shared storage (RU-5); until then it acts as SERVER. */
    public enum Announce { OFF, SERVER, NETWORK }

    private final String track;
    private final List<Rank> ranks;
    private final EnumMap<Signal, SignalSetting> signals;
    private final int killCooldownMinutes;
    private final Announce announce;
    private final int titlePriority;
    private final boolean rewardsAgainEachSeason;

    private Settings(Builder b) {
        this.track = b.track;
        this.ranks = List.copyOf(b.ranks);
        this.signals = new EnumMap<>(b.signals);
        this.killCooldownMinutes = b.killCooldownMinutes;
        this.announce = b.announce;
        this.titlePriority = b.titlePriority;
        this.rewardsAgainEachSeason = b.rewardsAgainEachSeason;
    }

    /** The defaults: track {@code rankup}, no ranks (the admin adds them), every signal at its own default. */
    public static Settings defaults() {
        return new Builder().build();
    }

    public String track() {
        return track;
    }

    /** The ladder, first rung first (the LuckPerms track's order). */
    public List<Rank> ranks() {
        return ranks;
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

    public SignalSetting signal(Signal signal) {
        return signals.getOrDefault(signal, SignalSetting.defaults(signal));
    }

    public Map<Signal, SignalSetting> signals() {
        Map<Signal, SignalSetting> out = new EnumMap<>(Signal.class);
        for (Signal s : Signal.values()) {
            out.put(s, signal(s));
        }
        return out;
    }

    public int killCooldownMinutes() {
        return killCooldownMinutes;
    }

    public Announce announce() {
        return announce;
    }

    public int titlePriority() {
        return titlePriority;
    }

    public boolean rewardsAgainEachSeason() {
        return rewardsAgainEachSeason;
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.track = track;
        b.ranks = new ArrayList<>(ranks);
        b.signals = new EnumMap<>(signals);
        b.killCooldownMinutes = killCooldownMinutes;
        b.announce = announce;
        b.titlePriority = titlePriority;
        b.rewardsAgainEachSeason = rewardsAgainEachSeason;
        return b;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Track", track);
        ListTag list = new ListTag();
        for (Rank r : ranks) {
            list.add(r.save());
        }
        tag.put("Ranks", list);
        CompoundTag sig = new CompoundTag();
        signals.forEach((s, v) -> sig.put(s.key(), v.save()));
        tag.put("Signals", sig);
        tag.putInt("KillCooldownMinutes", killCooldownMinutes);
        tag.putString("Announce", announce.name());
        tag.putInt("TitlePriority", titlePriority);
        tag.putBoolean("RewardsAgainEachSeason", rewardsAgainEachSeason);
        return tag;
    }

    public static Settings load(CompoundTag tag) {
        Builder b = new Builder();
        if (tag.contains("Track")) {
            b.track = tag.getString("Track");
        }
        ListTag list = tag.getList("Ranks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            b.ranks.add(Rank.load(list.getCompound(i)));
        }
        CompoundTag sig = tag.getCompound("Signals");
        for (Signal s : Signal.values()) {
            if (sig.contains(s.key())) {
                b.signals.put(s, SignalSetting.load(sig.getCompound(s.key()), s));
            }
        }
        if (tag.contains("KillCooldownMinutes")) {
            b.killCooldownMinutes = tag.getInt("KillCooldownMinutes");
        }
        if (tag.contains("Announce")) {
            try {
                b.announce = Announce.valueOf(tag.getString("Announce").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // keep the default
            }
        }
        if (tag.contains("TitlePriority")) {
            b.titlePriority = tag.getInt("TitlePriority");
        }
        if (tag.contains("RewardsAgainEachSeason")) {
            b.rewardsAgainEachSeason = tag.getBoolean("RewardsAgainEachSeason");
        }
        return b.build();
    }

    public static final class Builder {
        private String track = DEFAULT_TRACK;
        private List<Rank> ranks = new ArrayList<>();
        private EnumMap<Signal, SignalSetting> signals = new EnumMap<>(Signal.class);
        private int killCooldownMinutes = DEFAULT_KILL_COOLDOWN_MINUTES;
        private Announce announce = Announce.SERVER;
        private int titlePriority = DEFAULT_TITLE_PRIORITY;
        private boolean rewardsAgainEachSeason = true;

        public Builder() {
            for (Signal s : Signal.values()) {
                signals.put(s, SignalSetting.defaults(s));
            }
        }

        public Builder track(String track) {
            this.track = track;
            return this;
        }

        public Builder ranks(List<Rank> ranks) {
            this.ranks = new ArrayList<>(ranks);
            return this;
        }

        public Builder addRank(Rank rank) {
            ranks.add(rank);
            return this;
        }

        public Builder setRank(int index, Rank rank) {
            ranks.set(index, rank);
            return this;
        }

        public Builder removeRank(int index) {
            ranks.remove(index);
            return this;
        }

        public Builder signal(Signal signal, SignalSetting setting) {
            signals.put(signal, setting);
            return this;
        }

        public Builder killCooldownMinutes(int minutes) {
            this.killCooldownMinutes = Math.max(0, minutes);
            return this;
        }

        public Builder announce(Announce announce) {
            this.announce = announce;
            return this;
        }

        public Builder titlePriority(int priority) {
            this.titlePriority = priority;
            return this;
        }

        public Builder rewardsAgainEachSeason(boolean again) {
            this.rewardsAgainEachSeason = again;
            return this;
        }

        public Settings build() {
            return new Settings(this);
        }
    }
}
