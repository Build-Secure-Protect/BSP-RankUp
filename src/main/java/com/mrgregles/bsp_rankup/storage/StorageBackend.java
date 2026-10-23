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
    record PlayerRow(UUID player, Map<String, Long> counts, long bonus, @Nullable String rank, long rankedAt, @Nullable String pendingJson, int season, long seasonBase, long scorePeak, @Nullable String rewarded) {
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
     * bonus deltas added, then the values set outright (null: unchanged, last write wins).
     */
    record PlayerWrite(UUID player, boolean delete, Map<String, Long> counts, long bonus, @Nullable RankValue rank, @Nullable Text pending, @Nullable SeasonValue season, @Nullable Text rewarded) {
    }

    void init() throws Exception;

    /** Writes every player's changes, all or none: a backend with transactions overrides this with one. */
    default void write(List<PlayerWrite> writes) throws Exception {
        for (PlayerWrite w : writes) {
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
        }
    }

    void addCount(UUID player, String signal, long delta) throws Exception;

    void addBonus(UUID player, long delta) throws Exception;

    void setRank(UUID player, @Nullable String rank, long rankedAt) throws Exception;

    void setPending(UUID player, @Nullable String pendingJson) throws Exception;

    void setSeason(UUID player, int season, long seasonBase, long scorePeak) throws Exception;

    /** The groups rewarded once, as a comma-separated list (null for none). */
    void setRewarded(UUID player, @Nullable String rewarded) throws Exception;

    void deletePlayer(UUID player) throws Exception;

    @Nullable
    PlayerRow loadPlayer(UUID player) throws Exception;

    void saveSettings(String json, long version) throws Exception;

    @Nullable
    SettingsRow loadSettings() throws Exception;

    long publish(Notice notice) throws Exception;

    List<Notice> fetchNoticesAfter(long id, int limit) throws Exception;

    long latestNoticeId() throws Exception;

    /** Deletes the notices created before the given time; returns how many went. */
    int deleteNoticesBefore(long createdAtMs) throws Exception;

    String describe();

    void close() throws Exception;
}
