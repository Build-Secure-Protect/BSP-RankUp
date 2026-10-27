package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.admin.Reply;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The admin screen's server side for several ladders and awards (owner decisions D49 to D51, RU-15): every ladder and
 * award action the screen sends is applied as the commands apply it, and the view carries LuckPerms' groups for the
 * GROUPS tab and counts the holders of every ladder's rungs and every award.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class AdminLaddersGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static Reply apply(ServerPlayer admin, String action, String a, String b, String c) {
        Reply r = AdminSync.apply(admin, new AdminActionPacket(action, a, b, c));
        return r == null ? Reply.ok("(later)") : r;
    }

    @GameTest(template = EMPTY, batch = "admin-ladders")
    public void the_screens_ladder_and_award_actions_are_applied(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        SettingsLedger.get(server).set(Settings.defaults());
        try {
            String first = SettingsLedger.settings(server).first().name();
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_ADD, "ru_al_raid", "ru_al_track", "").ok(), "+ LADDER adds a ladder");
            h.assertTrue(apply(admin, AdminActionPacket.RUNG_ADD, "ru_al_raid", "ru_al_r1", "50 Cut Purse").ok(), "+ ADD RANK on the chosen ladder");
            Ladder raid = SettingsLedger.settings(server).ladder("ru_al_raid");
            h.assertTrue(raid != null && raid.rank("ru_al_r1") != null && raid.rank("ru_al_r1").points() == 50 && raid.rank("ru_al_r1").displayName().equals("Cut Purse"),
                    "the rung, with its points and name, on that ladder: " + raid);
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_COUNTS, "ru_al_raid", "player_kills,totems_stolen", "").ok()
                    && SettingsLedger.settings(server).ladder("ru_al_raid").counts().equals(Set.of(Signal.PLAYER_KILLS, Signal.TOTEMS_STOLEN)), "COUNTS");
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_COUNTS, "ru_al_raid", "player_kills", "").ok()
                    && SettingsLedger.settings(server).ladder("ru_al_raid").counts().equals(Set.of(Signal.PLAYER_KILLS)), "a chip taken off");
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_COUNTS, "ru_al_raid", "all", "").ok() && SettingsLedger.settings(server).ladder("ru_al_raid").counts().isEmpty(), "back to every signal");
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_PRIORITY, "ru_al_raid", "900", "").ok() && SettingsLedger.settings(server).ladder("ru_al_raid").titlePriority() == 900, "titles at");
            h.assertTrue(!apply(admin, AdminActionPacket.LADDERS_PRIORITY, "ru_al_raid", "lots", "").ok(), "a priority that is not a number is refused");
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_TRACK, "ru_al_raid", "ru_al_track2", "").ok() && SettingsLedger.settings(server).ladder("ru_al_raid").track().equals("ru_al_track2"), "TRACK");
            h.assertTrue(!apply(admin, AdminActionPacket.LADDERS_RENAME, "ru_al_raid", "x", "").ok(), "RENAME is the first ladder's only");
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_RENAME, first, "ru_al_main", "").ok() && SettingsLedger.settings(server).first().name().equals("ru_al_main"), "the first renamed");
            h.assertTrue(!apply(admin, AdminActionPacket.LADDERS_REMOVE, "ru_al_main", "", "").ok(), "the first cannot be removed");

            h.assertTrue(apply(admin, AdminActionPacket.AWARD_ADD, "ru_al_apple", "5000", "").ok(), "MAKE AWARD");
            h.assertTrue(apply(admin, AdminActionPacket.AWARD_POINTS, "ru_al_apple", "4000", "").ok(), "NEEDS");
            h.assertTrue(apply(admin, AdminActionPacket.AWARD_NAME, "ru_al_apple", "Apple Picker", "").ok(), "DISPLAY NAME");
            h.assertTrue(apply(admin, AdminActionPacket.AWARD_COUNTS, "ru_al_apple", "coins_earned", "").ok(), "its COUNTS");
            h.assertTrue(apply(admin, AdminActionPacket.AWARD_SEASONAL, "ru_al_apple", "true", "").ok(), "SEASON reset");
            h.assertTrue(apply(admin, AdminActionPacket.REWARD_ADD, "ru_al_apple", "coins", "64").ok(), "+ COINS on the award");
            Award apple = SettingsLedger.settings(server).award("ru_al_apple");
            h.assertTrue(apple != null && apple.points() == 4000 && apple.displayName().equals("Apple Picker") && apple.counts().equals(Set.of(Signal.COINS_EARNED)) && apple.seasonal()
                    && apple.rewards().equals(List.of(Reward.coins(64))), "the award as edited: " + apple);
            h.assertTrue(apply(admin, AdminActionPacket.REWARD_REMOVE, "ru_al_apple", "1", "").ok() && SettingsLedger.settings(server).award("ru_al_apple").rewards().isEmpty(), "its reward's x");
            h.assertTrue(apply(admin, AdminActionPacket.AWARD_REMOVE, "ru_al_apple", "", "").ok() && SettingsLedger.settings(server).awards().isEmpty(), "STOP AWARDING");
            h.assertTrue(apply(admin, AdminActionPacket.LADDERS_REMOVE, "ru_al_raid", "", "").ok() && SettingsLedger.settings(server).ladders().size() == 1, "REMOVE on the second ladder");
        } finally {
            BspCore.setAdminNames(List.copyOf(previousAdmins));
            SettingsLedger.get(server).set(Settings.defaults());
            admin.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "admin-ladders-view")
    public void the_view_carries_luckperms_groups_and_every_ladders_and_awards_holders(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String lpGroup = "ru_al_lpgroup";
        Ladder raid = new Ladder("ru_al_v", "ru_al_vtrack", Set.of(Signal.PLAYER_KILLS), List.of(com.mrgregles.bsp_rankup.settings.Rank.of("ru_al_v1", "V1", 0)), 999);
        SettingsLedger.get(server).set(Settings.defaults().toBuilder().addLadder(raid).award(Award.of("ru_al_vaward", "VA", Set.of(), 10)).build());
        if (LuckPermsBridge.isPresent()) {
            net.luckperms.api.LuckPermsProvider.get().getGroupManager().createAndLoadGroup(lpGroup).join();
        }
        try {
            ledger.setOtherRank(a, "ru_al_v", "ru_al_v1", 1L);
            ledger.setOtherRank(b, "ru_al_v", "ru_al_v1", 1L);
            ledger.setAward(a, "ru_al_vaward", true, 2L);
            AdminViewPacket view = AdminSync.view(admin, "", true, false);
            h.assertTrue(view.holders().getOrDefault("ru_al_v1", 0) == 2 && view.holders().getOrDefault("ru_al_vaward", 0) == 1, "holders on a later ladder and an award: " + view.holders());
            if (LuckPermsBridge.isPresent()) {
                h.assertTrue(view.groups().contains(lpGroup), "LuckPerms' groups for the GROUPS tab: " + view.groups());
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                AdminViewPacket.encode(view, buf);
                AdminViewPacket back = AdminViewPacket.decode(buf);
                h.assertTrue(back.groups().equals(view.groups()) && back.holders().equals(view.holders()), "the groups and holders survive the packet");
                h.assertTrue(com.mrgregles.bsp_rankup.settings.Settings.load(back.settings()).ladders().size() == 2, "and the settings carry both ladders");
            } finally {
                buf.release();
            }
        } finally {
            ledger.reset(a);
            ledger.reset(b);
            SettingsLedger.get(server).set(Settings.defaults());
            admin.discard();
            if (LuckPermsBridge.isPresent()) {
                var g = net.luckperms.api.LuckPermsProvider.get().getGroupManager().getGroup(lpGroup);
                if (g != null) {
                    net.luckperms.api.LuckPermsProvider.get().getGroupManager().deleteGroup(g).join();
                }
            }
        }
        h.succeed();
    }
}
