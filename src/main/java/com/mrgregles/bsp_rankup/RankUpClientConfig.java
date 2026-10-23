package com.mrgregles.bsp_rankup;

import net.minecraftforge.common.ForgeConfigSpec;

/** Settings for this player's own game (config/bsp_rankup-client.toml), not the server's. */
public final class RankUpClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue UI_SCALE;
    public static final ForgeConfigSpec.IntValue INVENTORY_BUTTON_X, INVENTORY_BUTTON_Y;
    public static final ForgeConfigSpec.BooleanValue INVENTORY_BUTTON;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("ui");
        UI_SCALE = b.comment("How big RankUp's screens are drawn: 0 fills the window (the default, owner decision D29), 1 is the original size,",
                "2 twice as big. A screen that would not fit the window is drawn as large as fits.").defineInRange("scale", 0.0, 0.0, 4.0);
        INVENTORY_BUTTON = b.comment("A RankUp button in the inventory screen that opens your rank screen.").define("inventoryButton", true);
        INVENTORY_BUTTON_X = b.comment("Where the inventory button sits, from the inventory's top-left corner (move it off other mods' buttons).").defineInRange("inventoryButtonX", 128, -200, 400);
        INVENTORY_BUTTON_Y = b.defineInRange("inventoryButtonY", 61, -200, 400);
        b.pop();
        SPEC = b.build();
    }

    private RankUpClientConfig() {
    }

    /**
     * The scale a screen of {@code baseW} by {@code baseH} units is drawn at in a window of {@code w} by {@code h} GUI
     * units: as large as fits with {@code margin} to spare when {@code want} is 0, else {@code want} capped to what
     * fits; never below 1, unless the window is too small for the original size (1920 x 1080 at GUI scale 4 is 480 x
     * 270 units): then exactly as small as fits, so no edge is cut off.
     */
    public static float screenScale(double want, int w, int h, int baseW, int baseH, float margin) {
        float fit = Math.max(MIN_SCALE, Math.min((w - margin) / baseW, (h - margin) / baseH));
        return Math.max(Math.min(1, fit), want <= 0 ? fit : Math.min((float) want, fit));
    }

    /** The smallest a screen is ever drawn (Minecraft's smallest window, 320 x 240 units, needs about 0.6). */
    private static final float MIN_SCALE = 0.25f;
}
