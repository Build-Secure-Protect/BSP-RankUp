package com.mrgregles.bsp_rankup;

import com.mojang.logging.LogUtils;
import com.mrgregles.bsp_rankup.command.AdminCommands;
import com.mrgregles.bsp_rankup.command.RankCommand;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.RegionGuard;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.network.RankSync;
import com.mrgregles.bsp_rankup.network.RankUpNetwork;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay;
import com.mrgregles.bsp_rankup.prefix.PrefixImages;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.signal.KillCooldowns;
import com.mrgregles.bsp_rankup.signal.KillSignals;
import com.mrgregles.bsp_rankup.signal.Playtime;
import com.mrgregles.bsp_rankup.signal.ScoreSignal;
import com.mrgregles.bsp_rankup.storage.Storage;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.config.ModConfig;
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
        context.registerConfig(ModConfig.Type.SERVER, RankUpConfig.SPEC);
        context.registerConfig(ModConfig.Type.CLIENT, RankUpClientConfig.SPEC);
        RankUpNetwork.register();
        // Signals: what players do, counted into the progress ledger.
        MinecraftForge.EVENT_BUS.addListener(Playtime::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(KillSignals::onDeath);
        ScoreSignal.init(); // BSP-Core's score, each season's peak (D48)
        MinecraftForge.EVENT_BUS.addListener(ScoreSignal::onLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(BSPRankUp::onLoggedOut);
        // The ladder: points, ranks, rewards, announcements.
        RankEngine.init();
        com.mrgregles.bsp_rankup.rank.Holders.init();
        MinecraftForge.EVENT_BUS.addListener(RankEngine::onLoggedIn);
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            RankEngine.onServerStarted(e.getServer());
            Storage.start(e.getServer()); // shared MySQL and Redis, when configured
            PrefixImages.onServerStarted(e.getServer());
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            Storage.stop();
            RankEngine.onServerStopping(e.getServer());
            PrefixImages.onServerStopping(e.getServer());
        });
        // The shared row's read starts first; the ladder's login check waits for it (RankEngine.awaitRow).
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, Storage::onLoggedIn);
        // Prefixes before names in chat and the tab list, drawn as images when a rank has one (D55 to D74). Low
        // priority: another mod's name change comes first and the prefix goes in front of it.
        PrefixImages.init();
        MinecraftForge.EVENT_BUS.addListener(PrefixImages::onLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, PrefixDisplay::onNameFormat);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, PrefixDisplay::onTabListNameFormat);
        MinecraftForge.EVENT_BUS.addListener(PrefixDisplay::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(RankCommand::onRegister);
        MinecraftForge.EVENT_BUS.addListener(AdminCommands::onRegister);
        BspCore.init();
        RegionGuard.init(); // optional: the "claims bought" signal when Region Guard is installed
        LOGGER.info("BSP RankUp loaded");
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Playtime.forget(event.getEntity().getUUID());
        KillCooldowns.forget(event.getEntity().getUUID());
        AdminSync.forget(event.getEntity().getUUID());
        RankSync.forget(event.getEntity().getUUID());
        RankEngine.forget(event.getEntity().getUUID());
        PrefixImages.forget(event.getEntity().getUUID());
        PrefixDisplay.forget(event.getEntity().getUUID());
    }
}
