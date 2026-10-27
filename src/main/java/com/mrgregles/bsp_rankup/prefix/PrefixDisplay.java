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
 * The prefix before a player's name and the suffix after it, in chat and the tab list (owner decisions D55 to D73,
 * D88 to D97). LuckPerms Forge keeps prefixes and suffixes but nothing on the network shows them, so RankUp does: the
 * prefix and suffix LuckPerms formats for the player (its meta-formatting: the staff tag then the ladder tag, every
 * achievement) go inside vanilla's {@code <name>} around the name (D67). The formatted text is split into the pieces
 * RankUp knows (titles, groups' own prefixes and suffixes, D95); each known piece with an image is drawn as the image,
 * everything else as its coloured text. Two images side by side sit one pixel apart; the name has a space each side
 * (D96).
 *
 * <p>An image is sent as a translatable part whose key names the image ({@value #KEY}{@code <id>}) in RankUp's font,
 * with the coloured prefix as its fallback: RankUp's client turns the key into the image's glyph once it has drawn
 * it, while the console, the logs and anything else read the fallback text (D73). Hovering it shows the prefix text
 * and the rank's name (D72).
 *
 * <p>A piece is matched to its rank by text: every RankUp title (a rung's or an award's TITLE reward) and every
 * LuckPerms group's own prefix and suffix is listed with its image, titles first, the longest text first. Group
 * prefixes and suffixes are read again every half minute; each online player's are looked at every five seconds and
 * their name refreshed when they changed.
 */
public final class PrefixDisplay {
    /** The start of an image's translation key; the client watches for it. */
    public static final String KEY = "bsp_rankup.prefix_image.";
    /** The font images are drawn in: RankUp's glyphs, then the default font for everything else. */
    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(BSPRankUp.MODID, "prefix");
    private static final int CHECK_TICKS = 100;
    private static final long GROUPS_EVERY_MS = 30_000;

    /** A prefix or suffix RankUp knows: its rank key, text, the rank's name, its image (null: drawn as text) and its hover note. */
    public record Source(String key, String text, String rankName, @Nullable PrefixImage image, String note) {
        /** True for a LuckPerms group's own suffix, drawn after the name. */
        public boolean suffix() {
            return key.startsWith(PrefixImage.SUFFIX);
        }
    }

    /** A run of formatted prefix or suffix text: a known piece (source set) or text RankUp does not know. */
    public record Piece(String text, @Nullable Source source) {
    }

    @Nullable
    private static Settings indexedFor;
    private static long groupsReadAt;
    private static Map<String, LuckPermsBridge.Prefix> groupPrefixes = Map.of();
    private static Map<String, LuckPermsBridge.Prefix> groupSuffixes = Map.of();
    private static Map<String, Source> byText = Map.of();
    /** Known prefixes and suffixes, the longest text first (the split tries them in this order). */
    private static List<Source> prefixOrder = List.of(), suffixOrder = List.of();
    private static final Map<String, List<Piece>> SPLITS = new HashMap<>();
    /** What each online player was last drawn with: prefix, suffix and their team, see {@link #drawnWith}. */
    private static final Map<UUID, String> SHOWN = new HashMap<>();
    private static int ticks;

    private PrefixDisplay() {
    }

    // ------------------------------------------------------------------ the sources

    /**
     * Every prefix and suffix RankUp lists (D56, D89), in the PREFIXES tab's order: each ladder's titles rung by rung,
     * the awards' titles, the LuckPerms groups that have their own prefix, then those that have their own suffix.
     */
    public static List<Source> sources(Settings settings, Map<String, LuckPermsBridge.Prefix> groups, Map<String, LuckPermsBridge.Prefix> suffixes) {
        List<Source> out = new ArrayList<>();
        boolean several = settings.ladders().size() > 1;
        for (Ladder l : settings.ladders()) {
            for (Rank r : l.ranks()) {
                String title = titleOf(r.rewards());
                if (title != null) {
                    String key = PrefixImage.titleKey(r.group());
                    out.add(new Source(key, title, several ? RankEngine.label(l) + ": " + r.displayName() : r.displayName(), settings.prefixImage(key), settings.prefixNote(key)));
                }
            }
        }
        for (Award a : settings.awards()) {
            String title = titleOf(a.rewards());
            if (title != null) {
                String key = PrefixImage.titleKey(a.group());
                out.add(new Source(key, title, a.displayName(), settings.prefixImage(key), settings.prefixNote(key)));
            }
        }
        groups.forEach((group, prefix) -> {
            String key = PrefixImage.groupKey(group);
            out.add(new Source(key, prefix.text(), group, settings.prefixImage(key), settings.prefixNote(key)));
        });
        suffixes.forEach((group, suffix) -> {
            String key = PrefixImage.suffixKey(group);
            out.add(new Source(key, suffix.text(), group, settings.prefixImage(key), settings.prefixNote(key)));
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

    /** LuckPerms' groups' own prefixes, read (with their suffixes) at most every half minute. */
    public static Map<String, LuckPermsBridge.Prefix> groupPrefixes() {
        readGroups();
        return groupPrefixes;
    }

    /** LuckPerms' groups' own suffixes, read (with their prefixes) at most every half minute. */
    public static Map<String, LuckPermsBridge.Prefix> groupSuffixes() {
        readGroups();
        return groupSuffixes;
    }

    private static void readGroups() {
        long now = System.currentTimeMillis();
        if (now - groupsReadAt >= GROUPS_EVERY_MS) {
            groupsReadAt = now;
            Map<String, LuckPermsBridge.Prefix> prefixes = LuckPermsBridge.groupPrefixes(), suffixes = LuckPermsBridge.groupSuffixes();
            if (!prefixes.equals(groupPrefixes) || !suffixes.equals(groupSuffixes)) {
                groupPrefixes = prefixes;
                groupSuffixes = suffixes;
                indexedFor = null;
            }
        }
    }

    /** Builds the lookups again when the settings or LuckPerms' groups changed. */
    private static void index(MinecraftServer server) {
        readGroups();
        Settings settings = SettingsLedger.settings(server);
        if (settings == indexedFor) {
            return;
        }
        Map<String, Source> index = new LinkedHashMap<>();
        List<Source> prefixes = new ArrayList<>(), suffixes = new ArrayList<>();
        for (Source s : sources(settings, groupPrefixes, groupSuffixes)) {
            if (s.suffix()) {
                suffixes.add(s);
            } else {
                index.putIfAbsent(s.text(), s);
                prefixes.add(s);
            }
        }
        byText = index;
        prefixOrder = longestFirst(prefixes);
        suffixOrder = longestFirst(suffixes);
        SPLITS.clear();
        indexedFor = settings;
    }

    /** Known pieces with text, one per text (the first listed: titles before groups), the longest first. */
    public static List<Source> longestFirst(List<Source> sources) {
        Map<String, Source> unique = new LinkedHashMap<>();
        for (Source s : sources) {
            if (!Legacy.strip(s.text()).isBlank()) {
                unique.putIfAbsent(s.text(), s);
            }
        }
        List<Source> out = new ArrayList<>(unique.values());
        out.sort((a, b) -> Integer.compare(b.text().length(), a.text().length())); // stable: equal lengths keep their order
        return out;
    }

    /** The source for a whole prefix text, titles before group prefixes; null for a prefix RankUp does not list. */
    @Nullable
    public static Source sourceOf(MinecraftServer server, String text) {
        index(server);
        return byText.get(text);
    }

    /**
     * Splits a formatted prefix or suffix into runs (D95): at each place, the longest known text that starts there is
     * a known piece; characters no known text starts with gather into unknown runs (spacers, codes, other plugins' text).
     */
    public static List<Piece> split(String text, List<Source> known) {
        List<Piece> out = new ArrayList<>();
        StringBuilder other = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            Source found = null;
            for (Source s : known) {
                if (text.startsWith(s.text(), i)) {
                    found = s;
                    break;
                }
            }
            if (found == null) {
                other.append(text.charAt(i++));
                continue;
            }
            if (!other.isEmpty()) {
                out.add(new Piece(other.toString(), null));
                other.setLength(0);
            }
            out.add(new Piece(found.text(), found));
            i += found.text().length();
        }
        if (!other.isEmpty()) {
            out.add(new Piece(other.toString(), null));
        }
        return out;
    }

    /** The runs of a player's formatted prefix or suffix on this server, kept until the settings or groups change. */
    public static List<Piece> pieces(MinecraftServer server, String text, boolean suffix) {
        index(server);
        return SPLITS.computeIfAbsent((suffix ? "s" : "p") + text, k -> split(text, suffix ? suffixOrder : prefixOrder));
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

    // ------------------------------------------------------------------ the parts around the name

    /** The prefix text drawn before the player's name, or empty when nothing is (switched off, no LuckPerms, no prefix). */
    public static String shownText(ServerPlayer player) {
        if (!SettingsLedger.settings(player.server).showPrefixes()) {
            return "";
        }
        String text = LuckPermsBridge.shownPrefix(player.getUUID()).orElse("");
        return Legacy.strip(text).isBlank() ? "" : text;
    }

    /** The suffix text drawn after the player's name, or empty when nothing is (D89). */
    public static String shownSuffix(ServerPlayer player) {
        if (!SettingsLedger.settings(player.server).showPrefixes()) {
            return "";
        }
        String text = LuckPermsBridge.shownSuffix(player.getUUID()).orElse("");
        return Legacy.strip(text).isBlank() ? "" : text;
    }

    /** A formatted prefix as drawn before the name: its pieces, then a space (D96). */
    public static Component part(MinecraftServer server, String text) {
        return draw(pieces(server, text.stripLeading(), false), false);
    }

    /** A formatted suffix as drawn after the name: a space, then its pieces (D96). */
    public static Component suffixPart(MinecraftServer server, String text) {
        return draw(pieces(server, text.stripTrailing(), true), true);
    }

    /**
     * Pieces drawn: a known piece with an image as the image (its text as the fallback the console reads), anything
     * else as its coloured text. Two images with only spaces between them sit one pixel apart (the fallback keeps the
     * spaces); spaces after an image that no image follows stay real spaces. The name gets a space on its side (D96).
     */
    public static MutableComponent draw(List<Piece> pieces, boolean suffix) {
        MutableComponent out = Component.empty();
        if (suffix && !pieces.isEmpty() && !Character.isWhitespace(pieces.get(0).text().charAt(0))) {
            out.append(" ");
        }
        boolean endsInSpace = suffix;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            if (isImage(p)) {
                String core = p.text().stripTrailing(), trail = p.text().substring(core.length());
                boolean gapThenImage = i + 2 < pieces.size() && isGap(pieces.get(i + 1)) && isImage(pieces.get(i + 2));
                boolean imageNext = i + 1 < pieces.size() && isImage(pieces.get(i + 1));
                if (gapThenImage || imageNext) {
                    out.append(image(p.source(), core + trail + (gapThenImage ? pieces.get(++i).text() : "")));
                    endsInSpace = false;
                } else {
                    out.append(image(p.source(), core));
                    if (!trail.isEmpty()) {
                        out.append(trail);
                    }
                    endsInSpace = !trail.isEmpty();
                }
            } else {
                MutableComponent text = Legacy.parse(p.text());
                if (p.source() != null && !p.source().note().isEmpty()) {
                    text.withStyle(st -> st.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover(p.source()))));
                }
                out.append(text);
                endsInSpace = Character.isWhitespace(p.text().charAt(p.text().length() - 1));
            }
        }
        if (!suffix && !endsInSpace) {
            out.append(" ");
        }
        return out;
    }

    private static boolean isImage(Piece p) {
        return p.source() != null && p.source().image() != null;
    }

    private static boolean isGap(Piece p) {
        return p.source() == null && p.text().isBlank();
    }

    /** An image piece: the key names the image, the fallback is the coloured text (D73); hovering shows the text, the rank and its note. */
    private static Component image(Source source, String fallback) {
        // the fallback is "%s" filled with the coloured text, so a client without the image and the console see the prefix
        return Component.translatableWithFallback(KEY + source.image().id(), "%s", Legacy.parse(fallback))
                .withStyle(s -> s.withFont(FONT).withColor(0xFFFFFF).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover(source))));
    }

    private static Component hover(Source source) {
        MutableComponent hover = Legacy.parse(source.text().strip()).append(Component.literal(" " + source.rankName()).withStyle(s -> s.withColor(0xAAAAAA)));
        if (!source.note().isEmpty()) {
            hover.append(Component.literal("\n" + source.note()).withStyle(s -> s.withColor(0xAAAAAA)));
        }
        return hover;
    }

    // ------------------------------------------------------------------ events

    /** Chat and anything else that asks for the display name (D67). */
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String text = shownText(player), suffix = shownSuffix(player);
            SHOWN.put(player.getUUID(), drawnWith(player, text, suffix));
            if (!text.isEmpty() || !suffix.isEmpty()) {
                event.setDisplayname(around(player.server, text, event.getDisplayname(), suffix));
            }
        }
    }

    /** The tab list: the prefix, the name in its team's colour as vanilla draws it, the suffix. */
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String text = shownText(player), suffix = shownSuffix(player);
            if (!text.isEmpty() || !suffix.isEmpty()) {
                Component name = event.getDisplayName() != null ? event.getDisplayName() : PlayerTeam.formatNameForTeam(player.getTeam(), player.getName());
                event.setDisplayName(around(player.server, text, name, suffix));
            }
        }
    }

    private static Component around(MinecraftServer server, String prefix, Component name, String suffix) {
        MutableComponent out = Component.empty();
        if (!prefix.isEmpty()) {
            out.append(part(server, prefix));
        }
        out.append(name);
        if (!suffix.isEmpty()) {
            out.append(suffixPart(server, suffix));
        }
        return out;
    }

    /** Every five seconds: a player whose prefix changed (a new title, a group's prefix edited) is drawn again. */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % CHECK_TICKS != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        Map<String, LuckPermsBridge.Prefix> groupsBefore = groupPrefixes, suffixesBefore = groupSuffixes;
        readGroups();
        boolean changed = groupsBefore != groupPrefixes || suffixesBefore != groupSuffixes;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String text = shownText(p), suffix = shownSuffix(p);
            String now = drawnWith(p, text, suffix);
            String was = SHOWN.get(p.getUUID());
            if (was == null || !was.equals(now) || changed && !(text.isEmpty() && suffix.isEmpty())) {
                refresh(p);
            }
        }
    }

    /**
     * The prefix and suffix text and the player's team (name, colour, its prefix and suffix): the tab list name is
     * drawn with the team's formatting fixed in, so joining or leaving a team, or the team's colour changing, draws it
     * again.
     */
    private static String drawnWith(ServerPlayer player, String text, String suffix) {
        String t = player.getTeam() instanceof PlayerTeam team ? team.getName() + "/" + team.getColor() + "/" + team.getPlayerPrefix().getString() + "/" + team.getPlayerSuffix().getString() : "";
        return text + "\n" + suffix + "\n" + t;
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
