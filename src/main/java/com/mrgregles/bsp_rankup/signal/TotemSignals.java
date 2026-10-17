package com.mrgregles.bsp_rankup.signal;

import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Totem signals, fed by {@code compat/BspCore} from BSP-Core's totem events with plain data. A successful steal is
 * a totem stolen for the thief and a totem lost for the owner; a failed steal is a totem protected for the owner
 * when the owner was online on this server (owner decision D4).
 */
public final class TotemSignals {
    private TotemSignals() {
    }

    public static void stealEnded(MinecraftServer server, @Nullable UUID owner, UUID thief, boolean success) {
        ProgressLedger ledger = ProgressLedger.get(server);
        if (success) {
            ledger.add(thief, Signal.TOTEMS_STOLEN, 1);
            if (owner != null && !owner.equals(thief)) {
                ledger.add(owner, Signal.TOTEMS_LOST, 1);
            }
        } else if (owner != null && !owner.equals(thief) && Players.isOnline(server, owner)) {
            ledger.add(owner, Signal.TOTEMS_PROTECTED, 1);
        }
    }

    public static void placed(MinecraftServer server, UUID owner) {
        ProgressLedger.get(server).add(owner, Signal.TOTEMS_PLACED, 1);
    }

}
