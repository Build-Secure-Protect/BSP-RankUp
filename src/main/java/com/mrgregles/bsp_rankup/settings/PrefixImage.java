package com.mrgregles.bsp_rankup.settings;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * An image drawn in place of a prefix (owner decisions D55 to D74): {@code id} is the image's content hash (the shared
 * store keeps the bytes under it), {@code file} the name it had in the images folder when it was picked, and its size in
 * pixels. Images are tied to a rank, not to the prefix text (D57), by a key: {@link #titleKey} for a RankUp title (the
 * TITLE reward of a rung or an award) and {@link #groupKey} for a LuckPerms group's own prefix.
 */
public record PrefixImage(String id, String file, int width, int height) {
    public static final String TITLE = "title:", GROUP = "group:";

    public static String titleKey(String group) {
        return TITLE + group.toLowerCase(Locale.ROOT);
    }

    public static String groupKey(String group) {
        return GROUP + group.toLowerCase(Locale.ROOT);
    }

    /** True for a key {@link #titleKey} or {@link #groupKey} made. */
    public static boolean isKey(String key) {
        return (key.startsWith(TITLE) || key.startsWith(GROUP)) && key.indexOf(':') < key.length() - 1;
    }

    /** The group a key names. */
    public static String groupOf(String key) {
        return key.substring(key.indexOf(':') + 1);
    }

    public CompoundTag save(String key) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Key", key);
        tag.putString("Id", id);
        tag.putString("File", file);
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        return tag;
    }

    public static PrefixImage load(CompoundTag tag) {
        return new PrefixImage(tag.getString("Id"), tag.getString("File"), tag.getInt("Width"), tag.getInt("Height"));
    }
}
