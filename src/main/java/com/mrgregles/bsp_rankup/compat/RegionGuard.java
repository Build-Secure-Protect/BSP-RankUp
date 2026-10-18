package com.mrgregles.bsp_rankup.compat;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The only class that touches BSP Region Guard, which is optional at runtime: the nested class holds every
 * Region Guard type, so nothing of it is loaded unless the mod is present. Gives RankUp Region Guard's server id
 * and the "claims bought" signal (Region Guard's request RG-R1).
 */
public final class RegionGuard {
    private static boolean listening;

    private RegionGuard() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("bsp_region_guard");
    }

    /** Listens for claim purchases when Region Guard is installed; an older Region Guard without the event is skipped. */
    public static void init() {
        if (!isLoaded() || listening) {
            return;
        }
        try {
            Api.listen();
            listening = true;
        } catch (LinkageError | RuntimeException e) {
            BSPRankUp.LOGGER.debug("Region Guard's claim event not reachable (an older Region Guard?): {}", e.toString());
        }
    }

    public static boolean isListening() {
        return listening;
    }

    /** Region Guard's {@code storage.serverId} (its API 5), or null when Region Guard is absent or older. */
    @Nullable
    public static String serverId() {
        if (!isLoaded()) {
            return null;
        }
        try {
            return Api.serverId();
        } catch (LinkageError | RuntimeException e) {
            BSPRankUp.LOGGER.debug("Region Guard's server id not readable: {}", e.toString());
            return null;
        }
    }

    /**
     * A claim was bought ({@code enlarged} false) or an existing one grew ({@code enlarged} true): only a new claim
     * counts as a claim bought. Plain data, so the GameTests can call it without Region Guard; the signal's on/off
     * switch governs its points, as for every other signal.
     */
    public static void claimBought(MinecraftServer server, UUID buyer, boolean enlarged) {
        if (!enlarged) {
            ProgressLedger.get(server).add(buyer, Signal.CLAIMS_BOUGHT, 1);
        }
    }

    private static final class Api {
        static String serverId() {
            return com.mrgregles.bsp_region_guard.api.RegionGuardApi.get().serverId();
        }

        static void listen() {
            MinecraftForge.EVENT_BUS.addListener(Api::onClaimPurchased);
        }

        private static void onClaimPurchased(com.mrgregles.bsp_region_guard.api.event.ClaimPurchasedEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer buyer) {
                claimBought(buyer.server, buyer.getUUID(), event.enlarged());
            }
        }
    }
}
