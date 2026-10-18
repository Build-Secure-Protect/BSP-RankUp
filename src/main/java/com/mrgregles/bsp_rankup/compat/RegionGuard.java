package com.mrgregles.bsp_rankup.compat;

import com.mrgregles.bsp_rankup.BSPRankUp;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * The only class that touches BSP Region Guard, which is optional at runtime: the nested class holds every
 * Region Guard type, so nothing of it is loaded unless the mod is present.
 */
public final class RegionGuard {
    private RegionGuard() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("bsp_region_guard");
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

    private static final class Api {
        static String serverId() {
            return com.mrgregles.bsp_region_guard.api.RegionGuardApi.get().serverId();
        }
    }
}
