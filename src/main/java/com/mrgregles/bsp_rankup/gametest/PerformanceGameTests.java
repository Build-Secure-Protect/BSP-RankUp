package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.RankUpClientConfig;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.network.RankSync;
import com.mrgregles.bsp_rankup.network.RankViewPacket;
import com.mrgregles.bsp_rankup.util.Players;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.signal.KillCooldowns;
import com.mrgregles.bsp_rankup.storage.JdbcBackend;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.net.ServerSocket;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The UI and performance pass (2026-10-23): screens shrink to a window too small for them instead of being cut off,
 * a refresh asked too soon is not answered, an unreachable database fails writes at once, views count holders
 * without copying the ledger, and the kill cooldown forgets victims whose cooldown is over.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class PerformanceGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "perf-scale")
    public void a_window_too_small_for_a_screen_shrinks_it_and_a_big_one_still_fills(GameTestHelper h) {
        int baseW = 520, baseH = 300;
        float small = RankUpClientConfig.screenScale(0, 480, 270, baseW, baseH, 8); // 1920 x 1080 at GUI scale 4
        h.assertTrue(small < 1 && baseW * small <= 480 - 8 + 0.01 && baseH * small <= 270 - 8 + 0.01, "shrunk to fit 480 x 270: " + small);
        float asked = RankUpClientConfig.screenScale(2, 480, 270, baseW, baseH, 8);
        h.assertTrue(asked == small, "an asked-for size is capped to what fits there too: " + asked);
        float tiny = RankUpClientConfig.screenScale(0, 320, 240, baseW, baseH, 8);
        h.assertTrue(tiny > 0.5 && baseW * tiny <= 320, "Minecraft's smallest window still holds it: " + tiny);
        float big = RankUpClientConfig.screenScale(0, 960, 540, baseW, baseH, 8);
        h.assertTrue(big > 1.7 && big < 1.8, "a big window is still filled (D29): " + big);
        float one = RankUpClientConfig.screenScale(1, 960, 540, baseW, baseH, 8);
        h.assertTrue(one == 1, "ui.scale 1 is the original size where it fits: " + one);
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "perf-refresh")
    public void a_refresh_asked_sooner_than_ten_ticks_is_not_answered(GameTestHelper h) {
        UUID player = UUID.randomUUID();
        try {
            h.assertTrue(RankSync.allowRefresh(player, 1000), "the first refresh is answered");
            h.assertFalse(RankSync.allowRefresh(player, 1000 + RankSync.MIN_REFRESH_TICKS - 1), "one asked too soon is not");
            h.assertTrue(RankSync.allowRefresh(player, 1000 + RankSync.MIN_REFRESH_TICKS), "one asked after the gap is");
            h.assertTrue(RankSync.allowRefresh(player, 5), "a tick count that went backwards (a new server) is answered");
        } finally {
            RankSync.forget(player);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "perf-db", timeoutTicks = 400)
    public void an_unreachable_database_fails_the_next_writes_at_once(GameTestHelper h) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        JdbcBackend db = new JdbcBackend("127.0.0.1", port, "bsp", "bsp", "", "bsp_ru_test_");
        try {
            try {
                db.init();
                h.fail("a connection to a closed port succeeded");
            } catch (SQLException first) {
                h.assertTrue(first.getClass().getName().startsWith("com.mrgregles.bsp_core.shaded.mysql."), "the first try reaches the driver: " + first);
            }
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                try {
                    db.addCount(UUID.randomUUID(), "playtime", 1);
                    h.fail("a write succeeded with no database");
                } catch (SQLException again) {
                    h.assertTrue(String.valueOf(again.getMessage()).contains("unreachable"), "later writes fail at once: " + again);
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000L;
            h.assertTrue(ms < 1000, "twenty writes failed in " + ms + " ms, not one connect timeout each");
        } finally {
            db.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "perf-ledger")
    public void the_ledger_visitor_sees_what_the_copy_holds(GameTestHelper h) {
        ProgressLedger ledger = ProgressLedger.get(h.getLevel().getServer());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try {
            ledger.add(a, Signal.MOB_KILLS, 3);
            ledger.add(b, Signal.MOB_KILLS, 4);
            Map<UUID, Progress> seen = new HashMap<>();
            ledger.forEach(seen::put);
            h.assertTrue(seen.equals(ledger.all()) && ledger.size() == ledger.all().size(), "forEach and size match all(): " + seen.size() + " / " + ledger.all().size());
        } finally {
            ledger.reset(a);
            ledger.reset(b);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "perf-cooldown")
    public void the_kill_cooldown_forgets_victims_whose_cooldown_is_over(GameTestHelper h) {
        long before = KillCooldowns.cooldownMs();
        UUID killer = UUID.randomUUID();
        try {
            KillCooldowns.setCooldownMs(60_000L);
            for (int i = 0; i < 70; i++) {
                KillCooldowns.record(killer, UUID.randomUUID(), 1_000L);
            }
            h.assertTrue(KillCooldowns.tracked(killer) == 70, "70 victims remembered: " + KillCooldowns.tracked(killer));
            UUID late = UUID.randomUUID();
            h.assertTrue(KillCooldowns.record(killer, late, 1_000L + 60_000L), "a new victim counts");
            h.assertTrue(KillCooldowns.tracked(killer) == 1, "the old ones were forgotten once their cooldown passed: " + KillCooldowns.tracked(killer));
            h.assertFalse(KillCooldowns.record(killer, late, 1_000L + 61_000L), "and the rule still holds for the new one");
        } finally {
            KillCooldowns.forget(killer);
            KillCooldowns.setCooldownMs(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "perf-packets")
    public void views_with_overlong_lines_still_encode(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        String longLine = "x".repeat(700) + "\nsecond line";
        AdminViewPacket view = AdminSync.view(admin, longLine, false, false);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AdminViewPacket.encode(view, buf); // threw for a message over 512 characters before
            AdminViewPacket back = AdminViewPacket.decode(buf);
            h.assertTrue(back.message().length() <= 500 && !back.message().contains("\n"), "the message is cut to one line of 500: " + back.message().length());
            h.assertTrue(back.info().stream().allMatch(l -> l.length() <= 500), "every info line fits");
            RankViewPacket rank = RankSync.view(admin.server, admin.getUUID(), false);
            RankViewPacket.encode(rank, new FriendlyByteBuf(Unpooled.buffer()));
            h.assertTrue(RankSync.cut("a".repeat(80), 64).length() == 64 && RankSync.cut("short", 64).equals("short"), "cut keeps short text and cuts long text");
        } finally {
            buf.release();
            h.getLevel().removePlayerImmediately(admin, Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "perf-lookup")
    public void a_name_nobody_here_has_is_not_looked_up_on_the_server_thread(GameTestHelper h) {
        var server = h.getLevel().getServer();
        UUID id = UUID.randomUUID();
        h.assertTrue(Players.findHere(server, id.toString()).equals(java.util.Optional.of(id)), "a uuid written out is found");
        h.assertTrue(Players.findHere(server, "nobody_" + System.nanoTime() % 100000).isEmpty(), "an unknown name is not looked up here");
        h.assertTrue(AdminSync.search(server, "nobody_xyz_" + System.nanoTime() % 100000).isEmpty(), "a search for it shows nothing at once (the profile cache answers later)");
        h.succeed();
    }
}
