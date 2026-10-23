package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.signal.ScoreSignal;
import com.mrgregles.bsp_rankup.storage.MemoryBackend;
import com.mrgregles.bsp_rankup.storage.Storage;
import com.mrgregles.bsp_rankup.storage.StorageBackend;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The score signal (owner decision D48, BSP-Core 1.4.5's request RU-R1): each season's peak of BSP-Core's points,
 * added up; counted only where the player is online; the peak shared through the storage with the season values.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class ScoreGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "score-peak")
    public void only_points_past_this_seasons_peak_count_and_a_new_season_starts_the_peak_again(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        try {
            ledger.setSeason(player, ledger.lastEndedSeason(), 0); // a player the last season's end was applied to
            h.assertTrue(ScoreSignal.count(server, player, 300) == 300, "the first points count in full");
            h.assertTrue(ScoreSignal.count(server, player, 250) == 0, "a drop (a totem stolen) takes nothing back");
            h.assertTrue(ScoreSignal.count(server, player, 300) == 0, "climbing back to the peak (a totem put back) gains nothing");
            h.assertTrue(ScoreSignal.count(server, player, 400) == 100, "only what passes the peak counts");
            Progress p = ledger.progress(player);
            h.assertTrue(p.count(Signal.SCORE) == 400 && p.scorePeak() == 400, "count 400, peak 400: " + p);
            if (ledger.lastEndedSeason() > 0) {
                ledger.setSeason(player, ledger.lastEndedSeason() - 1, 0);
                h.assertTrue(ScoreSignal.count(server, player, 900) == 0, "nothing counts while a season's end is still to be applied");
            }
            ledger.setSeason(player, Math.max(1, ledger.lastEndedSeason()), 0);
            h.assertTrue(ledger.progress(player).scorePeak() == 0, "a season's end starts the peak again");
            h.assertTrue(ScoreSignal.count(server, player, 150) == 150, "the new season's points count from 0");
            h.assertTrue(ledger.progress(player).count(Signal.SCORE) == 550, "both seasons' peaks add up: " + ledger.progress(player));
        } finally {
            ledger.reset(player);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "score-online")
    public void bsp_cores_change_counts_only_on_the_server_the_player_is_on(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID away = UUID.randomUUID();
        ServerPlayer here = TestPlayers.mockServerPlayer(h);
        h.getLevel().addNewPlayer(here);
        try {
            h.assertTrue(BspCore.hasScores(), "this BSP-Core (1.4.5) has the scores api");
            ScoreSignal.changed(server, away, 500);
            h.assertFalse(ledger.has(away), "a player on another server is not counted here");
            if (ledger.lastEndedSeason() > 0) {
                h.assertTrue(ScoreSignal.count(server, here.getUUID(), 999) == 0 && ledger.progress(here.getUUID()).season() == ledger.lastEndedSeason(),
                        "a player here with a season's end still to apply has it applied first and nothing counted: " + ledger.progress(here.getUUID()));
            } else {
                ledger.setSeason(here.getUUID(), 0, 0);
            }
            MinecraftForge.EVENT_BUS.post(new com.mrgregles.bsp_core.api.event.ScoreEvent.Changed(server, here.getUUID(), here.getGameProfile().getName(), 220, 220, 2, 1));
            Progress p = ledger.progress(here.getUUID());
            h.assertTrue(p.count(Signal.SCORE) == 220 && p.scorePeak() == 220, "BSP-Core's event counts for a player here: " + p);
        } finally {
            ledger.reset(away);
            ledger.reset(here.getUUID());
            h.getLevel().removePlayerImmediately(here, Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "score-storage")
    public void the_peak_is_shared_through_the_storage_with_the_season_values(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ProgressLedger ledger = ProgressLedger.get(server);
        MemoryBackend db = new MemoryBackend();
        UUID player = UUID.randomUUID();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        try {
            ledger.setSeason(player, ledger.lastEndedSeason(), 0);
            ScoreSignal.count(server, player, 700);
            alpha.drain();
            StorageBackend.PlayerRow row = db.loadPlayer(player);
            h.assertTrue(row != null && row.scorePeak() == 700 && db.count(player, "score") == 700, "the peak and the count are written: " + row);
            alpha.close();
            ledger.reset(player);
            Storage beta = Storage.startWith(server, db, "beta");
            beta.drain();
            try {
                beta.loadPlayer(player);
                beta.drain();
                Progress p = ledger.progress(player);
                h.assertTrue(p.scorePeak() == 700 && p.count(Signal.SCORE) == 700, "another server reads the peak back, so it does not count the same points again: " + p);
                h.assertTrue(ScoreSignal.count(server, player, 700) == 0, "the same points on the other server count nothing");
            } finally {
                beta.close();
            }
        } finally {
            alpha.close();
            ledger.reset(player);
        }
        h.succeed();
    }
}
