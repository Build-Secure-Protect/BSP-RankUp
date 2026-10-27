package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.storage.MemoryBackend;
import com.mrgregles.bsp_rankup.storage.Storage;
import com.mrgregles.bsp_rankup.storage.StorageBackend;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Several ladders through the shared storage (owner decisions D49 to D53, RU-14c): the later ladders' rungs, the
 * season bases kept by name and the awards reached are written and read back on another server; a row written before
 * several ladders leaves the server's own copy; an award reached is announced on the other servers.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class LadderStorageGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "ladder-storage")
    public void later_ladders_bases_and_awards_travel_to_another_server(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        try {
            ledger.setOtherRank(player, "raider", "ru_ls_raider", 11L);
            ledger.setSeason(player, ledger.lastEndedSeason(), 40, Map.of("raider", 75L, Progress.awardKey("ru_ls_award"), 3L));
            ledger.setAward(player, "ru_ls_award", true, 22L);
            alpha.drain();
            StorageBackend.PlayerRow row = db.loadPlayer(player);
            h.assertTrue(row != null && row.ladders() != null, "the ladders are written: " + row);
            h.assertTrue(row.ladders().rankMap().equals(Map.of("raider", new Progress.Held("ru_ls_raider", 11L))), "the later ladder's rung: " + row.ladders());
            h.assertTrue(row.ladders().baseMap().equals(Map.of("raider", 75L, Progress.awardKey("ru_ls_award"), 3L)) && row.seasonBase() == 40, "the bases, the first ladder's in its own column: " + row);
            h.assertTrue(row.ladders().awardMap().equals(Map.of("ru_ls_award", 22L)), "the award: " + row.ladders());
            alpha.close();
            ledger.reset(player);
            Storage beta = Storage.startWith(server, db, "beta");
            beta.drain();
            try {
                beta.loadPlayer(player);
                beta.drain();
                Progress p = ledger.progress(player);
                h.assertTrue(p.otherRank("raider") != null && p.otherRank("raider").group().equals("ru_ls_raider") && p.otherRank("raider").atMs() == 11L, "another server reads the rung back: " + p);
                h.assertTrue(p.otherBase("raider") == 75L && p.otherBase(Progress.awardKey("ru_ls_award")) == 3L && p.seasonBase() == 40, "and the bases: " + p);
                h.assertTrue(p.hasAward("ru_ls_award") && p.awards().get("ru_ls_award") == 22L, "and the award: " + p);
            } finally {
                beta.close();
            }
        } finally {
            alpha.close();
            ledger.reset(player);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "ladder-storage-old")
    public void a_row_from_before_several_ladders_keeps_this_servers_copy(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        db.setRank(player, "ru_ls_first", 5L); // written by RankUp 0.2: no ladders columns
        Storage beta = Storage.startWith(server, db, "beta");
        beta.drain();
        try {
            ledger.setOtherRank(player, "raider", "ru_ls_kept", 9L);
            ledger.setAward(player, "ru_ls_kept_award", true, 10L);
            beta.drain(); // this server's own write: now the row has ladders
            db.setLadders(player, StorageBackend.LadderValue.NONE); // ...which a newer write clears again
            db.deletePlayer(player);
            db.setRank(player, "ru_ls_first", 5L); // back to an old row
            beta.loadPlayer(player);
            beta.drain();
            Progress p = ledger.progress(player);
            h.assertTrue("ru_ls_first".equals(p.rank()), "the row's first ladder: " + p);
            h.assertTrue(p.otherRank("raider") != null && p.hasAward("ru_ls_kept_award"), "an old row leaves this server's later ladders and awards: " + p);
            db.setLadders(player, StorageBackend.LadderValue.NONE);
            beta.loadPlayer(player);
            beta.drain();
            Progress q = ledger.progress(player);
            h.assertTrue(q.otherRank("raider") == null && !q.hasAward("ru_ls_kept_award"), "a row that holds the ladders, even empty, is the truth: " + q);
        } finally {
            beta.close();
            ledger.reset(player);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "ladder-storage-award")
    public void an_award_reached_here_is_announced_on_the_other_servers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        String group = "ru_ls_netaward";
        Award award = Award.of(group, "Net Award", Set.of(Signal.MOB_KILLS), 1);
        SettingsLedger.get(server).set(Settings.defaults().toBuilder().award(award).announce(Settings.Announce.NETWORK)
                .signal(Signal.MOB_KILLS, new SignalSetting(true, 1, 1)).build());
        if (LuckPermsBridge.isPresent()) {
            net.luckperms.api.LuckPermsProvider.get().getGroupManager().createAndLoadGroup(group).join();
        }
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        level.addNewPlayer(player);
        RankEngine.onLogin(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        ledger.add(player.getUUID(), Signal.MOB_KILLS, 1);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(ledger.progress(player.getUUID()).hasAward(group), "the award was reached"))
                .thenExecute(() -> {
                    alpha.drain();
                    List<StorageBackend.Notice> notices;
                    try {
                        notices = db.fetchNoticesAfter(0, 50);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    h.assertTrue(notices.stream().anyMatch(n -> n.kind().equals(Storage.KIND_AWARD) && n.payload().endsWith("\tNet Award") && n.source().equals("alpha")),
                            "an award notice went out for the other servers: " + notices);
                })
                .thenExecute(() -> {
                    alpha.close();
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    ledger.reset(player.getUUID());
                    SettingsLedger.get(server).set(Settings.defaults());
                    if (LuckPermsBridge.isPresent()) {
                        var lp = net.luckperms.api.LuckPermsProvider.get();
                        var user = lp.getUserManager().getUser(player.getUUID());
                        if (user != null) {
                            lp.getUserManager().cleanupUser(user);
                        }
                        var g = lp.getGroupManager().getGroup(group);
                        if (g != null) {
                            lp.getGroupManager().deleteGroup(g).join();
                        }
                    }
                })
                .thenSucceed();
    }
}
