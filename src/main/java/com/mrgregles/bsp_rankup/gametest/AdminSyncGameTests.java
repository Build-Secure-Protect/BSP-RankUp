package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.admin.Reply;
import com.mrgregles.bsp_rankup.admin.ResetAll;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
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

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * The server side of the admin screen: every action the screen can send, applied through {@link AdminSync} by a
 * BSP Core admin, and the view built for them; the packets' encoding. The screen itself cannot be drawn headlessly.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class AdminSyncGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "admin-sync")
    public void every_screen_action_is_applied_for_an_admin_and_the_view_reports_it(GameTestHelper h) throws IOException {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer other = TestPlayers.mockServerPlayer(h);
        UUID someone = UUID.randomUUID();
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        ProgressLedger ledger = ProgressLedger.get(server);
        try {
            level.addNewPlayer(admin);
            h.assertTrue(AdminSync.mayEdit(admin) && !AdminSync.mayEdit(other), "only the named admin may edit");

            // ladder
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.LADDER_ADD, "ru_s_a", "0", "Alpha")).ok(), "rung added");
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.LADDER_ADD, "ru_s_b", "50", "")).ok(), "rung without a name");
            h.assertFalse(apply(admin, new AdminActionPacket(AdminActionPacket.LADDER_ADD, "ru_s_a", "0", "")).ok(), "the same group twice is refused");
            h.assertFalse(apply(admin, new AdminActionPacket(AdminActionPacket.LADDER_ADD, "bad name!", "0", "")).ok(), "a bad group name is refused");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.LADDER_POINTS, "ru_s_b", "75")).ok() && settings(server).rank("ru_s_b").points() == 75, "points set");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.LADDER_NAME, "ru_s_b", "Beta")).ok() && settings(server).rank("ru_s_b").displayName().equals("Beta"), "name set");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.LADDER_SEASONAL, "ru_s_b", "true")).ok() && settings(server).rank("ru_s_b").seasonal(), "seasonal set");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.LADDER_MOVE, "ru_s_b", "1")).ok() && settings(server).indexOf("ru_s_b") == 0, "moved first");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.LADDER_MOVE, "ru_s_b", "2")).ok() && settings(server).indexOf("ru_s_b") == 1, "moved back");
            Reply notNumber = apply(admin, AdminActionPacket.of(AdminActionPacket.LADDER_POINTS, "ru_s_b", "lots"));
            h.assertFalse(notNumber.ok(), "a word for a number is refused: " + notNumber.text());

            // rewards
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_s_b", "coins", "5")).ok(), "coins");
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_s_b", "pieces", "10")).ok(), "pieces");
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_s_b", "item", "minecraft:stick 3")).ok(), "an item with a count");
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_s_b", "title", "[B]")).ok(), "a title");
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_s_b", "command", "/say hi")).ok(), "a command");
            h.assertFalse(apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_s_b", "item", "minecraft:no_such_thing")).ok(), "an unknown item is refused");
            h.assertFalse(apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_s_b", "coins", "0")).ok(), "zero coins are refused");
            List<Reward> rewards = settings(server).rank("ru_s_b").rewards();
            h.assertTrue(rewards.size() == 5 && rewards.get(2).equals(Reward.item("minecraft:stick", 3)) && rewards.get(4).equals(Reward.command("say hi")), "five rewards, the slash stripped: " + rewards);
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.REWARD_REMOVE, "ru_s_b", "5")).ok() && settings(server).rank("ru_s_b").rewards().size() == 4, "the fifth removed");

            // signals and options
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.SIGNAL_ON, "mob_kills", "false")).ok() && !settings(server).signal(Signal.MOB_KILLS).on(), "a signal off");
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.SIGNAL_POINTS, "playtime", "3", "5")).ok() && settings(server).signal(Signal.PLAYTIME).points() == 3 && settings(server).signal(Signal.PLAYTIME).per() == 5, "points per set");
            h.assertTrue(apply(admin, new AdminActionPacket(AdminActionPacket.SIGNAL_POINTS, "playtime", "4", "")).ok() && settings(server).signal(Signal.PLAYTIME).per() == 5, "points alone keep 'per'");
            h.assertFalse(apply(admin, AdminActionPacket.of(AdminActionPacket.SIGNAL_ON, "nope", "true")).ok(), "an unknown signal is refused");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "announce", "network")).ok() && settings(server).announce() == Settings.Announce.NETWORK, "announce");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "track", "ru_sync_track")).ok() && settings(server).track().equals("ru_sync_track"), "track");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "cooldown", "2")).ok() && settings(server).killCooldownMinutes() == 2, "cooldown");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "titlepriority", "7")).ok() && settings(server).titlePriority() == 7, "title priority");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "rewardsagain", "false")).ok() && !settings(server).rewardsAgainEachSeason(), "rewards once");
            h.assertFalse(apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "colour", "blue")).ok(), "an unknown option is refused");
            h.assertFalse(apply(admin, AdminActionPacket.of("dance", "")).ok(), "an unknown action is refused");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.REFRESH, "")).ok(), "refresh answers");

            // players
            ledger.add(someone, Signal.PLAYER_KILLS, 2);
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_SEARCH, "")).ok(), "search set");
            AdminViewPacket view = AdminSync.view(admin, "hello", true, false);
            h.assertTrue(view.players().stream().anyMatch(p -> p.uuid().equals(admin.getUUID()) && p.online()), "the admin online is listed: " + view.players());
            h.assertTrue(view.players().stream().anyMatch(p -> p.uuid().equals(someone) && !p.online() && p.points() == 50), "a player with progress is listed with points: " + view.players());
            h.assertTrue(view.holders().containsKey("ru_s_a") && view.holders().containsKey("ru_s_b"), "holders per rung: " + view.holders());
            h.assertTrue(view.message().equals("hello") && view.ok() && !view.open() && !view.info().isEmpty(), "message, ok, open and info carried");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_SEARCH, "zzz_nobody_zzz")).ok() && AdminSync.view(admin, "", true, false).players().isEmpty(), "a search nobody matches lists nobody");
            apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_SEARCH, ""));
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_SELECT, someone.toString())).ok(), "selected");
            AdminViewPacket.PlayerDetail d = AdminSync.view(admin, "", true, false).selected();
            h.assertTrue(!d.isEmpty() && d.uuid().equals(someone) && d.counts().get("player_kills") == 2 && d.points() == 50, "the selected player's standing: " + d);
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_ADD, someone.toString(), "7")).ok() && ledger.progress(someone).bonus() == 7, "points added");
            h.assertFalse(apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_ADD, someone.toString(), "0")).ok(), "zero points refused");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_RESET, someone.toString())).ok(), "reset");
            h.assertFalse(apply(admin, AdminActionPacket.of(AdminActionPacket.PLAYER_ADD, "not-a-uuid", "7")).ok(), "a bad uuid is refused");

            // reset everyone
            ledger.add(someone, Signal.PLAYTIME, 9);
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.RESET_ALL_REQUEST, "")).ok(), "a code is issued");
            String code = AdminSync.view(admin, "", true, false).resetCode();
            h.assertTrue(code.length() == 4 && AdminSync.view(admin, "", true, false).resetSeconds() > 50, "the view shows the code and its minute: " + code);
            h.assertFalse(apply(admin, AdminActionPacket.of(AdminActionPacket.RESET_ALL_CONFIRM, code.equals("1234") ? "4321" : "1234")).ok(), "a wrong code is refused");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.RESET_ALL_CONFIRM, code)).ok(), "the right code resets everyone");
            h.assertFalse(ledger.has(someone), "nothing left of the player");
            h.assertTrue(AdminSync.view(admin, "", true, false).resetCode().isEmpty(), "no code waits any more");
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.RESET_ALL_REQUEST, "")).ok() && apply(admin, AdminActionPacket.of(AdminActionPacket.RESET_ALL_CANCEL, "")).ok() && ResetAll.pending(admin.getUUID()).isEmpty(), "a request can be cancelled");

            // files
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.EXPORT, "synctest")).ok() && Files.exists(SettingsFiles.file("synctest")), "exported");
            apply(admin, AdminActionPacket.of(AdminActionPacket.LADDER_REMOVE, "ru_s_a"));
            h.assertTrue(apply(admin, AdminActionPacket.of(AdminActionPacket.IMPORT, "synctest")).ok() && settings(server).ranks().size() == 2, "imported");
            h.assertFalse(apply(admin, AdminActionPacket.of(AdminActionPacket.IMPORT, "no_such_file_xyz")).ok(), "a missing file is refused");

            // the packets survive the wire
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            AdminViewPacket.encode(view, buf);
            AdminViewPacket back = AdminViewPacket.decode(buf);
            h.assertTrue(back.message().equals("hello") && back.players().size() == view.players().size() && back.holders().equals(view.holders()) && Settings.load(back.settings()).ranks().size() == 2, "the view packet round-trips");
            FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
            AdminActionPacket.encode(new AdminActionPacket("x", "a", "b", "c"), buf2);
            h.assertTrue(AdminActionPacket.decode(buf2).equals(new AdminActionPacket("x", "a", "b", "c")), "the action packet round-trips");
        } finally {
            BspCore.setAdminNames(List.copyOf(previousAdmins));
            level.removePlayerImmediately(admin, Entity.RemovalReason.DISCARDED);
            other.discard();
            AdminSync.forget(admin.getUUID());
            ledger.reset(someone);
            ledger.reset(admin.getUUID());
            SettingsLedger.get(server).set(Settings.defaults());
            Files.deleteIfExists(SettingsFiles.file("synctest"));
        }
        h.succeed();
    }

    private static Reply apply(ServerPlayer admin, AdminActionPacket packet) {
        Reply r = AdminSync.apply(admin, packet);
        return r == null ? Reply.ok("(later)") : r;
    }

    private static Settings settings(MinecraftServer server) {
        return SettingsLedger.settings(server);
    }
}
