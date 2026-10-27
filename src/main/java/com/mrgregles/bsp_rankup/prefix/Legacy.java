package com.mrgregles.bsp_rankup.prefix;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * LuckPerms prefixes as chat text (D67): {@code &} or {@code §} colour and format codes ({@code &b}, {@code &l},
 * {@code &r}), hex colours as {@code &#RRGGBB} or Bukkit's {@code &x&R&R&G&G&B&B}. A colour code clears the formats
 * before it, as in Minecraft's own legacy text.
 */
public final class Legacy {
    private Legacy() {
    }

    public static MutableComponent parse(String text) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                TextColor hex = hexAt(text, i);
                if (hex != null) {
                    flush(out, run, style);
                    style = Style.EMPTY.withColor(hex);
                    i += text.charAt(i + 1) == '#' ? 8 : 14;
                    continue;
                }
                ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                if (f != null) {
                    flush(out, run, style);
                    style = f == ChatFormatting.RESET ? Style.EMPTY : f.isColor() ? Style.EMPTY.withColor(f) : style.applyFormat(f);
                    i += 2;
                    continue;
                }
            }
            run.append(c);
            i++;
        }
        flush(out, run, style);
        return out;
    }

    /** The text without its codes (console lines, hover text, comparisons). */
    public static String strip(String text) {
        return parse(text).getString();
    }

    private static void flush(MutableComponent out, StringBuilder run, Style style) {
        if (!run.isEmpty()) {
            out.append(Component.literal(run.toString()).setStyle(style));
            run.setLength(0);
        }
    }

    /** A hex colour code starting at {@code i}, or null. */
    private static TextColor hexAt(String text, int i) {
        char next = text.charAt(i + 1);
        if (next == '#' && i + 8 <= text.length()) {
            return hex(text.substring(i + 2, i + 8));
        }
        if ((next == 'x' || next == 'X') && i + 14 <= text.length()) {
            StringBuilder digits = new StringBuilder();
            for (int k = 0; k < 6; k++) {
                char code = text.charAt(i + 2 + k * 2);
                if (code != '&' && code != '§') {
                    return null;
                }
                digits.append(text.charAt(i + 3 + k * 2));
            }
            return hex(digits.toString());
        }
        return null;
    }

    private static TextColor hex(String digits) {
        return digits.matches("[0-9a-fA-F]{6}") ? TextColor.fromRgb(Integer.parseInt(digits, 16)) : null;
    }
}
