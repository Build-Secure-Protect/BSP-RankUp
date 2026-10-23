package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_core.api.event.TotemEvent;
import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.signal.KillCooldowns;
import com.mrgregles.bsp_rankup.signal.Playtime;
import com.mrgregles.bsp_rankup.signal.Teams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The signals: playtime, kills with the cooldown and the team hook, deaths, totem events; and the ledger itself. */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class SignalGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void playtime_counts_a_minute_for_each_player_online(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer online = TestPlayers.mockServerPlayer(h);
        ServerPlayer absent = TestPlayers.mockServerPlayer(h);
        ProgressLedger ledger = ProgressLedger.get(level.getServer());
        try {
            level.addNewPlayer(online); // the mock is not in the world's player list until it is added
            for (int i = 0; i < Playtime.TICKS_PER_MINUTE - 1; i++) {
                Playtime.tick(level.getServer());
            }
            h.assertTrue(ledger.progress(online.getUUID()).count(Signal.PLAYTIME) == 0, "59.95 seconds is not a minute yet");
            Playtime.tick(level.getServer());
            h.assertTrue(ledger.progress(online.getUUID()).count(Signal.PLAYTIME) == 1, "one minute online counts 1: " + ledger.progress(online.getUUID()));
            h.assertTrue(Playtime.pendingTicks(online.getUUID()) == 0, "the minute was taken off the pending ticks");
            h.assertTrue(ledger.progress(absent.getUUID()).count(Signal.PLAYTIME) == 0, "a player not in any world gets nothing");
            for (int i = 0; i < Playtime.TICKS_PER_MINUTE * 2; i++) {
                Playtime.tick(level.getServer());
            }
            h.assertTrue(ledger.progress(online.getUUID()).count(Signal.PLAYTIME) == 3, "three minutes after three: " + ledger.progress(online.getUUID()));
        } finally {
            level.removePlayerImmediately(online, Entity.RemovalReason.DISCARDED);
            Playtime.forget(online.getUUID());
            ledger.reset(online.getUUID());
            ledger.reset(absent.getUUID());
            absent.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void player_kills_count_once_per_victim_within_the_cooldown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer killer = TestPlayers.mockServerPlayer(h);
        ServerPlayer first = TestPlayers.mockServerPlayer(h);
        ServerPlayer second = TestPlayers.mockServerPlayer(h);
        ProgressLedger ledger = ProgressLedger.get(level.getServer());
        try {
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(first, level.damageSources().playerAttack(killer)));
            h.assertTrue(kills(ledger, killer) == 1, "the first kill counts: " + ledger.progress(killer.getUUID()));
            h.assertTrue(ledger.progress(first.getUUID()).count(Signal.DEATHS) == 1, "the victim's death counts");
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(first, level.damageSources().playerAttack(killer)));
            h.assertTrue(kills(ledger, killer) == 1, "the same victim again within the cooldown does not count: " + ledger.progress(killer.getUUID()));
            h.assertTrue(ledger.progress(first.getUUID()).count(Signal.DEATHS) == 2, "the death still counts for the victim");
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(second, level.damageSources().playerAttack(killer)));
            h.assertTrue(kills(ledger, killer) == 2, "another victim counts: " + ledger.progress(killer.getUUID()));
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(killer, level.damageSources().playerAttack(killer)));
            h.assertTrue(kills(ledger, killer) == 2 && ledger.progress(killer.getUUID()).count(Signal.DEATHS) == 1, "killing yourself is a death, not a kill: " + ledger.progress(killer.getUUID()));
            h.assertTrue(KillCooldowns.cooldownMs() == KillCooldowns.DEFAULT_MS, "the default cooldown is ten minutes");
        } finally {
            for (ServerPlayer p : List.of(killer, first, second)) {
                KillCooldowns.forget(p.getUUID());
                ledger.reset(p.getUUID());
                p.discard();
            }
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "teams") // the team hook is global: never beside the other kill tests
    public void kills_of_teammates_do_not_count(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer killer = TestPlayers.mockServerPlayer(h);
        ServerPlayer mate = TestPlayers.mockServerPlayer(h);
        ProgressLedger ledger = ProgressLedger.get(level.getServer());
        var registered = Teams.provider(); // BSP-Core 1.4.5 and newer registers its teams at server start
        try {
            h.assertTrue(Teams.isRegistered(), "BSP-Core registered its teams at server start");
            Teams.setProvider((a, b) -> true);
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(mate, level.damageSources().playerAttack(killer)));
            h.assertTrue(kills(ledger, killer) == 0, "a teammate's death is no kill: " + ledger.progress(killer.getUUID()));
            h.assertTrue(ledger.progress(mate.getUUID()).count(Signal.DEATHS) == 1, "but still a death");
            Teams.setProvider((a, b) -> false);
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(mate, level.damageSources().playerAttack(killer)));
            h.assertTrue(kills(ledger, killer) == 1, "not teammates: the kill counts: " + ledger.progress(killer.getUUID()));
        } finally {
            Teams.setProvider(registered);
            for (ServerPlayer p : List.of(killer, mate)) {
                KillCooldowns.forget(p.getUUID());
                ledger.reset(p.getUUID());
                p.discard();
            }
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void mob_kills_count_for_the_killer_and_other_deaths_for_nobody(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer killer = TestPlayers.mockServerPlayer(h);
        ServerPlayer fallen = TestPlayers.mockServerPlayer(h);
        ProgressLedger ledger = ProgressLedger.get(level.getServer());
        Zombie zombie = new Zombie(EntityType.ZOMBIE, level);
        try {
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(zombie, level.damageSources().playerAttack(killer)));
            h.assertTrue(ledger.progress(killer.getUUID()).count(Signal.MOB_KILLS) == 1, "a zombie killed counts as a mob kill: " + ledger.progress(killer.getUUID()));
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(zombie, level.damageSources().generic()));
            h.assertTrue(ledger.progress(killer.getUUID()).count(Signal.MOB_KILLS) == 1, "a zombie dying by itself counts for nobody");
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(fallen, level.damageSources().generic()));
            h.assertTrue(ledger.progress(fallen.getUUID()).count(Signal.DEATHS) == 1 && kills(ledger, killer) == 0, "a fall is a death and nobody's kill: " + ledger.progress(fallen.getUUID()));
            Progress p = ledger.progress(killer.getUUID());
            h.assertTrue(p.counts().get(Signal.MOB_KILLS) == 1 && !p.counts().containsKey(Signal.PLAYER_KILLS), "counts() lists only what was counted: " + p.counts());
        } finally {
            zombie.discard();
            for (ServerPlayer p : List.of(killer, fallen)) {
                ledger.reset(p.getUUID());
                p.discard();
            }
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void totem_steals_count_stolen_lost_and_protected_when_the_owner_is_online(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        ServerPlayer thief = TestPlayers.mockServerPlayer(h);
        UUID away = UUID.randomUUID();
        ProgressLedger ledger = ProgressLedger.get(level.getServer());
        GlobalPos where = GlobalPos.of(level.dimension(), h.absolutePos(new BlockPos(3, 1, 3)));
        try {
            level.addNewPlayer(owner); // online on this server
            MinecraftForge.EVENT_BUS.post(new TotemEvent.StealEnded(level.getServer(), where, owner.getUUID(), thief.getUUID(), thief.getGameProfile().getName(), true, ""));
            h.assertTrue(ledger.progress(thief.getUUID()).count(Signal.TOTEMS_STOLEN) == 1, "a successful steal is a totem stolen for the thief: " + ledger.progress(thief.getUUID()));
            h.assertTrue(ledger.progress(owner.getUUID()).count(Signal.TOTEMS_LOST) == 1, "and a totem lost for the owner: " + ledger.progress(owner.getUUID()));
            MinecraftForge.EVENT_BUS.post(new TotemEvent.StealEnded(level.getServer(), where, owner.getUUID(), thief.getUUID(), thief.getGameProfile().getName(), false, "message.bsp_core.steal.failed_dead"));
            h.assertTrue(ledger.progress(owner.getUUID()).count(Signal.TOTEMS_PROTECTED) == 1, "a failed steal while the owner is online is a totem protected: " + ledger.progress(owner.getUUID()));
            h.assertTrue(ledger.progress(thief.getUUID()).count(Signal.TOTEMS_STOLEN) == 1, "a failed steal gives the thief nothing");
            MinecraftForge.EVENT_BUS.post(new TotemEvent.StealEnded(level.getServer(), where, away, thief.getUUID(), thief.getGameProfile().getName(), false, "message.bsp_core.steal.failed_dead"));
            h.assertTrue(ledger.progress(away).count(Signal.TOTEMS_PROTECTED) == 0, "an owner who is not online here protected nothing: " + ledger.progress(away));
            MinecraftForge.EVENT_BUS.post(new TotemEvent.StealEnded(level.getServer(), where, null, thief.getUUID(), thief.getGameProfile().getName(), true, ""));
            h.assertTrue(ledger.progress(thief.getUUID()).count(Signal.TOTEMS_STOLEN) == 2, "an unclaimed totem taken still counts as stolen");
            MinecraftForge.EVENT_BUS.post(new TotemEvent.Placed(level.getServer(), where, owner.getUUID()));
            h.assertTrue(ledger.progress(owner.getUUID()).count(Signal.TOTEMS_PLACED) == 1, "a placed totem counts: " + ledger.progress(owner.getUUID()));
        } finally {
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
            ledger.reset(owner.getUUID());
            ledger.reset(thief.getUUID());
            ledger.reset(away);
            thief.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void ledger_saves_loads_tells_listeners_and_resets(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID player = UUID.randomUUID();
        ProgressLedger ledger = ProgressLedger.get(level.getServer());
        List<ProgressLedger.Change> seen = new ArrayList<>();
        java.util.function.Consumer<ProgressLedger.Change> listener = c -> {
            if (c.player().equals(player)) {
                seen.add(c);
            }
        };
        ProgressLedger.onChange(listener);
        try {
            ledger.add(player, Signal.PLAYTIME, 5);
            ledger.add(player, Signal.TOTEMS_STOLEN, 2);
            ledger.add(player, Signal.TOTEMS_STOLEN, -1);
            ledger.addBonus(player, 40);
            ledger.setRank(player, "warden", 1234L);
            ledger.add(player, Signal.PLAYTIME, 0);
            h.assertTrue(seen.size() == 5, "every change that moved something told the listener once: " + seen.size());
            h.assertTrue(seen.get(0).kind() == ProgressLedger.Kind.COUNT && seen.get(0).signal() == Signal.PLAYTIME && seen.get(0).amount() == 5, "the first change: " + seen.get(0));
            h.assertTrue(seen.get(3).kind() == ProgressLedger.Kind.BONUS && seen.get(3).signal() == null && seen.get(3).amount() == 40, "a bonus is a change with no signal: " + seen.get(3));
            h.assertTrue(seen.get(4).kind() == ProgressLedger.Kind.RANK, "a rank is a change of its own: " + seen.get(4));

            CompoundTag saved = ledger.save(new CompoundTag());
            ProgressLedger loaded = ProgressLedger.load(saved);
            Progress p = loaded.progress(player);
            h.assertTrue(p.count(Signal.PLAYTIME) == 5 && p.count(Signal.TOTEMS_STOLEN) == 1, "counts survive save and load: " + p);
            h.assertTrue(p.bonus() == 40 && "warden".equals(p.rank()) && p.rankedAtMs() == 1234L, "bonus and rank survive: " + p);
            h.assertFalse(loaded.has(UUID.randomUUID()), "an unknown player has no entry");

            ledger.set(player, Signal.PLAYTIME, 0);
            h.assertTrue(ledger.progress(player).count(Signal.PLAYTIME) == 0 && !ledger.progress(player).counts().containsKey(Signal.PLAYTIME), "set to zero removes the count");
            ledger.reset(player);
            h.assertFalse(ledger.has(player), "reset forgets the player");
            h.assertTrue(ledger.progress(player).isEmpty(), "and a fresh entry is empty");
        } finally {
            ProgressLedger.removeListener(listener);
            ledger.reset(player);
        }
        h.succeed();
    }

    private static long kills(ProgressLedger ledger, ServerPlayer p) {
        return ledger.progress(p.getUUID()).count(Signal.PLAYER_KILLS);
    }
}
