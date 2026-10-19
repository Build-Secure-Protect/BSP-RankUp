package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.admin.AdminEdits;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.rank.Rewards;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The KIT reward kind (BSP-Core's request K-1): a kit made in BSP-Core (through its internals, test only) is given
 * on a rank-up through {@code BspKits.give}; an unknown kit is refused by the editor and skipped by the giver.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class KitGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String KIT = "ru_test_kit";

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "kits")
    public void a_kit_reward_is_given_through_bsp_core_and_an_unknown_kit_is_refused(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        h.assertTrue(BspCore.hasKits(), "this BSP-Core has kits");
        Reward kit = Reward.kit(KIT);
        h.assertTrue(Reward.load(kit.save()).equals(kit) && !kit.needsPlayerOnline(), "a kit reward survives NBT and needs no online player");

        // a kit, made as BSP-Core's admin panel would (internals, test only)
        com.mrgregles.bsp_core.kit.Kit made = new com.mrgregles.bsp_core.kit.Kit(KIT, "RankUp Test Kit", System.currentTimeMillis());
        made.items.add(new ItemStack(Items.STICK, 2));
        com.mrgregles.bsp_core.kit.Kits.Outcome put = com.mrgregles.bsp_core.kit.Kits.put(server, made);
        h.assertTrue(put.ok(), "BSP-Core takes the test kit: " + put.message());
        LadderGameTests.install(server);
        SettingsLedger.get(server).update(b -> b.setRank(1, Rank.of("ru_l_b", "B", 50).withRewards(List.of(kit))));
        level.addNewPlayer(player);
        RankEngine.onLogin(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        try {
            h.assertTrue(BspCore.kits(server).stream().anyMatch(k -> k.id().equals(KIT) && k.name().equals("RankUp Test Kit")), "the adapter lists the kit: " + BspCore.kits(server));
            h.assertFalse(AdminEdits.rewardAdd(server, "ru_l_a", Reward.kit("no_such_kit")).ok(), "an unknown kit is refused by the editor");
            h.assertTrue(AdminEdits.rewardAdd(server, "ru_l_a", Reward.kit(KIT)).ok(), "a known kit is taken: " + AdminEdits.rewardAdd(server, "ru_l_a", Reward.kit(KIT)).text());
            h.assertTrue(Rewards.give(server, id, "test", List.of(Reward.kit("no_such_kit")), SettingsLedger.settings(server)).isEmpty(), "an unknown kit is skipped by the giver, nothing waits");
            h.assertTrue(ledger.progress(id).pending().isEmpty(), "nothing pends for a kit: BSP-Core owes it");
        } catch (RuntimeException e) {
            cleanup(server, level, player);
            throw e;
        }
        ledger.addBonus(id, 60); // the second rung: its reward is the kit
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue("ru_l_b".equals(ledger.progress(id).rank()), "on the second rung: " + ledger.progress(id));
                    boolean landed = player.getInventory().countItem(Items.STICK) >= 2;
                    boolean owed = com.mrgregles.bsp_core.api.BspKits.owed(server, id).contains(KIT);
                    h.assertTrue(landed || owed, "the kit landed or BSP-Core owes it (landed " + landed + ", owed " + owed + ")");
                })
                .thenExecute(() -> cleanup(server, level, player))
                .thenSucceed();
    }

    private static void cleanup(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        LadderGameTests.uninstall(server, player.getUUID());
        com.mrgregles.bsp_core.kit.Kits.delete(server, KIT);
    }
}
