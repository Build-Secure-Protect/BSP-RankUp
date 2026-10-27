package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.admin.AdminEdits;
import com.mrgregles.bsp_rankup.admin.Reply;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.network.PrefixImagePacket;
import com.mrgregles.bsp_rankup.prefix.Legacy;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay;
import com.mrgregles.bsp_rankup.prefix.PrefixImages;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.PrefixImage;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.storage.MemoryBackend;
import com.mrgregles.bsp_rankup.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Prefix images (owner decisions D55 to D74): the settings keep them, the folder's files are checked, picking one keeps
 * its bytes here and in the shared storage so another server gets them without the file, players are sent them, the
 * admin screen's view lists the prefixes and files, and the name in chat and the tab list carries the prefix LuckPerms
 * ranks highest, as its image (with the text for the console) or as coloured text. The client's drawing of the image
 * cannot be run headlessly. Every test writes its own files ({@code ru_pi_*}) into the images folder and removes them.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class PrefixImageGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "prefix-settings")
    public void settings_keep_prefix_images_and_the_switch(GameTestHelper h) throws IOException {
        PrefixImage image = new PrefixImage("a".repeat(40), "warden.png", 40, 9);
        Settings s = Settings.defaults().toBuilder().prefixImage(PrefixImage.titleKey("Warden"), image).prefixImage(PrefixImage.groupKey("admin"), image).showPrefixes(false).build();
        h.assertTrue(Settings.defaults().showPrefixes() && Settings.defaults().prefixImages().isEmpty(), "by default prefixes show, with no images");
        Settings back = SettingsFiles.fromJson(SettingsFiles.toJson(s));
        h.assertTrue(back.prefixImage("title:warden").equals(image) && back.prefixImage("group:admin").equals(image), "images by rank key survive the shared JSON: " + back.prefixImages());
        h.assertFalse(back.showPrefixes(), "the switch survives");
        Settings cleared = back.toBuilder().prefixImage("title:warden", null).build();
        h.assertTrue(cleared.prefixImage("title:warden") == null && cleared.prefixImages().size() == 1, "an image can be taken away");
        h.assertTrue(PrefixImage.isKey("title:x") && PrefixImage.isKey("group:y") && !PrefixImage.isKey("title:") && !PrefixImage.isKey("warden"), "keys are title:<group> or group:<group>");
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "prefix-folder")
    public void the_folder_is_read_and_each_file_checked(GameTestHelper h) throws IOException {
        Path dir = PrefixImages.folder();
        Files.createDirectories(dir);
        List<Path> made = new ArrayList<>();
        try {
            byte[] good = png(40, 9);
            made.add(write(dir, "ru_pi_good.png", good));
            made.add(write(dir, "ru_pi_big.png", png(300, 80)));
            made.add(write(dir, "ru_pi_wide.png", png(100, 9)));
            made.add(write(dir, "ru_pi_animated.png", animated(png(16, 9))));
            made.add(write(dir, "ru_pi_broken.png", broken(png(40, 9))));
            made.add(write(dir, "ru_pi_text.png", "not an image at all, just text".getBytes()));
            PrefixImages.scan();
            PrefixImages.FileInfo ok = PrefixImages.file("ru_pi_good.png");
            h.assertTrue(ok != null && ok.usable() && ok.width() == 40 && ok.height() == 9 && ok.id().equals(PrefixImages.idOf(good)), "a 40 x 9 PNG is usable, known by its hash: " + ok);
            h.assertTrue(PrefixImages.inFolder(ok.id()) && PrefixImages.folderBytes(ok.id()) != null, "and its bytes are at hand");
            h.assertTrue(problem("ru_pi_big.png").contains("over 256 x 64"), "300 x 80 is too big: " + problem("ru_pi_big.png"));
            h.assertTrue(problem("ru_pi_wide.png").startsWith("too wide"), "100 x 9 is over 64 px wide in chat: " + problem("ru_pi_wide.png"));
            h.assertTrue(problem("ru_pi_animated.png").equals("animated"), "an animated PNG is refused: " + problem("ru_pi_animated.png"));
            h.assertTrue(problem("ru_pi_broken.png").equals("broken"), "a PNG that cannot be read is broken: " + problem("ru_pi_broken.png"));
            h.assertTrue(problem("ru_pi_text.png").equals("not a PNG"), "a text file named .png is not a PNG: " + problem("ru_pi_text.png"));
            h.assertTrue(PrefixImages.check("x.png", new byte[PrefixImages.MAX_BYTES + 1]).problem().equals("over 256 KB"), "over 256 KB is refused");
            h.assertTrue(PrefixImages.drawnWidth(256, 64) == 36 && PrefixImages.drawnWidth(64, 9) == 64, "drawn one line (9 px) high: 256 x 64 is 36 wide, 64 x 9 is 64");
        } finally {
            for (Path p : made) {
                Files.deleteIfExists(p);
            }
            PrefixImages.scan();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "prefix-pick")
    public void picking_an_image_keeps_it_here_and_shared_and_another_server_fetches_it(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        SettingsLedger ledger = SettingsLedger.get(server);
        Settings before = ledger.settings();
        Path dir = PrefixImages.folder();
        Files.createDirectories(dir);
        Path file = dir.resolve("ru_pi_pick.png");
        byte[] bytes = png(30, 9);
        Files.write(file, bytes);
        String id = PrefixImages.idOf(bytes);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        PrefixImages.useStorage(alpha);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        try {
            ledger.update(b -> b.addLadder(new Ladder("ru_pi_l", "ru_pi_track", Set.of(), List.of(new Rank("ru_pi_r", "Picker", 10, false, List.of(Reward.title("&b[Pick] ")))), 990)));
            PrefixImages.scan();
            h.assertFalse(AdminEdits.prefixImage(server, "title:ru_pi_nowhere", "ru_pi_pick.png").ok(), "a title of no rung or award is refused");
            h.assertFalse(AdminEdits.prefixImage(server, "title:ru_pi_r", "ru_pi_missing.png").ok(), "a file not in the folder is refused");
            h.assertFalse(AdminEdits.prefixImage(server, "title:ru_pi_r", "../ru_pi_pick.png").ok(), "a path is refused");
            Reply picked = AdminEdits.prefixImage(server, "title:ru_pi_r", "ru_pi_pick.png");
            h.assertTrue(picked.ok(), "picked: " + picked.text());
            PrefixImage image = ledger.settings().prefixImage("title:ru_pi_r");
            h.assertTrue(image != null && image.id().equals(id) && image.file().equals("ru_pi_pick.png") && image.width() == 30 && image.height() == 9, "the settings name the image: " + image);
            h.assertTrue(PrefixImages.Store.get(server).has(id), "kept on this server");
            alpha.drain();
            h.assertTrue(java.util.Arrays.equals(db.loadImage(id), bytes), "and in the shared table");

            // a player is sent it once per login
            h.assertTrue(PrefixImages.send(player, id) && PrefixImages.wasSent(player.getUUID(), id), "sent to a player");

            // the file goes: nothing changes, the view marks it not in the folder (D71)
            Files.delete(file);
            PrefixImages.scan();
            h.assertTrue(PrefixImages.bytes(server, id) != null && !PrefixImages.inFolder(id), "still drawn without the file");

            // another server that never had it: fetched from the shared table
            PrefixImages.forgetKept(server);
            h.assertTrue(PrefixImages.bytes(server, id) == null, "a server without it");
            PrefixImages.fetchMissing(server);
            alpha.drain();
            h.assertTrue(java.util.Arrays.equals(PrefixImages.bytes(server, id), bytes), "fetched from the shared table on a settings change or start-up");

            // one image on several ranks (D70), then taken away
            h.assertTrue(AdminEdits.prefixClear(server, "title:ru_pi_r").ok() && ledger.settings().prefixImage("title:ru_pi_r") == null, "cleared: the text shows again");
            h.assertFalse(AdminEdits.prefixClear(server, "title:ru_pi_r").ok(), "clearing twice says there is none");
            h.assertTrue(!PrefixImages.Store.get(server).has(id), "an image no rank uses is no longer kept here");
        } finally {
            PrefixImages.useStorage(null);
            alpha.close();
            ledger.set(before);
            Files.deleteIfExists(file);
            PrefixImages.scan();
            player.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "prefix-view")
    public void the_admin_view_lists_prefixes_and_files_and_the_actions_apply(GameTestHelper h) throws IOException {
        MinecraftServer server = h.getLevel().getServer();
        SettingsLedger ledger = SettingsLedger.get(server);
        Settings before = ledger.settings();
        Path file = PrefixImages.folder().resolve("ru_pi_view.png");
        Files.createDirectories(file.getParent());
        byte[] bytes = png(20, 9);
        Files.write(file, bytes);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        try {
            ledger.update(b -> b.addLadder(new Ladder("ru_pi_v", "ru_pi_vtrack", Set.of(), List.of(new Rank("ru_pi_vr", "Viewer", 0, false, List.of(Reward.title("&a[View] ")))), 989)));
            h.assertTrue(AdminSync.apply(admin, AdminActionPacket.of(AdminActionPacket.PREFIX_SCAN)).ok(), "REFRESH reads the folder");
            h.assertTrue(PrefixImages.wasSent(admin.getUUID(), PrefixImages.idOf(bytes)), "and sends the admin the previews");
            h.assertTrue(AdminSync.apply(admin, AdminActionPacket.of(AdminActionPacket.PREFIX_SET, "title:ru_pi_vr", "ru_pi_view.png")).ok(), "the screen picks an image");
            AdminViewPacket view = AdminSync.view(admin, "", true, false);
            AdminViewPacket.PrefixRow row = view.prefixes().stream().filter(r -> r.key().equals("title:ru_pi_vr")).findFirst().orElse(null);
            h.assertTrue(row != null && row.text().equals("&a[View] ") && row.rankName().endsWith("Viewer"), "the view lists the title with its rank: " + row);
            h.assertTrue(view.files().stream().anyMatch(f -> f.name().equals("ru_pi_view.png") && f.problem().isEmpty() && f.width() == 20), "and the folder's files: " + view.files());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            AdminViewPacket.encode(view, buf);
            AdminViewPacket back = AdminViewPacket.decode(buf);
            h.assertTrue(back.prefixes().equals(view.prefixes()) && back.files().equals(view.files()), "prefixes and files survive the packet");
            Settings shown = Settings.load(back.settings());
            h.assertTrue(shown.prefixImage("title:ru_pi_vr") != null, "the settings in the view carry the image");

            FriendlyByteBuf ibuf = new FriendlyByteBuf(Unpooled.buffer());
            PrefixImagePacket.encode(new PrefixImagePacket(PrefixImages.idOf(bytes), bytes), ibuf);
            PrefixImagePacket ib = PrefixImagePacket.decode(ibuf);
            h.assertTrue(ib.id().equals(PrefixImages.idOf(bytes)) && java.util.Arrays.equals(ib.png(), bytes), "an image survives its packet");

            h.assertTrue(AdminSync.apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "showprefixes", "false")).ok() && !ledger.settings().showPrefixes(), "the switch turns off");
            h.assertTrue(AdminSync.apply(admin, AdminActionPacket.of(AdminActionPacket.OPTION, "showprefixes", "true")).ok() && ledger.settings().showPrefixes(), "and on");
            h.assertTrue(AdminSync.apply(admin, AdminActionPacket.of(AdminActionPacket.PREFIX_CLEAR, "title:ru_pi_vr")).ok(), "the screen takes the image away");
        } finally {
            ledger.set(before);
            Files.deleteIfExists(file);
            PrefixImages.scan();
            admin.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "prefix-legacy")
    public void colour_codes_become_styles(GameTestHelper h) {
        Component c = Legacy.parse("&b[Pi] ");
        h.assertTrue(c.getString().equals("[Pi] ") && c.getSiblings().get(0).getStyle().getColor().equals(TextColor.fromLegacyFormat(ChatFormatting.AQUA)), "&b is aqua: " + c);
        Component hex = Legacy.parse("&#ff8800A&x&f&f&0&0&0&0B");
        h.assertTrue(hex.getSiblings().get(0).getStyle().getColor().getValue() == 0xff8800 && hex.getSiblings().get(1).getStyle().getColor().getValue() == 0xff0000, "&#RRGGBB and &x&R&R&G&G&B&B: " + hex);
        Component fmt = Legacy.parse("&lB&cC§rD");
        h.assertTrue(fmt.getSiblings().get(0).getStyle().isBold() && !fmt.getSiblings().get(1).getStyle().isBold() && fmt.getSiblings().get(2).getStyle().getColor() == null, "a colour clears bold, &r clears all: " + fmt);
        h.assertTrue(Legacy.strip("&6[Gold] &zx&").equals("[Gold] &zx&"), "unknown codes stay as text: " + Legacy.strip("&6[Gold] &zx&"));
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "prefix-names", timeoutTicks = 2400)
    public void chat_and_tab_list_names_carry_the_prefix_luckperms_shows(GameTestHelper h) throws IOException {
        if (!LuckPermsBridge.isPresent()) {
            BSPRankUp.LOGGER.warn("LuckPerms is not loaded in this run; the prefix name test did nothing.");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        SettingsLedger ledger = SettingsLedger.get(server);
        Settings before = ledger.settings();
        Path dir = PrefixImages.folder();
        Files.createDirectories(dir);
        byte[] titleBytes = png(18, 9), groupBytes = png(24, 9);
        Path titleFile = write(dir, "ru_pi_title.png", titleBytes), groupFile = write(dir, "ru_pi_group.png", groupBytes);
        String titleId = PrefixImages.idOf(titleBytes), groupId = PrefixImages.idOf(groupBytes);
        String group = "ru_pi_boss";
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        try {
            ledger.update(b -> b.addLadder(new Ladder("ru_pi_n", "ru_pi_ntrack", Set.of(), List.of(new Rank("ru_pi_nr", "Named", 0, false, List.of(Reward.title("&b[Pi] ")))), 988)));
            PrefixImages.scan();

            h.assertTrue(text(player).equals(name), "no prefix: the name alone: " + text(player));
            h.assertTrue(LuckPermsBridge.setPrefix(id, 988, "&b[Pi] ").join(), "a RankUp title");
            PrefixDisplay.refresh(player);
            h.assertTrue(text(player).equals("[Pi] " + name) && imageKey(player.getDisplayName()) == null, "the title as text before the name: " + text(player));

            h.assertTrue(AdminEdits.prefixImage(server, "title:ru_pi_nr", "ru_pi_title.png").ok(), "the title gets an image");
            PrefixDisplay.refresh(player); // the server refreshes everyone in its player list; the mock is not in it
            Component shown = player.getDisplayName();
            h.assertTrue((PrefixDisplay.KEY + titleId).equals(imageKey(shown)), "the name carries the title's image key: " + shown);
            h.assertTrue(text(player).equals("[Pi] " + name), "the console reads the text (D73): " + text(player));
            Component tab = player.getTabListDisplayName();
            h.assertTrue(tab != null && (PrefixDisplay.KEY + titleId).equals(imageKey(tab)) && tab.getString().endsWith(name), "the tab list too: " + tab);
            HoverEvent hover = styleWithKey(shown) == null ? null : styleWithKey(shown).getStyle().getHoverEvent();
            Component hoverText = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_TEXT);
            h.assertTrue(hoverText != null && hoverText.getString().contains("[Pi]") && hoverText.getString().contains("Named"), "hovering shows the prefix and the rank (D72): " + hoverText);

            // a group prefix LuckPerms ranks higher wins (D66), drawn as its own image
            net.luckperms.api.model.group.Group g = lp.getGroupManager().createAndLoadGroup(group).join();
            g.data().add(net.luckperms.api.node.types.PrefixNode.builder("&c[Boss.1] ", 2000).build());
            lp.getGroupManager().saveGroup(g).join();
            h.assertTrue(LuckPermsBridge.addGroup(id, group).join() == LuckPermsBridge.Added.ADDED, "the player joins the group");
            PrefixDisplay.forgetGroups();
            PrefixDisplay.refresh(player);
            h.assertTrue(text(player).equals("[Boss.1] " + name) && imageKey(player.getDisplayName()) == null, "the group's prefix (a dot in it, which LuckPerms escapes in its key), above the title, as text: " + text(player));
            h.assertTrue(LuckPermsBridge.groupPrefixes().get(group).equals(new LuckPermsBridge.Prefix(2000, "&c[Boss.1] ")), "the group's own prefix is read: " + LuckPermsBridge.groupPrefixes().get(group));
            h.assertTrue(AdminEdits.prefixImage(server, "group:" + group, "ru_pi_group.png").ok(), "the group's prefix gets an image");
            PrefixDisplay.refresh(player);
            h.assertTrue((PrefixDisplay.KEY + groupId).equals(imageKey(player.getDisplayName())), "now drawn as the group's image: " + player.getDisplayName());

            // the switch off (D55): the name alone
            h.assertTrue(AdminEdits.optionShowPrefixes(server, false).ok(), "switched off");
            PrefixDisplay.refresh(player);
            h.assertTrue(text(player).equals(name) && player.getTabListDisplayName() == null, "names are left alone: " + text(player));
        } finally {
            ledger.set(before);
            Files.deleteIfExists(titleFile);
            Files.deleteIfExists(groupFile);
            PrefixImages.scan();
            PrefixDisplay.forgetGroups();
            net.luckperms.api.model.user.User user = lp.getUserManager().getUser(id);
            if (user != null) {
                lp.getUserManager().cleanupUser(user);
            }
            net.luckperms.api.model.group.Group g = lp.getGroupManager().getGroup(group);
            if (g != null) {
                lp.getGroupManager().deleteGroup(g).join();
            }
            player.discard();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ helpers

    private static String text(ServerPlayer player) {
        player.refreshDisplayName();
        return player.getDisplayName().getString();
    }

    /** The image key in a name, or null when it has none. */
    @Nullable
    private static String imageKey(Component c) {
        Component part = styleWithKey(c);
        return part == null ? null : ((TranslatableContents) part.getContents()).getKey();
    }

    @Nullable
    private static Component styleWithKey(Component c) {
        if (c.getContents() instanceof TranslatableContents t && t.getKey().startsWith(PrefixDisplay.KEY)) {
            return c;
        }
        for (Component sibling : c.getSiblings()) {
            Component found = styleWithKey(sibling);
            if (found != null) {
                return found;
            }
        }
        if (c.getContents() instanceof TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a) {
                    Component found = styleWithKey(a);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static String problem(String name) {
        PrefixImages.FileInfo f = PrefixImages.file(name);
        return f == null ? "(missing)" : f.problem();
    }

    private static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    /** A PNG of the size, filled with a colour that depends on the size (so two sizes are two images). */
    static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, 0xFF000000 | (w * 977 + h * 131 + x * 7 + y) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** The PNG with an acTL chunk after its header, as an animated PNG has. */
    private static byte[] animated(byte[] png) {
        byte[] chunk = {0, 0, 0, 8, 'a', 'c', 'T', 'L', 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] out = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, out, 0, 33);
        System.arraycopy(chunk, 0, out, 33, chunk.length);
        System.arraycopy(png, 33, out, 33 + chunk.length, png.length - 33);
        return out;
    }

    /** The PNG with its image data scrambled: the header still reads, the pixels do not. */
    private static byte[] broken(byte[] png) {
        byte[] out = png.clone();
        for (int i = 41; i < out.length - 12; i++) {
            out[i] = (byte) (i * 31);
        }
        return out;
    }
}
