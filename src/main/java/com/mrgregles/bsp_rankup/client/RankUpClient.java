package com.mrgregles.bsp_rankup.client;

import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.network.RankViewPacket;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/** Client-side state for RankUp's screens: the latest view the server sent. Never referenced from common code. */
public final class RankUpClient {
    @Nullable
    private static AdminViewPacket adminView;
    @Nullable
    private static RankViewPacket rankView;

    private RankUpClient() {
    }

    @Nullable
    public static AdminViewPacket adminView() {
        return adminView;
    }

    public static void receive(AdminViewPacket msg) {
        adminView = msg;
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

    public static void receive(RankViewPacket msg) {
        rankView = msg;
        Minecraft mc = Minecraft.getInstance();
        if (msg.open()) {
            mc.setScreen(new RankScreen());
        }
    }

    public static void clear() {
        adminView = null;
        rankView = null;
    }
}
