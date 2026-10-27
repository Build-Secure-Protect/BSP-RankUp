package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_core.api.event.SeasonEvent;
import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.api.event.RankChangedEvent;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A season's end: players on a seasonal rung fall back to the highest permanent rung below, seasonal rungs are then
 * judged on points earned since, rewards come again (or once) as the setting says, players who were away are
 * handled on login. The ladder of {@link LadderGameTests}: a (0), b (50), c (100, seasonal).
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class SeasonGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "season")
    public void a_seasons_end_drops_seasonal_rungs_and_restarts_their_points(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer away = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        List<RankChangedEvent> events = new CopyOnWriteArrayList<>();
        Consumer<RankChangedEvent> listener = e -> {
            if (e.getPlayer().equals(id) && e.getCause() == RankChangedEvent.Cause.SEASON) {
                events.add(e);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RankChangedEvent.class, listener);
        LadderGameTests.install(server);
        // the seasonal top gives a title too: falling off it gives back b's (D75)
        SettingsLedger.get(server).update(b -> b.setRank(2, Rank.of("ru_l_c", "C", 100).withSeasonal(true)
                .withRewards(List.of(Reward.item("minecraft:bread", 1), Reward.title("[C] ")))));
        level.addNewPlayer(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        int base = ledger.lastEndedSeason(); // seasons ended by earlier tests persist in the test world
        RankEngine.onLogin(player); // a real login: any earlier season end is applied with 0 points
        h.assertTrue(ledger.progress(id).season() == base && ledger.progress(id).seasonBase() == 0, "logged in after the earlier seasons: " + ledger.progress(id));
        // the player who is away: on the seasonal top by the ledger alone, seen up to the current season
        ledger.setSeason(away.getUUID(), base, 0);
        ledger.addBonus(away.getUUID(), 150);
        ledger.setRank(away.getUUID(), "ru_l_c", 1L);
        ledger.addBonus(id, 150); // straight to c, the seasonal top
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue("ru_l_c".equals(ledger.progress(id).rank()) && player.getInventory().countItem(Items.BREAD) == 1, "on the seasonal top with its bread: " + ledger.progress(id));
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(LuckPermsBridge.prefixOf(id, Settings.DEFAULT_TITLE_PRIORITY).equals(Optional.of("[C] ")), "and its title: " + LuckPermsBridge.prefixOf(id, Settings.DEFAULT_TITLE_PRIORITY));
                    }
                })
                .thenExecute(() -> MinecraftForge.EVENT_BUS.post(new SeasonEvent.Ended(server, base + 1, false, "test")))
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue("ru_l_b".equals(p.rank()), "fell back to the highest permanent rung: " + p);
                    h.assertTrue(p.season() == base + 1 && p.seasonBase() == 150, "the season and the base are recorded: " + p);
                    h.assertTrue(p.count(Signal.SEASONS_SURVIVED) == 1, "one season survived: " + p);
                    h.assertTrue(events.size() == 1 && events.get(0).getFrom().index() == 2 && events.get(0).getTo().index() == 1 && !events.get(0).isPromotion(), "one SEASON event, c to b: " + events);
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(LuckPermsBridge.rankOn(id, "ru_ladder_track").equals(Optional.of("ru_l_b")), "LuckPerms agrees: " + LuckPermsBridge.rankOn(id, "ru_ladder_track"));
                        h.assertTrue(LuckPermsBridge.prefixOf(id, Settings.DEFAULT_TITLE_PRIORITY).equals(Optional.of("[B] ")), "the title follows the rung back to b's (D75): " + LuckPermsBridge.prefixOf(id, Settings.DEFAULT_TITLE_PRIORITY));
                    }
                })
                .thenExecuteAfter(10, () -> {
                    h.assertTrue("ru_l_b".equals(ledger.progress(id).rank()), "the lifetime points do not climb back up: " + ledger.progress(id));
                    h.assertTrue("ru_l_c".equals(ledger.progress(away.getUUID()).rank()) && ledger.progress(away.getUUID()).season() == base, "the player who is away is untouched until they log in: " + ledger.progress(away.getUUID()));
                    ledger.addBonus(id, 100); // 100 points this season: c again
                })
                .thenWaitUntil(() -> h.assertTrue("ru_l_c".equals(ledger.progress(id).rank()) && player.getInventory().countItem(Items.BREAD) == 2, "earned the seasonal rung again, rewarded again: " + ledger.progress(id) + ", bread " + player.getInventory().countItem(Items.BREAD)))
                .thenExecute(() -> {
                    SettingsLedger.get(server).update(b -> b.rewardsAgainEachSeason(false));
                    MinecraftForge.EVENT_BUS.post(new SeasonEvent.Ended(server, base + 2, false, "test"));
                })
                .thenWaitUntil(() -> h.assertTrue("ru_l_b".equals(ledger.progress(id).rank()) && ledger.progress(id).seasonBase() == 250, "fell again with the new base: " + ledger.progress(id)))
                .thenExecute(() -> ledger.addBonus(id, 100))
                .thenWaitUntil(() -> h.assertTrue("ru_l_c".equals(ledger.progress(id).rank()), "c a third time: " + ledger.progress(id)))
                .thenExecuteAfter(5, () -> {
                    h.assertTrue(player.getInventory().countItem(Items.BREAD) == 2, "no bread when seasonal rungs reward once: " + player.getInventory().countItem(Items.BREAD));
                    // the player who was away logs in: two seasons ended meanwhile
                    level.addNewPlayer(away);
                    RankEngine.onLogin(away);
                })
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(away.getUUID());
                    h.assertTrue("ru_l_b".equals(p.rank()) && p.season() == base + 2 && p.seasonBase() == 150 && p.count(Signal.SEASONS_SURVIVED) == 1, "handled once on login, falling to b: " + p);
                })
                .thenExecute(() -> {
                    MinecraftForge.EVENT_BUS.unregister(listener);
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    level.removePlayerImmediately(away, Entity.RemovalReason.DISCARDED);
                    LadderGameTests.uninstall(server, id);
                    ledger.reset(away.getUUID());
                    if (LuckPermsBridge.isPresent()) {
                        net.luckperms.api.model.user.User u = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(away.getUUID());
                        if (u != null) {
                            net.luckperms.api.LuckPermsProvider.get().getUserManager().cleanupUser(u);
                        }
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "season-off")
    public void a_player_on_a_seasonal_first_rung_leaves_the_ladder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        LadderGameTests.install(server);
        SettingsLedger.get(server).update(b -> b.setRank(0, Rank.of("ru_l_a", "A", 0).withSeasonal(true)).setRank(1, Rank.of("ru_l_b", "B", 50).withSeasonal(true)));
        level.addNewPlayer(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        int base = ledger.lastEndedSeason();
        RankEngine.onLogin(player);
        ledger.addBonus(id, 60);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue("ru_l_b".equals(ledger.progress(id).rank()), "on b: " + ledger.progress(id)))
                .thenExecute(() -> MinecraftForge.EVENT_BUS.post(new SeasonEvent.Ended(server, base + 1, true, "test")))
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(p.season() == base + 1 && p.rank() != null && p.rank().equals("ru_l_a"), "with every rung seasonal the player lands on the first again, since 0 season points reach it: " + p);
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(LuckPermsBridge.rankOn(id, "ru_ladder_track").equals(Optional.of("ru_l_a")), "LuckPerms: " + LuckPermsBridge.rankOn(id, "ru_ladder_track"));
                    }
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    LadderGameTests.uninstall(server, id);
                })
                .thenSucceed();
    }
}
