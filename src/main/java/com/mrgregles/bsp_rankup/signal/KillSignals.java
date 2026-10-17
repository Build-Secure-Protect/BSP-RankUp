package com.mrgregles.bsp_rankup.signal;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

/**
 * Player kills, mob kills and deaths from {@link LivingDeathEvent}. A kill is credited to the player the damage
 * source names ({@code DamageSource.getEntity()}), so arrows and other projectiles count for their shooter and
 * a mob's kill counts for nobody.
 */
public final class KillSignals {
    private KillSignals() {
    }

    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        MinecraftServer server = victim.getServer();
        if (server == null) {
            return;
        }
        Entity source = event.getSource().getEntity();
        ServerPlayer killer = source instanceof ServerPlayer p ? p : null;
        ProgressLedger ledger = ProgressLedger.get(server);
        if (victim instanceof ServerPlayer dead) {
            ledger.add(dead.getUUID(), Signal.DEATHS, 1);
            if (killer != null && !killer.getUUID().equals(dead.getUUID())) {
                if (Teams.sameTeam(killer.getUUID(), dead.getUUID())) {
                    BSPRankUp.LOGGER.debug("kill of a teammate not counted: {} -> {}", killer.getGameProfile().getName(), dead.getGameProfile().getName());
                } else if (!KillCooldowns.record(killer.getUUID(), dead.getUUID(), System.currentTimeMillis())) {
                    BSPRankUp.LOGGER.debug("repeat kill within the cooldown not counted: {} -> {}", killer.getGameProfile().getName(), dead.getGameProfile().getName());
                } else {
                    ledger.add(killer.getUUID(), Signal.PLAYER_KILLS, 1);
                }
            }
        } else if (killer != null) {
            ledger.add(killer.getUUID(), Signal.MOB_KILLS, 1);
        }
    }
}
