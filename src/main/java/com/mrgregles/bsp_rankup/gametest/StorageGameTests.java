package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.storage.JdbcBackend;
import com.mrgregles.bsp_rankup.storage.MemoryBackend;
import com.mrgregles.bsp_rankup.storage.RedisLink;
import com.mrgregles.bsp_rankup.storage.Storage;
import com.mrgregles.bsp_rankup.storage.StorageBackend;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The shared storage on an in-memory backend: changes merged and written as deltas in one batch, kept through an
 * outage, a row read on login replacing the local copy with the unsent changes on top, settings shared through the
 * database and a notice, notices through a fake Redis with the table as the safety net, old notices deleted. Each
 * test has its own batch: a storage listens to the server's ledgers.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class StorageGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "storage-counts")
    public void changes_are_written_through_as_deltas_and_a_row_comes_back_on_login(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        try {
            ledger.add(player, Signal.PLAYTIME, 3);
            ledger.add(player, Signal.PLAYER_KILLS, 2);
            ledger.add(player, Signal.PLAYER_KILLS, -1);
            ledger.addBonus(player, 5);
            ledger.setRank(player, "ru_s_x", 7L);
            ledger.addPending(player, List.of(Reward.coins(9)));
            h.assertTrue(alpha.unsentPlayers() == 1 && db.count(player, "playtime") == 0, "the changes wait in memory, merged, until the next write: " + alpha.unsentPlayers());
            alpha.drain();
            h.assertTrue(db.count(player, "playtime") == 3 && db.count(player, "player_kills") == 1, "counts written as deltas: " + db.loadPlayer(player));
            StorageBackend.PlayerRow row = db.loadPlayer(player);
            h.assertTrue(row != null && row.bonus() == 5 && "ru_s_x".equals(row.rank()) && row.rankedAt() == 7L && row.pendingJson() != null && row.pendingJson().contains("COINS"), "bonus, rank and waiting rewards written: " + row);

            db.addCount(player, "playtime", 4); // another server's minutes
            alpha.close();
            ledger.reset(player); // the local copy is gone (no storage listens now)
            h.assertFalse(ledger.has(player), "local copy cleared");

            Storage beta = Storage.startWith(server, db, "beta");
            beta.drain();
            try {
                beta.loadPlayer(player);
                beta.drain();
                Progress p = ledger.progress(player);
                h.assertTrue(p.count(Signal.PLAYTIME) == 7 && p.count(Signal.PLAYER_KILLS) == 1, "the row read on login holds both servers' minutes: " + p);
                h.assertTrue(p.bonus() == 5 && "ru_s_x".equals(p.rank()) && p.rankedAtMs() == 7L && p.pending().equals(List.of(Reward.coins(9))), "bonus, rank and waiting rewards back: " + p);
                beta.drain();
                h.assertTrue(db.count(player, "playtime") == 7, "applying the row did not write it back: " + db.count(player, "playtime"));

                ledger.reset(player);
                beta.drain();
                h.assertTrue(db.loadPlayer(player) == null, "a reset deletes the row");
                h.assertTrue(db.latestNoticeId() == 1 && db.fetchNoticesAfter(0, 10).get(0).kind().equals(Storage.KIND_PROGRESS), "and tells the other servers: " + db.fetchNoticesAfter(0, 10));
            } finally {
                beta.close();
            }
        } finally {
            alpha.close();
            ledger.reset(player);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "storage-outage")
    public void changes_made_during_an_outage_are_kept_and_written_when_the_database_returns(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        UUID wiped = UUID.randomUUID();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        try {
            ledger.add(wiped, Signal.PLAYER_KILLS, 5);
            s.drain();
            h.assertTrue(db.count(wiped, "player_kills") == 5, "written before the outage");

            db.setFailing(true);
            ledger.add(player, Signal.PLAYTIME, 3);
            ledger.addBonus(player, 2);
            ledger.setRank(player, "ru_out", 9L);
            ledger.reset(wiped);
            ledger.add(wiped, Signal.PLAYER_KILLS, 1);
            s.drain();
            h.assertTrue(s.unsentPlayers() == 2 && db.writes() == 1, "the failed write is kept: " + s.unsentPlayers() + " players, " + db.writes() + " writes");
            ledger.add(player, Signal.PLAYTIME, 4);
            s.drain();
            h.assertTrue(s.unsentPlayers() == 2, "a second failure keeps both the old and the new changes");

            db.setFailing(false);
            s.drain();
            h.assertTrue(s.unsentPlayers() == 0 && db.writes() == 2, "everything went in one write once the database returned: " + db.writes());
            StorageBackend.PlayerRow row = db.loadPlayer(player);
            h.assertTrue(db.count(player, "playtime") == 7 && row != null && row.bonus() == 2 && "ru_out".equals(row.rank()) && row.rankedAt() == 9L, "no minute, point or rank lost: " + row);
            h.assertTrue(db.count(wiped, "player_kills") == 1, "a reset in the outage deletes the old row before the newer kill: " + db.count(wiped, "player_kills"));
            List<StorageBackend.Notice> notices = db.fetchNoticesAfter(0, 10).stream().filter(n -> n.kind().equals(Storage.KIND_PROGRESS)).toList();
            h.assertTrue(notices.size() == 1 && notices.get(0).payload().equals(wiped.toString()), "the reset is announced once, after its delete is written: " + notices);
        } finally {
            s.close();
            ledger.reset(player);
            ledger.reset(wiped);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "storage-merge")
    public void many_changes_are_merged_into_one_write(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        try {
            for (int i = 0; i < 10; i++) {
                ledger.add(player, Signal.PLAYER_KILLS, 1);
            }
            for (int i = 0; i < 5; i++) {
                ledger.add(player, Signal.PLAYTIME, 1);
            }
            ledger.add(player, Signal.PLAYTIME, -2);
            ledger.setRank(player, "ru_first", 1L);
            ledger.setRank(player, "ru_second", 2L);
            ledger.add(other, Signal.MOB_KILLS, 3);
            h.assertTrue(s.queued() == 0, "no task per change: " + s.queued());
            s.drain();
            h.assertTrue(db.writes() == 1, "one write for every change of both players: " + db.writes());
            List<StorageBackend.PlayerWrite> batch = db.lastWrite();
            StorageBackend.PlayerWrite mine = batch.stream().filter(w -> w.player().equals(player)).findFirst().orElse(null);
            h.assertTrue(batch.size() == 2 && mine != null, "one entry per player: " + batch);
            h.assertTrue(mine.counts().equals(java.util.Map.of("player_kills", 10L, "playtime", 3L)), "the deltas are summed per signal: " + mine.counts());
            String latest = ledger.progress(player).rank();
            h.assertTrue(mine.rank() != null && java.util.Objects.equals(mine.rank().rank(), latest), "the rank is the latest one set: " + mine.rank() + " vs " + latest);
            h.assertTrue(db.count(player, "player_kills") == 10 && db.count(player, "playtime") == 3 && db.count(other, "mob_kills") == 3, "and stored");
            s.drain();
            h.assertTrue(db.writes() == 1, "nothing unsent, nothing written");
        } finally {
            s.close();
            ledger.reset(player);
            ledger.reset(other);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "storage-login")
    public void a_row_read_at_login_gets_the_unsent_changes_on_top(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        db.addCount(player, "playtime", 10); // another server's minutes
        db.addBonus(player, 1);
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        try {
            db.setFailing(true);
            ledger.add(player, Signal.PLAYTIME, 4);
            ledger.setRank(player, "ru_local", 5L);
            s.drain();
            h.assertTrue(s.unsentPlayers() == 1, "kept through the outage");

            db.setFailing(false);
            s.loadPlayer(player);
            s.drain(); // the read first (the unsent changes are still unsent), then the write
            Progress p = ledger.progress(player);
            h.assertTrue(p.count(Signal.PLAYTIME) == 14, "the row's 10 minutes plus the 4 not yet written: " + p.count(Signal.PLAYTIME));
            h.assertTrue(p.bonus() == 1 && "ru_local".equals(p.rank()) && p.rankedAtMs() == 5L, "the row's bonus, the newer local rank: " + p);
            h.assertTrue(db.count(player, "playtime") == 14 && s.unsentPlayers() == 0, "the 4 minutes then reached the database once: " + db.count(player, "playtime"));
        } finally {
            s.close();
            ledger.reset(player);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "storage-prune")
    public void notices_older_than_a_day_are_deleted(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        long now = System.currentTimeMillis();
        db.publish(new StorageBackend.Notice(0, UUID.randomUUID().toString(), now - 2 * 86_400_000L, "beta", Storage.KIND_RANKUP, "Old\tHero"));
        db.publish(new StorageBackend.Notice(0, UUID.randomUUID().toString(), now - 60_000L, "beta", Storage.KIND_RANKUP, "New\tHero"));
        Storage s = Storage.startWith(server, db, "alpha");
        try {
            s.pruneNotices();
            s.drain();
            List<StorageBackend.Notice> left = db.fetchNoticesAfter(0, 10);
            h.assertTrue(left.size() == 1 && left.get(0).payload().equals("New\tHero"), "only the old notice went: " + left);
            h.assertTrue(db.latestNoticeId() == 2, "notice ids keep counting: " + db.latestNoticeId());
        } finally {
            s.close();
        }
        h.succeed();
    }

    /**
     * The MySQL driver inside BSP Core's full jar (the dev run's BSP Core) is found and used: a connection to a
     * closed port fails inside that driver, not with DriverManager's "No suitable driver". No database is needed.
     */
    @GameTest(template = EMPTY, batch = "storage-driver")
    public void the_mysql_driver_in_bsp_cores_full_jar_is_found_and_used(GameTestHelper h) throws Exception {
        Driver driver = JdbcBackend.driver();
        h.assertTrue(driver != null && driver.getClass().getName().equals(JdbcBackend.DRIVERS.get(0)),
                "BSP Core's relocated driver is picked first: " + (driver == null ? "none" : driver.getClass().getName()));
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort(); // closed again before the connection is tried
        }
        JdbcBackend db = new JdbcBackend("127.0.0.1", port, "bsp", "bsp", "", "bsp_ru_test_");
        try {
            db.init();
            h.fail("a connection to a closed port succeeded");
        } catch (SQLException e) {
            h.assertFalse(String.valueOf(e.getMessage()).contains("No suitable driver"), "the driver was found: " + e);
            h.assertTrue(e.getClass().getName().startsWith("com.mrgregles.bsp_core.shaded.mysql."),
                    "the failure comes from inside BSP Core's driver (the port is closed): " + e.getClass().getName() + ": " + e.getMessage());
        } finally {
            db.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "storage-settings")
    public void settings_are_shared_through_the_database_and_reloaded_on_a_notice(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        try {
            alpha.loadSettingsFromShared();
            alpha.drain();
            h.assertTrue(db.loadSettings() != null && db.loadSettings().version() == alpha.settingsVersion(), "with no row the local settings are written: " + db.loadSettings());

            SettingsLedger.get(server).update(b -> b.track("shared_t"));
            alpha.drain();
            h.assertTrue(db.loadSettings().json().contains("shared_t"), "an edit is written through: " + db.loadSettings().json().length() + " chars");
            h.assertTrue(db.latestNoticeId() == 1 && db.fetchNoticesAfter(0, 10).get(0).kind().equals(Storage.KIND_SETTINGS), "and announced: " + db.fetchNoticesAfter(0, 10));

            long newer = alpha.settingsVersion() + 1000;
            db.saveSettings(SettingsFiles.toJson(Settings.defaults().toBuilder().track("from_beta").build()), newer); // another server saved
            db.publish(new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", Storage.KIND_SETTINGS, Long.toString(newer)));
            alpha.pollNotices();
            alpha.drain(); // reads the notices: our own is skipped, beta's starts a settings read
            alpha.drain(); // the read itself
            h.assertTrue(SettingsLedger.settings(server).track().equals("from_beta"), "the newer settings replaced the local ones: " + SettingsLedger.settings(server).track());
            h.assertTrue(alpha.delivered() == 1, "one notice from another server acted: " + alpha.delivered());
            h.assertTrue(db.latestNoticeId() == 2, "applying shared settings did not write or announce again: " + db.latestNoticeId());
        } finally {
            alpha.close();
            SettingsLedger.get(server).set(Settings.defaults());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "storage-redis")
    public void notices_arrive_through_redis_and_through_the_table_when_redis_is_down(GameTestHelper h) throws IOException {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        FakeRedis redis = FakeRedis.start("secret");
        Storage alpha = Storage.startWith(server, db, "alpha");
        Storage beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        RedisLink.Settings settings = new RedisLink.Settings("127.0.0.1", redis.port(), "", "secret", "test-rankup");
        alpha.connectRedis(settings);
        beta.connectRedis(settings);
        List<StorageBackend.Notice> got = new CopyOnWriteArrayList<>();
        beta.onNotice(got::add);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(redis.subscribers(settings.fullChannel()) == 2 && alpha.redis().publishing() && beta.redis().subscribed(), "both connect and subscribe"))
                .thenExecute(() -> alpha.publish(Storage.KIND_RANKUP, "Someone\tHero"))
                .thenWaitUntil(() -> h.assertTrue(got.size() == 1, "the other server gets it through Redis (no table read happened): " + got))
                .thenExecute(() -> {
                    h.assertTrue(got.get(0).source().equals("alpha") && got.get(0).payload().equals("Someone\tHero"), "source and payload carried: " + got.get(0));
                    h.assertTrue(alpha.delivered() == 0, "the sender does not act on its own notice");
                    alpha.drain(); // the table copy
                    beta.pollNotices();
                    beta.drain();
                    h.assertTrue(got.size() == 1, "the table's copy of the same notice is dropped: " + got.size());
                    redis.stop();
                })
                .thenWaitUntil(() -> h.assertTrue(!beta.redis().subscribed(), "the receiver notices the outage (its read fails); the sender only notices on its next send"))
                .thenExecute(() -> {
                    alpha.publish(Storage.KIND_RANKUP, "Other\tLegend");
                    alpha.drain();
                    h.assertTrue(got.size() == 1, "nothing arrived through Redis");
                    beta.pollNotices();
                    beta.drain();
                    h.assertTrue(got.size() == 2 && got.get(1).payload().equals("Other\tLegend"), "the table carried it: " + got);
                })
                .thenWaitUntil(() -> h.assertTrue(!alpha.redis().publishing(), "the sender noticed when it tried to send"))
                .thenExecute(() -> {
                    alpha.close();
                    beta.close();
                    redis.close();
                })
                .thenSucceed();
    }
}
