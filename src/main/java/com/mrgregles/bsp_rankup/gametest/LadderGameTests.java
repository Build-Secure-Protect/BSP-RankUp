package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.network.chat.Component;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ladder engine: points from counts, the rung they reach, promotion through LuckPerms with rewards and
 * announcements, no demotion, rewards kept for players who are away. Tests that install a ladder run in their
 * own batch because the settings are global.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class LadderGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String TRACK = "ru_ladder_track";
    private static final List<String> GROUPS = List.of("ru_l_a", "ru_l_b", "ru_l_c");
    private static final String OBJECTIVE = "ru_test_cmd";

    @GameTest(template = EMPTY)
    public void points_and_rank_index_follow_the_settings(GameTestHelper h) {
        Settings s = ladder().toBuilder()
                .signal(Signal.PLAYTIME, new SignalSetting(true, 3, 10))
                .signal(Signal.PLAYER_KILLS, new SignalSetting(true, 25, 1))
                .signal(Signal.MOB_KILLS, new SignalSetting(false, 100, 1))
                .build();
        ProgressLedger scratch = new ProgressLedger();
        UUID p = UUID.randomUUID();
        scratch.add(p, Signal.PLAYTIME, 29);     // 2 x 10 minutes = 6 points
        scratch.add(p, Signal.PLAYER_KILLS, 2);  // 50
        scratch.add(p, Signal.MOB_KILLS, 500);   // off: 0
        scratch.addBonus(p, 4);
        Progress pr = scratch.progress(p);
        h.assertTrue(RankEngine.points(s, pr) == 60, "points: 6 + 50 + 4 = " + RankEngine.points(s, pr));
        h.assertTrue(RankEngine.rankIndex(s, 0) == 0, "0 points reach the first rung (threshold 0)");
        h.assertTrue(RankEngine.rankIndex(s, 60) == 1, "60 points reach the second rung (50)");
        h.assertTrue(RankEngine.rankIndex(s, 100) == 2, "100 points reach the third (100)");
        h.assertTrue(RankEngine.rankIndex(Settings.defaults(), 1000) == -1, "no ladder, no rung");
        Settings odd = ladder().toBuilder().setRank(1, Rank.of("ru_l_b", "B", 500)).build();
        h.assertTrue(RankEngine.rankIndex(odd, 100) == 0, "a rung above a higher threshold is never skipped to: " + RankEngine.rankIndex(odd, 100));
        h.assertTrue(new SignalSetting(true, 5, 0).pointsFor(10) == 0 && new SignalSetting(true, 5, 3).pointsFor(-1) == 0, "a zero 'per' or a negative count is worth nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void settings_save_and_load(GameTestHelper h) {
        Settings s = ladder().toBuilder()
                .track("other")
                .announce(Settings.Announce.NETWORK)
                .titlePriority(77)
                .killCooldownMinutes(3)
                .rewardsAgainEachSeason(false)
                .signal(Signal.DEATHS, new SignalSetting(true, -5, 1))
                .build();
        CompoundTag tag = s.save();
        Settings back = Settings.load(tag);
        h.assertTrue(back.track().equals("other") && back.announce() == Settings.Announce.NETWORK && back.titlePriority() == 77 && back.killCooldownMinutes() == 3 && !back.rewardsAgainEachSeason(), "options survive");
        h.assertTrue(back.ranks().size() == 3 && back.ranks().get(1).points() == 50 && back.ranks().get(1).rewards().size() == 5 && back.ranks().get(2).seasonal(), "ranks and rewards survive: " + back.ranks());
        h.assertTrue(back.ranks().get(1).rewards().get(2).equals(Reward.item("minecraft:stick", 3)), "a reward survives exactly: " + back.ranks().get(1).rewards().get(2));
        h.assertTrue(back.signal(Signal.DEATHS).points() == -5 && back.signal(Signal.DEATHS).on(), "a signal setting survives");
        h.assertTrue(back.signal(Signal.TOTEMS_STOLEN).equals(SignalSetting.defaults(Signal.TOTEMS_STOLEN)), "an untouched signal keeps its default");
        h.assertTrue(Settings.load(new CompoundTag()).ranks().isEmpty() && Settings.load(new CompoundTag()).track().equals(Settings.DEFAULT_TRACK), "an empty tag loads the defaults");
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ladder-up")
    public void reaching_a_rung_moves_the_player_there_with_rewards_and_an_announcement(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        String name = player.getGameProfile().getName();
        List<RankEngine.RankUp> ups = new ArrayList<>();
        java.util.function.Consumer<RankEngine.RankUp> listener = u -> {
            if (u.player().equals(player.getUUID())) {
                ups.add(u);
            }
        };
        RankEngine.onRankUp(listener);
        Objective objective = server.getScoreboard().getObjective(OBJECTIVE) != null ? server.getScoreboard().getObjective(OBJECTIVE)
                : server.getScoreboard().addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal(OBJECTIVE), ObjectiveCriteria.RenderType.INTEGER);
        install(server);
        level.addNewPlayer(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        long balanceBefore = BspCore.balance(server, player.getUUID());

        ledger.add(player.getUUID(), Signal.PLAYER_KILLS, 2); // 50 points: past the first rung (0) onto the second (50)
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue("ru_l_b".equals(ledger.progress(player.getUUID()).rank()), "the ledger holds the second rung: " + ledger.progress(player.getUUID()));
                    h.assertTrue(ups.size() == 2 && ups.get(0).from() == null && ups.get(0).to().group().equals("ru_l_a") && ups.get(1).to().group().equals("ru_l_b"), "two rank-ups, first then second rung: " + ups);
                    h.assertTrue(player.getInventory().countItem(Items.STICK) == 3, "the second rung's sticks arrived: " + player.getInventory().countItem(Items.STICK));
                    h.assertTrue(BspCore.balance(server, player.getUUID()) == balanceBefore + 10, "10 pieces into the bank: " + BspCore.balance(server, player.getUUID()));
                    h.assertTrue(BspCore.coinValue(player) == 5, "coins worth 5 Copper in hand: " + BspCore.coinValue(player));
                    h.assertTrue(server.getScoreboard().getOrCreatePlayerScore(name, objective).getScore() == 7, "the command ran with %player% replaced");
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(LuckPermsBridge.rankOn(player.getUUID(), TRACK).equals(Optional.of("ru_l_b")), "LuckPerms holds the second rung: " + LuckPermsBridge.rankOn(player.getUUID(), TRACK));
                        h.assertTrue(LuckPermsBridge.prefixOf(player.getUUID(), Settings.DEFAULT_TITLE_PRIORITY).equals(Optional.of("[B] ")), "the title is a prefix: " + LuckPermsBridge.prefixOf(player.getUUID(), Settings.DEFAULT_TITLE_PRIORITY));
                    }
                })
                .thenExecute(() -> ledger.addBonus(player.getUUID(), 100)) // 150: the top rung
                .thenWaitUntil(() -> {
                    h.assertTrue("ru_l_c".equals(ledger.progress(player.getUUID()).rank()), "the ledger holds the top rung: " + ledger.progress(player.getUUID()));
                    h.assertTrue(ups.size() == 3 && ups.get(2).from().group().equals("ru_l_b"), "a third rank-up from the second rung: " + ups);
                    h.assertTrue(player.getInventory().countItem(Items.BREAD) == 1, "the top rung's bread arrived");
                })
                .thenExecute(() -> {
                    ledger.add(player.getUUID(), Signal.PLAYER_KILLS, 1);
                    h.assertTrue(ups.size() == 3, "more points at the top change nothing");
                })
                .thenExecute(() -> {
                    RankEngine.removeListener(listener);
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    uninstall(server, player.getUUID());
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ladder-hold")
    public void a_rank_is_never_taken_away_by_falling_points(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        List<RankEngine.RankUp> ups = new ArrayList<>();
        java.util.function.Consumer<RankEngine.RankUp> listener = u -> ups.add(u);
        RankEngine.onRankUp(listener);
        install(server);
        level.addNewPlayer(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        ledger.setRank(player.getUUID(), "ru_l_c", 1L); // given by an admin, with no points at all
        ledger.add(player.getUUID(), Signal.MOB_KILLS, 1);
        RankEngine.check(server, player.getUUID());
        h.startSequence()
                .thenExecuteAfter(20, () -> {
                    h.assertTrue("ru_l_c".equals(ledger.progress(player.getUUID()).rank()), "the top rung stays with 1 point: " + ledger.progress(player.getUUID()));
                    h.assertTrue(ups.isEmpty(), "no rank-up fired: " + ups);
                })
                .thenExecute(() -> {
                    RankEngine.removeListener(listener);
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    uninstall(server, player.getUUID());
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ladder-away")
    public void rewards_wait_for_a_player_who_is_away_and_arrive_on_login(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h); // never added to a world: away
        install(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        long balanceBefore = BspCore.balance(server, player.getUUID());
        ledger.addBonus(player.getUUID(), 60); // an admin's points while the player is away: the second rung
        h.startSequence()
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(player.getUUID());
                    h.assertTrue("ru_l_b".equals(p.rank()), "ranked while away: " + p);
                    h.assertTrue(BspCore.balance(server, player.getUUID()) == balanceBefore + 10, "pieces go into the bank at once");
                    h.assertTrue(p.pending().size() == 3, "coins, item and command wait: " + p.pending());
                    h.assertTrue(player.getInventory().countItem(Items.STICK) == 0, "nothing in the inventory yet");
                })
                .thenExecute(() -> {
                    level.addNewPlayer(player);
                    RankEngine.onLogin(player);
                    h.assertTrue(player.getInventory().countItem(Items.STICK) == 3, "the sticks arrive on login: " + player.getInventory().countItem(Items.STICK));
                    h.assertTrue(BspCore.coinValue(player) == 5, "and the coins: " + BspCore.coinValue(player));
                    h.assertTrue(ledger.progress(player.getUUID()).pending().isEmpty(), "nothing left waiting");
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    uninstall(server, player.getUUID());
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ helpers

    /** Three rungs: a at 0 with nothing, b at 50 with every kind of reward, c at 100 (seasonal) with bread. */
    static Settings ladder() {
        List<Reward> second = List.of(
                Reward.coins(5),
                Reward.pieces(10),
                Reward.item("minecraft:stick", 3),
                Reward.title("[B] "),
                Reward.command("scoreboard players set %player% " + OBJECTIVE + " 7"));
        return Settings.defaults().toBuilder()
                .track(TRACK)
                .addRank(Rank.of("ru_l_a", "A", 0))
                .addRank(Rank.of("ru_l_b", "B", 50).withRewards(second))
                .addRank(Rank.of("ru_l_c", "C", 100).withSeasonal(true).withRewards(List.of(Reward.item("minecraft:bread", 1))))
                .build();
    }

    static void install(MinecraftServer server) {
        if (LuckPermsBridge.isPresent()) {
            LuckPermsBridge.ensureTrack(TRACK, GROUPS).join();
        }
        SettingsLedger.get(server).set(ladder());
    }

    static void uninstall(MinecraftServer server, UUID player) {
        SettingsLedger.get(server).set(Settings.defaults());
        ProgressLedger.get(server).reset(player);
        if (LuckPermsBridge.isPresent()) {
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player);
            if (user != null) {
                lp.getUserManager().cleanupUser(user);
            }
            net.luckperms.api.track.Track track = lp.getTrackManager().getTrack(TRACK);
            if (track != null) {
                lp.getTrackManager().deleteTrack(track).join();
            }
            for (String name : GROUPS) {
                net.luckperms.api.model.group.Group group = lp.getGroupManager().getGroup(name);
                if (group != null) {
                    lp.getGroupManager().deleteGroup(group).join();
                }
            }
        }
    }
}
