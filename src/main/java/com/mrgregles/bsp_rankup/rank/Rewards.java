package com.mrgregles.bsp_rankup.rank;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hands a rank's rewards to a player (owner decisions D9, D24). Coins, items and commands need the player on
 * this server and are kept in the ledger as pending when they are not (given on their next login here); pieces
 * go into the bank and titles into LuckPerms whether or not the player is online.
 */
public final class Rewards {
    private Rewards() {
    }

    /** Gives what can be given now, pends the rest, and tells the player what they got. Returns the rewards given now. */
    public static List<Reward> give(MinecraftServer server, UUID player, Rank rank, Settings settings) {
        Ladder ladder = settings.ladderOf(rank.group());
        return give(server, player, rank.displayName(), rank.rewards(), settings, ladder == null ? settings.titlePriority() : ladder.titlePriority());
    }

    public static List<Reward> give(MinecraftServer server, UUID player, String forWhat, List<Reward> rewards, Settings settings) {
        return give(server, player, forWhat, rewards, settings, settings.titlePriority());
    }

    /**
     * Gives what can be given now, pends the rest, and tells the player what they got; a TITLE goes to LuckPerms at
     * {@code titlePriority} (each ladder has its own, D51), and a pended TITLE keeps that priority in its amount.
     */
    public static List<Reward> give(MinecraftServer server, UUID player, String forWhat, List<Reward> rewards, Settings settings, int titlePriority) {
        ServerPlayer online = Players.online(server, player);
        List<Reward> given = new ArrayList<>();
        List<Reward> pending = new ArrayList<>();
        for (Reward r : rewards) {
            if (r.needsPlayerOnline() && online == null) {
                pending.add(r.kind() == Reward.Kind.TITLE && r.amount() == 0 ? new Reward(Reward.Kind.TITLE, r.value(), titlePriority) : r);
            } else if (apply(server, player, online, r, forWhat, titlePriority)) {
                given.add(r);
            }
        }
        if (!pending.isEmpty()) {
            ProgressLedger.get(server).addPending(player, pending);
        }
        if (online != null && !given.isEmpty()) {
            for (Reward r : given) {
                online.sendSystemMessage(describe(r));
            }
        }
        return given;
    }

    /** Gives the rewards kept for the player while they were away. */
    public static List<Reward> givePending(ServerPlayer player, Settings settings) {
        List<Reward> pending = ProgressLedger.get(player.server).takePending(player.getUUID());
        if (pending.isEmpty()) {
            return pending;
        }
        player.sendSystemMessage(Component.translatable("message.bsp_rankup.reward.kept"));
        return give(player.server, player.getUUID(), "earlier", pending, settings);
    }

    private static boolean apply(MinecraftServer server, UUID player, @Nullable ServerPlayer online, Reward r, String forWhat, int titlePriority) {
        try {
            switch (r.kind()) {
                case COINS -> {
                    if (online == null) {
                        return false;
                    }
                    BspCore.giveCoins(online, r.amount());
                }
                case PIECES -> BspCore.creditPieces(server, player, r.amount(), "Rank " + forWhat, ok -> {
                    if (!ok) {
                        BSPRankUp.LOGGER.warn("Bank refused {} pieces for {} ({})", r.amount(), player, forWhat);
                    }
                });
                case ITEM -> {
                    if (online == null) {
                        return false;
                    }
                    ItemStack stack = stack(r);
                    if (stack.isEmpty()) {
                        BSPRankUp.LOGGER.warn("Reward item {} does not exist; skipped for {}", r.value(), player);
                        return false;
                    }
                    if (!online.getInventory().add(stack)) {
                        online.drop(stack, false);
                    }
                }
                case TITLE -> {
                    if (LuckPermsBridge.isPresent()) {
                        LuckPermsBridge.setPrefix(player, r.amount() != 0 ? (int) r.amount() : titlePriority, r.value());
                    }
                }
                case COMMAND -> {
                    if (online == null) {
                        return false;
                    }
                    CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
                    String command = r.value().replace("%player%", online.getGameProfile().getName());
                    server.getCommands().performPrefixedCommand(source, command);
                }
                case KIT -> {
                    if (!BspCore.giveKit(server, player, r.value())) {
                        BSPRankUp.LOGGER.warn("Kit {} is unknown to BSP-Core (or this BSP-Core has no kits); skipped for {}", r.value(), player);
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException e) {
            BSPRankUp.LOGGER.warn("Reward {} failed for {}: {}", r, player, e.toString());
            return false;
        }
    }

    /** The item stack a reward names, empty when the id is unknown. */
    public static ItemStack stack(Reward r) {
        ResourceLocation id = ResourceLocation.tryParse(r.value());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, (int) Math.max(1, Math.min(r.amount(), item.getMaxStackSize() * 36L)));
    }

    /** The kit's name as BSP-Core knows it, or the id. Needs a server; the chat line is built on the server thread. */
    private static String kitName(String id) {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (BspCore.KitInfo k : BspCore.kits(server)) {
                if (k.id().equals(id)) {
                    return k.name();
                }
            }
        }
        return id;
    }

    /** One line for the player: what a reward is. */
    public static Component describe(Reward r) {
        return switch (r.kind()) {
            case COINS -> Component.translatable("message.bsp_rankup.reward.coins", r.amount());
            case PIECES -> Component.translatable("message.bsp_rankup.reward.pieces", BspCore.formatPieces(r.amount()));
            case ITEM -> {
                ItemStack stack = stack(r);
                yield Component.translatable("message.bsp_rankup.reward.item", r.amount(), stack.isEmpty() ? Component.literal(r.value()) : stack.getHoverName());
            }
            case TITLE -> Component.translatable("message.bsp_rankup.reward.title", com.mrgregles.bsp_rankup.prefix.Legacy.parse(r.value().strip())); // in its colours, not its & codes
            case COMMAND -> Component.translatable("message.bsp_rankup.reward.command");
            case KIT -> Component.translatable("message.bsp_rankup.reward.kit", kitName(r.value()));
        };
    }
}
