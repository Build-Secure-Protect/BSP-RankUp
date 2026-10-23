package com.mrgregles.bsp_rankup.storage;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The backend in memory, for tests: one instance shared by two storages plays one database shared by two servers. */
public final class MemoryBackend implements StorageBackend {
    private final Map<UUID, Map<String, Long>> counts = new HashMap<>();
    private final Map<UUID, long[]> bonusAndRankedAt = new HashMap<>();
    private final Map<UUID, String> ranks = new HashMap<>();
    private final Map<UUID, String> pending = new HashMap<>();
    private final Map<UUID, long[]> seasons = new HashMap<>();
    private final Map<UUID, String> rewarded = new HashMap<>();
    @Nullable
    private SettingsRow settings;
    private final List<Notice> notices = new ArrayList<>();
    private long lastNoticeId;
    private boolean failing;
    private int writes;
    private List<PlayerWrite> lastWrite = List.of();

    /** When true every call throws, playing a database outage. */
    public synchronized void setFailing(boolean failing) {
        this.failing = failing;
    }

    private void check() throws Exception {
        if (failing) {
            throw new Exception("database down (test)");
        }
    }

    @Override
    public synchronized void init() throws Exception {
        check();
    }

    /** All or none, as one transaction: the outage check comes before anything is written. */
    @Override
    public synchronized void write(List<PlayerWrite> batch) throws Exception {
        check();
        StorageBackend.super.write(batch);
        writes++;
        lastWrite = List.copyOf(batch);
    }

    /** How many batch writes arrived (tests). */
    public synchronized int writes() {
        return writes;
    }

    /** The last batch written (tests). */
    public synchronized List<PlayerWrite> lastWrite() {
        return lastWrite;
    }

    @Override
    public synchronized void addCount(UUID player, String signal, long delta) throws Exception {
        check();
        counts.computeIfAbsent(player, k -> new HashMap<>()).merge(signal, delta, Long::sum);
    }

    @Override
    public synchronized void addBonus(UUID player, long delta) throws Exception {
        check();
        bonusAndRankedAt.computeIfAbsent(player, k -> new long[2])[0] += delta;
    }

    @Override
    public synchronized void setRank(UUID player, @Nullable String rank, long rankedAt) throws Exception {
        check();
        if (rank == null) {
            ranks.remove(player);
        } else {
            ranks.put(player, rank);
        }
        bonusAndRankedAt.computeIfAbsent(player, k -> new long[2])[1] = rankedAt;
    }

    @Override
    public synchronized void setPending(UUID player, @Nullable String pendingJson) throws Exception {
        check();
        if (pendingJson == null) {
            pending.remove(player);
        } else {
            pending.put(player, pendingJson);
        }
    }

    @Override
    public synchronized void setSeason(UUID player, int season, long seasonBase, long scorePeak) throws Exception {
        check();
        seasons.put(player, new long[]{season, seasonBase, scorePeak});
    }

    @Override
    public synchronized void setRewarded(UUID player, @Nullable String groups) throws Exception {
        check();
        if (groups == null) {
            rewarded.remove(player);
        } else {
            rewarded.put(player, groups);
        }
    }

    @Override
    public synchronized void deletePlayer(UUID player) throws Exception {
        check();
        counts.remove(player);
        bonusAndRankedAt.remove(player);
        ranks.remove(player);
        pending.remove(player);
        seasons.remove(player);
        rewarded.remove(player);
    }

    @Override
    @Nullable
    public synchronized PlayerRow loadPlayer(UUID player) throws Exception {
        check();
        if (!counts.containsKey(player) && !bonusAndRankedAt.containsKey(player) && !ranks.containsKey(player) && !pending.containsKey(player) && !seasons.containsKey(player)) {
            return null;
        }
        long[] br = bonusAndRankedAt.getOrDefault(player, new long[2]);
        long[] se = seasons.getOrDefault(player, new long[3]);
        return new PlayerRow(player, Map.copyOf(counts.getOrDefault(player, Map.of())), br[0], ranks.get(player), br[1], pending.get(player), (int) se[0], se[1], se[2], rewarded.get(player));
    }

    /** A count as stored, for tests. */
    public synchronized long count(UUID player, String signal) {
        return counts.getOrDefault(player, Map.of()).getOrDefault(signal, 0L);
    }

    @Override
    public synchronized void saveSettings(String json, long version) throws Exception {
        check();
        settings = new SettingsRow(json, version);
    }

    @Override
    @Nullable
    public synchronized SettingsRow loadSettings() throws Exception {
        check();
        return settings;
    }

    @Override
    public synchronized long publish(Notice n) throws Exception {
        check();
        long id = ++lastNoticeId;
        notices.add(new Notice(id, n.uid(), n.createdAt(), n.source(), n.kind(), n.payload()));
        return id;
    }

    @Override
    public synchronized List<Notice> fetchNoticesAfter(long id, int limit) throws Exception {
        check();
        List<Notice> out = new ArrayList<>();
        for (Notice n : notices) {
            if (n.id() > id && out.size() < limit) {
                out.add(n);
            }
        }
        return out;
    }

    @Override
    public synchronized long latestNoticeId() throws Exception {
        check();
        return lastNoticeId;
    }

    @Override
    public synchronized int deleteNoticesBefore(long createdAtMs) throws Exception {
        check();
        int before = notices.size();
        notices.removeIf(n -> n.createdAt() < createdAtMs);
        return before - notices.size();
    }

    @Override
    public String describe() {
        return "memory";
    }

    @Override
    public void close() {
    }
}
