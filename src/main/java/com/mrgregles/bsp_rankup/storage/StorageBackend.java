package com.mrgregles.bsp_rankup.storage;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the shared storage needs from a database: counts and bonus points as additive deltas (so two servers'
 * playtime adds up), the rank and the waiting rewards as values, the settings as one JSON document with a version,
 * and a notice table carrying messages between servers when Redis is not there.
 */
public interface StorageBackend {
    /** One player's row as stored. */
    /**
     * {@code resetSeason}: the season ended by the last season reset this row was started over for, or written from
     * scratch after (0: none). Only the season reset's writes and a row's first write set it (owner decision SR12).
     */
    record PlayerRow(UUID player, Map<String, Long> counts, long bonus, @Nullable String rank, long rankedAt, @Nullable String pendingJson, int season, long seasonBase, long scorePeak, @Nullable String rewarded, @Nullable LadderValue ladders,
                     int resetSeason) {
        public PlayerRow(UUID player, Map<String, Long> counts, long bonus, @Nullable String rank, long rankedAt, @Nullable String pendingJson, int season, long seasonBase, long scorePeak, @Nullable String rewarded, @Nullable LadderValue ladders) {
            this(player, counts, bonus, rank, rankedAt, pendingJson, season, seasonBase, scorePeak, rewarded, ladders, 0);
        }
    }

    /**
     * The later ladders' rungs, the season bases kept by name and the awards reached (D49 to D51), as text set together
     * (the latest wins). Null in a row means never written (a row from RankUp 0.2): the server's own copy then stays.
     * Each is lines of tab-separated parts: {@code ladder group atMs}, {@code key base}, {@code group atMs}.
     */
    record LadderValue(String ranks, String bases, String awards) {
        public static final LadderValue NONE = new LadderValue("", "", "");

        public static LadderValue of(com.mrgregles.bsp_rankup.progress.Progress p) {
            StringBuilder r = new StringBuilder();
            p.otherRanks().forEach((ladder, held) -> r.append(ladder).append('\t').append(held.group()).append('\t').append(held.atMs()).append('\n'));
            StringBuilder b = new StringBuilder();
            p.otherBases().forEach((key, base) -> b.append(key).append('\t').append(base).append('\n'));
            StringBuilder a = new StringBuilder();
            p.awardMarks().forEach((group, at) -> a.append(group).append('\t').append(at).append('\n')); // taken: a negative time (D115)
            return new LadderValue(r.toString(), b.toString(), a.toString());
        }

        public Map<String, com.mrgregles.bsp_rankup.progress.Progress.Held> rankMap() {
            Map<String, com.mrgregles.bsp_rankup.progress.Progress.Held> out = new java.util.LinkedHashMap<>();
            for (String[] parts : lines(ranks, 3)) {
                out.put(parts[0], new com.mrgregles.bsp_rankup.progress.Progress.Held(parts[1], number(parts[2])));
            }
            return out;
        }

        public Map<String, Long> baseMap() {
            return pairs(bases);
        }

        public Map<String, Long> awardMap() {
            return pairs(awards);
        }

        private static Map<String, Long> pairs(String text) {
            Map<String, Long> out = new java.util.LinkedHashMap<>();
            for (String[] parts : lines(text, 2)) {
                out.put(parts[0], number(parts[1]));
            }
            return out;
        }

        /** The lines with exactly {@code n} parts; a malformed line (a hand edit) is skipped. */
        private static List<String[]> lines(String text, int n) {
            List<String[]> out = new java.util.ArrayList<>();
            for (String line : text.split("\n")) {
                String[] parts = line.split("\t", -1);
                if (parts.length == n && !parts[0].isEmpty()) {
                    out.add(parts);
                }
            }
            return out;
        }

