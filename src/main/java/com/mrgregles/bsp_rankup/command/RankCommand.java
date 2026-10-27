package com.mrgregles.bsp_rankup.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /rank} opens the player's rank screen (owner decision D39); {@code /rank text} prints the same as chat
 * lines; {@code /rank <player>} prints another player's for admins.
 */
public final class RankCommand {
    private RankCommand() {
    }

    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("rank")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    com.mrgregles.bsp_rankup.network.RankSync.open(player);
                    return 1;
                })
                .then(Commands.literal("text").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    for (Component line : summary(player.server, player.getUUID())) {
                        ctx.getSource().sendSuccess(() -> line, false);
                    }
                    return 1;
                }))
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(AdminCommands::isAdmin)
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            Optional<UUID> who = Players.find(server, StringArgumentType.getString(ctx, "player"));
                            if (who.isEmpty()) {
                                ctx.getSource().sendFailure(Component.literal("No player called " + StringArgumentType.getString(ctx, "player")));
                                return 0;
                            }
                            for (Component line : summary(server, who.get())) {
                                ctx.getSource().sendSuccess(() -> line, false);
                            }
                            return 1;
                        })));
    }

    /**
     * The lines {@code /rank} shows: with one ladder its rank, points and next rung; with several (D52) one line per
     * ladder. Then every signal that counts, the awards reached, the bonus and what waits.
     */
    public static List<Component> summary(MinecraftServer server, UUID player) {
        Settings settings = SettingsLedger.settings(server);
        Progress progress = ProgressLedger.get(server).peek(player);
        if (progress == null) {
            progress = new Progress();
        }
        String name = Players.name(server, player);
        if (settings.ladders().size() == 1) {
            List<Component> out = oneLadder(settings, progress, name);
            awardsLine(settings, progress, out);
            return out;
        }
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("message.bsp_rankup.rank.title", name).withStyle(ChatFormatting.AQUA));
        for (Ladder ladder : settings.ladders()) {
            long points = RankEngine.points(settings, ladder, progress);
            int current = ladder.indexOf(RankEngine.rankOn(settings, ladder, progress));
            MutableComponent line = current < 0
                    ? Component.translatable("message.bsp_rankup.rank.ladder_none", RankEngine.label(ladder), points)
                    : Component.translatable("message.bsp_rankup.rank.ladder", RankEngine.label(ladder), ladder.ranks().get(current).displayName(), current + 1, ladder.ranks().size(), points);
            if (current + 1 < ladder.ranks().size()) {
                Rank next = ladder.ranks().get(current + 1);
                long have = next.seasonal() ? RankEngine.seasonPoints(settings, ladder, progress) : points;
                line.append(Component.translatable("message.bsp_rankup.rank.ladder_next", next.displayName(), next.points(), Math.max(0, next.points() - have)));
            } else if (!ladder.ranks().isEmpty()) {
                line.append(Component.translatable("message.bsp_rankup.rank.ladder_top"));
            }
            out.add(line.withStyle(ChatFormatting.AQUA));
        }
        signalLines(settings, progress, out);
        awardsLine(settings, progress, out);
        tail(progress, out);
        return out;
    }

    /** The awards the player reached, by the names players see. */
    private static void awardsLine(Settings settings, Progress progress, List<Component> out) {
        List<String> names = new ArrayList<>();
        for (String group : progress.awards().keySet()) {
            Award a = settings.award(group);
            names.add(a == null ? group : a.displayName());
        }
        if (!names.isEmpty()) {
            out.add(Component.translatable("message.bsp_rankup.rank.awards", String.join(", ", names)).withStyle(ChatFormatting.GOLD));
        }
    }

    private static void signalLines(Settings settings, Progress progress, List<Component> out) {
        for (Signal s : Signal.values()) {
            SignalSetting setting = settings.signal(s);
            long count = progress.count(s);
            if (!setting.on() || (count == 0 && setting.points() == 0)) {
                continue;
            }
            MutableComponent line = Component.translatable("message.bsp_rankup.rank.signal", Component.translatable("signal.bsp_rankup." + s.key()), count, setting.pointsFor(count));
            out.add(line.withStyle(ChatFormatting.GRAY));
        }
    }

    private static void tail(Progress progress, List<Component> out) {
        if (progress.bonus() != 0) {
            out.add(Component.translatable("message.bsp_rankup.rank.bonus", progress.bonus()).withStyle(ChatFormatting.GRAY));
        }
        if (!progress.pending().isEmpty()) {
            out.add(Component.translatable("message.bsp_rankup.rank.pending", progress.pending().size()).withStyle(ChatFormatting.YELLOW));
        }
    }

    /** The lines as they were with one ladder. */
    private static List<Component> oneLadder(Settings settings, Progress progress, String name) {
        long points = RankEngine.points(settings, progress);
        int current = settings.indexOf(progress.rank());
        List<Component> out = new ArrayList<>();
        if (current < 0) {
            out.add(Component.translatable("message.bsp_rankup.rank.none", name, points).withStyle(ChatFormatting.AQUA));
        } else {
            Rank rank = settings.ranks().get(current);
            out.add(Component.translatable("message.bsp_rankup.rank.header", name, rank.displayName(), current + 1, settings.ranks().size(), points).withStyle(ChatFormatting.AQUA));
        }
        if (current + 1 < settings.ranks().size()) {
            Rank next = settings.ranks().get(current + 1);
            out.add(Component.translatable("message.bsp_rankup.rank.next", next.displayName(), next.points(), Math.max(0, next.points() - points)).withStyle(ChatFormatting.GRAY));
        } else if (!settings.ranks().isEmpty()) {
            out.add(Component.translatable("message.bsp_rankup.rank.top").withStyle(ChatFormatting.GOLD));
        }
        signalLines(settings, progress, out);
        if (settings.ranks().stream().anyMatch(Rank::seasonal) && progress.seasonBase() != 0) {
            out.add(Component.translatable("message.bsp_rankup.rank.season", RankEngine.seasonPoints(settings, progress)).withStyle(ChatFormatting.GRAY));
        }
        tail(progress, out);
        return out;
    }

    /** True for the console and for BSP Core admins. */
    static boolean canSee(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer p ? BspCore.isAdmin(p) : source.hasPermission(2);
    }
}
