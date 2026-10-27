package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay;
import com.mrgregles.bsp_rankup.prefix.PrefixImages;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.storage.MemoryBackend;
import com.mrgregles.bsp_rankup.storage.PendingJson;
import com.mrgregles.bsp_rankup.storage.Storage;
import com.mrgregles.bsp_rankup.storage.StorageBackend;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fixes from the 2026-10-27 audit (AU-1): the login check waits for the shared row, a settings notice goes out only
 * once the row is written, notices committed out of id order are still read, the prefix commands take what
 * {@code prefix list} prints, a PNG with an impossible chunk length is "broken" instead of an error, the folder is not
 * read again for unchanged files, and the last TITLE reward is the one matched.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class AuditGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "audit-login")
    public void on_a_network_the_login_check_waits_for_the_shared_row(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ProgressLedger ledger = ProgressLedger.get(server);
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        try {
            level.addNewPlayer(player);
            // this server's copy is old: rewards it holds were already given on another server, which owes others
            ledger.addPending(id, List.of(Reward.item("minecraft:gold_ingot", 1)));
            s.drain();
            db.setPending(id, PendingJson.toJson(List.of(Reward.item("minecraft:diamond", 1))));
            RankEngine.awaitRow(id);
            RankEngine.check(server, id); // a change during the read: nothing is decided on the old copy
            h.assertTrue(player.getInventory().countItem(Items.GOLD_INGOT) == 0 && player.getInventory().countItem(Items.DIAMOND) == 0, "nothing given before the row is read");
            s.loadPlayer(id);
            s.drain();
            h.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 1, "the row's waiting reward is given after the read");
            h.assertTrue(player.getInventory().countItem(Items.GOLD_INGOT) == 0, "the old copy's reward, given elsewhere, is not given again");

            // a player with no row (new to the network): the login check still runs when the read finds nothing
            ServerPlayer fresh = TestPlayers.mockServerPlayer(h);
            List<UUID> read = new CopyOnWriteArrayList<>();
            Storage.onPlayerRead((srv, who) -> read.add(who));
            RankEngine.awaitRow(fresh.getUUID());
            s.loadPlayer(fresh.getUUID());
            s.drain();
            h.assertTrue(read.contains(fresh.getUUID()), "a missing row ends the wait too");
            fresh.discard();
        } finally {
            RankEngine.forget(id);
            s.close();
            ledger.reset(id);
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "audit-settings")
    public void a_settings_notice_goes_out_only_after_the_row_is_written(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        SettingsLedger settings = SettingsLedger.get(server);
        Settings before = settings.settings();
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        try {
            long notices = db.latestNoticeId();
            settings.update(b -> b.killCooldownMinutes(before.killCooldownMinutes() + 1));
            h.assertTrue(db.latestNoticeId() == notices, "no notice while the row waits to be written");
            s.drain();
            StorageBackend.SettingsRow row = db.loadSettings();
            List<StorageBackend.Notice> sent = db.fetchNoticesAfter(notices, 10);
            h.assertTrue(row != null && sent.size() == 1 && sent.get(0).kind().equals(Storage.KIND_SETTINGS) && sent.get(0).payload().equals(Long.toString(row.version())),
                    "the notice names the version written: " + sent + " " + (row == null ? null : row.version()));
        } finally {
            s.close();
            settings.set(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "audit-notices")
    public void a_notice_committed_after_a_later_one_is_still_read(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        List<String> got = new CopyOnWriteArrayList<>();
        s.onNotice(n -> got.add(n.uid()));
        try {
            long now = System.currentTimeMillis();
            db.insertNotice(new StorageBackend.Notice(5, "late-a", now, "beta", "unknown-kind", ""));
            db.insertNotice(new StorageBackend.Notice(7, "late-c", now, "beta", "unknown-kind", ""));
            s.pollNotices();
            s.drain();
            h.assertTrue(got.equals(List.of("late-a", "late-c")), "both read: " + got);
            db.insertNotice(new StorageBackend.Notice(6, "late-b", now, "gamma", "unknown-kind", "")); // committed after 7
            s.pollNotices();
            s.drain();
            h.assertTrue(got.equals(List.of("late-a", "late-c", "late-b")), "the late one is read, the others not twice: " + got);
        } finally {
            s.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "audit-commands")
    public void the_prefix_commands_take_title_or_group_and_a_name(GameTestHelper h) {
        CommandGameTests.Collector out = new CommandGameTests.Collector();
        CommandSourceStack console = CommandGameTests.console(h, out);
        h.assertTrue(CommandGameTests.run(console, "rankup prefix clear title ru_audit_nowhere") == 0, "parsed and refused: nothing to clear");
        h.assertTrue(out.lines.stream().anyMatch(l -> l.contains("has no image")), "the reply says why, not a syntax error: " + out.lines);
        out.lines.clear();
        h.assertTrue(CommandGameTests.run(console, "rankup prefix image group ru_audit_nowhere x.png") == 0, "an image for an unknown group is refused");
        h.assertFalse(out.lines.stream().anyMatch(l -> l.contains("Unknown or incomplete") || l.contains("Expected")), "and parsed: " + out.lines);
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "audit-png")
    public void a_png_with_an_impossible_chunk_is_broken_and_unchanged_files_are_not_read_again(GameTestHelper h) throws IOException {
        byte[] png = PrefixImageGameTests.png(12, 9);
        byte[] bad = png.clone();
        ByteBuffer.wrap(bad).putInt(33, Integer.MAX_VALUE - 4); // the chunk after the header claims ~2 GB
        h.assertTrue(PrefixImages.check("bad.png", bad).problem().equals("broken"), "broken, not an error: " + PrefixImages.check("bad.png", bad));
        Path file = PrefixImages.folder().resolve("ru_audit_same.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        try {
            PrefixImages.scan();
            PrefixImages.FileInfo first = PrefixImages.file("ru_audit_same.png");
            PrefixImages.scan();
            h.assertTrue(first != null && first == PrefixImages.file("ru_audit_same.png"), "an unchanged file keeps its reading");
            Files.write(file, PrefixImageGameTests.png(14, 9));
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
            PrefixImages.scan();
            h.assertTrue(PrefixImages.file("ru_audit_same.png").width() == 14, "a changed file is read again");
        } finally {
            Files.deleteIfExists(file);
            PrefixImages.scan();
        }
        h.assertTrue("[Second] ".equals(PrefixDisplay.titleOf(List.of(Reward.title("[First] "), Reward.coins(1), Reward.title("[Second] ")))), "the last TITLE is the one LuckPerms keeps");
        h.succeed();
    }
}
