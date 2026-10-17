package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Proves the GameTest wiring: the mod and BSP-Core are loaded in the test server and a mock player can be made.
 * Every server-side rule added later gets its own test class next to this one.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class SetupGameTests {
    static final String EMPTY = "empty";

    @GameTest(template = EMPTY)
    public void mod_and_bsp_core_are_loaded_and_a_mock_player_can_be_made(GameTestHelper h) {
        if (!ModList.get().isLoaded(BSPRankUp.MODID)) {
            h.fail("bsp_rankup is not loaded");
            return;
        }
        if (!ModList.get().isLoaded("bsp_core")) {
            h.fail("bsp_core is not loaded");
            return;
        }
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        if (player.getGameProfile().getName().isEmpty()) {
            h.fail("mock player has no name");
            return;
        }
        h.succeed();
    }
}
