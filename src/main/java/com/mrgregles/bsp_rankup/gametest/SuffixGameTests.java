package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.admin.AdminEdits;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay.Piece;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay.Source;
import com.mrgregles.bsp_rankup.settings.PrefixImage;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Suffixes and the stacked name line (owner decisions D88 to D97): LuckPerms' formatted prefix and suffix split into
 * the pieces RankUp knows, images one pixel apart with real spaces around the name, hover notes kept and shared,
 * groups' own suffixes read, and a player's suffix drawn after their name.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class SuffixGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final PrefixImage ADMIN = new PrefixImage("a".repeat(40), "admin.png", 46, 9), LEGEND = new PrefixImage("b".repeat(40), "legend.png", 48, 9),
            REAVER = new PrefixImage("c".repeat(40), "totem_reaver.png", 9, 9), FOUNDER = new PrefixImage("d".repeat(40), "founder.png", 9, 9);

    @GameTest(template = EMPTY)
    public void a_formatted_prefix_splits_into_the_pieces_rankup_knows(GameTestHelper h) {
        List<Source> known = PrefixDisplay.longestFirst(List.of(
                new Source("group:admin", "&8[&c&lAdmin&8]", "admin", ADMIN, ""),
                new Source("title:warden", "&8[&3Warden&8]", "Warden", null, ""),
                new Source("group:legend", "&8[&c&lLegend&8]", "legend", LEGEND, ""),
                new Source("group:war", "&8[&3War", "war", null, ""),
                new Source("group:copy", "&8[&3Warden&8]", "copy", null, "")));
        h.assertTrue(known.size() == 4 && known.stream().filter(s -> s.text().equals("&8[&3Warden&8]")).findFirst().get().key().equals("title:warden"),
                "one piece per text, the first listed (a title before a group): " + known);
        List<Source> imaged = PrefixDisplay.longestFirst(List.of(
                new Source("title:warden", "&8[&3Warden&8]", "Warden", null, ""),
                new Source("group:warden", "&8[&3Warden&8]", "warden", ADMIN, "")));
        h.assertTrue(imaged.size() == 1 && imaged.get(0).key().equals("group:warden"),
                "the same text: a title without an image gives way to the group's prefix that has one (AU-2c): " + imaged);

        List<Piece> pieces = PrefixDisplay.split("&8[&c&lAdmin&8] &8[&c&lLegend&8] ", known);
        h.assertTrue(pieces.size() == 4 && "group:admin".equals(key(pieces.get(0))) && pieces.get(1).text().equals(" ") && key(pieces.get(1)) == null
                && "group:legend".equals(key(pieces.get(2))) && pieces.get(3).text().equals(" "), "staff tag, spacer, ladder tag, end spacer: " + pieces);
        List<Piece> longest = PrefixDisplay.split("&8[&3Warden&8]", known);
        h.assertTrue(longest.size() == 1 && "title:warden".equals(key(longest.get(0))), "the longest known text wins over one it starts with: " + longest);
        List<Piece> other = PrefixDisplay.split("&7[VIP] &8[&c&lAdmin&8]", known);
        h.assertTrue(other.size() == 2 && key(other.get(0)) == null && other.get(0).text().equals("&7[VIP] ") && "group:admin".equals(key(other.get(1))),
                "text RankUp doesn't know stays as text: " + other);

        // drawn before the name: two images one pixel apart (the spacer only in the fallback), then a real space
        MutableComponent drawn = PrefixDisplay.draw(pieces, false);
        h.assertTrue(drawn.getString().equals("[Admin] [Legend] "), "the console reads the whole prefix: '" + drawn.getString() + "'");
        h.assertTrue(drawn.getSiblings().size() == 3 && imageKey(drawn.getSiblings().get(0)) != null && imageKey(drawn.getSiblings().get(1)) != null
                && drawn.getSiblings().get(2).getString().equals(" "), "image, image, a space before the name: " + drawn.getSiblings());
        MutableComponent lone = PrefixDisplay.draw(PrefixDisplay.split("&8[&c&lLegend&8]", known), false);
        h.assertTrue(lone.getString().equals("[Legend] ") && lone.getSiblings().size() == 2, "a lone image gets a real space after it: " + lone.getSiblings());
        MutableComponent text = PrefixDisplay.draw(PrefixDisplay.split("&8[&3Warden&8]", known), false);
        h.assertTrue(text.getString().equals("[Warden] "), "a piece without an image is its text, then a space: '" + text.getString() + "'");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_formatted_suffix_draws_after_the_name_with_its_notes(GameTestHelper h) {
        List<Source> known = PrefixDisplay.longestFirst(List.of(
                new Source("suffix:totem_reaver", "&5✦ Totem Reaver", "totem_reaver", REAVER, "Most totems stolen in a season"),
                new Source("suffix:founder", "&e✦ Founder", "founder", FOUNDER, ""),
                new Source("suffix:bulwark", "&3✦ Bulwark", "bulwark", null, "Your totem survives 25 steal attempts")));
        MutableComponent both = PrefixDisplay.draw(PrefixDisplay.split(" &5✦ Totem Reaver &e✦ Founder", known), true);
        h.assertTrue(both.getString().equals(" ✦ Totem Reaver ✦ Founder"), "the console reads every suffix: '" + both.getString() + "'");
        List<Component> parts = both.getSiblings();
        h.assertTrue(parts.size() == 3 && parts.get(0).getString().equals(" ") && imageKey(parts.get(1)) != null && imageKey(parts.get(2)) != null,
                "a space after the name, then two icons one pixel apart: " + parts);
        Component hover = hoverOf(parts.get(1));
        h.assertTrue(hover != null && hover.getString().contains("✦ Totem Reaver") && hover.getString().contains("Most totems stolen in a season"),
                "hovering an icon shows its text and its note (D94): " + hover);
        MutableComponent bare = PrefixDisplay.draw(PrefixDisplay.split("&3✦ Bulwark", known), true);
        h.assertTrue(bare.getString().equals(" ✦ Bulwark"), "a suffix without a leading space gets one: '" + bare.getString() + "'");
        Component noted = hoverOf(bare.getSiblings().get(1));
        h.assertTrue(noted != null && noted.getString().contains("25 steal attempts"), "text with a note shows it on hover too: " + noted);
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "suffix-settings")
    public void hover_notes_are_kept_shared_and_checked(GameTestHelper h) throws IOException {
        Settings s = Settings.defaults().toBuilder().prefixNote("suffix:headhunter", "  Most player kills in a season ").prefixImage("suffix:headhunter", REAVER).build();
        h.assertTrue(s.prefixNote("suffix:headhunter").equals("Most player kills in a season") && s.prefixNote("group:admin").isEmpty(), "a note is kept trimmed; none is empty");
        Settings back = SettingsFiles.fromJson(SettingsFiles.toJson(s));
        h.assertTrue(back.prefixNotes().equals(s.prefixNotes()) && REAVER.equals(back.prefixImage("suffix:headhunter")), "notes and suffix images survive the shared JSON: " + back.prefixNotes());
        h.assertTrue(back.toBuilder().prefixNote("suffix:headhunter", " ").build().prefixNotes().isEmpty(), "a blank note takes it away");
        h.assertTrue(PrefixImage.isKey("suffix:x") && PrefixImage.isLuckPerms("suffix:x") && PrefixImage.isLuckPerms("group:x") && !PrefixImage.isLuckPerms("title:x"),
                "suffix:<group> is a key, and LuckPerms' own");

        MinecraftServer server = h.getLevel().getServer();
        SettingsLedger ledger = SettingsLedger.get(server);
        Settings before = ledger.settings();
        String group = "ru_sf_notes";
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        try {
            lp.getGroupManager().createAndLoadGroup(group).join();
            h.assertTrue(AdminEdits.prefixNote(server, "suffix:" + group, "Earned by testing").ok() && ledger.settings().prefixNote("suffix:" + group).equals("Earned by testing"), "a note is set");
            h.assertFalse(AdminEdits.prefixNote(server, "suffix:" + group, "x".repeat(AdminEdits.NOTE_MAX + 1)).ok(), "a note over the limit is refused");
            h.assertFalse(AdminEdits.prefixNote(server, "suffix:ru_sf_no_such_group", "x").ok(), "a group LuckPerms doesn't have is refused");
            h.assertFalse(AdminEdits.prefixNote(server, "warden", "x").ok(), "a bare name is not a key");
            h.assertTrue(AdminEdits.prefixNote(server, "SUFFIX:" + group.toUpperCase(), "").ok() && ledger.settings().prefixNote("suffix:" + group).isEmpty(), "an empty note takes it away, keys in any case");
            h.assertFalse(AdminEdits.prefixNote(server, "suffix:" + group, "").ok(), "nothing to take away twice");
            ServerPlayer admin = TestPlayers.mockServerPlayer(h);
            h.assertTrue(AdminSync.apply(admin, AdminActionPacket.of(AdminActionPacket.PREFIX_NOTE, "suffix:" + group, "From the screen")).ok()
                    && ledger.settings().prefixNote("suffix:" + group).equals("From the screen"), "the screen's note action sets it");
            admin.discard();

            // the group goes; its note can still be taken away (AU-2a), though a new one is refused
            lp.getGroupManager().deleteGroup(lp.getGroupManager().getGroup(group)).join();
            h.assertFalse(AdminEdits.prefixNote(server, "suffix:" + group, "x").ok(), "no new note for a group that is gone");
            h.assertTrue(AdminEdits.prefixNote(server, "suffix:" + group, "").ok() && ledger.settings().prefixNote("suffix:" + group).isEmpty(),
                    "a note whose group is gone is taken away");
        } finally {
            ledger.set(before);
            var g = lp.getGroupManager().getGroup(group);
            if (g != null) {
                lp.getGroupManager().deleteGroup(g).join();
            }
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "suffix-live", timeoutTicks = 200)
    public void a_players_suffix_shows_after_their_name(GameTestHelper h) {
        if (!LuckPermsBridge.isPresent()) {
            h.succeed(); // the dev run has LuckPerms; without it there are no suffixes to read
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        SettingsLedger ledger = SettingsLedger.get(server);
        Settings before = ledger.settings();
        String group = "ru_sf_reaver";
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        try {
            var g = lp.getGroupManager().createAndLoadGroup(group).join();
            g.data().add(net.luckperms.api.node.types.SuffixNode.builder("&5✦ Reaver.1", 70).build());
            lp.getGroupManager().saveGroup(g).join();
            PrefixDisplay.forgetGroups();
            h.assertTrue(PrefixDisplay.groupSuffixes().get(group).equals(new LuckPermsBridge.Prefix(70, "&5✦ Reaver.1")), "a group's own suffix is read: " + PrefixDisplay.groupSuffixes().get(group));
            boolean listed = PrefixDisplay.sources(ledger.settings(), Map.of(), PrefixDisplay.groupSuffixes()).stream().anyMatch(s -> s.key().equals("suffix:" + group) && s.suffix());
            h.assertTrue(listed, "PREFIXES lists it as a suffix");

            h.assertTrue(LuckPermsBridge.addGroup(id, group).join() == LuckPermsBridge.Added.ADDED, "the player joins the group");
            PrefixDisplay.refresh(player);
            h.assertTrue(PrefixDisplay.shownSuffix(player).equals("&5✦ Reaver.1"), "LuckPerms' suffix for the player: " + PrefixDisplay.shownSuffix(player));
            h.assertTrue(player.getDisplayName().getString().equals(name + " ✦ Reaver.1"), "the suffix after the name, a space between: " + player.getDisplayName().getString());
            Component tab = player.getTabListDisplayName();
            h.assertTrue(tab != null && tab.getString().endsWith(name + " ✦ Reaver.1"), "and in the tab list: " + tab);

            h.assertTrue(AdminEdits.optionShowPrefixes(server, false).ok(), "switched off");
            PrefixDisplay.refresh(player);
            h.assertTrue(player.getDisplayName().getString().equals(name), "the switch covers suffixes too: " + player.getDisplayName().getString());
        } finally {
            ledger.set(before);
            PrefixDisplay.forgetGroups();
            var user = lp.getUserManager().getUser(id);
            if (user != null) {
                lp.getUserManager().cleanupUser(user);
            }
            var g = lp.getGroupManager().getGroup(group);
            if (g != null) {
                lp.getGroupManager().deleteGroup(g).join();
            }
            player.discard();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "suffix-cache")
    public void the_split_cache_stays_small(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        for (int i = 0; i < PrefixDisplay.MAX_SPLITS + 50; i++) {
            PrefixDisplay.pieces(server, "&7[Tag " + i + "] ", false);
            h.assertTrue(PrefixDisplay.splitsKept() <= PrefixDisplay.MAX_SPLITS, "never more than " + PrefixDisplay.MAX_SPLITS + " splits kept (AU-2c): " + PrefixDisplay.splitsKept());
        }
        h.assertTrue(PrefixDisplay.pieces(server, "&7[Tag 1] ", false).get(0).text().equals("&7[Tag 1] "), "a split made again after the cache restarted");
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "suffix-redraw", timeoutTicks = 400)
    public void a_group_change_read_elsewhere_still_redraws_names(GameTestHelper h) {
        if (!LuckPermsBridge.isPresent()) {
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        SettingsLedger ledger = SettingsLedger.get(server);
        Settings before = ledger.settings();
        String plain = "ru_sf_rd_plain", pictured = "ru_sf_rd_pictured";
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        try {
            var a = lp.getGroupManager().createAndLoadGroup(plain).join();
            a.data().add(net.luckperms.api.node.types.PrefixNode.builder("&7[RD]", 55).build());
            lp.getGroupManager().saveGroup(a).join();
            lp.getGroupManager().createAndLoadGroup(pictured).join();
            ledger.update(b -> b.prefixImage(PrefixImage.groupKey(pictured), ADMIN));
            h.assertTrue(LuckPermsBridge.addGroup(id, plain).join() == LuckPermsBridge.Added.ADDED, "the player joins the group without an image");
            PrefixDisplay.forgetGroups();
            PrefixDisplay.refresh(player);
            h.assertTrue(!hasImage(player.getDisplayName()) && player.getDisplayName().getString().startsWith("[RD] "), "drawn as text first: " + player.getDisplayName().getString());

            // the other group takes the same prefix text; the admin screen reads the groups before the name check does
            var b = lp.getGroupManager().getGroup(pictured);
            b.data().add(net.luckperms.api.node.types.PrefixNode.builder("&7[RD]", 55).build());
            lp.getGroupManager().saveGroup(b).join();
            PrefixDisplay.forgetGroups();
            h.assertTrue(PrefixDisplay.groupPrefixes().containsKey(pictured), "the screen's read saw the new prefix");
            PrefixDisplay.checkNames(List.of(player));
            h.assertTrue(hasImage(player.getDisplayName()), "the name check still draws the name again, now with the image (AU-2c): " + player.getDisplayName());
        } finally {
            ledger.set(before);
            PrefixDisplay.forgetGroups();
            var user = lp.getUserManager().getUser(id);
            if (user != null) {
                lp.getUserManager().cleanupUser(user);
            }
            for (String name : List.of(plain, pictured)) {
                var g = lp.getGroupManager().getGroup(name);
                if (g != null) {
                    lp.getGroupManager().deleteGroup(g).join();
                }
            }
            player.discard();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasImage(Component c) {
        if (imageKey(c) != null) {
            return true;
        }
        for (Component sib : c.getSiblings()) {
            if (hasImage(sib)) {
                return true;
            }
        }
        return c.getContents() instanceof TranslatableContents t && java.util.Arrays.stream(t.getArgs()).anyMatch(arg -> arg instanceof Component ac && hasImage(ac));
    }

    private static String key(Piece p) {
        return p.source() == null ? null : p.source().key();
    }

    private static String imageKey(Component c) {
        return c.getContents() instanceof TranslatableContents t && t.getKey().startsWith(PrefixDisplay.KEY) ? t.getKey() : null;
    }

    private static Component hoverOf(Component c) {
        List<Component> todo = new ArrayList<>(List.of(c));
        while (!todo.isEmpty()) {
            Component next = todo.remove(0);
            HoverEvent e = next.getStyle().getHoverEvent();
            if (e != null) {
                return e.getValue(HoverEvent.Action.SHOW_TEXT);
            }
            todo.addAll(next.getSiblings());
        }
        return null;
    }
}
