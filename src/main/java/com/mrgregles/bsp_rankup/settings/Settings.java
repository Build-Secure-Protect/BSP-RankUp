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
 * Everything the admin screen edits (owner decisions D11, D19, D49 to D51): the ladders, each with its LuckPerms
 * track, the signals it counts, its rungs and its title priority; the awards (groups on no ladder given on their own);
 * the signals' worth; the options. Immutable; {@link SettingsLedger} holds the current one and hands out changed
 * copies through {@link #toBuilder()}.
 *
 * <p>There is always at least one ladder. {@link #track()}, {@link #ranks()}, {@link #indexOf}, {@link #rank} and
 * {@link #titlePriority()} answer for the first ladder, as RankUp had only one before D49; code that knows about
 * ladders uses {@link #ladders()}. Saved settings keep the first ladder in the old single-ladder form too, so a
 * backend on an older RankUp still reads it.
 */
public final class Settings {
    public static final String DEFAULT_TRACK = "rankup";
    public static final int DEFAULT_TITLE_PRIORITY = 1000;
    public static final int DEFAULT_KILL_COOLDOWN_MINUTES = 10;

    /** Where rank-ups are announced (owner decision D8). NETWORK needs the shared storage (RU-5); until then it acts as SERVER. */
    public enum Announce { OFF, SERVER, NETWORK }

    private final List<Ladder> ladders;
    private final List<Award> awards;
    private final EnumMap<Signal, SignalSetting> signals;
    private final int killCooldownMinutes;
    private final Announce announce;
    private final boolean rewardsAgainEachSeason;
    /** Images drawn in place of prefixes, by rank key (D57); see {@link PrefixImage}. */
    private final Map<String, PrefixImage> prefixImages;
    private final boolean showPrefixes;

    private Settings(Builder b) {
        this.ladders = b.ladders.isEmpty() ? List.of(Ladder.of(DEFAULT_TRACK, DEFAULT_TRACK)) : List.copyOf(b.ladders);
        this.awards = List.copyOf(b.awards);
        this.signals = new EnumMap<>(b.signals);
        this.killCooldownMinutes = b.killCooldownMinutes;
        this.announce = b.announce;
        this.rewardsAgainEachSeason = b.rewardsAgainEachSeason;
        this.prefixImages = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(b.prefixImages));
        this.showPrefixes = b.showPrefixes;
    }

    /** The defaults: one ladder {@code rankup} on track {@code rankup} counting every signal, no ranks, no awards. */
    public static Settings defaults() {
        return new Builder().build();
    }

    // ------------------------------------------------------------------ ladders and awards

    /** Every ladder, the first one first (never empty). */
    public List<Ladder> ladders() {
        return ladders;
    }

    /** The first ladder: today's ladder for settings made before D49. */
    public Ladder first() {
        return ladders.get(0);
    }

    @Nullable
    public Ladder ladder(String name) {
        for (Ladder l : ladders) {
            if (l.name().equalsIgnoreCase(name)) {
                return l;
            }
        }
        return null;
    }

    /** The ladder that has a rung on this group, or null. */
    @Nullable
    public Ladder ladderOf(@Nullable String group) {
        for (Ladder l : ladders) {
            if (l.indexOf(group) >= 0) {
                return l;
            }
        }
        return null;
    }

    public List<Award> awards() {
        return awards;
    }

    @Nullable
    public Award award(@Nullable String group) {
        if (group == null) {
            return null;
        }
        for (Award a : awards) {
            if (a.group().equalsIgnoreCase(group)) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the first ladder (before D49 the only one)

    public String track() {
        return first().track();
    }

    /** The first ladder's rungs, first rung first (the LuckPerms track's order). */
    public List<Rank> ranks() {
        return first().ranks();
    }

    /** The first ladder's rung with this group name, or -1. */
    public int indexOf(@Nullable String group) {
        return first().indexOf(group);
    }

    @Nullable
    public Rank rank(String group) {
        return first().rank(group);
    }

    public int titlePriority() {
        return first().titlePriority();
    }

    // ------------------------------------------------------------------ signals and options

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

    public boolean rewardsAgainEachSeason() {
        return rewardsAgainEachSeason;
    }

    /** Every prefix image by rank key, keys in order. */
    public Map<String, PrefixImage> prefixImages() {
        return prefixImages;
    }

    @Nullable
    public PrefixImage prefixImage(String key) {
        return prefixImages.get(key);
    }

    /** Whether RankUp puts the player's LuckPerms prefix before their name in chat and the tab list (D55). */
    public boolean showPrefixes() {
        return showPrefixes;
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.ladders = new ArrayList<>(ladders);
        b.awards = new ArrayList<>(awards);
        b.signals = new EnumMap<>(signals);
        b.killCooldownMinutes = killCooldownMinutes;
        b.announce = announce;
        b.rewardsAgainEachSeason = rewardsAgainEachSeason;
        b.prefixImages = new java.util.TreeMap<>(prefixImages);
        b.showPrefixes = showPrefixes;
        return b;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag ladderList = new ListTag();
        for (Ladder l : ladders) {
            ladderList.add(l.save());
        }
        tag.put("Ladders", ladderList);
        ListTag awardList = new ListTag();
        for (Award a : awards) {
            awardList.add(a.save());
        }
        tag.put("Awards", awardList);
        // the first ladder as RankUp 0.2 wrote it, for a backend that has not updated yet
        tag.putString("Track", first().track());
        ListTag list = new ListTag();
        for (Rank r : first().ranks()) {
            list.add(r.save());
        }
        tag.put("Ranks", list);
        tag.putInt("TitlePriority", first().titlePriority());
        CompoundTag sig = new CompoundTag();
        signals.forEach((s, v) -> sig.put(s.key(), v.save()));
        tag.put("Signals", sig);
        tag.putInt("KillCooldownMinutes", killCooldownMinutes);
        tag.putString("Announce", announce.name());
        tag.putBoolean("RewardsAgainEachSeason", rewardsAgainEachSeason);
        ListTag images = new ListTag();
        prefixImages.forEach((key, image) -> images.add(image.save(key)));
        tag.put("PrefixImages", images);
        tag.putBoolean("ShowPrefixes", showPrefixes);
        return tag;
    }

    public static Settings load(CompoundTag tag) {
        Builder b = new Builder();
        if (tag.contains("Ladders")) {
            ListTag ladderList = tag.getList("Ladders", Tag.TAG_COMPOUND);
            for (int i = 0; i < ladderList.size(); i++) {
                b.ladders.add(Ladder.load(ladderList.getCompound(i)));
            }
        } else {
            // settings from before D49: their ladder becomes the first ladder, named after its track, counting every signal
            b.ladders.add(Ladder.load(tag));
        }
        ListTag awardList = tag.getList("Awards", Tag.TAG_COMPOUND);
        for (int i = 0; i < awardList.size(); i++) {
            b.awards.add(Award.load(awardList.getCompound(i)));
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
        if (tag.contains("RewardsAgainEachSeason")) {
            b.rewardsAgainEachSeason = tag.getBoolean("RewardsAgainEachSeason");
        }
        ListTag images = tag.getList("PrefixImages", Tag.TAG_COMPOUND);
        for (int i = 0; i < images.size(); i++) {
            CompoundTag image = images.getCompound(i);
            String key = image.getString("Key");
            if (PrefixImage.isKey(key) && !image.getString("Id").isEmpty()) {
                b.prefixImages.put(key, PrefixImage.load(image));
            }
        }
        if (tag.contains("ShowPrefixes")) {
            b.showPrefixes = tag.getBoolean("ShowPrefixes");
        }
        return b.build();
    }

    public static final class Builder {
        private List<Ladder> ladders = new ArrayList<>();
        private List<Award> awards = new ArrayList<>();
        private EnumMap<Signal, SignalSetting> signals = new EnumMap<>(Signal.class);
        private int killCooldownMinutes = DEFAULT_KILL_COOLDOWN_MINUTES;
        private Announce announce = Announce.SERVER;
        private boolean rewardsAgainEachSeason = true;
        private Map<String, PrefixImage> prefixImages = new java.util.TreeMap<>();
        private boolean showPrefixes = true;

        public Builder() {
            for (Signal s : Signal.values()) {
                signals.put(s, SignalSetting.defaults(s));
            }
        }

        // ---------------------------------------------------------- ladders

        public Builder ladders(List<Ladder> ladders) {
            this.ladders = new ArrayList<>(ladders);
            return this;
        }

        public Builder addLadder(Ladder ladder) {
            ladders.add(ladder);
            return this;
        }

        /** Replaces the ladder with the same name, or adds it. */
        public Builder ladder(Ladder ladder) {
            for (int i = 0; i < ladders.size(); i++) {
                if (ladders.get(i).name().equals(ladder.name())) {
                    ladders.set(i, ladder);
                    return this;
                }
            }
            ladders.add(ladder);
            return this;
        }

        public Builder removeLadder(String name) {
            ladders.removeIf(l -> l.name().equalsIgnoreCase(name));
            return this;
        }

        /** The first ladder, made when there is none. */
        private Ladder firstLadder() {
            if (ladders.isEmpty()) {
                ladders.add(Ladder.of(DEFAULT_TRACK, DEFAULT_TRACK));
            }
            return ladders.get(0);
        }

        private Builder editFirst(java.util.function.UnaryOperator<Ladder> edit) {
            Ladder first = firstLadder();
            ladders.set(0, edit.apply(first));
            return this;
        }

        // ---------------------------------------------------------- the first ladder (the edits of before D49)

        public Builder track(String track) {
            return editFirst(l -> l.withTrack(track));
        }

        public Builder ranks(List<Rank> ranks) {
            return editFirst(l -> l.withRanks(ranks));
        }

        public Builder addRank(Rank rank) {
            return editFirst(l -> {
                List<Rank> r = new ArrayList<>(l.ranks());
                r.add(rank);
                return l.withRanks(r);
            });
        }

        public Builder setRank(int index, Rank rank) {
            return editFirst(l -> {
                List<Rank> r = new ArrayList<>(l.ranks());
                r.set(index, rank);
                return l.withRanks(r);
            });
        }

        public Builder removeRank(int index) {
            return editFirst(l -> {
                List<Rank> r = new ArrayList<>(l.ranks());
                r.remove(index);
                return l.withRanks(r);
            });
        }

        public Builder titlePriority(int priority) {
            return editFirst(l -> l.withTitlePriority(priority));
        }

        // ---------------------------------------------------------- awards

        public Builder awards(List<Award> awards) {
            this.awards = new ArrayList<>(awards);
            return this;
        }

        /** Replaces the award for the same group, or adds it. */
        public Builder award(Award award) {
            for (int i = 0; i < awards.size(); i++) {
                if (awards.get(i).group().equals(award.group())) {
                    awards.set(i, award);
                    return this;
                }
            }
            awards.add(award);
            return this;
        }

        public Builder removeAward(String group) {
            awards.removeIf(a -> a.group().equalsIgnoreCase(group));
            return this;
        }

        // ---------------------------------------------------------- signals and options

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

        public Builder rewardsAgainEachSeason(boolean again) {
            this.rewardsAgainEachSeason = again;
            return this;
        }

        /** Sets the image for a rank key; null takes it away (the text shows again). */
        public Builder prefixImage(String key, @Nullable PrefixImage image) {
            if (image == null) {
                prefixImages.remove(key);
            } else {
                prefixImages.put(key, image);
            }
            return this;
        }

        public Builder showPrefixes(boolean show) {
            this.showPrefixes = show;
            return this;
        }

        public Settings build() {
            return new Settings(this);
        }
    }
}
