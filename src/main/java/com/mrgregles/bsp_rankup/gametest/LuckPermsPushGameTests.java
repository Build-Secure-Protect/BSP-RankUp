package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.admin.AdminEdits;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Telling the network about LuckPerms edits (owner decisions D129 to D132, request VA-RU1 from BSP-Velocity
 * Additions): after each edit RankUp saves, the user is pushed through LuckPerms' messaging service so the proxy and
 * the other servers reload it. The dev LuckPerms has no messaging service, so a stand-in one records the pushes.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class LuckPermsPushGameTests {
    private static final String GROUP = "ru_push_test";

    /** A messaging service as LuckPerms' API has it: a name and a push per user. Public, so the bridge's reflection reaches it. */
    public static class Recording {
        public final List<Object> pushed = new CopyOnWriteArrayList<>();

        public String getName() {
            return "test";
        }

        public void pushUserUpdate(Object user) {
            pushed.add(user);
        }
    }

    /** One that fails every push, as a dropped Redis connection might. */
    public static class Failing extends Recording {
        @Override
        public void pushUserUpdate(Object user) {
            throw new IllegalStateException("redis is down");
        }
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void the_switch_is_on_by_default_and_kept(GameTestHelper h) {
        h.assertTrue(Settings.defaults().tellOtherServers(), "on by default (D129)");
        CompoundTag old = Settings.defaults().save();
        old.remove("TellOtherServers");
        h.assertTrue(Settings.load(old).tellOtherServers(), "settings saved before 0.6.0 read as on");
        Settings off = Settings.defaults().toBuilder().tellOtherServers(false).build();
        h.assertFalse(Settings.load(off.save()).tellOtherServers(), "off is saved and read back");
        h.assertTrue(AdminSync.messagingLine(Optional.of("Redis"), true).equals("LuckPerms messaging: redis (rank changes reach other servers at once)")
                && AdminSync.messagingLine(Optional.of("Redis"), false).equals("LuckPerms messaging: redis, but TELL OTHER SERVERS is off")
                && AdminSync.messagingLine(Optional.empty(), true).startsWith("LuckPerms messaging: none"), "the OPTIONS info line says whether a push reaches anyone");

        boolean was = SettingsLedger.settings(h.getLevel().getServer()).tellOtherServers();
        try {
            h.assertTrue(AdminEdits.optionTellOtherServers(h.getLevel().getServer(), false).ok(), "the OPTIONS action switches it off");
            h.assertFalse(SettingsLedger.settings(h.getLevel().getServer()).tellOtherServers() || LuckPermsBridge.tellsOtherServers(),
                    "the settings and the bridge both see it off");
            AdminEdits.optionTellOtherServers(h.getLevel().getServer(), true);
            h.assertTrue(LuckPermsBridge.tellsOtherServers(), "and on again");
        } finally {
            SettingsLedger.get(h.getLevel().getServer()).update(b -> b.tellOtherServers(was));
        }
        h.succeed();
    }

    @GameTest(template = SetupGameTests.EMPTY, timeoutTicks = 600)
    public void each_saved_edit_is_pushed_to_the_other_servers(GameTestHelper h) {
        if (!LuckPermsBridge.isPresent()) {
            BSPRankUp.LOGGER.warn("LuckPerms is not loaded in this run; the push test did nothing.");
            h.succeed();
            return;
        }
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        UUID offline = UUID.randomUUID();
        boolean was = SettingsLedger.settings(h.getLevel().getServer()).tellOtherServers();
        Recording recording = new Recording();
        try {
            lp.getGroupManager().createAndLoadGroup(GROUP).join();
            SettingsLedger.get(h.getLevel().getServer()).update(b -> b.tellOtherServers(true));

            LuckPermsBridge.useMessagingForTests(Optional::empty);
            h.assertTrue(LuckPermsBridge.messagingName().isEmpty(), "no messaging service: no name");
            h.assertTrue(LuckPermsBridge.addGroup(id, GROUP).join() == LuckPermsBridge.Added.ADDED && !LuckPermsBridge.pushFailing(),
                    "without a messaging service the edit works and nothing is sent or logged");
            h.assertTrue(LuckPermsBridge.removeGroup(id, GROUP).join(), "and back");

            LuckPermsBridge.useMessagingForTests(() -> Optional.of(recording));
            h.assertTrue(LuckPermsBridge.messagingName().equals(Optional.of("test")), "the service's name, for the info line: " + LuckPermsBridge.messagingName());
            h.assertTrue(LuckPermsBridge.addGroup(id, GROUP).join() == LuckPermsBridge.Added.ADDED, "the edit is made");
            h.assertTrue(recording.pushed.size() == 1 && ((net.luckperms.api.model.user.User) recording.pushed.get(0)).getUniqueId().equals(id),
                    "once saved, that user is pushed (D130): " + recording.pushed);
            h.assertTrue(LuckPermsBridge.addGroup(id, GROUP).join() == LuckPermsBridge.Added.ALREADY && recording.pushed.size() == 1,
                    "an edit that changes nothing is not pushed: " + recording.pushed.size());
            h.assertTrue(LuckPermsBridge.removeGroup(id, GROUP).join() && recording.pushed.size() == 2, "every edit is pushed: " + recording.pushed.size());

            h.assertTrue(LuckPermsBridge.addGroup(offline, GROUP).join() == LuckPermsBridge.Added.ADDED && recording.pushed.size() == 3
                    && ((net.luckperms.api.model.user.User) recording.pushed.get(2)).getUniqueId().equals(offline), "a player who is offline is pushed too (D131)");

            SettingsLedger.get(h.getLevel().getServer()).update(b -> b.tellOtherServers(false));
            h.assertTrue(LuckPermsBridge.addGroup(id, GROUP).join() == LuckPermsBridge.Added.ADDED && recording.pushed.size() == 3, "switched off: the edit, no push");
            SettingsLedger.get(h.getLevel().getServer()).update(b -> b.tellOtherServers(true));

            LuckPermsBridge.useMessagingForTests(() -> Optional.of(new Failing()));
            h.assertTrue(LuckPermsBridge.removeGroup(id, GROUP).join() && !LuckPermsBridge.groupsOf(id).contains(GROUP),
                    "a failed push never fails the edit (D132): " + LuckPermsBridge.groupsOf(id));
            h.assertTrue(LuckPermsBridge.pushFailing(), "the failure is noted, logged once");
            h.assertTrue(LuckPermsBridge.addGroup(id, GROUP).join() == LuckPermsBridge.Added.ADDED, "the next edit still works");

            Recording back = new Recording();
            LuckPermsBridge.useMessagingForTests(() -> Optional.of(back));
            h.assertTrue(LuckPermsBridge.removeGroup(id, GROUP).join() && back.pushed.size() == 1 && !LuckPermsBridge.pushFailing(),
                    "a push that works again clears it, so a later failure is logged again");
        } finally {
            LuckPermsBridge.useMessagingForTests(null);
            SettingsLedger.get(h.getLevel().getServer()).update(b -> b.tellOtherServers(was));
            for (UUID u : List.of(id, offline)) {
                net.luckperms.api.model.user.User user = lp.getUserManager().getUser(u);
                if (user != null) {
                    lp.getUserManager().cleanupUser(user);
                }
            }
            net.luckperms.api.model.group.Group group = lp.getGroupManager().getGroup(GROUP);
            if (group != null) {
                lp.getGroupManager().deleteGroup(group).join();
            }
            player.discard();
        }
        h.succeed();
    }
}