        private static long number(String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    record SettingsRow(String json, long version) {
    }

    /** A notice between servers; {@code id} is 0 until the table assigns one. */
    record Notice(long id, String uid, long createdAt, String source, String kind, String payload) {
    }

    /** A value set outright, which may itself be null ("none"). */
    record Text(@Nullable String text) {
    }

    record RankValue(@Nullable String rank, long rankedAt) {
    }

    /** The season values set together: the last season applied, the points then, and the score peak since (D48). */
    record SeasonValue(int season, long seasonBase, long scorePeak) {
    }

    /**
     * One player's changes since the last write, merged: a delete first when the player was reset, then count and
     * bonus deltas added, then the values set outright (null: unchanged, last write wins). {@code resetSeason} is the
     * last season reset the writing server had heard of; {@code startOver} marks the season reset's own rewrite of the row.
     */
    record PlayerWrite(UUID player, boolean delete, Map<String, Long> counts, long bonus, @Nullable RankValue rank, @Nullable Text pending, @Nullable SeasonValue season, @Nullable Text rewarded,
                       @Nullable LadderValue ladders, int resetSeason, boolean startOver) {
        public PlayerWrite(UUID player, boolean delete, Map<String, Long> counts, long bonus, @Nullable RankValue rank, @Nullable Text pending, @Nullable SeasonValue season, @Nullable Text rewarded,
                           @Nullable LadderValue ladders) {
            this(player, delete, counts, bonus, rank, pending, season, rewarded, ladders, 0, false);
        }
    }

    /**
     * Whether a write may go onto a row whose reset marker is {@code rowReset} (-1: no row at all). A server that has
     * not heard of the season reset the row was started over for writes nothing onto it (its copy is from before);
     * the season reset's rewrite of a row already started over for that reset (by a server that read it) is left out.
     */
    static boolean admits(int rowReset, PlayerWrite w) {
        if (rowReset < 0) {
            return true;
        }
        if (rowReset > w.resetSeason()) {
            return false;
        }
        return !w.startOver() || rowReset < w.resetSeason();
    }

    /** Whether a write starts the row afresh, so it carries the writer's reset marker: a delete, or a row that did not exist. */
    static boolean marks(int rowReset, PlayerWrite w) {
        return w.resetSeason() > 0 && (w.delete() || rowReset < 0);
    }

    void init() throws Exception;

    /**
     * Writes every player's changes, all or none, and says whose were written: a write {@link #admits} refuses is
     * left out. A backend with transactions overrides this to decide and write under one lock.
     */
    default List<UUID> write(List<PlayerWrite> writes) throws Exception {
        List<UUID> written = new java.util.ArrayList<>();
        for (PlayerWrite w : writes) {
            PlayerRow now = loadPlayer(w.player());
            int rowReset = now == null ? -1 : now.resetSeason();
            if (!admits(rowReset, w)) {
                continue;
            }
            if (w.delete()) {
                deletePlayer(w.player());
            }
            for (Map.Entry<String, Long> e : w.counts().entrySet()) {
                addCount(w.player(), e.getKey(), e.getValue());
            }
            if (w.bonus() != 0) {
                addBonus(w.player(), w.bonus());
            }
            if (w.rank() != null) {
                setRank(w.player(), w.rank().rank(), w.rank().rankedAt());
            }
            if (w.pending() != null) {
                setPending(w.player(), w.pending().text());
            }
            if (w.season() != null) {
                setSeason(w.player(), w.season().season(), w.season().seasonBase(), w.season().scorePeak());
            }
            if (w.rewarded() != null) {
                setRewarded(w.player(), w.rewarded().text());
            }
            if (w.ladders() != null) {
                setLadders(w.player(), w.ladders());
            }
            if (marks(rowReset, w)) {
                markReset(w.player(), w.resetSeason());
            }
            written.add(w.player());
        }
        return written;
    }

    /** Raises the row's reset marker to {@code season} (never lowers it), making the row if there is none. */
    void markReset(UUID player, int season) throws Exception;

    void addCount(UUID player, String signal, long delta) throws Exception;

    void addBonus(UUID player, long delta) throws Exception;

    void setRank(UUID player, @Nullable String rank, long rankedAt) throws Exception;

    void setPending(UUID player, @Nullable String pendingJson) throws Exception;

    void setSeason(UUID player, int season, long seasonBase, long scorePeak) throws Exception;

    /** The groups rewarded once, as a comma-separated list (null for none). */
    void setRewarded(UUID player, @Nullable String rewarded) throws Exception;

    /** The later ladders' rungs, the bases kept by name and the awards (D49 to D51), set together. */
    void setLadders(UUID player, LadderValue ladders) throws Exception;

    void deletePlayer(UUID player) throws Exception;

    @Nullable
    PlayerRow loadPlayer(UUID player) throws Exception;

    /**
     * Every player's row at once (counts-only rows too), by player: the season reset's pass over the shared rows
     * (owner decision SR12) reads them in one go rather than one player at a time.
     */
    Map<UUID, PlayerRow> loadAllPlayers() throws Exception;

    void saveSettings(String json, long version) throws Exception;

    @Nullable
    SettingsRow loadSettings() throws Exception;

    /** Keeps a prefix image's bytes under its id (D59); an id already kept is left as it is (the id is the bytes' hash). */
    void saveImage(String id, byte[] png) throws Exception;

    /** A prefix image's bytes, or null when no server has kept it. */
    @Nullable
    byte[] loadImage(String id) throws Exception;

    long publish(Notice notice) throws Exception;

    List<Notice> fetchNoticesAfter(long id, int limit) throws Exception;

    long latestNoticeId() throws Exception;

    /** Deletes the notices created before the given time; returns how many went. */
    int deleteNoticesBefore(long createdAtMs) throws Exception;

    String describe();

    void close() throws Exception;
}
