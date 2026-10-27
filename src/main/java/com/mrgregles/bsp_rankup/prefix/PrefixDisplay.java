package com.mrgregles.bsp_rankup.prefix;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.PrefixImage;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The prefix before a player's name in chat and the tab list (owner decisions D55 to D73). LuckPerms Forge keeps
 * prefixes but nothing on the network shows them, so RankUp does: the prefix LuckPerms ranks highest for the player
 * (D66) goes inside vanilla's {@code <name>} before the name (D67), as its rank's image when the rank has one, else
 * as its coloured text.
 *
 * <p>An image is sent as a translatable part whose key names the image ({@value #KEY}{@code <id>}) in RankUp's font,
 * with the coloured prefix as its fallback: RankUp's client turns the key into the image's glyph once it has drawn
 * it, while the console, the logs and anything else read the fallback text (D73). Hovering it shows the prefix text
 * and the rank's name (D72).
 *
 * <p>A prefix is matched to its rank by text: every RankUp title (a rung's or an award's TITLE reward) and every
 * LuckPerms group's own prefix is listed with its image, titles first. Group prefixes are read again every half
 * minute; each online player's prefix is looked at every five seconds and their name refreshed when it changed.
 */
public final class PrefixDisplay {
    /** The start of an image's translation key; the client watches for it. */
    public static final String KEY = "bsp_rankup.prefix_image.";
    /** The font images are drawn in: RankUp's glyphs, then the default font for everything else. */
    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(BSPRankUp.MODID, "prefix");
    private static final int CHECK_TICKS = 100;
    private static final long GROUPS_EVERY_MS = 30_000;

    /** A prefix RankUp knows: its rank key, text, the rank's name and its image (null: drawn as text). */
    public record Source(String key, String text, String rankName, @Nullable PrefixImage image) {
    }

    @Nullable
    private static Settings indexedFor;
    private static long groupsReadAt;
    private static Map<String, LuckPermsBridge.Prefix> groupPrefixes = Map.of();
    private static Map<String, Source> byText = Map.of();
    /** What each online player was last drawn with: the prefix text (empty for none) and their team, see {@link #drawnWith}. */
    private static final Map<UUID, String> SHOWN = new HashMap<>();
    private static int ticks;

    private PrefixDisplay() {
    }

    // ------------------------------------------------------------------ the sources

    /**
     * Every prefix RankUp lists (D56), in the PREFIXES tab's order: each ladder's titles rung by rung, the awards'
     * titles, then the LuckPerms groups that have their own prefix.
     */
    public static List<Source> sources(Settings settings, Map<String, LuckPermsBridge.Prefix> groups) {
        List<Source> out = new ArrayList<>();
        boolean several = settings.ladders().size() > 1;
        for (Ladder l : settings.ladders()) {
            for (Rank r : l.ranks()) {
                String title = titleOf(r.rewards());
                if (title != null) {
                    String key = PrefixImage.titleKey(r.group());
                    out.add(new Source(key, title, several ? RankEngine.label(l) + ": " + r.displayName() : r.displayName(), settings.prefixImage(key)));
                }
            }
        }
        for (Award a : settings.awards()) {
            String title = titleOf(a.rewards());
            if (title != null) {
                String key = PrefixImage.titleKey(a.group());
                out.add(new Source(key, title, a.displayName(), settings.prefixImage(key)));
            }
        }
        groups.forEach((group, prefix) -> {
            String key = PrefixImage.groupKey(group);
            out.add(new Source(key, prefix.text(), group, settings.prefixImage(key)));
        });
        return out;
    }

    /** The text of the last TITLE reward (each replaces the one before at the same priority, so the last is what shows), or null. */
    @Nullable
    public static String titleOf(List<Reward> rewards) {
        String title = null;
        for (Reward r : rewards) {
            if (r.kind() == Reward.Kind.TITLE && !r.value().isEmpty()) {
                title = r.value();
            }
        }
        return title;
    }

    /** LuckPerms' groups' own prefixes, read at most every half minute. */
    public static Map<String, LuckPermsBridge.Prefix> groupPrefixes() {
        long now = System.currentTimeMillis();
        if (now - groupsReadAt >= GROUPS_EVERY_MS) {
            groupsReadAt = now;
            Map<String, LuckPermsBridge.Prefix> read = LuckPermsBridge.groupPrefixes();
            if (!read.equals(groupPrefixes)) {
                groupPrefixes = read;
                indexedFor = null;
            }
        }
        return groupPrefixes;
    }

    /** The source for a prefix text, titles before group prefixes; null for a prefix RankUp does not list. */
    @Nullable
    public static Source sourceOf(MinecraftServer server, String text) {
        Map<String, LuckPermsBridge.Prefix> groups = groupPrefixes();
        Settings settings = SettingsLedger.settings(server);
        if (settings != indexedFor) {
            Map<String, Source> index = new LinkedHashMap<>();
            for (Source s : sources(settings, groups)) {
                index.putIfAbsent(s.text(), s);
            }
            byText = index;
            indexedFor = settings;
        }
        return byText.get(text);
    }

    /** The image id drawn for a prefix text, or empty (the player screen's title chip, D61). */
    public static String imageIdFor(MinecraftServer server, String text) {
        Source s = text.isEmpty() ? null : sourceOf(server, text);
        return s == null || s.image() == null ? "" : s.image().id();
    }

    /** Reads LuckPerms' groups again on the next look (REFRESH on the admin screen). */
    public static void forgetGroups() {
        groupsReadAt = 0;
    }

    // ------------------------------------------------------------------ the part before the name

    /** The prefix text drawn before the player's name, or empty when nothing is (switched off, no LuckPerms, no prefix). */
    public static String shownText(ServerPlayer player) {
        if (!SettingsLedger.settings(player.server).showPrefixes()) {
            return "";
        }
        String text = LuckPermsBridge.shownPrefix(player.getUUID()).orElse("");
        return Legacy.strip(text).isBlank() ? "" : text;
    }

    /** A prefix as drawn: its rank's image with the text as fallback, or the coloured text; followed by a space when it is text. */
    public static Component part(MinecraftServer server, String text) {
        Source source = sourceOf(server, text);
        String spaced = Character.isWhitespace(text.charAt(text.length() - 1)) ? text : text + " ";
        MutableComponent coloured = Legacy.parse(spaced);
        if (source == null || source.image() == null) {
            return coloured;
        }
        MutableComponent hover = Legacy.parse(text.strip()).append(Component.literal(" " + source.rankName()).withStyle(s -> s.withColor(0xAAAAAA)));
        // the fallback is "%s" filled with the coloured text, so a client without the image and the console see the prefix
        return Component.translatableWithFallback(KEY + source.image().id(), "%s", coloured)
                .withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    // ------------------------------------------------------------------ events

    /** Chat and anything else that asks for the display name (D67). */
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String text = shownText(player);
            SHOWN.put(player.getUUID(), drawnWith(player, text));
            if (!text.isEmpty()) {
                event.setDisplayname(Component.empty().append(part(player.server, text)).append(event.getDisplayname()));
            }
        }
    }

    /** The tab list: the prefix, then the name in its team's colour as vanilla draws it. */
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String text = shownText(player);
            if (!text.isEmpty()) {
                Component name = event.getDisplayName() != null ? event.getDisplayName() : PlayerTeam.formatNameForTeam(player.getTeam(), player.getName());
                event.setDisplayName(Component.empty().append(part(player.server, text)).append(name));
            }
        }
    }

    /** Every five seconds: a player whose prefix changed (a new title, a group's prefix edited) is drawn again. */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % CHECK_TICKS != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        Map<String, LuckPermsBridge.Prefix> groupsBefore = groupPrefixes;
        groupPrefixes();
        boolean indexChanged = groupsBefore != groupPrefixes;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String now = drawnWith(p, shownText(p));
            String was = SHOWN.get(p.getUUID());
            if (was == null || !was.equals(now) || indexChanged && !now.startsWith("\n")) {
                refresh(p);
            }
        }
    }

    /**
     * The prefix text and the player's team (name, colour, its prefix and suffix): the tab list name is drawn with the
     * team's formatting fixed in, so joining or leaving a team, or the team's colour changing, draws it again.
     */
    private static String drawnWith(ServerPlayer player, String text) {
        String t = player.getTeam() instanceof PlayerTeam team ? team.getName() + "/" + team.getColor() + "/" + team.getPlayerPrefix().getString() + "/" + team.getPlayerSuffix().getString() : "";
        return text + "\n" + t;
    }

    /** Draws everyone's name again (settings changed: an image picked, the switch turned). */
    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            refresh(p);
        }
    }

    public static void refresh(ServerPlayer player) {
        SHOWN.remove(player.getUUID());
        player.refreshDisplayName();
        player.refreshTabListName();
        player.getDisplayName(); // asks again now, which records what is shown
    }

    public static void forget(UUID player) {
        SHOWN.remove(player);
    }
}
