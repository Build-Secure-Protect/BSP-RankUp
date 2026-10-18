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
import java.util.Collections;
import java.util.EnumMap;
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
 * rewards is written through to the database as it happens (counts and bonus as additive deltas, so two servers'
 * playtime adds up); a player's row is read on login and replaces the local copy; the settings are one shared
 * document, written on every edit and re-read on a notice. Notices between servers (a settings change, a rank-up
 * to announce network-wide, a player's progress changed elsewhere) go through Redis when it is configured and are
 * always written to the notice table too, which every server reads on a timer; a notice acts once by its id.
 *
 * <p>Database work runs on one worker thread; results come back on the server thread. Without shared storage
 * (the default) nothing here runs and the local ledgers are the truth.
 */
public final class Storage {
    public static final String KIND_SETTINGS = "settings", KIND_RANKUP = "rankup", KIND_PROGRESS = "progress";
    private static final int NOTICE_BATCH = 100, SEEN_LIMIT = 20_000;
    private static final long FLUSH_MS = 5000;
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
    private volatile int delivered;

    private Storage(MinecraftServer server, StorageBackend backend, String serverId, int pollSeconds, boolean threaded) {
        this.server = server;
        this.backend = backend;
        this.serverId = serverId;
        this.pollSeconds = pollSeconds;
        ProgressLedger.onChange(changeListener);
        SettingsLedger.onChange(settingsListener);
        RankEngine.onRankUp(rankUpListener);
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
        return backend.describe() + ", this server '" + serverId + "', " + (lastError.isEmpty() ? "no errors" : errors + " errors, last: " + lastError)
                + ", " + delivered + " notices handled" + (r == null ? ", no Redis" : "; " + r.status());
    }

    // ------------------------------------------------------------------ the worker

    public void submit(Task task) {
        work.add(() -> {
            try {
                task.run();
            } catch (Exception e) {
                errors++;
                String message = e.toString();
                if (!message.equals(lastError)) {
                    BSPRankUp.LOGGER.warn("RankUp storage: {}", message);
                }
                lastError = message;
            }
        });
    }

    /** Runs every queued task on the calling thread (tests, and the last flush of an unthreaded storage). */
    public void drain() {
        Runnable r;
        while ((r = work.poll()) != null) {
            if (r != STOP) {
                r.run();
            }
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                Runnable r = work.poll(1, TimeUnit.SECONDS);
                if (r == STOP) {
                    return;
                }
                if (r != null) {
                    r.run();
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

    // ------------------------------------------------------------------ write-through

    private void onChange(ProgressLedger.Change c) {
        if (applying || c.server() != server) {
            return;
        }
        UUID player = c.player();
        switch (c.kind()) {
            case COUNT -> {
                Signal signal = c.signal();
                if (signal != null && c.amount() != 0) {
                    submit(() -> backend.addCount(player, signal.key(), c.amount()));
                }
            }
            case BONUS -> {
                if (c.amount() != 0) {
                    submit(() -> backend.addBonus(player, c.amount()));
                }
            }
            case RANK -> {
                String rank = c.progress().rank();
                long at = c.progress().rankedAtMs();
                submit(() -> backend.setRank(player, rank, at));
            }
            case PENDING -> {
                String json = PendingJson.toJson(c.progress().pending());
                submit(() -> backend.setPending(player, json));
            }
            case SEASON -> {
                int season = c.progress().season();
                long base = c.progress().seasonBase();
                submit(() -> backend.setSeason(player, season, base));
            }
            case REWARDED -> {
                String csv = c.progress().rewarded().isEmpty() ? null : String.join(",", c.progress().rewarded());
                submit(() -> backend.setRewarded(player, csv));
            }
            case RESET -> {
                submit(() -> backend.deletePlayer(player));
                publish(KIND_PROGRESS, player.toString());
            }
            case LOAD -> {
                // came from here
            }
        }
    }

    private void onSettingsChanged(Settings settings) {
        if (applying) {
            return;
        }
        long version = Math.max(System.currentTimeMillis(), settingsVersion + 1);
        settingsVersion = version;
        String json = SettingsFiles.toJson(settings);
        submit(() -> backend.saveSettings(json, version));
        publish(KIND_SETTINGS, Long.toString(version));
    }

    private void onRankUp(RankEngine.RankUp up) {
        if (up.server() != server || SettingsLedger.settings(server).announce() != Settings.Announce.NETWORK) {
            return;
        }
        boolean quiet = up.from() == null && SettingsLedger.settings(server).indexOf(up.to().group()) == 0;
        if (!quiet) {
            publish(KIND_RANKUP, Players.name(server, up.player()) + "\t" + up.to().displayName());
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

    /** Reads the player's row and replaces the local copy with it (nothing changes when the row is missing). */
    public void loadPlayer(UUID player) {
        submit(() -> {
            StorageBackend.PlayerRow row = backend.loadPlayer(player);
            if (row != null) {
                server.execute(() -> apply(row));
            }
        });
    }

    private void apply(StorageBackend.PlayerRow row) {
        Map<Signal, Long> counts = new EnumMap<>(Signal.class);
        row.counts().forEach((key, n) -> {
            Signal s = Signal.byKey(key);
            if (s != null) {
                counts.put(s, n);
            }
        });
        applying = true;
        try {
            Set<String> rewarded = new java.util.LinkedHashSet<>();
            if (row.rewarded() != null && !row.rewarded().isBlank()) {
                rewarded.addAll(List.of(row.rewarded().split(",")));
            }
            ProgressLedger.get(server).load(row.player(), counts, row.bonus(), row.rank(), row.rankedAt(), PendingJson.fromJson(row.pendingJson()), row.season(), row.seasonBase(), rewarded);
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
            List<StorageBackend.Notice> rows = backend.fetchNoticesAfter(lastNoticeId, NOTICE_BATCH);
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
            case KIND_RANKUP -> {
                String[] parts = n.payload().split("\t", 2);
                if (parts.length == 2 && SettingsLedger.settings(server).announce() == Settings.Announce.NETWORK) {
                    server.getPlayerList().broadcastSystemMessage(Component.translatable("message.bsp_rankup.announce", parts[0], parts[1]), false);
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
