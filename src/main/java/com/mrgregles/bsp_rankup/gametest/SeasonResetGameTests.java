package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_core.api.event.SeasonEvent;
import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.rank.SeasonReset;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.storage.MemoryBackend;
import com.mrgregles.bsp_rankup.storage.Storage;
import com.mrgregles.bsp_rankup.storage.StorageBackend;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BSP-Core's season reset (owner decision SR12): every player back to the bottom of every ladder, offline players
 * too, in the ledger and in LuckPerms; seasonal awards taken back, permanent awards and the seasons survived kept
 * (one more); groups off the ladders untouched; rung rewards earned again. On a network only the server it was typed
 * on rewrites the shared rows; every server forgets what it had not written and starts stale rows over as it reads
 * them. The ladder of {@link LadderGameTests}: a (0), b (50, a title and sticks), c (100, seasonal).
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class SeasonResetGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String TRACK = "ru_ladder_track";
    private static final String PERM = "ru_sr_perm", SEASONAL = "ru_sr_seasonal", VIP = "ru_sr_vip";

    /** A season reset's event as a newer BSP-Core posts it (this repo builds against a BSP-Core without the getters). */
    public static final class ResetEnded extends SeasonEvent.Ended {
        private final boolean origin;

        public ResetEnded(MinecraftServer server, int endedNumber, boolean origin) {
            super(server, endedNumber, true, "test");
            this.origin = origin;
        }

        public boolean isSeasonReset() {
            return true;
        }

        public boolean isOrigin() {
            return origin;
        }
    }

    @GameTest(template = EMPTY, batch = "season-reset-flags")
    public void the_reset_flags_are_read_by_reflection(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        h.assertTrue(!BspCore.flag(new SeasonEvent.Ended(server, 1, true, "test"), "isSeasonReset"), "a plain season end (or an older BSP-Core) is no season reset");
        h.assertTrue(!BspCore.flag(new SeasonEvent.Ended(server, 1, true, "test"), "isNoSuchGetter"), "a missing getter reads as false");
        h.assertTrue(!BspCore.flag(new Object(), "isOrigin"), "an object without the getter reads as false");
        h.assertTrue(BspCore.flag(new ResetEnded(server, 1, true), "isSeasonReset") && BspCore.flag(new ResetEnded(server, 1, true), "isOrigin"), "a reset typed here reads both");
        h.assertTrue(BspCore.flag(new ResetEnded(server, 1, false), "isSeasonReset") && !BspCore.flag(new ResetEnded(server, 1, false), "isOrigin"), "a reset heard from another server");
        h.succeed();
    }

    private static Settings withAwards(Settings ladder) {
        return ladder.toBuilder()
                .award(Award.of(PERM, "Perm", Set.of(), 10).withRewards(List.of(Reward.title("[P] "))))
                .award(Award.of(SEASONAL, "Seasonal", Set.of(), 55).withSeasonal(true).withRewards(List.of(Reward.title("[S] "))))
                .build();
    }

    private static void createGroups() {
        if (LuckPermsBridge.isPresent()) {
            for (String g : List.of(PERM, SEASONAL, VIP)) {
                net.luckperms.api.LuckPermsProvider.get().getGroupManager().createAndLoadGroup(g).join();
            }
        }
    }

    private static void deleteGroups(UUID... players) {
        if (!LuckPermsBridge.isPresent()) {
            return;
        }
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        for (UUID p : players) {
            net.luckperms.api.model.user.User u = lp.getUserManager().getUser(p);
            if (u != null) {
                lp.getUserManager().cleanupUser(u);
            }
        }
        for (String g : List.of(PERM, SEASONAL, VIP)) {
            net.luckperms.api.model.group.Group group = lp.getGroupManager().getGroup(g);
            if (group != null) {
                lp.getGroupManager().deleteGroup(group).join();
            }
        }
    }

    /**
     * The player's LuckPerms user as loaded, or loaded from storage when it is not: a reload of a loaded user while an
     * edit of it is between its load and its save could make that save write the old nodes back.
     */
    private static net.luckperms.api.model.user.User user(UUID player) {
        net.luckperms.api.model.user.UserManager users = net.luckperms.api.LuckPermsProvider.get().getUserManager();
        net.luckperms.api.model.user.User loaded = users.getUser(player);
        return loaded != null ? loaded : users.loadUser(player).join();
    }

    /** The groups LuckPerms holds for the player (online or not). */
    private static Set<String> groups(UUID player) {
        return user(player).getNodes(net.luckperms.api.node.NodeType.INHERITANCE).stream().map(net.luckperms.api.node.types.InheritanceNode::getGroupName).collect(Collectors.toSet());
    }

    private static Optional<String> prefix(UUID player, int priority) {
        return user(player).getNodes(net.luckperms.api.node.NodeType.PREFIX).stream().filter(n -> n.getPriority() == priority).map(net.luckperms.api.node.types.PrefixNode::getMetaValue).findFirst();
    }

    /**
     * Before and after each batch of these tests, failed or not: the resets heard are forgotten, so the other tests'
     * rows (season-reset marker 0) are not taken for rows from before a reset. The game test world persists.
     */
    @net.minecraft.gametest.framework.BeforeBatch(batch = "season-reset")
    public static void beforeReset(ServerLevel level) {
        ProgressLedger.get(level.getServer()).forgetResetsForTests();
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "season-reset")
    public static void afterReset(ServerLevel level) {
        ProgressLedger.get(level.getServer()).forgetResetsForTests();
    }

    @net.minecraft.gametest.framework.BeforeBatch(batch = "season-reset-network")
    public static void beforeNetwork(ServerLevel level) {
        ProgressLedger.get(level.getServer()).forgetResetsForTests();
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "season-reset-network")
    public static void afterNetwork(ServerLevel level) {
        ProgressLedger.get(level.getServer()).forgetResetsForTests();
    }

    private static int nextReset(ProgressLedger ledger) {
        return Math.max(ledger.lastEndedSeason(), ledger.lastResetSeason()) + 1;
    }

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "season-reset")
    public void a_season_reset_starts_every_player_over(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        UUID away = UUID.randomUUID();
        LadderGameTests.install(server);
        createGroups();
        Settings settings = withAwards(SettingsLedger.settings(server));
        SettingsLedger.get(server).set(settings);
        int awardPriority = RankEngine.awardTitlePriority(settings);
        level.addNewPlayer(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        ledger.forgetResetsForTests(); // a failed run of these tests may have left one
        RankEngine.onLogin(player);
        int[] season = new int[1];
        if (LuckPermsBridge.isPresent()) {
            LuckPermsBridge.addGroup(id, VIP).join(); // through the bridge's queue, so RankUp's own edits of this player cannot save over it
        }
        ledger.addBonus(id, 20); // the permanent award first...
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(ledger.progress(id).hasAward(PERM), "the permanent award: " + ledger.progress(id)))
                .thenExecute(() -> ledger.addBonus(id, 40)) // ...then b and the seasonal award, whose title shows last
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue("ru_l_b".equals(p.rank()) && p.hasAward(SEASONAL) && player.getInventory().countItem(Items.STICK) == 3, "on b with both awards and b's sticks: " + p);
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(prefix(id, awardPriority).equals(Optional.of("[S] ")), "the seasonal award's title shows: " + prefix(id, awardPriority));
                        h.assertTrue(prefix(id, Settings.DEFAULT_TITLE_PRIORITY).equals(Optional.of("[B] ")), "and b's title: " + prefix(id, Settings.DEFAULT_TITLE_PRIORITY));
                    }
                })
                .thenExecute(() -> {
                    ledger.add(id, Signal.SEASONS_SURVIVED, 2);
                    ledger.add(id, Signal.PLAYTIME, 7);
                    ledger.addBonus(away, 60); // the player who is away: on b and both awards by the ledger and LuckPerms
                })
                .thenWaitUntil(() -> h.assertTrue("ru_l_b".equals(ledger.progress(away).rank()) && ledger.progress(away).hasAward(SEASONAL), "the player away climbed too: " + ledger.progress(away)))
                .thenExecute(() -> cleanOnFailure(ledger, () -> {
                    season[0] = nextReset(ledger);
                    MinecraftForge.EVENT_BUS.post(new ResetEnded(server, season[0], true));
                    Progress p = ledger.progress(id);
                    h.assertTrue(p.counts().equals(Map.of(Signal.SEASONS_SURVIVED, 3L)), "only the seasons survived are kept, one more: " + p.counts());
                    h.assertTrue(p.bonus() == 0 && p.rank() == null && p.otherRanks().isEmpty() && p.rewarded().isEmpty() && p.pending().isEmpty(), "no bonus, rung, rewards had or waiting: " + p);
                    h.assertTrue(p.season() == season[0] && p.seasonBase() == 0 && p.otherBases().isEmpty(), "the season ended is recorded, every base at zero: " + p);
                    h.assertTrue(p.hasAward(PERM) && !p.hasAward(SEASONAL), "the permanent award is kept, the seasonal one taken back: " + p.awards());
                    Progress q = ledger.progress(away);
                    h.assertTrue(q.rank() == null && q.bonus() == 0 && q.count(Signal.SEASONS_SURVIVED) == 1 && q.hasAward(PERM) && !q.hasAward(SEASONAL), "the player away too: " + q);
                    h.assertTrue(ledger.lastResetSeason() == season[0] && ledger.lastEndedSeason() == season[0], "the reset is remembered");
                }))
                .thenWaitUntil(() -> {
                    if (LuckPermsBridge.isPresent()) {
                        Set<String> mine = groups(id);
                        h.assertTrue(mine.contains("ru_l_a") && !mine.contains("ru_l_b") && !mine.contains(SEASONAL), "LuckPerms: back to the first rung, the seasonal award gone: " + mine);
                        h.assertTrue(mine.contains(PERM) && mine.contains(VIP), "the permanent award and the group off the ladders stay: " + mine);
                        h.assertTrue(prefix(id, Settings.DEFAULT_TITLE_PRIORITY).isEmpty(), "b's title went with b: " + prefix(id, Settings.DEFAULT_TITLE_PRIORITY));
                        h.assertTrue(prefix(id, awardPriority).equals(Optional.of("[P] ")), "the kept award's title shows again: " + prefix(id, awardPriority));
                        Set<String> theirs = groups(away);
                        h.assertTrue(theirs.contains("ru_l_a") && !theirs.contains("ru_l_b") && !theirs.contains(SEASONAL) && theirs.contains(PERM), "the player away is moved too: " + theirs);
                    }
                })
                .thenExecute(() -> cleanOnFailure(ledger, () -> {
                    MinecraftForge.EVENT_BUS.post(new ResetEnded(server, season[0], false)); // heard a second time
                    h.assertTrue(ledger.progress(id).count(Signal.SEASONS_SURVIVED) == 3, "a reset heard twice counts once: " + ledger.progress(id));
                    RankEngine.check(server, id); // as at the next login
                }))
                .thenWaitUntil(() -> h.assertTrue("ru_l_a".equals(ledger.progress(id).rank()) && ledger.progress(id).rewarded().contains("ru_l_a"), "the next check climbs to the first rung: " + ledger.progress(id)))
                .thenExecute(() -> ledger.addBonus(id, 60))
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue("ru_l_b".equals(p.rank()) && player.getInventory().countItem(Items.STICK) == 6, "b reached again gives its rewards again: " + p + ", sticks " + player.getInventory().countItem(Items.STICK));
                    h.assertTrue(p.hasAward(SEASONAL), "the seasonal award is earned again on this season's points: " + p.awards());
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    ledger.reset(away);
                    ledger.forgetResetsForTests();
                    LadderGameTests.uninstall(server, id);
                    deleteGroups(id, away);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "season-reset-network")
    public void only_the_origin_rewrites_the_shared_rows(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        LadderGameTests.install(server);
        createGroups();
        Settings settings = withAwards(SettingsLedger.settings(server));
        SettingsLedger.get(server).set(settings);
        ProgressLedger ledger = ProgressLedger.get(server);
        ledger.forgetResetsForTests(); // a failed run of these tests may have left one
        MemoryBackend db = new MemoryBackend();
        UUID row = UUID.randomUUID(), local = UUID.randomUUID(), later = UUID.randomUUID(), stray = UUID.randomUUID();
        try {
            networkSteps(h, server, settings, ledger, db, row, local, later, stray);
        } catch (RuntimeException | Error e) {
            ledger.forgetResetsForTests();
            throw e;
        }
        h.startSequence()
                .thenWaitUntil(() -> {
                    if (LuckPermsBridge.isPresent()) {
                        Set<String> theirs = groups(row);
                        h.assertTrue(theirs.contains("ru_l_a") && !theirs.contains("ru_l_b"), "a player only the rows know is moved in LuckPerms: " + theirs);
                        Set<String> strays = groups(stray);
                        h.assertTrue(strays.contains("ru_l_a") && !strays.contains("ru_l_b") && !strays.contains(SEASONAL),
                                "a player whose move was lost is put right at their login: " + strays);
                    }
                })
                .thenExecute(() -> {
                    ledger.reset(row);
                    ledger.reset(local);
                    ledger.reset(later);
                    ledger.reset(stray);
                    ledger.forgetResetsForTests();
                    LadderGameTests.uninstall(server, local);
                    deleteGroups(row, local, later, stray);
                })
                .thenSucceed();
    }

    /** Runs synchronous checks; a failure clears the resets heard first, so the other tests' rows are not taken for stale ones next run. */
    private static void cleanOnFailure(ProgressLedger ledger, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException | Error e) {
            ledger.forgetResetsForTests();
            throw e;
        }
    }

    private static void networkSteps(GameTestHelper h, MinecraftServer server, Settings settings, ProgressLedger ledger, MemoryBackend db, UUID row, UUID local, UUID later, UUID stray)
            throws Exception {
        int first = nextReset(ledger), second = first + 1;
        // a row from before the reset, for a player this server has no copy of
        db.addCount(row, Signal.PLAYTIME.key(), 100);
        db.addCount(row, Signal.SEASONS_SURVIVED.key(), 1);
        db.addBonus(row, 5);
        db.setRank(row, "ru_l_b", 7L);
        db.setRewarded(row, "ru_l_a,ru_l_b");
        db.setSeason(row, first - 1, 3, 4);
        db.setLadders(row, new StorageBackend.LadderValue("", "", PERM + "\t9\n" + SEASONAL + "\t10\n"));
        Storage beta = Storage.startWith(server, db, "beta");
        beta.drain();
        try {
            ledger.addBonus(local, 5); // a change this server has not written yet
            // heard from another server: this server's copies start over, nothing it had not written is written
            SeasonReset.apply(server, first, false, beta);
            h.assertTrue(ledger.progress(local).bonus() == 0, "this server's copy started over: " + ledger.progress(local));
            beta.drain();
            h.assertTrue(db.loadPlayer(local) == null, "the change from before the reset was never written: " + db.loadPlayer(local));
            StorageBackend.PlayerRow untouched = db.loadPlayer(row);
            h.assertTrue(untouched != null && "ru_l_b".equals(untouched.rank()) && untouched.season() == first - 1, "the shared row is not this server's to change: " + untouched);
            beta.loadPlayer(row);
            beta.drain();
            Progress read = ledger.progress(row);
            h.assertTrue(read.rank() == null && read.bonus() == 0 && read.count(Signal.PLAYTIME) == 0 && read.count(Signal.SEASONS_SURVIVED) == 2, "a stale row read here starts over as it is read: " + read);
            h.assertTrue(read.hasAward(PERM) && !read.hasAward(SEASONAL) && read.rewarded().isEmpty() && read.season() == first, "with its permanent award only: " + read);
            StorageBackend.PlayerRow fixed = db.loadPlayer(row);
            h.assertTrue(fixed != null && fixed.rank() == null && fixed.resetSeason() == first && fixed.counts().equals(Map.of(Signal.SEASONS_SURVIVED.key(), 2L)) && fixed.rewarded() == null,
                    "and put right in the row, so the next read does not start it over again: " + fixed);
            beta.loadPlayer(row);
            beta.drain();
            h.assertTrue(ledger.progress(row).count(Signal.SEASONS_SURVIVED) == 2 && db.loadPlayer(row).counts().equals(Map.of(Signal.SEASONS_SURVIVED.key(), 2L)), "read again: as it is");
        } finally {
            beta.close();
        }
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        try {
            // typed here, with the database down at first
            db.setFailing(true);
            SeasonReset.apply(server, second, true, alpha);
            h.assertTrue(ledger.pendingSharedReset() == second, "the pass over the rows is remembered until it is written");
            alpha.drain();
            h.assertTrue(alpha.sharedResetWaiting(), "a failed pass waits to be tried again");
            db.setFailing(false);
            alpha.drain();
            h.assertTrue(!alpha.sharedResetWaiting() && ledger.pendingSharedReset() == 0, "tried again and written");
            StorageBackend.PlayerRow r = db.loadPlayer(row);
            h.assertTrue(r != null && r.rank() == null && r.rankedAt() == 0 && r.bonus() == 0 && r.rewarded() == null && r.pendingJson() == null, "the row starts over: " + r);
            h.assertTrue(r.counts().equals(Map.of(Signal.SEASONS_SURVIVED.key(), 2L)), "only the seasons survived, not counted again for a season not played: " + r.counts());
            h.assertTrue(r.resetSeason() == second && r.season() == second && r.seasonBase() == 0 && r.scorePeak() == 0, "marked with the season the reset ended: " + r);
            // a server that has only heard of the first reset writes nothing onto the row; one that heard of this one does
            StorageBackend.PlayerWrite behind = new StorageBackend.PlayerWrite(row, false, Map.of(Signal.PLAYTIME.key(), 50L), 0, new StorageBackend.RankValue("ru_l_c", 1L), null, null, null, null, first, false);
            h.assertTrue(db.write(List.of(behind)).isEmpty() && db.loadPlayer(row).rank() == null && db.count(row, Signal.PLAYTIME.key()) == 0,
                    "an old rank from a server that has not heard yet is refused: " + db.loadPlayer(row));
            StorageBackend.PlayerWrite heard = new StorageBackend.PlayerWrite(row, false, Map.of(Signal.PLAYTIME.key(), 2L), 0, null, null, null, null, null, second, false);
            h.assertTrue(db.write(List.of(heard)).size() == 1 && db.count(row, Signal.PLAYTIME.key()) == 2, "a change made after the reset is written");
            h.assertTrue(r.ladders() != null && r.ladders().rankMap().isEmpty() && r.ladders().baseMap().isEmpty() && r.ladders().awardMap().equals(Map.of(PERM, 9L)), "no later rung or base, the permanent award kept: " + r.ladders());
            // a second pass (a restart before it was marked done) changes nothing, and a row first written after the reset is left alone
            db.write(List.of(new StorageBackend.PlayerWrite(later, false, Map.of(Signal.PLAYTIME.key(), 4L), 0, null, null, null, null, null, second, false)));
            h.assertTrue(db.loadPlayer(later).resetSeason() == second, "a row first written after the reset carries its marker");
            alpha.startOverShared(second, settings);
            alpha.drain();
            StorageBackend.PlayerWrite again = new StorageBackend.PlayerWrite(row, true, Map.of(), 0, null, null, new StorageBackend.SeasonValue(second, 0, 0), null, null, second, true);
            h.assertTrue(db.write(List.of(again)).isEmpty(), "the reset's rewrite of a row already started over for it is left out");
            h.assertTrue(db.count(row, Signal.SEASONS_SURVIVED.key()) == 2 && db.count(row, Signal.PLAYTIME.key()) == 2, "no second season counted, the change since kept: " + db.loadPlayer(row).counts());
            h.assertTrue(db.count(later, Signal.PLAYTIME.key()) == 4, "a row from after the reset keeps its playtime");
        } finally {
            alpha.close();
        }
        // a player whose LuckPerms move was lost (or never made here): still on b and the seasonal award in LuckPerms
        ledger.startOver(stray, new Progress().startedOver(second, Set.of()));
        if (LuckPermsBridge.isPresent()) {
            // through the bridge's queue, as RankUp's own edits go, so the login's edit comes after them
            h.assertTrue(LuckPermsBridge.setGroup(stray, TRACK, "ru_l_b").join(), "set up: b in LuckPerms");
            h.assertTrue(LuckPermsBridge.addGroup(stray, SEASONAL).join() != LuckPermsBridge.Added.FAILED, "set up: the seasonal award in LuckPerms");
            h.assertTrue(groups(stray).containsAll(Set.of("ru_l_b", SEASONAL)), "set up: both held " + groups(stray));
        }
        SeasonReset.atLogin(server, stray);
    }
}
