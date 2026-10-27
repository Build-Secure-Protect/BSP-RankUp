package com.mrgregles.bsp_rankup.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.RankUpClientConfig;
import com.mrgregles.bsp_rankup.RankUpConfig;
import com.mrgregles.bsp_rankup.network.RankActionPacket;
import com.mrgregles.bsp_rankup.network.RankUpNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only registrations (owner decision D39): the "open your rank" key, the button in the inventory screen, and
 * the client state. Never referenced from common code.
 */
@Mod.EventBusSubscriber(modid = BSPRankUp.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    public static final KeyMapping OPEN_RANK = new KeyMapping("key.bsp_rankup.open_rank", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.bsp_rankup");

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_RANK);
    }

    /** The prefix images' font, as a hidden pack that is always on (D62). */
    @SubscribeEvent
    public static void onAddPacks(net.minecraftforge.event.AddPackFindersEvent event) {
        if (event.getPackType() == net.minecraft.server.packs.PackType.CLIENT_RESOURCES) {
            event.addRepositorySource(add -> add.accept(PrefixPack.pack()));
        }
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onScreenInit);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLoggingOut);
    }

    public static void openRank() {
        RankUpNetwork.CHANNEL.sendToServer(new RankActionPacket(RankActionPacket.OPEN));
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (OPEN_RANK.consumeClick()) {
            openRank();
        }
        PrefixFont.tick();
    }

    /** The RankUp button in the survival inventory (its place is a client setting, so it can move off other mods' buttons). */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen inv) || !RankUpConfig.getOr(RankUpClientConfig.INVENTORY_BUTTON, true)) {
            return;
        }
        int dx = RankUpConfig.getOr(RankUpClientConfig.INVENTORY_BUTTON_X, 128);
        int dy = RankUpConfig.getOr(RankUpClientConfig.INVENTORY_BUTTON_Y, 61);
        Button.Builder builder = Button.builder(Component.literal(Tr.t("inventory_button")), b -> openRank())
                .bounds(inv.getGuiLeft() + dx, inv.getGuiTop() + dy, 20, 14)
                .tooltip(Tooltip.create(Component.literal(Tr.t("your_rank_tooltip"))));
        event.addListener(new InventoryButton(builder, inv, dx, dy));
    }

    /**
     * The inventory button, kept on the inventory's corner: opening or closing the recipe book moves the inventory
     * without telling other mods' buttons, so the place is taken again every frame.
     */
    private static final class InventoryButton extends Button {
        private final InventoryScreen inv;
        private final int dx, dy;

        InventoryButton(Button.Builder builder, InventoryScreen inv, int dx, int dy) {
            super(builder);
            this.inv = inv;
            this.dx = dx;
            this.dy = dy;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            setPosition(inv.getGuiLeft() + dx, inv.getGuiTop() + dy);
            super.renderWidget(g, mouseX, mouseY, partial);
        }
    }

    private static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        RankUpClient.clear();
    }
}
