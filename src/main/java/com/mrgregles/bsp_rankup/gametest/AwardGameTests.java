package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.admin.AdminEdits;
import com.mrgregles.bsp_rankup.admin.Reply;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.rank.Rewards;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.storage.StorageBackend;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Awards by an admin's hand (owner decisions D109 to D115) and the login lines about what a player reached while away
 * (D119 to D122): GIVE without rewards, TAKE marks the award taken so the check never gives it back, the mark survives
 * the save and the shared row, and the lines are one per thing reached, at most five, then how many more.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class AwardGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String GROUP = "ru_aw_hand";

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "award-hand")
    public void an_admin_gives_and_takes_an_award(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h); // away at first
        UUID id = player.getUUID();
        if (LuckPermsBridge.isPresent()) {
            net.luckperms.api.LuckPermsProvider.get().getGroupManager().createAndLoadGroup(GROUP).join();
        }
        Award award = new Award(GROUP, "Hand Award", Set.of(Signal.MOB_KILLS), 5, false, List.of(Reward.pieces(7)));
        SettingsLedger.get(server).set(Settings.defaults().toBuilder().award(award).signal(Signal.MOB_KILLS, new SignalSetting(true, 1, 1)).build());
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(player.getGameProfile().getName()));
        ProgressLedger ledger = ProgressLedger.get(server);
        long balanceBefore = BspCore.balance(server, id);
        List<Reply> replies = new ArrayList<>();
        h.startSequence()
                .thenExecute(() -> AdminEdits.awardGive(server, id, GROUP, replies::add))
                .thenWaitUntil(() -> h.assertTrue(ledger.progress(id).hasAward(GROUP), "given while away: " + ledger.progress(id) + " " + replies))
                .thenExecute(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(replies.get(0).ok() && p.rewarded().contains(GROUP), "without rewards, then or later (D110): " + p.rewarded());
                    h.assertTrue(BspCore.balance(server, id) == balanceBefore, "no pieces: " + BspCore.balance(server, id));
                    h.assertTrue(p.pending().isEmpty(), "and no login line: an admin's gift is silent (D121): " + p.pending());
                    if (LuckPermsBridge.isPresent()) {
                        h.assertTrue(LuckPermsBridge.groupsOf(id).contains(GROUP), "the LuckPerms group added");
                    }
                    replies.clear();
                    AdminEdits.awardGive(server, id, GROUP, replies::add);
                    h.assertTrue(replies.size() == 1 && !replies.get(0).ok(), "given twice is refused: " + replies);
                    replies.clear();
                    level.addNewPlayer(player);
                    // TAKE from the screen
                    AdminSync.apply(player, AdminActionPacket.of(AdminActionPacket.AWARD_TAKE, id.toString(), GROUP));
                })
                .thenWaitUntil(() -> h.assertTrue(ledger.progress(id).isAwardTaken(GROUP), "taken: " + ledger.progress(id)))
                .thenExecute(() -> {
                    Progress p = ledger.progress(id);
                    h.assertFalse(p.hasAward(GROUP), "no longer held");
                    h.assertTrue(p.awards().isEmpty() && p.awardMarks().get(GROUP) == Progress.TAKEN, "awards() lists held ones only; the mark is kept apart: " + p.awardMarks());
                    if (LuckPermsBridge.isPresent()) {
                        h.assertFalse(LuckPermsBridge.groupsOf(id).contains(GROUP), "the LuckPerms group removed");
                    }
                    h.assertTrue(Progress.load(p.save()).isAwardTaken(GROUP), "the mark survives the save");
                    h.assertTrue(StorageBackend.LadderValue.of(p).awardMap().get(GROUP) == Progress.TAKEN, "and the shared row (D115): " + StorageBackend.LadderValue.of(p));
                    h.assertTrue(AdminSync.detail(server, id).awards().get(0).taken(), "PLAYERS shows it TAKEN");
                    ledger.add(id, Signal.MOB_KILLS, 10); // well past the award's 5 points
                    RankEngine.check(server, id);
                })
                .thenExecuteAfter(40, () -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(p.isAwardTaken(GROUP) && !p.hasAward(GROUP), "the check never gives a taken award back (D111): " + p);
                    AdminEdits.awardTake(server, id, GROUP, replies::add);
                    h.assertTrue(replies.size() == 1 && !replies.get(0).ok(), "taken twice is refused: " + replies);
                    replies.clear();
                    AdminEdits.awardGive(server, id, GROUP, replies::add);
                })
                .thenWaitUntil(() -> h.assertTrue(ledger.progress(id).hasAward(GROUP), "given back"))
                .thenExecute(() -> {
                    h.assertFalse(ledger.progress(id).isAwardTaken(GROUP), "the taken mark is gone");
                    h.assertTrue(BspCore.balance(server, id) == balanceBefore, "still no pieces, the points notwithstanding: " + BspCore.balance(server, id));
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    BspCore.setAdminNames(List.copyOf(previousAdmins));
                    SettingsLedger.get(server).set(Settings.defaults());
                    ledger.reset(id);
                    if (LuckPermsBridge.isPresent()) {
                        var lp = net.luckperms.api.LuckPermsProvider.get();
                        var user = lp.getUserManager().getUser(id);
                        if (user != null) {
                            lp.getUserManager().cleanupUser(user);
                        }
                        var g = lp.getGroupManager().getGroup(GROUP);
                        if (g != null) {
                            lp.getGroupManager().deleteGroup(g).join();
                        }
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public void the_login_lines_say_what_was_reached_while_away(GameTestHelper h) {
        h.assertTrue(key(Rewards.describe(Reward.reachedNote("Captain", "Pvp"))).equals("message.bsp_rankup.away_reached_on"), "a rung on a named ladder");
        h.assertTrue(key(Rewards.describe(Reward.reachedNote("Captain", ""))).equals("message.bsp_rankup.away_reached"), "a rung with one ladder");
        h.assertTrue(key(Rewards.describe(Reward.earnedNote("Headhunter"))).equals("message.bsp_rankup.away_earned"), "an award");
        List<Reward> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(Reward.reachedNote("Rung " + i, ""));
        }
        List<Component> lines = Rewards.loginLines(seven);
        h.assertTrue(lines.size() == 6 && key(lines.get(5)).equals("message.bsp_rankup.away_more")
                        && ((TranslatableContents) lines.get(5).getContents()).getArgs()[0].equals(2),
                "five lines, then how many more (D119): " + lines);
        h.assertTrue(Rewards.loginLines(seven.subList(0, 5)).size() == 5, "five fit without a count");
        h.assertTrue(Reward.reachedNote("A", "").needsPlayerOnline(), "a line waits for a login like coins do");
        h.assertTrue(Reward.load(Reward.earnedNote("Headhunter").save()).equals(Reward.earnedNote("Headhunter")), "and is kept like a reward");
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(admin.getGameProfile().getName()));
        try {
            Reply note = AdminSync.apply(admin, new AdminActionPacket(AdminActionPacket.REWARD_ADD, "ru_none", "note", "x"));
            h.assertTrue(note != null && !note.ok() && note.text().contains("note"), "an admin can't give a note as a reward: " + (note == null ? null : note.text()));
        } finally {
            BspCore.setAdminNames(List.copyOf(previousAdmins));
            admin.discard();
        }
        h.succeed();
    }

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }
}
