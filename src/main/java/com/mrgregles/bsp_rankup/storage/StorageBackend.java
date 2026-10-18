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
    record PlayerRow(UUID player, Map<String, Long> counts, long bonus, @Nullable String rank, long rankedAt, @Nullable String pendingJson, int season, long seasonBase, @Nullable String rewarded) {
    }

    record SettingsRow(String json, long version) {
    }

    /** A notice between servers; {@code id} is 0 until the table assigns one. */
    record Notice(long id, String uid, long createdAt, String source, String kind, String payload) {
    }

    void init() throws Exception;

    void addCount(UUID player, String signal, long delta) throws Exception;

    void addBonus(UUID player, long delta) throws Exception;

    void setRank(UUID player, @Nullable String rank, long rankedAt) throws Exception;

    void setPending(UUID player, @Nullable String pendingJson) throws Exception;

    void setSeason(UUID player, int season, long seasonBase) throws Exception;

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

    String describe();

    void close() throws Exception;
}
