package com.mrgregles.bsp_rankup.settings;

import net.minecraft.nbt.CompoundTag;

/**
 * One thing a rank gives when reached (owner decisions D9, D24): Shatter Coins into the hand ({@code amount} in
 * Copper value), Shatter Pieces into the bank, an item ({@code value} is the item id, {@code amount} the count),
 * a title (a LuckPerms prefix, {@code value} the text), a console command ({@code value}, with {@code %player%}
 * replaced by the player's name), or a BSP-Core kit ({@code value} the kit id; BSP-Core owes it to the player and
 * delivers it when it fits, so it needs no online check).
 */
public record Reward(Kind kind, String value, long amount) {
    /** NOTE is not a reward an admin gives: it is a line kept for the player's next login (what they reached while away, D119). */
    public enum Kind { COINS, PIECES, ITEM, TITLE, COMMAND, KIT, NOTE }

    public static Reward coins(long copperValue) {
        return new Reward(Kind.COINS, "", copperValue);
    }

    public static Reward pieces(long pieces) {
        return new Reward(Kind.PIECES, "", pieces);
    }

    public static Reward item(String itemId, int count) {
        return new Reward(Kind.ITEM, itemId, count);
    }

    public static Reward title(String text) {
        return new Reward(Kind.TITLE, text, 0);
    }

    public static Reward command(String command) {
        return new Reward(Kind.COMMAND, command, 0);
    }

    public static Reward kit(String kitId) {
        return new Reward(Kind.KIT, kitId, 0);
    }

    /** A rung reached while away, told at the next login (D119): its name and, with several ladders, the ladder's. */
    public static Reward reachedNote(String rankName, String ladderLabel) {
        return new Reward(Kind.NOTE, "rank\t" + rankName + (ladderLabel.isEmpty() ? "" : "\t" + ladderLabel), 0);
    }

    /** An award earned while away, told at the next login (D119). */
    public static Reward earnedNote(String awardName) {
        return new Reward(Kind.NOTE, "award\t" + awardName, 0);
    }

    /** True for rewards that need the player on this server to be handed over (coins, items, commands, the login notes). */
    public boolean needsPlayerOnline() {
        return kind == Kind.COINS || kind == Kind.ITEM || kind == Kind.COMMAND || kind == Kind.NOTE;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.name());
        if (!value.isEmpty()) {
            tag.putString("Value", value);
        }
        if (amount != 0) {
            tag.putLong("Amount", amount);
        }
        return tag;
    }

    public static Reward load(CompoundTag tag) {
        Kind kind;
        try {
            kind = Kind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException e) {
            kind = Kind.COMMAND;
        }
        return new Reward(kind, tag.getString("Value"), tag.getLong("Amount"));
    }
}
