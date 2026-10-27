package com.mrgregles.bsp_rankup.storage;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.RankUpConfig;
import com.mrgregles.bsp_rankup.compat.RegionGuard;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The shared storage (owner decisions D8, D19, D21): every change to a player's counts, bonus, rank and waiting
 * rewards is kept in memory, merged per player (counts and bonus as additive deltas, so two servers' playtime adds
 * up; rank, waiting rewards, season and rewarded rungs as the latest value), and written every few seconds as one
 * batch. A batch that fails stays for the next try, with a growing wait, so an outage loses nothing while this
 * server runs. A player's row is read on login and replaces the local copy, with the changes not yet written added
 * on top. The settings are one shared document, written on every edit and re-read on a notice. Notices between servers (a settings change, a rank-up
 * to announce network-wide, a player's progress changed elsewhere) go through Redis when it is configured and are
 * always written to the notice table too, which every server reads on a timer; a notice acts once by its id.
 * Notices older than a day are deleted now and then.
 *
 * <p>Database work runs on one worker thread; results come back on the server thread. Without shared storage
 * (the default) nothing here runs and the local ledgers are the truth.
 */
public final class Storage {
    public static final String KIND_SETTINGS = "settings", KIND_RANKUP = "rankup", KIND_PROGRESS = "progress", KIND_AWARD = "award";
    private static final int NOTICE_BATCH = 100, SEEN_LIMIT = 20_000, NOTICE_OVERLAP = 20;
    private static final long FLUSH_MS = 5000;
    /** Unsent changes are written this often; after a failure the wait doubles up to {@link #BACKOFF_MAX_MS}. */
    private static final long WRITE_EVERY_MS = 5000, BACKOFF_MAX_MS = 60_000;
    private static final long PRUNE_EVERY_MS = 3_600_000, NOTICE_KEEP_MS = 86_400_000;
    private static final Runnable STOP = () -> {
    };

    /** A unit of database work. */
    public interface Task {
        void run() throws Exception;
    }

    @Nullable
    private static Storage active;

    private final MinecraftServer server;
    private final StorageBackend backend;
    private final String serverId;
    private final int pollSeconds;
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    @Nullable
    private final Thread worker;
    private volatile long lastNoticeId = -1;
    private volatile long lastPollMs;
    private volatile String lastError = "";
    private volatile int errors;
    private volatile long settingsVersion;
    /** True while a row read from the database is being applied to a local ledger, so the write-through stays quiet. */
    private boolean applying;
    @Nullable
    private volatile RedisLink redis;
    private volatile int safetyPollSeconds = 30;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    });
    private final List<Consumer<StorageBackend.Notice>> noticeListeners = new CopyOnWriteArrayList<>();
    private final Consumer<ProgressLedger.Change> changeListener = this::onChange;
    private final Consumer<Settings> settingsListener = this::onSettingsChanged;
    private final Consumer<RankEngine.RankUp> rankUpListener = this::onRankUp;
    private final Consumer<RankEngine.AwardReached> awardListener = this::onAward;
    private volatile int delivered;
    /** Each player's changes not yet written, merged; guarded by itself, as is {@link #reading}. */
    private final Map<UUID, Unsent> unsent = new HashMap<>();
    /** Changes made while a player's row is being read, one collector per read in flight. */
    private final Map<UUID, List<Unsent>> reading = new HashMap<>();
    /** A reset waits to be written (its notice goes out after the delete). */
    private volatile boolean writeSoon;
    /** The worker's write and prune timers (worker thread, or a test's drain). */
    private long nextWriteMs, backoffMs, lastPruneMs;

    private Storage(MinecraftServer server, StorageBackend backend, String serverId, int pollSeconds, boolean threaded) {
        this.server = server;
        this.backend = backend;
        this.serverId = serverId;
        this.pollSeconds = pollSeconds;
        ProgressLedger.onChange(changeListener);
        SettingsLedger.onChange(settingsListener);
        RankEngine.onRankUp(rankUpListener);
        RankEngine.onAward(awardListener);
        if (threaded) {
            worker = new Thread(this::loop, "bsp-rankup-storage");
            worker.setDaemon(true);
            worker.start();
        } else {
            worker = null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the shared storage from the config, if {@code storage.mode} is mysql. */
    public static void start(MinecraftServer server) {
        stop();
        if (!"mysql".equalsIgnoreCase(RankUpConfig.getOr(RankUpConfig.STORAGE_MODE, "none"))) {
            if (RankUpConfig.getOr(RankUpConfig.REDIS_ENABLED, false)) {
                BSPRankUp.LOGGER.warn("storage.redis.enabled is on but storage.mode is not mysql: Redis is not used");
            }
            return;
        }
        StorageBackend backend = new JdbcBackend(
                RankUpConfig.getOr(RankUpConfig.STORAGE_HOST, "localhost"),
                RankUpConfig.getOr(RankUpConfig.STORAGE_PORT, 3306),
                RankUpConfig.getOr(RankUpConfig.STORAGE_DATABASE, "bsp"),
                RankUpConfig.getOr(RankUpConfig.STORAGE_USER, "bsp"),
                RankUpConfig.getOr(RankUpConfig.STORAGE_PASSWORD, ""),
                RankUpConfig.getOr(RankUpConfig.STORAGE_TABLE_PREFIX, "bsp_ru_"));
        String id = RankUpConfig.getOr(RankUpConfig.STORAGE_SERVER_ID, "");
        if (id.isBlank()) {
            String rg = RegionGuard.serverId();
            id = rg == null || rg.isBlank() ? "server-1" : rg;
        }
        Storage s = new Storage(server, backend, id, RankUpConfig.getOr(RankUpConfig.STORAGE_POLL_SECONDS, 5), true);
        active = s;
        s.submit(() -> {
            backend.init();
            s.lastNoticeId = backend.latestNoticeId();
            BSPRankUp.LOGGER.info("RankUp storage connected: {} (this server is '{}')", backend.describe(), s.serverId);
        });
        s.loadSettingsFromShared();
        if (RankUpConfig.getOr(RankUpConfig.REDIS_ENABLED, false)) {
            s.safetyPollSeconds = RankUpConfig.getOr(RankUpConfig.REDIS_SAFETY_POLL_SECONDS, 30);
            s.connectRedis(new RedisLink.Settings(
                    RankUpConfig.getOr(RankUpConfig.REDIS_HOST, "localhost"),
                    RankUpConfig.getOr(RankUpConfig.REDIS_PORT, 6379),
                    RankUpConfig.getOr(RankUpConfig.REDIS_USERNAME, ""),
                    RankUpConfig.getOr(RankUpConfig.REDIS_PASSWORD, ""),
                    RankUpConfig.getOr(RankUpConfig.REDIS_CHANNEL, "messages")));
        }
    }

    /** A storage on a given backend without a worker thread: {@link #drain()} runs its work. For tests. */
    public static Storage startWith(MinecraftServer server, StorageBackend backend, String serverId) {
        Storage s = new Storage(server, backend, serverId, 5, false);
        s.submit(() -> {
            backend.init();
            s.lastNoticeId = backend.latestNoticeId();
        });
        return s;
    }

    public static void stop() {
        Storage s = active;
        active = null;
        if (s != null) {
            s.close();
        }
    }

    public static boolean enabled() {
        return active != null;
    }

    @Nullable
    public static Storage active() {
        return active;
    }

    public String serverId() {
        return serverId;
    }

    public void connectRedis(RedisLink.Settings settings) {
        RedisLink old = redis;
        if (old != null) {
            old.close();
        }
        redis = new RedisLink(settings, this::onRedisMessage);
    }

    @Nullable
    public RedisLink redis() {
        return redis;
    }

    /** Stops listening, sends what is queued (up to a few seconds) and closes the database and Redis. */
    public void close() {
        ProgressLedger.removeListener(changeListener);
        SettingsLedger.removeListener(settingsListener);
        RankEngine.removeListener(rankUpListener);
        RankEngine.removeAwardListener(awardListener);
        RedisLink r = redis;
        if (r != null) {
            r.close();
            redis = null;
        }
        if (worker != null) {
            work.add(STOP);
            try {
                worker.join(FLUSH_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            drain();
        }
        running.set(false);
        try {
            backend.close();
        } catch (Exception e) {
            BSPRankUp.LOGGER.debug("closing the storage: {}", e.toString());
        }
    }

    public String status() {
        RedisLink r = redis;
        int waiting = unsentPlayers();
        return backend.describe() + ", this server '" + serverId + "', " + (lastError.isEmpty() ? "no errors" : errors + " errors, last: " + lastError)
                + (waiting == 0 ? "" : ", " + waiting + " players' changes waiting to be written")
                + ", " + delivered + " notices handled" + (r == null ? ", no Redis" : "; " + r.status());
    }

    // ------------------------------------------------------------------ the worker

    public void submit(Task task) {
        work.add(() -> {
            try {
                task.run();
            } catch (Exception e) {
                failed(e);
            }
        });
    }

    private void failed(Exception e) {
        errors++;
        String message = e.toString();
        if (!message.equals(lastError)) {
            BSPRankUp.LOGGER.warn("RankUp storage: {}", message);
        }
        lastError = message;
    }

    /**
     * Runs every queued task and writes the unsent changes at once, whatever the wait, on the calling thread (tests,
     * and the last flush of an unthreaded storage).
     */
    public void drain() {
        do {
            Runnable r;
            while ((r = work.poll()) != null) {
                if (r != STOP) {
                    r.run();
                }
            }
            writeUnsent();
        } while (!work.isEmpty()); // a reset's notice, queued after its delete was written
    }

    private void loop() {
        while (running.get()) {
            try {
                Runnable r = work.poll(1, TimeUnit.SECONDS);
                if (r == STOP) {
                    writeUnsent();
                    int left = unsentPlayers();
                    if (left > 0) {
                        BSPRankUp.LOGGER.warn("RankUp storage closed with {} players' changes unwritten (the database is unreachable); this server's own copy keeps them until those players' next login", left);
                    }
                    return;
                }
                if (r != null) {
                    r.run();
                }
                long now = System.currentTimeMillis();
                if (now >= nextWriteMs || (writeSoon && backoffMs == 0)) {
                    writeUnsent();
                }
                if (now - lastPruneMs >= PRUNE_EVERY_MS) {
                    lastPruneMs = now;
                    pruneNotices();
                }
                long every = (redis != null && redis.subscribed() ? safetyPollSeconds : pollSeconds) * 1000L;
                if (System.currentTimeMillis() - lastPollMs >= every) {
                    lastPollMs = System.currentTimeMillis();
                    pollNotices();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                BSPRankUp.LOGGER.warn("RankUp storage worker: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ unsent changes

    private void onChange(ProgressLedger.Change c) {
        if (applying || c.server() != server) {
            return;
        }
        UUID player = c.player();
        long amount = c.amount();
        switch (c.kind()) {
            case COUNT -> {
                Signal signal = c.signal();
                if (signal != null && amount != 0) {
                    record(player, u -> u.counts.merge(signal.key(), amount, Long::sum));
                }
            }
            case BONUS -> {
                if (amount != 0) {
                    record(player, u -> u.bonus += amount);
                }
            }
            case RANK -> {
                StorageBackend.RankValue rank = new StorageBackend.RankValue(c.progress().rank(), c.progress().rankedAtMs());
                StorageBackend.LadderValue ladders = StorageBackend.LadderValue.of(c.progress());
                record(player, u -> {
                    u.rank = rank;
                    u.ladders = ladders;
                });
            }
            case AWARD -> {
                StorageBackend.LadderValue ladders = StorageBackend.LadderValue.of(c.progress());
                record(player, u -> u.ladders = ladders);
            }
            case PENDING -> {
                StorageBackend.Text pending = new StorageBackend.Text(PendingJson.toJson(c.progress().pending()));
                record(player, u -> u.pending = pending);
            }
            case SEASON -> {
                StorageBackend.SeasonValue season = new StorageBackend.SeasonValue(c.progress().season(), c.progress().seasonBase(), c.progress().scorePeak());
                StorageBackend.LadderValue ladders = StorageBackend.LadderValue.of(c.progress());
                record(player, u -> {
                    u.season = season;
                    u.ladders = ladders;
                });
            }
            case REWARDED -> {
                StorageBackend.Text rewarded = new StorageBackend.Text(c.progress().rewarded().isEmpty() ? null : String.join(",", c.progress().rewarded()));
                record(player, u -> u.rewarded = rewarded);
            }
            case RESET -> {
                record(player, Unsent::reset); // what came before is wiped; the notice goes out once the delete is written
                writeSoon = true;
            }
            case LOAD -> {
                // came from here
            }
        }
    }

    /** Applies a change to the player's unsent changes, and to every read of their row in flight. */
    private void record(UUID player, Consumer<Unsent> change) {
        synchronized (unsent) {
            change.accept(unsent.computeIfAbsent(player, k -> new Unsent()));
            List<Unsent> reads = reading.get(player);
            if (reads != null) {
                reads.forEach(change);
            }
        }
    }

    /**
     * Writes every player's unsent changes as one batch (worker thread, or a test's drain). On a failure they go back,
     * under any made since, and the next try waits twice as long as the last, up to a minute.
     */
    private void writeUnsent() {
        Map<UUID, Unsent> batch;
        synchronized (unsent) {
            if (unsent.isEmpty()) {
                return;
            }
            batch = new HashMap<>(unsent);
            unsent.clear();
        }
        writeSoon = false;
        List<StorageBackend.PlayerWrite> writes = new ArrayList<>(batch.size());
        batch.forEach((player, u) -> writes.add(u.toWrite(player)));
        try {
            backend.write(writes);
            backoffMs = 0;
            nextWriteMs = System.currentTimeMillis() + WRITE_EVERY_MS;
            List<UUID> reset = writes.stream().filter(StorageBackend.PlayerWrite::delete).map(StorageBackend.PlayerWrite::player).toList();
            if (!reset.isEmpty()) {
                server.execute(() -> reset.forEach(player -> publish(KIND_PROGRESS, player.toString())));
            }
        } catch (Exception e) {
            synchronized (unsent) {
                batch.forEach((player, older) -> {
                    Unsent newer = unsent.get(player);
                    if (newer != null) {
                        older.then(newer);
                    }
                    unsent.put(player, older);
                });
            }
            backoffMs = Math.min(BACKOFF_MAX_MS, backoffMs == 0 ? WRITE_EVERY_MS * 2 : backoffMs * 2);
            nextWriteMs = System.currentTimeMillis() + backoffMs;
            failed(e);
        }
    }

    /** How many players have changes not yet written. */
    public int unsentPlayers() {
        synchronized (unsent) {
            return unsent.size();
        }
    }

    /** One player's changes not yet written: count and bonus deltas merged, the latest of each set value (null: unchanged). */
    private static final class Unsent {
        boolean delete;
        final Map<String, Long> counts = new HashMap<>();
        long bonus;
        @Nullable
        StorageBackend.RankValue rank;
        @Nullable
        StorageBackend.Text pending, rewarded;
        @Nullable
        StorageBackend.SeasonValue season;
        @Nullable
        StorageBackend.LadderValue ladders;

        /** The player was reset: everything before is dropped and the row is deleted first. */
        void reset() {
            delete = true;
            counts.clear();
            bonus = 0;
            rank = null;
            pending = null;
            rewarded = null;
            season = null;
            ladders = null;
        }

        /** Puts newer changes on top of these. */
        void then(Unsent newer) {
            if (newer.delete) {
                reset();
            }
            newer.counts.forEach((key, n) -> counts.merge(key, n, Long::sum));
            bonus += newer.bonus;
            if (newer.rank != null) {
                rank = newer.rank;
            }
            if (newer.pending != null) {
                pending = newer.pending;
            }
            if (newer.season != null) {
                season = newer.season;
            }
            if (newer.rewarded != null) {
                rewarded = newer.rewarded;
            }
            if (newer.ladders != null) {
                ladders = newer.ladders;
            }
        }

        Unsent copy() {
            Unsent u = new Unsent();
            u.then(this);
            return u;
        }

        StorageBackend.PlayerWrite toWrite(UUID player) {
            Map<String, Long> deltas = new HashMap<>();
            counts.forEach((key, n) -> {
                if (n != 0) {
                    deltas.put(key, n);
                }
            });
            return new StorageBackend.PlayerWrite(player, delete, deltas, bonus, rank, pending, season, rewarded, ladders);
        }
    }

    private void onSettingsChanged(Settings settings) {
        if (applying) {
            return;
        }
        long version = Math.max(System.currentTimeMillis(), settingsVersion + 1);
        settingsVersion = version;
        String json = SettingsFiles.toJson(settings);
        // the notice goes out once the row is written: a server told sooner would read the old row and never look again
        submit(() -> {
            backend.saveSettings(json, version);
            server.execute(() -> publish(KIND_SETTINGS, Long.toString(version)));
        });
    }

    /** An award reached here is announced on the other servers too when announcements are network-wide (D53). */
    private void onAward(RankEngine.AwardReached reached) {
        if (reached.server() == server && SettingsLedger.settings(server).announce() == Settings.Announce.NETWORK) {
            publish(KIND_AWARD, Players.name(server, reached.player()) + "\t" + reached.award().displayName());
        }
    }

    private void onRankUp(RankEngine.RankUp up) {
        if (up.server() != server || SettingsLedger.settings(server).announce() != Settings.Announce.NETWORK) {
            return;
        }
        boolean quiet = up.from() == null && up.ladder().indexOf(up.to().group()) == 0;
        if (!quiet) {
            // a third part names the ladder when there are several (D52)
            String ladder = SettingsLedger.settings(server).ladders().size() > 1 ? "\t" + RankEngine.label(up.ladder()) : "";
            publish(KIND_RANKUP, Players.name(server, up.player()) + "\t" + up.to().displayName() + ladder);
        }
    }

    // ------------------------------------------------------------------ reads

    /** Forwards a login to the active storage. */
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Storage s = active;
        if (s != null && event.getEntity() instanceof ServerPlayer player) {
            s.loadPlayer(player.getUUID());
        }
    }

    /** Told on the server thread when a read of a player's row is over: applied, missing, or failed (the local copy stands). */
    public interface ReadListener {
        void read(MinecraftServer server, UUID player);
    }

    private static final List<ReadListener> READ_LISTENERS = new CopyOnWriteArrayList<>();

    /** The ladder engine's login check waits for this (on a network the row read at login is the truth). */
    public static void onPlayerRead(ReadListener listener) {
        READ_LISTENERS.add(listener);
    }

    private void readDone(UUID player) {
        for (ReadListener l : READ_LISTENERS) {
            l.read(server, player);
        }
    }

    /**
     * Reads the player's row and replaces the local copy with it, the changes not yet written added on top (nothing
     * changes when the row is missing). The read and the batch writes share the worker, so the changes unsent at the
     * read are exactly those the row lacks; the ones made while the row travels to the server thread are collected
     * on the way. The read listeners are told when it is over, whatever the outcome.
     */
    public void loadPlayer(UUID player) {
        work.add(() -> {
            StorageBackend.PlayerRow row;
            try {
                row = backend.loadPlayer(player);
            } catch (Exception e) {
                failed(e);
                server.execute(() -> readDone(player));
                return;
            }
            if (row == null) {
                server.execute(() -> readDone(player));
                return;
            }
            Unsent before, since = new Unsent();
            synchronized (unsent) {
                Unsent u = unsent.get(player);
                before = u == null ? new Unsent() : u.copy();
                reading.computeIfAbsent(player, k -> new ArrayList<>()).add(since);
            }
            server.execute(() -> {
                apply(row, before, since);
                readDone(player);
            });
        });
    }

    private void apply(StorageBackend.PlayerRow row, Unsent before, Unsent since) {
        synchronized (unsent) {
            List<Unsent> reads = reading.get(row.player());
            if (reads != null) {
                reads.remove(since);
                if (reads.isEmpty()) {
                    reading.remove(row.player());
                }
            }
        }
        Unsent extra = before;
        extra.then(since);
        // a reset not yet written: the row read is the old one
        Map<String, Long> rowCounts = new HashMap<>(extra.delete ? Map.of() : row.counts());
        extra.counts.forEach((key, n) -> rowCounts.merge(key, n, Long::sum));
        Map<Signal, Long> counts = new EnumMap<>(Signal.class);
        rowCounts.forEach((key, n) -> {
            Signal s = Signal.byKey(key);
            if (s != null) {
                counts.put(s, Math.max(0, n));
            }
        });
        long bonus = (extra.delete ? 0 : row.bonus()) + extra.bonus;
        String rank = extra.rank != null ? extra.rank.rank() : extra.delete ? null : row.rank();
        long rankedAt = extra.rank != null ? extra.rank.rankedAt() : extra.delete ? 0 : row.rankedAt();
        String pending = extra.pending != null ? extra.pending.text() : extra.delete ? null : row.pendingJson();
        int season = extra.season != null ? extra.season.season() : extra.delete ? 0 : row.season();
        long seasonBase = extra.season != null ? extra.season.seasonBase() : extra.delete ? 0 : row.seasonBase();
        long scorePeak = extra.season != null ? extra.season.scorePeak() : extra.delete ? 0 : row.scorePeak();
        StorageBackend.LadderValue ladders = extra.ladders != null ? extra.ladders : extra.delete ? StorageBackend.LadderValue.NONE : row.ladders();
        String rewardedCsv = extra.rewarded != null ? extra.rewarded.text() : extra.delete ? null : row.rewarded();
        applying = true;
        try {
            Set<String> rewarded = new java.util.LinkedHashSet<>();
            if (rewardedCsv != null && !rewardedCsv.isBlank()) {
                rewarded.addAll(List.of(rewardedCsv.split(",")));
            }
            ProgressLedger.get(server).load(row.player(), counts, bonus, rank, rankedAt, PendingJson.fromJson(pending), season, seasonBase, scorePeak, rewarded,
                    ladders == null ? null : ladders.rankMap(), ladders == null ? null : ladders.baseMap(), ladders == null ? null : ladders.awardMap());
        } finally {
            applying = false;
        }
    }

    /** Reads the shared settings; a newer version replaces the local one, a missing row is written from the local one. */
    public void loadSettingsFromShared() {
        submit(() -> {
            StorageBackend.SettingsRow row = backend.loadSettings();
            if (row == null) {
                Settings local = SettingsLedger.settings(server);
                long version = Math.max(System.currentTimeMillis(), settingsVersion + 1);
                backend.saveSettings(SettingsFiles.toJson(local), version);
                settingsVersion = version;
            } else {
                server.execute(() -> applySettings(row));
            }
        });
    }

    private void applySettings(StorageBackend.SettingsRow row) {
        if (row.version() <= settingsVersion) {
            return;
        }
        try {
            Settings settings = SettingsFiles.fromJson(row.json());
            applying = true;
            try {
                SettingsLedger.get(server).set(settings);
            } finally {
                applying = false;
            }
            settingsVersion = row.version();
            BSPRankUp.LOGGER.info("RankUp settings read from the shared storage (version {}): {} rungs on track {}", row.version(), settings.ranks().size(), settings.track());
        } catch (IOException | RuntimeException e) {
            BSPRankUp.LOGGER.warn("Shared RankUp settings could not be read: {}", e.toString());
        }
    }

    public long settingsVersion() {
        return settingsVersion;
    }

    // ------------------------------------------------------------------ prefix images (D59)

    /** Keeps a prefix image in the shared table, for the other servers. */
    public void saveImage(String id, byte[] png) {
        submit(() -> backend.saveImage(id, png));
    }

    /** Reads a prefix image from the shared table; the answer (null when missing or unreadable) comes on the server thread. */
    public void loadImage(String id, Consumer<byte[]> then) {
        work.add(() -> {
            byte[] png = null;
            try {
                png = backend.loadImage(id);
            } catch (Exception e) {
                failed(e);
            }
            byte[] found = png;
            server.execute(() -> then.accept(found));
        });
    }

    // ------------------------------------------------------------------ notices

    /** Sends a notice to every server: through Redis at once when connected, and always into the table. */
    public void publish(String kind, String payload) {
        StorageBackend.Notice n = new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), serverId, kind, payload);
        seen.add(n.uid()); // our own copy never acts here
        RedisLink r = redis;
        if (r != null) {
            r.publish(encode(n));
        }
        submit(() -> backend.publish(n));
    }

    /** Deletes the notices older than a day from the table; every hour on the worker. */
    public void pruneNotices() {
        submit(() -> {
            int gone = backend.deleteNoticesBefore(System.currentTimeMillis() - NOTICE_KEEP_MS);
            if (gone > 0) {
                BSPRankUp.LOGGER.debug("RankUp storage: {} old notices deleted", gone);
            }
        });
    }

    /** Listeners for tests and the api: every notice from another server, after it acted. */
    public void onNotice(Consumer<StorageBackend.Notice> listener) {
        noticeListeners.add(listener);
    }

    private static String encode(StorageBackend.Notice n) {
        return n.uid() + "\n" + n.createdAt() + "\n" + n.source() + "\n" + n.kind() + "\n" + n.payload();
    }

    @Nullable
    private static StorageBackend.Notice decode(String message) {
        String[] parts = message.split("\n", 5);
        if (parts.length < 5) {
            return null;
        }
        try {
            return new StorageBackend.Notice(0, parts[0], Long.parseLong(parts[1]), parts[2], parts[3], parts[4]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void onRedisMessage(String message) {
        StorageBackend.Notice n = decode(message);
        if (n != null) {
            server.execute(() -> handle(n));
        }
    }

    /** Reads the notices the table has gained since the last read; on the worker thread (or a test's drain). */
    public void pollNotices() {
        submit(() -> {
            // a little below the last id too: two servers' inserts can commit out of id order, and a later id seen first
            // must not hide an earlier one committed after it (the notices already handled are skipped by uid)
            List<StorageBackend.Notice> rows = backend.fetchNoticesAfter(Math.max(0, lastNoticeId - NOTICE_OVERLAP), NOTICE_BATCH);
            for (StorageBackend.Notice n : rows) {
                lastNoticeId = Math.max(lastNoticeId, n.id());
                server.execute(() -> handle(n));
            }
        });
    }

    private void handle(StorageBackend.Notice n) {
        if (!seen.add(n.uid()) || n.source().equals(serverId)) {
            return;
        }
        delivered++;
        switch (n.kind()) {
            case KIND_SETTINGS -> loadSettingsFromShared();
            case KIND_AWARD -> {
                String[] parts = n.payload().split("\t", 2);
                if (parts.length == 2 && SettingsLedger.settings(server).announce() == Settings.Announce.NETWORK) {
                    server.getPlayerList().broadcastSystemMessage(Component.translatable("message.bsp_rankup.award_announce", parts[0], parts[1]), false);
                }
            }
            case KIND_RANKUP -> {
                String[] parts = n.payload().split("\t", 3);
                if (parts.length >= 2 && SettingsLedger.settings(server).announce() == Settings.Announce.NETWORK) {
                    server.getPlayerList().broadcastSystemMessage(parts.length == 3
                            ? Component.translatable("message.bsp_rankup.announce_on", parts[0], parts[1], parts[2])
                            : Component.translatable("message.bsp_rankup.announce", parts[0], parts[1]), false);
                }
            }
            case KIND_PROGRESS -> {
                try {
                    UUID player = UUID.fromString(n.payload());
                    if (Players.isOnline(server, player)) {
                        loadPlayer(player);
                    }
                } catch (IllegalArgumentException ignored) {
                    // not a player id
                }
            }
            default -> {
                // a kind from a newer RankUp
            }
        }
        for (Consumer<StorageBackend.Notice> l : noticeListeners) {
            l.accept(n);
        }
    }

    public int delivered() {
        return delivered;
    }

    /** Resets what a newer version of the settings must beat (tests). */
    public void forgetSettingsVersion() {
        settingsVersion = 0;
    }

    /** How many tasks wait for the worker (tests). */
    public int queued() {
        return work.size();
    }
}
