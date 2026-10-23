package com.mrgregles.bsp_rankup.client;

import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.network.RankViewPacket;
import com.mrgregles.bsp_rankup.settings.Settings;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side state for RankUp's screens: the latest view the server sent, and its settings read once when it
 * arrives (the screens draw from them every frame). Never referenced from common code.
 */
public final class RankUpClient {
    @Nullable
    private static AdminViewPacket adminView;
    @Nullable
    private static Settings adminSettings;
    @Nullable
    private static RankViewPacket rankView;
    @Nullable
    private static Settings rankSettings;

    private RankUpClient() {
    }

    @Nullable
    public static AdminViewPacket adminView() {
        return adminView;
    }

    /** The settings of {@link #adminView()}, read when it arrived. */
    @Nullable
    public static Settings adminSettings() {
        return adminSettings;
    }

    public static void receive(AdminViewPacket msg) {
        adminView = msg;
        adminSettings = Settings.load(msg.settings());
        Minecraft mc = Minecraft.getInstance();
        if (msg.open()) {
            mc.setScreen(new AdminScreen());
        } else if (mc.screen instanceof AdminScreen screen) {
            screen.onView(msg);
        }
    }

    @Nullable
    public static RankViewPacket rankView() {
        return rankView;
    }

    /** The settings of {@link #rankView()}, read when it arrived. */
    @Nullable
    public static Settings rankSettings() {
        return rankSettings;
    }

    public static void receive(RankViewPacket msg) {
        rankView = msg;
        rankSettings = Settings.load(msg.settings());
        Minecraft mc = Minecraft.getInstance();
        if (msg.open()) {
            mc.setScreen(new RankScreen());
        }
    }

    public static void clear() {
        adminView = null;
        adminSettings = null;
        rankView = null;
        rankSettings = null;
    }
}
