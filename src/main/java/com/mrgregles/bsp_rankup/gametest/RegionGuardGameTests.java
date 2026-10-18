package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.RegionGuard;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The "claims bought" signal from Region Guard (request RG-R1). Region Guard is compile-only, so the dev run has no
 * Region Guard: the plain-data entry point is tested here, and the real event is posted only when Region Guard
 * happens to be loaded (a note is logged otherwise).
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class RegionGuardGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "claims")
    public void a_new_claim_counts_once_an_enlargement_not_at_all_and_the_switch_governs_the_points(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer buyer = TestPlayers.mockServerPlayer(h);
        UUID id = buyer.getUUID();
        ProgressLedger ledger = ProgressLedger.get(server);
        try {
            h.assertFalse(SettingsLedger.settings(server).signal(Signal.CLAIMS_BOUGHT).on(), "the signal is off by default");
            RegionGuard.claimBought(server, id, false);
            h.assertTrue(ledger.progress(id).count(Signal.CLAIMS_BOUGHT) == 1, "a new claim counts one: " + ledger.progress(id));
            RegionGuard.claimBought(server, id, true);
            h.assertTrue(ledger.progress(id).count(Signal.CLAIMS_BOUGHT) == 1, "an enlargement counts nothing: " + ledger.progress(id));
            h.assertTrue(RankEngine.points(SettingsLedger.settings(server), ledger.progress(id)) == 0, "worth nothing while the signal is off");
            SettingsLedger.get(server).update(b -> b.signal(Signal.CLAIMS_BOUGHT, new SignalSetting(true, 40, 1)));
            h.assertTrue(RankEngine.points(SettingsLedger.settings(server), ledger.progress(id)) == 40, "switched on, the claim already counted is worth 40: " + RankEngine.points(SettingsLedger.settings(server), ledger.progress(id)));
            RegionGuard.claimBought(server, id, false);
            h.assertTrue(ledger.progress(id).count(Signal.CLAIMS_BOUGHT) == 2 && RankEngine.points(SettingsLedger.settings(server), ledger.progress(id)) == 80, "a second claim: 80 points");
            if (RegionGuard.isLoaded()) {
                h.assertTrue(RegionGuard.isListening(), "the listener is registered when Region Guard is loaded");
                RealEvent.post(buyer);
                h.assertTrue(ledger.progress(id).count(Signal.CLAIMS_BOUGHT) == 3, "the real event counts too: " + ledger.progress(id));
            } else {
                BSPRankUp.LOGGER.warn("Region Guard is not loaded in this run; the real ClaimPurchasedEvent was not posted (the plain-data path was).");
            }
        } finally {
            SettingsLedger.get(server).set(Settings.defaults());
            ledger.reset(id);
            buyer.discard();
        }
        h.succeed();
    }

    /** Holds the Region Guard types, so this class loads only when the event is posted. */
    private static final class RealEvent {
        static void post(ServerPlayer buyer) {
            MinecraftForge.EVENT_BUS.post(new com.mrgregles.bsp_region_guard.api.event.ClaimPurchasedEvent(buyer, "rg_test_claim", buyer.level().dimension(), 100, false, null));
        }
    }
}
