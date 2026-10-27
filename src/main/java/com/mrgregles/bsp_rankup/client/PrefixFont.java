package com.mrgregles.bsp_rankup.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.prefix.Legacy;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay;
import com.mrgregles.bsp_rankup.prefix.PrefixImages;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Unit;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Prefix images on the client (owner decisions D60 to D62). Each image the server sends gets a character of Unicode's
 * private use area; {@link PrefixPack} serves the font {@code bsp_rankup:prefix} with one bitmap per image (one text
 * line high) and then the default font for every other character. When images arrive the fonts alone are reloaded
 * (not the whole resource reload), then the game's language is wrapped so the server's image key
 * ({@value PrefixDisplay#KEY}{@code <id>}) reads as that character. Until then, and for any image this client does
 * not have, the key falls back to the prefix's text.
 */
public final class PrefixFont {
    /** The first private-use character handed out. */
    private static final int FIRST = 0xE000, LAST = 0xF8FF;
    /** Ticks without a new image before the fonts are reloaded, so a login's images load in one go. */
    private static final int QUIET_TICKS = 10;
    /** Drawn one text line high, the top one pixel above capitals (D60). */
    static final int HEIGHT = PrefixImages.LINE, ASCENT = 8;

    private static final Map<String, byte[]> IMAGES = new LinkedHashMap<>();
    private static final Map<String, Integer> CHARS = new HashMap<>();
    private static int next = FIRST;
    /** The images the font file listed when it was last read (a reload's preparation, any thread). */
    private static volatile Map<String, Integer> listed = Map.of();
    /** The images the font draws now, by key: what the language wrapper answers. */
    private static Map<String, String> drawn = Map.of();
    private static boolean wanted, reloading;
    private static int quiet;

    private PrefixFont() {
    }

    /** An image from the server (main thread). Ignored when already known, malformed, or past the limits. */
    public static void receive(String id, byte[] png) {
        if (IMAGES.containsKey(id) || !id.matches("[0-9a-f]{40}") || png.length < 24 || next > LAST) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(png);
        int w = buf.getInt(16), h = buf.getInt(20);
        if (w <= 0 || h <= 0 || w > PrefixImages.MAX_WIDTH || h > PrefixImages.MAX_HEIGHT) {
            return;
        }
        synchronized (IMAGES) {
            IMAGES.put(id, png);
            CHARS.put(id, next++);
        }
        wanted = true;
        quiet = 0;
    }

    /** Each client tick: keeps the language wrapped (a full resource reload replaces it) and reloads the fonts when images came. */
    static void tick() {
        if (!(Language.getInstance() instanceof Wrapped)) {
            wrap();
        }
        if (wanted && !reloading && ++quiet >= QUIET_TICKS) {
            reload();
        }
    }

    private static void reload() {
        wanted = false;
        reloading = true;
        Minecraft mc = Minecraft.getInstance();
        FontManager fonts;
        try {
            fonts = ObfuscationReflectionHelper.getPrivateValue(Minecraft.class, mc, "f_91045_");
        } catch (RuntimeException e) {
            BSPRankUp.LOGGER.warn("RankUp could not reach the font manager; prefix images show after the next resource reload (F3+T): {}", e.toString());
            reloading = false;
            return;
        }
        SimpleReloadInstance.create(mc.getResourceManager(), List.of(fonts), Util.backgroundExecutor(), mc, CompletableFuture.completedFuture(Unit.INSTANCE), false)
                .done().whenComplete((v, err) -> mc.execute(() -> {
                    reloading = false;
                    if (err != null) {
                        BSPRankUp.LOGGER.warn("RankUp prefix images: the font reload failed: {}", err.toString());
                    }
                    wrap();
                }));
    }

    /** Wraps the current language again with the images the font now has (a new wrapper clears translations already worked out). */
    private static void wrap() {
        Language base = Language.getInstance();
        if (base instanceof Wrapped w) {
            base = w.base;
        }
        Map<String, String> glyphs = new HashMap<>();
        listed.forEach((id, c) -> glyphs.put(PrefixDisplay.KEY + id, Character.toString(c)));
        drawn = glyphs;
        Language.inject(new Wrapped(base, glyphs));
    }

    /** Whether the font draws this image now. */
    public static boolean has(String id) {
        return drawn.containsKey(PrefixDisplay.KEY + id);
    }

    /** A prefix as the server draws it, for RankUp's own screens: the image, or the coloured text when there is none (D61). */
    public static MutableComponent component(String id, String text) {
        MutableComponent coloured = Legacy.parse(text.strip());
        if (id.isEmpty()) {
            return coloured;
        }
        return Component.translatableWithFallback(PrefixDisplay.KEY + id, "%s", coloured).withStyle(s -> s.withFont(PrefixDisplay.FONT).withColor(0xFFFFFF));
    }

    // ------------------------------------------------------------------ the pack's font file

    /** The font file: one bitmap per image, then the default font. Records what it listed. */
    static String fontJson() {
        Map<String, Integer> now;
        synchronized (IMAGES) {
            now = Map.copyOf(CHARS);
        }
        JsonArray providers = new JsonArray();
        now.forEach((id, c) -> {
            JsonObject bitmap = new JsonObject();
            bitmap.addProperty("type", "bitmap");
            bitmap.addProperty("file", BSPRankUp.MODID + ":prefix/" + id + ".png");
            bitmap.addProperty("height", HEIGHT);
            bitmap.addProperty("ascent", ASCENT);
            JsonArray chars = new JsonArray();
            chars.add(Character.toString(c));
            bitmap.add("chars", chars);
            providers.add(bitmap);
        });
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "reference");
        fallback.addProperty("id", "minecraft:default");
        providers.add(fallback);
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        listed = now;
        return font.toString();
    }

    @Nullable
    static byte[] png(String id) {
        synchronized (IMAGES) {
            return IMAGES.get(id);
        }
    }

    /** The game's language with the images' keys answered by their characters; everything else goes to the language it wraps. */
    static final class Wrapped extends Language {
        final Language base;
        private final Map<String, String> glyphs;

        Wrapped(Language base, Map<String, String> glyphs) {
            this.base = base;
            this.glyphs = glyphs;
        }

        @Override
        public String getOrDefault(String key, String fallback) {
            String glyph = glyphs.get(key);
            return glyph != null ? glyph : base.getOrDefault(key, fallback);
        }

        @Override
        public boolean has(String key) {
            return glyphs.containsKey(key) || base.has(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return base.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return base.getVisualOrder(text);
        }

        @Override
        public Map<String, String> getLanguageData() {
            return base.getLanguageData();
        }
    }
}
