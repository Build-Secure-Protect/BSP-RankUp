package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.api.ProgressInfo;
import com.mrgregles.bsp_rankup.api.RankUpApi;
import com.mrgregles.bsp_rankup.api.event.RankChangedEvent;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.signal.Teams;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The api other mods see: the ladder and a player's standing, points given from outside, the event, the team hook. */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class ApiGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "api-ladder")
    public void the_api_reports_the_ladder_a_players_standing_and_fires_the_event(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        List<RankChangedEvent> events = new CopyOnWriteArrayList<>();
        Consumer<RankChangedEvent> listener = e -> {
            if (e.getPlayer().equals(id)) {
                events.add(e);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RankChangedEvent.class, listener);
        h.assertTrue(RankUpApi.ladder(server).isEmpty() && RankUpApi.rankOf(server, id).isEmpty(), "no ladder, no rank before the setup");
        LadderGameTests.install(server);
        level.addNewPlayer(player);
        RankEngine.onLogin(player);
        ProgressLedger ledger = ProgressLedger.get(server);

        h.assertTrue(RankUpApi.apiVersion() == RankUpApi.API_VERSION && RankUpApi.API_VERSION == 2, "api version 2");
        h.assertTrue(RankUpApi.track(server).equals("ru_ladder_track"), "the track: " + RankUpApi.track(server));
        h.assertTrue(RankUpApi.ladder(server).size() == 3 && RankUpApi.ladder(server).get(2).seasonal() && RankUpApi.ladder(server).get(1).points() == 50, "three rungs with their flags: " + RankUpApi.ladder(server));
        h.assertTrue(RankUpApi.rank(server, "ru_l_b").isPresent() && RankUpApi.rank(server, "ru_l_b").get().index() == 1 && RankUpApi.rank(server, "nope").isEmpty(), "a rung by group");
        ProgressInfo fresh = RankUpApi.progressOf(server, id);
        h.assertTrue(fresh.points() == 0 && fresh.rank() == null && fresh.next() != null && fresh.next().index() == 0 && fresh.counts().isEmpty(), "a fresh player: nothing yet, the first rung next: " + fresh);
        h.assertTrue(ledger.peek(id) == null || ledger.peek(id).counts().isEmpty(), "asking did not create counts");

        ledger.add(id, Signal.PLAYER_KILLS, 1);          // 25 points
        RankUpApi.addPoints(server, id, 35, "api test"); // 60
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(RankUpApi.rankOf(server, id).map(r -> r.group()).orElse("").equals("ru_l_b"), "the api sees the second rung: " + RankUpApi.rankOf(server, id)))
                .thenExecute(() -> {
                    ProgressInfo info = RankUpApi.progressOf(server, id);
                    h.assertTrue(info.points() == 60 && info.bonus() == 35 && Long.valueOf(1L).equals(info.counts().get("player_kills")), "points, bonus and counts: " + info);
                    h.assertTrue(info.rank().index() == 1 && info.next().group().equals("ru_l_c") && info.pointsToNext() == 40, "rung, next rung and the points to go: " + info);
                    h.assertTrue(RankUpApi.hasRank(server, id, "ru_l_a") && RankUpApi.hasRank(server, id, "ru_l_b") && !RankUpApi.hasRank(server, id, "ru_l_c"), "hasRank means this rung or higher");
                    h.assertTrue(events.size() == 2 && events.get(0).getFrom() == null && events.get(0).getTo().index() == 0 && events.get(1).getFrom().index() == 0 && events.get(1).getTo().index() == 1, "two EARNED events, first rung then second: " + events.size());
                    h.assertTrue(events.stream().allMatch(e -> e.getCause() == RankChangedEvent.Cause.EARNED && e.isPromotion() && e.getServer() == server), "earned, promotions, on this server");
                    RankEngine.assign(server, id, SettingsLedger.settings(server).ranks().get(2));
                })
                .thenWaitUntil(() -> h.assertTrue(events.size() == 3, "an admin's set fires too: " + events.size()))
                .thenExecute(() -> {
                    h.assertTrue(events.get(2).getCause() == RankChangedEvent.Cause.ADMIN && events.get(2).getFrom().index() == 1 && events.get(2).getTo().index() == 2, "ADMIN from the second to the third: " + events.get(2));
                    h.assertTrue(RankUpApi.progressOf(server, id).next() == null && RankUpApi.progressOf(server, id).pointsToNext() == 0, "at the top there is no next rung");
                    h.assertFalse(RankUpApi.isShared(), "no shared storage in this run");
                    h.assertTrue(RankUpApi.signalKeys().contains("totems_stolen"), "the signal keys are listed");
                })
                .thenExecute(() -> {
                    MinecraftForge.EVENT_BUS.unregister(listener);
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    LadderGameTests.uninstall(server, id);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, batch = "api-teams")
    public void a_team_provider_registered_through_the_api_answers_the_kill_rule(GameTestHelper h) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var registered = Teams.provider(); // BSP-Core's own, registered at server start: put back afterwards
        try {
            RankUpApi.setTeamProvider((x, y) -> (x.equals(a) && y.equals(b)) || (x.equals(b) && y.equals(a)));
            h.assertTrue(RankUpApi.hasTeamProvider() && Teams.sameTeam(a, b) && Teams.sameTeam(b, a) && !Teams.sameTeam(a, c), "the kill rule asks the provider");
            RankUpApi.setTeamProvider(null);
            h.assertFalse(RankUpApi.hasTeamProvider() || Teams.sameTeam(a, b), "removed again");
        } finally {
            Teams.setProvider(registered);
        }
        h.succeed();
    }
}
