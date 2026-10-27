package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.rank.Seasons;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Several ladders and awards in the engine (owner decisions D49 to D51, RU-14b), against the real LuckPerms Forge
 * mod when the dev run has it: each ladder counts its own signals and moves the player along its own track, a later
 * ladder's titles go to that ladder's priority, an award's group is added once its points are reached (and its
 * rewards given once), a group already held is recorded without rewards, and a season's end works per ladder and
 * takes seasonal awards back.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class MultiLadderGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final int RAID_PRIORITY = 990;

    /**
     * The tracks and groups of one test, with the test's own suffix: GameTests share one LuckPerms, and a group
     * deleted by one test may still be on its way out when the next makes it again.
     */
    private record Names(String sfx) {
        String playTrack() { return "ru_mt_play" + sfx; }
        String raidTrack() { return "ru_mt_raid" + sfx; }
        String pa() { return "ru_mp_a" + sfx; }
        String pb() { return "ru_mp_b" + sfx; }
        String ra() { return "ru_mr_a" + sfx; }
        String rb() { return "ru_mr_b" + sfx; }
        String award() { return "ru_maw_x" + sfx; }
        String held() { return "ru_maw_y" + sfx; }
    }

    private static Settings settings(Names n, boolean seasonalRaid, boolean seasonalAward) {
        Ladder play = new Ladder("ru_m_play", n.playTrack(), Set.of(Signal.PLAYTIME), List.of(Rank.of(n.pa(), "Play A", 0), Rank.of(n.pb(), "Play B", 100)), Settings.DEFAULT_TITLE_PRIORITY);
        Ladder raid = new Ladder("ru_m_raid", n.raidTrack(), Set.of(Signal.PLAYER_KILLS), List.of(Rank.of(n.ra(), "Raid A", 0),
                Rank.of(n.rb(), "Raid B", 50).withSeasonal(seasonalRaid).withRewards(List.of(Reward.title("[R] ")))), RAID_PRIORITY);
        Award award = new Award(n.award(), "Award X", Set.of(Signal.MOB_KILLS), 3, seasonalAward, List.of(Reward.item("minecraft:stick", 2)));
        Award held = new Award(n.held(), "Award Y", Set.of(Signal.MOB_KILLS), 3, false, List.of(Reward.item("minecraft:bread", 1)));
        Award onLadder = new Award(n.pb(), "Not an award", Set.of(), 0, false, List.of(Reward.item("minecraft:apple", 1)));
        return Settings.defaults().toBuilder().ladders(List.of(play, raid)).awards(List.of(award, held, onLadder))
                .signal(Signal.PLAYTIME, new SignalSetting(true, 1, 1))
                .signal(Signal.PLAYER_KILLS, new SignalSetting(true, 25, 1))
                .signal(Signal.MOB_KILLS, new SignalSetting(true, 1, 1)).build();
    }

    private static void install(MinecraftServer server, Names n, Settings settings) {
        if (LuckPermsBridge.isPresent()) {
            LuckPermsBridge.ensureTrack(n.playTrack(), List.of(n.pa(), n.pb())).join();
            LuckPermsBridge.ensureTrack(n.raidTrack(), List.of(n.ra(), n.rb())).join();
            var groups = net.luckperms.api.LuckPermsProvider.get().getGroupManager();
            groups.createAndLoadGroup(n.award()).join();
            groups.createAndLoadGroup(n.held()).join();
        }
        SettingsLedger.get(server).set(settings);
    }

    private static void uninstall(MinecraftServer server, Names n, UUID player) {
        SettingsLedger.get(server).set(Settings.defaults());
        ProgressLedger.get(server).reset(player);
        if (LuckPermsBridge.isPresent()) {
            var lp = net.luckperms.api.LuckPermsProvider.get();
            var user = lp.getUserManager().getUser(player);
            if (user != null) {
                lp.getUserManager().cleanupUser(user);
            }
            for (String t : List.of(n.playTrack(), n.raidTrack())) {
                var track = lp.getTrackManager().getTrack(t);
                if (track != null) {
                    lp.getTrackManager().deleteTrack(track).join();
                }
            }
            for (String g : List.of(n.pa(), n.pb(), n.ra(), n.rb(), n.award(), n.held())) {
                var group = lp.getGroupManager().getGroup(g);
                if (group != null) {
                    lp.getGroupManager().deleteGroup(group).join();
                }
            }
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "multi-ladder")
    public void each_ladder_counts_its_own_signals_and_moves_along_its_own_track(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        Names n = new Names("_1");
        Settings s = settings(n, false, false);
        install(server, n, s);
        level.addNewPlayer(player);
        RankEngine.onLogin(player); // any season ended by an earlier test is applied now
        ProgressLedger ledger = ProgressLedger.get(server);
        Ladder play = s.ladder("ru_m_play"), raid = s.ladder("ru_m_raid");

        ledger.add(id, Signal.PLAYER_KILLS, 2); // 50 points on the raid ladder, nothing on the playtime ladder
        Progress p0 = ledger.progress(id);
        h.assertTrue(RankEngine.points(s, raid, p0) == 50 && RankEngine.points(s, play, p0) == 0, "each ladder counts only its signals: raid " + RankEngine.points(s, raid, p0) + ", play " + RankEngine.points(s, play, p0));
        h.startSequence()
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(n.pa().equals(p.rank()), "the first ladder's first rung (0 points): " + p);
                    h.assertTrue(p.otherRank("ru_m_raid") != null && n.rb().equals(p.otherRank("ru_m_raid").group()), "the raid ladder's second rung: " + p);
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(LuckPermsBridge.rankOn(id, n.playTrack()).equals(Optional.of(n.pa())) && LuckPermsBridge.rankOn(id, n.raidTrack()).equals(Optional.of(n.rb())),
                                "LuckPerms holds a rung on each track: " + LuckPermsBridge.rankOn(id, n.playTrack()) + " " + LuckPermsBridge.rankOn(id, n.raidTrack()));
                        h.assertTrue(LuckPermsBridge.prefixOf(id, RAID_PRIORITY).equals(Optional.of("[R] ")), "the raid ladder's title at its own priority: " + LuckPermsBridge.prefixOf(id, RAID_PRIORITY));
                    }
                })
                .thenExecute(() -> {
                    ledger.addBonus(id, 50); // the bonus counts on every ladder: raid 100, play 50
                    ledger.add(id, Signal.PLAYTIME, 50);
                })
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(n.pb().equals(p.rank()), "playtime 50 and the bonus 50 reach the first ladder's second rung: " + p);
                    h.assertTrue(n.rb().equals(p.otherRank("ru_m_raid").group()), "the raid ladder is where it was (its top rung)");
                    h.assertFalse(p.hasAward(n.pb()) || player.getInventory().countItem(Items.APPLE) > 0, "a group on a ladder is never an award");
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    uninstall(server, n, id);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "multi-award")
    public void an_award_adds_its_group_once_reached_and_a_group_already_held_gives_no_rewards(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        Names n = new Names("_2");
        Settings s = settings(n, false, false);
        install(server, n, s);
        level.addNewPlayer(player);
        RankEngine.onLogin(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        if (LuckPermsBridge.isPresent()) {
            LuckPermsBridge.addGroup(id, n.held()).join(); // given by hand before RankUp would
        }
        h.startSequence()
                .thenExecute(() -> ledger.add(id, Signal.MOB_KILLS, 2))
                .thenExecuteAfter(10, () -> h.assertFalse(ledger.progress(id).hasAward(n.award()), "2 of 3 points: not yet"))
                .thenExecute(() -> ledger.add(id, Signal.MOB_KILLS, 1))
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(p.hasAward(n.award()), "3 points: reached: " + p);
                    h.assertTrue(player.getInventory().countItem(Items.STICK) == 2, "its rewards arrived: " + player.getInventory().countItem(Items.STICK));
                    if (LuckPermsBridge.isPresent()) {
                        var user = net.luckperms.api.LuckPermsProvider.get().getUserManager().loadUser(id).join();
                        h.assertTrue(user.getNodes().stream().anyMatch(node -> node.getKey().equals("group." + n.award())), "the group is on the player in LuckPerms");
                        h.assertTrue(p.hasAward(n.held()) && player.getInventory().countItem(Items.BREAD) == 0, "the group already held is recorded, with no rewards: " + p);
                    }
                })
                .thenExecute(() -> {
                    ledger.add(id, Signal.MOB_KILLS, 5);
                    RankEngine.check(server, id);
                })
                .thenExecuteAfter(10, () -> h.assertTrue(player.getInventory().countItem(Items.STICK) == 2, "reaching it again gives nothing more"))
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    uninstall(server, n, id);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "multi-season")
    public void a_seasons_end_works_per_ladder_and_takes_seasonal_awards_back(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        Names n = new Names("_3");
        Settings s = settings(n, true, true);
        install(server, n, s);
        level.addNewPlayer(player);
        RankEngine.onLogin(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        ledger.add(id, Signal.PLAYER_KILLS, 2); // raid 50: the seasonal second rung
        ledger.add(id, Signal.MOB_KILLS, 3); // the seasonal award
        h.startSequence()
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(p.otherRank("ru_m_raid") != null && n.rb().equals(p.otherRank("ru_m_raid").group()) && p.hasAward(n.award()), "the seasonal rung and award reached: " + p);
                })
                .thenExecute(() -> Seasons.endSeason(server, ledger.lastEndedSeason() + 1))
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(p.season() == ledger.lastEndedSeason(), "the season's end was applied: " + p);
                    h.assertTrue(p.otherRank("ru_m_raid") != null && n.ra().equals(p.otherRank("ru_m_raid").group()), "the raid ladder fell back to its permanent rung: " + p);
                    h.assertFalse(p.hasAward(n.award()), "the seasonal award was taken back");
                    h.assertTrue(p.otherBase("ru_m_raid") == 50 && p.otherBase(Progress.awardKey(n.award())) == 3, "the bases are this moment's points: " + p);
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(LuckPermsBridge.rankOn(id, n.raidTrack()).equals(Optional.of(n.ra())) && !LuckPermsBridge.groupsOf(id).contains(n.award()),
                                "and LuckPerms with it: " + LuckPermsBridge.groupsOf(id));
                    }
                })
                .thenExecute(() -> {
                    ledger.add(id, Signal.PLAYER_KILLS, 2); // 50 more this season
                    ledger.add(id, Signal.MOB_KILLS, 3);
                })
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(n.rb().equals(p.otherRank("ru_m_raid").group()) && p.hasAward(n.award()), "this season's points earn both back: " + p);
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    uninstall(server, n, id);
                })
                .thenSucceed();
    }
}
