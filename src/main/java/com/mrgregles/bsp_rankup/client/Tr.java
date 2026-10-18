package com.mrgregles.bsp_rankup.client;

import net.minecraft.client.resources.language.I18n;

/**
 * Screen text from the language file ({@code assets/bsp_rankup/lang/en_us.json}, written by {@code tools/gen_lang.py}).
 * Keys are {@code gui.bsp_rankup.<key>}; values use {@code %s} for the parts filled in. {@code tools/check_lang.py}
 * checks that every key used here exists.
 */
final class Tr {
    private static final String PREFIX = "gui.bsp_rankup.";

    private Tr() {
    }

    static String t(String key, Object... args) {
        return I18n.get(PREFIX + key, args);
    }

    /** {@code key.one} for 1, {@code key.many} otherwise; the number is the first value. */
    static String n(String key, long count, Object... more) {
        Object[] args = new Object[more.length + 1];
        args[0] = count;
        System.arraycopy(more, 0, args, 1, more.length);
        return I18n.get(PREFIX + key + (count == 1 ? ".one" : ".many"), args);
    }
}
