package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.RankActionPacket;
import com.mrgregles.bsp_rankup.network.RankSync;
import com.mrgregles.bsp_rankup.network.RankViewPacket;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** The server side of the player's rank screen: the view and the "fastest way" rows; the packets' encoding. */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class RankSyncGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void the_fastest_ways_come_from_the_heaviest_signals(GameTestHelper h) {
        Settings s = Settings.defaults().toBuilder()
                .signal(Signal.PLAYTIME, new SignalSetting(true, 1, 10))        // 0.1 per minute
                .signal(Signal.PLAYER_KILLS, new SignalSetting(true, 25, 1))    // 25 per kill
                .signal(Signal.MOB_KILLS, new SignalSetting(true, 1, 1))        // 1 per kill
                .signal(Signal.TOTEMS_STOLEN, new SignalSetting(true, 150, 1))  // 150 per totem
                .signal(Signal.TOTEMS_PROTECTED, new SignalSetting(true, 60, 1))
                .signal(Signal.DEATHS, new SignalSetting(false, 100, 1))        // off: never listed
                .build();
        List<RankViewPacket.Fastest> f = RankSync.fastest(s, 1000);
        h.assertTrue(f.size() == 3, "three ways: " + f);
        h.assertTrue(f.get(0).signal().equals("totems_stolen") && f.get(0).units() == 7, "7 totems for 1000 points: " + f.get(0));
        h.assertTrue(f.get(1).signal().equals("totems_protected") && f.get(1).units() == 17, "17 protected: " + f.get(1));
        h.assertTrue(f.get(2).signal().equals("player_kills") && f.get(2).units() == 40, "40 kills: " + f.get(2));
        h.assertTrue(RankSync.fastest(s, 0).isEmpty(), "nothing to go, nothing listed");
        Settings.Builder only = Settings.defaults().toBuilder();
        for (Signal sig : Signal.values()) {
            only.signal(sig, new SignalSetting(sig == Signal.PLAYTIME, 1, 10));
        }
        List<RankViewPacket.Fastest> p = RankSync.fastest(only.build(), 25);
        h.assertTrue(p.size() == 1 && p.get(0).signal().equals("playtime") && p.get(0).units() == 250, "25 points of playtime at 1 per 10 minutes is 250 minutes: " + p);
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "rank-sync")
    public void the_view_reports_the_ladder_and_a_players_standing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        LadderGameTests.install(server);
        level.addNewPlayer(player);
        RankEngine.onLogin(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        RankViewPacket fresh = RankSync.view(server, id, true);
        h.assertTrue(fresh.open() && fresh.points() == 0 && fresh.holders().size() == 3, "a fresh player: open flag, no points, three rungs: " + fresh.holders());
        ledger.add(id, Signal.PLAYER_KILLS, 1); // 25: onto the first rung, the second at 50 needs 25 more
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue("ru_l_a".equals(ledger.progress(id).rank()), "on the first rung: " + ledger.progress(id)))
                .thenExecute(() -> {
                    RankViewPacket v = RankSync.view(server, id, false);
                    h.assertTrue(v.rank().equals("ru_l_a") && v.points() == 25 && v.counts().get("player_kills") == 1 && !v.open(), "rung, points and counts: " + v.rank() + " " + v.points());
                    h.assertTrue(v.holders().get("ru_l_a") >= 1, "the player is counted as holding it: " + v.holders());
                    h.assertTrue(v.fastest().stream().anyMatch(f -> f.signal().equals("player_kills") && f.units() == 1), "one more kill reaches the second rung: " + v.fastest());
                    h.assertTrue(v.fastest().stream().anyMatch(f -> f.signal().equals("totems_stolen") && f.units() == 1), "or one totem: " + v.fastest());
                    h.assertTrue(v.title().isEmpty() && v.pending() == 0 && v.server().isEmpty(), "no title, nothing waiting, no shared storage");
                    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    RankViewPacket.encode(v, buf);
                    RankViewPacket back = RankViewPacket.decode(buf);
                    h.assertTrue(back.rank().equals("ru_l_a") && back.points() == 25 && back.fastest().equals(v.fastest()) && back.holders().equals(v.holders()) && Settings.load(back.settings()).ranks().size() == 3, "the view packet round-trips");
                    FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
                    RankActionPacket.encode(new RankActionPacket(RankActionPacket.REFRESH), buf2);
                    h.assertTrue(RankActionPacket.decode(buf2).action().equals(RankActionPacket.REFRESH), "the action packet round-trips");
                    ledger.addBonus(id, 25); // 50: the second rung, whose title reward is [B]
                })
                .thenWaitUntil(() -> {
                    h.assertTrue("ru_l_b".equals(ledger.progress(id).rank()), "on the second rung: " + ledger.progress(id));
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(RankSync.view(server, id, false).title().equals("[B] "), "the title comes from LuckPerms: '" + RankSync.view(server, id, false).title() + "'");
                        h.assertTrue(RankSync.view(server, id, false).ladders().get(0).title().equals("[B] "), "and is the ladder's own title (D76): " + RankSync.view(server, id, false).ladders().get(0));
                    }
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    LadderGameTests.uninstall(server, id);
                })
                .thenSucceed();
    }
}
