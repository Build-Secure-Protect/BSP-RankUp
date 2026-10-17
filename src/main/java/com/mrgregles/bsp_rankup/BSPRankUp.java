package com.mrgregles.bsp_rankup;

import com.mojang.logging.LogUtils;
import com.mrgregles.bsp_rankup.command.AdminCommands;
import com.mrgregles.bsp_rankup.command.RankCommand;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.signal.KillCooldowns;
import com.mrgregles.bsp_rankup.signal.KillSignals;
import com.mrgregles.bsp_rankup.signal.Playtime;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point for BSP RankUp: automatic LuckPerms ranks for the Build Secure Protect modpack, raised by what
 * players do on the server. Features live in their own packages and are wired up here.
 */
@Mod(BSPRankUp.MODID)
public class BSPRankUp {
    /** Mod id. Must match {@code mod_id} in gradle.properties and {@code modId} in mods.toml. */
    public static final String MODID = "bsp_rankup";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BSPRankUp(FMLJavaModLoadingContext context) {
        // Signals: what players do, counted into the progress ledger.
        MinecraftForge.EVENT_BUS.addListener(Playtime::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(KillSignals::onDeath);
        MinecraftForge.EVENT_BUS.addListener(BSPRankUp::onLoggedOut);
        // The ladder: points, ranks, rewards, announcements.
        RankEngine.init();
        MinecraftForge.EVENT_BUS.addListener(RankEngine::onLoggedIn);
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> RankEngine.onServerStarted(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> RankEngine.onServerStopping(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener(RankCommand::onRegister);
        MinecraftForge.EVENT_BUS.addListener(AdminCommands::onRegister);
        BspCore.init();
        LOGGER.info("BSP RankUp loaded");
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Playtime.forget(event.getEntity().getUUID());
        KillCooldowns.forget(event.getEntity().getUUID());
    }
}
