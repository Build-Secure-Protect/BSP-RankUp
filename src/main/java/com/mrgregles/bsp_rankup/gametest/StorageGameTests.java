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
 * The shared storage on an in-memory backend: write-through of every change as deltas, a row read on login
 * replacing the local copy, settings shared through the database and a notice, and notices through a fake Redis
 * with the table as the safety net. Each test has its own batch: a storage listens to the server's ledgers.
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
            h.assertTrue(alpha.queued() >= 6, "the writes are queued (plus a season mark when a test season ended earlier): " + alpha.queued());
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
