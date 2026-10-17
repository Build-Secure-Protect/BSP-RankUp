package com.mrgregles.bsp_rankup.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.rank.Rewards;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * {@code /rankup}: everything an admin does until the admin screen exists, and the commands behind it afterwards.
 * Players: {@code set, add, reset}. The ladder: {@code ladder add|remove|list|points|name|seasonal|move <rung>|import}.
 * Rewards: {@code reward add|list|remove|clear}. Signals: {@code signal <signal> on|off|points <n> per <n>}.
 * Options: {@code option track|announce|cooldown|titlepriority|rewardsagain}. Files: {@code export, import}.
 * {@code info}, {@code reload}. Replies are plain English (server text is not translated, as in Region Guard).
 */
public final class AdminCommands {
    private static final SuggestionProvider<CommandSourceStack> GROUPS = (ctx, b) ->
            SharedSuggestionProvider.suggest(SettingsLedger.settings(ctx.getSource().getServer()).ranks().stream().map(Rank::group), b);
    private static final SuggestionProvider<CommandSourceStack> SIGNALS = (ctx, b) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(Signal.values()).map(Signal::key), b);
    private static final SuggestionProvider<CommandSourceStack> ITEMS = (ctx, b) ->
            SharedSuggestionProvider.suggestResource(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKeys(), b);
    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (ctx, b) ->
            SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerList().getPlayerNamesArray(), b);

    private AdminCommands() {
    }

    /** True for the console and for BSP Core admins (the same admin list as the other BSP mods). */
    public static boolean isAdmin(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer p ? BspCore.isAdmin(p) : source.hasPermission(2);
    }

    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rankup").requires(AdminCommands::isAdmin);

        root.then(Commands.literal("info").executes(ctx -> info(ctx.getSource())));
        root.then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())));

        // players
        root.then(Commands.literal("set")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .then(Commands.argument("group", StringArgumentType.word()).suggests(GROUPS)
                                .executes(ctx -> set(ctx.getSource(), StringArgumentType.getString(ctx, "player"), StringArgumentType.getString(ctx, "group"))))));
        root.then(Commands.literal("add")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .then(Commands.argument("points", LongArgumentType.longArg())
                                .executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "player"), LongArgumentType.getLong(ctx, "points"))))));
        root.then(Commands.literal("reset")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(ctx -> reset(ctx.getSource(), StringArgumentType.getString(ctx, "player")))));

        // ladder
        LiteralArgumentBuilder<CommandSourceStack> ladder = Commands.literal("ladder");
        ladder.then(Commands.literal("list").executes(ctx -> ladderList(ctx.getSource())));
        ladder.then(Commands.literal("add")
                .then(Commands.argument("newgroup", StringArgumentType.word()) // not "group": that name means an existing rung
                        .then(Commands.argument("points", LongArgumentType.longArg(0))
                                .executes(ctx -> ladderAdd(ctx, StringArgumentType.getString(ctx, "newgroup")))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> ladderAdd(ctx, StringArgumentType.getString(ctx, "name")))))));
        ladder.then(Commands.literal("remove").then(group().executes(ctx -> edit(ctx, "Removed rung " + group(ctx), b -> b.removeRank(index(ctx))))));
        ladder.then(Commands.literal("points").then(group().then(Commands.argument("points", LongArgumentType.longArg(0))
                .executes(ctx -> edit(ctx, group(ctx) + " now needs " + LongArgumentType.getLong(ctx, "points") + " points", b -> b.setRank(index(ctx), rank(ctx).withPoints(LongArgumentType.getLong(ctx, "points"))))))));
        ladder.then(Commands.literal("name").then(group().then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> edit(ctx, group(ctx) + " is shown as " + StringArgumentType.getString(ctx, "name"), b -> b.setRank(index(ctx), new Rank(group(ctx), StringArgumentType.getString(ctx, "name"), rank(ctx).points(), rank(ctx).seasonal(), rank(ctx).rewards())))))));
        ladder.then(Commands.literal("seasonal").then(group().then(Commands.argument("seasonal", BoolArgumentType.bool())
                .executes(ctx -> edit(ctx, group(ctx) + (BoolArgumentType.getBool(ctx, "seasonal") ? " resets each season" : " is permanent"), b -> b.setRank(index(ctx), rank(ctx).withSeasonal(BoolArgumentType.getBool(ctx, "seasonal"))))))));
        ladder.then(Commands.literal("move").then(group().then(Commands.argument("rung", IntegerArgumentType.integer(1))
                .executes(ctx -> move(ctx, IntegerArgumentType.getInteger(ctx, "rung"))))));
        ladder.then(Commands.literal("import").executes(ctx -> ladderImport(ctx.getSource())));
        root.then(ladder);

        // rewards
        LiteralArgumentBuilder<CommandSourceStack> reward = Commands.literal("reward");
        reward.then(Commands.literal("list").then(group().executes(ctx -> rewardList(ctx))));
        reward.then(Commands.literal("clear").then(group().executes(ctx -> edit(ctx, "Cleared the rewards of " + group(ctx), b -> b.setRank(index(ctx), rank(ctx).withRewards(List.of()))))));
        reward.then(Commands.literal("remove").then(group().then(Commands.argument("number", IntegerArgumentType.integer(1))
                .executes(ctx -> rewardRemove(ctx, IntegerArgumentType.getInteger(ctx, "number"))))));
        LiteralArgumentBuilder<CommandSourceStack> rewardAdd = Commands.literal("add");
        rewardAdd.then(group()
                .then(Commands.literal("coins").then(Commands.argument("copper", LongArgumentType.longArg(1))
                        .executes(ctx -> rewardAdd(ctx, Reward.coins(LongArgumentType.getLong(ctx, "copper"))))))
                .then(Commands.literal("pieces").then(Commands.argument("pieces", LongArgumentType.longArg(1))
                        .executes(ctx -> rewardAdd(ctx, Reward.pieces(LongArgumentType.getLong(ctx, "pieces"))))))
                .then(Commands.literal("item")
                        .then(Commands.argument("item", ResourceLocationArgument.id()).suggests(ITEMS)
                                .executes(ctx -> rewardAdd(ctx, Reward.item(ResourceLocationArgument.getId(ctx, "item").toString(), 1)))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64 * 36))
                                        .executes(ctx -> rewardAdd(ctx, Reward.item(ResourceLocationArgument.getId(ctx, "item").toString(), IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("hand").executes(ctx -> rewardFromHand(ctx))))
                .then(Commands.literal("title").then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> rewardAdd(ctx, Reward.title(StringArgumentType.getString(ctx, "text"))))))
                .then(Commands.literal("command").then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(ctx -> rewardAdd(ctx, Reward.command(StringArgumentType.getString(ctx, "command")))))));
        reward.then(rewardAdd);
        root.then(reward);

        // signals
        root.then(Commands.literal("signal")
                .executes(ctx -> signalList(ctx.getSource()))
                .then(Commands.argument("signal", StringArgumentType.word()).suggests(SIGNALS)
                        .then(Commands.literal("on").executes(ctx -> signal(ctx, s -> new SignalSetting(true, s.points(), s.per()))))
                        .then(Commands.literal("off").executes(ctx -> signal(ctx, s -> new SignalSetting(false, s.points(), s.per()))))
                        .then(Commands.literal("points").then(Commands.argument("points", LongArgumentType.longArg())
                                .executes(ctx -> signal(ctx, s -> new SignalSetting(s.on(), LongArgumentType.getLong(ctx, "points"), s.per())))
                                .then(Commands.literal("per").then(Commands.argument("per", LongArgumentType.longArg(1))
                                        .executes(ctx -> signal(ctx, s -> new SignalSetting(s.on(), LongArgumentType.getLong(ctx, "points"), LongArgumentType.getLong(ctx, "per"))))))))));

        // options
        LiteralArgumentBuilder<CommandSourceStack> option = Commands.literal("option");
        option.then(Commands.literal("track").then(Commands.argument("track", StringArgumentType.word())
                .executes(ctx -> edit(ctx, "The ladder follows the LuckPerms track " + StringArgumentType.getString(ctx, "track"), b -> b.track(StringArgumentType.getString(ctx, "track"))))));
        LiteralArgumentBuilder<CommandSourceStack> announce = Commands.literal("announce");
        for (Settings.Announce a : Settings.Announce.values()) {
            announce.then(Commands.literal(a.name().toLowerCase(Locale.ROOT)).executes(ctx -> edit(ctx, "Rank-ups are announced: " + a.name().toLowerCase(Locale.ROOT), b -> b.announce(a))));
        }
        option.then(announce);
        option.then(Commands.literal("cooldown").then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                .executes(ctx -> edit(ctx, "A repeat kill of the same player counts again after " + IntegerArgumentType.getInteger(ctx, "minutes") + " minutes", b -> b.killCooldownMinutes(IntegerArgumentType.getInteger(ctx, "minutes"))))));
        option.then(Commands.literal("titlepriority").then(Commands.argument("priority", IntegerArgumentType.integer())
                .executes(ctx -> edit(ctx, "Titles are LuckPerms prefixes at priority " + IntegerArgumentType.getInteger(ctx, "priority"), b -> b.titlePriority(IntegerArgumentType.getInteger(ctx, "priority"))))));
        option.then(Commands.literal("rewardsagain").then(Commands.argument("again", BoolArgumentType.bool())
                .executes(ctx -> edit(ctx, BoolArgumentType.getBool(ctx, "again") ? "Seasonal ranks reward again each season" : "Seasonal ranks reward once", b -> b.rewardsAgainEachSeason(BoolArgumentType.getBool(ctx, "again"))))));
        root.then(option);

        // files
        root.then(Commands.literal("export").then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> export(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
        root.then(Commands.literal("import").then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> importFile(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        d.register(root);
    }

    // ------------------------------------------------------------------ players

    private static int set(CommandSourceStack source, String who, String group) {
        MinecraftServer server = source.getServer();
        Optional<UUID> player = find(source, who);
        if (player.isEmpty()) {
            return 0;
        }
        Settings settings = SettingsLedger.settings(server);
        Rank rank = settings.rank(group);
        if (rank == null) {
            source.sendFailure(Component.literal("No rung called " + group + ". /rankup ladder list shows them."));
            return 0;
        }
        RankEngine.assign(server, player.get(), rank).thenAccept(ok -> {
            if (ok) {
                source.sendSuccess(() -> Component.literal(who + " is now " + rank.displayName() + " (" + rank.group() + "), without rewards"), true);
            } else {
                source.sendFailure(Component.literal("LuckPerms did not put " + who + " on " + rank.group() + ". Is the group on track " + settings.track() + "?"));
            }
        });
        return 1;
    }

    private static int add(CommandSourceStack source, String who, long points) {
        Optional<UUID> player = find(source, who);
        if (player.isEmpty()) {
            return 0;
        }
        ProgressLedger.get(source.getServer()).addBonus(player.get(), points);
        Progress p = ProgressLedger.get(source.getServer()).progress(player.get());
        long total = RankEngine.points(SettingsLedger.settings(source.getServer()), p);
        source.sendSuccess(() -> Component.literal((points >= 0 ? "Added " + points : "Took " + (-points)) + " points " + (points >= 0 ? "to " : "from ") + who + ": " + total + " points now"), true);
        return 1;
    }

    private static int reset(CommandSourceStack source, String who) {
        Optional<UUID> player = find(source, who);
        if (player.isEmpty()) {
            return 0;
        }
        ProgressLedger.get(source.getServer()).reset(player.get());
        source.sendSuccess(() -> Component.literal(who + " starts over: every count, bonus and rank forgotten" + (SettingsLedger.settings(source.getServer()).ranks().isEmpty() ? "" : "; they land on the first rung")), true);
        return 1;
    }

    private static Optional<UUID> find(CommandSourceStack source, String who) {
        Optional<UUID> player = Players.find(source.getServer(), who);
        if (player.isEmpty()) {
            source.sendFailure(Component.literal("No player called " + who + " (online here, known to this server, or a uuid)"));
        }
        return player;
    }

    // ------------------------------------------------------------------ ladder

    private static int ladderList(CommandSourceStack source) {
        Settings settings = SettingsLedger.settings(source.getServer());
        if (settings.ranks().isEmpty()) {
            source.sendSuccess(() -> Component.literal("The ladder is empty. /rankup ladder add <group> <points> [name], or /rankup ladder import from track " + settings.track()), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Ladder on track " + settings.track() + ", " + settings.ranks().size() + " rungs:"), false);
        for (int i = 0; i < settings.ranks().size(); i++) {
            Rank r = settings.ranks().get(i);
            String line = (i + 1) + ". " + r.displayName() + " (" + r.group() + ") at " + r.points() + " points, " + r.rewards().size() + " reward" + (r.rewards().size() == 1 ? "" : "s") + (r.seasonal() ? ", seasonal" : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return settings.ranks().size();
    }

    private static int ladderAdd(CommandContext<CommandSourceStack> ctx, String name) {
        String group = StringArgumentType.getString(ctx, "newgroup").toLowerCase(Locale.ROOT);
        long points = LongArgumentType.getLong(ctx, "points");
        Settings settings = SettingsLedger.settings(ctx.getSource().getServer());
        if (settings.indexOf(group) >= 0) {
            ctx.getSource().sendFailure(Component.literal("The ladder already has " + group));
            return 0;
        }
        return edit(ctx, "Added rung " + name + " (" + group + ") at " + points + " points" + (points == 0 ? "; a rung at 0 is the silent start" : ""), b -> b.addRank(Rank.of(group, name, points)));
    }

    /** Moves a rung to a 1-based position in the ladder (1 is the first, lowest rung). */
    private static int move(CommandContext<CommandSourceStack> ctx, int rung) {
        Settings settings = SettingsLedger.settings(ctx.getSource().getServer());
        int i = index(ctx);
        int j = rung - 1;
        if (i < 0) {
            ctx.getSource().sendFailure(Component.literal("No rung called " + group(ctx) + ". /rankup ladder list shows them."));
            return 0;
        }
        if (j >= settings.ranks().size()) {
            ctx.getSource().sendFailure(Component.literal("The ladder has " + settings.ranks().size() + " rungs"));
            return 0;
        }
        List<Rank> ranks = new ArrayList<>(settings.ranks());
        Rank r = ranks.remove(i);
        ranks.add(j, r);
        return edit(ctx, group(ctx) + " is now rung " + (j + 1) + " (mind the points order: /rankup ladder list)", b -> b.ranks(ranks));
    }

    private static int ladderImport(CommandSourceStack source) {
        Settings settings = SettingsLedger.settings(source.getServer());
        if (!LuckPermsBridge.isPresent()) {
            source.sendFailure(Component.literal("LuckPerms is not reachable"));
            return 0;
        }
        List<String> groups = LuckPermsBridge.trackGroups(settings.track());
        if (groups.isEmpty()) {
            source.sendFailure(Component.literal("LuckPerms has no track called " + settings.track() + " (or it is empty). /rankup option track <name> picks another."));
            return 0;
        }
        List<Rank> ranks = new ArrayList<>(settings.ranks());
        int added = 0;
        for (String g : groups) {
            if (settings.indexOf(g) < 0) {
                ranks.add(Rank.of(g, g.substring(0, 1).toUpperCase(Locale.ROOT) + g.substring(1), 0));
                added++;
            }
        }
        List<Rank> finalRanks = ranks;
        SettingsLedger.get(source.getServer()).update(b -> b.ranks(finalRanks));
        int n = added;
        source.sendSuccess(() -> Component.literal("Imported " + n + " rung" + (n == 1 ? "" : "s") + " from track " + settings.track() + " at 0 points; set their points with /rankup ladder points"), true);
        return added;
    }

    // ------------------------------------------------------------------ rewards

    private static int rewardList(CommandContext<CommandSourceStack> ctx) {
        Rank r = rank(ctx);
        if (r.rewards().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(r.displayName() + " gives nothing. /rankup reward add " + r.group() + " coins|pieces|item|title|command ..."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(r.displayName() + " gives:"), false);
        for (int i = 0; i < r.rewards().size(); i++) {
            String line = (i + 1) + ". " + describe(r.rewards().get(i));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return r.rewards().size();
    }

    private static int rewardAdd(CommandContext<CommandSourceStack> ctx, Reward reward) {
        if (reward.kind() == Reward.Kind.ITEM && Rewards.stack(reward).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No item called " + reward.value()));
            return 0;
        }
        List<Reward> rewards = new ArrayList<>(rank(ctx).rewards());
        rewards.add(reward);
        return edit(ctx, rank(ctx).displayName() + " now also gives " + describe(reward), b -> b.setRank(index(ctx), rank(ctx).withRewards(rewards)));
    }

    private static int rewardFromHand(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Hold the item in your main hand"));
            return 0;
        }
        String id = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(held.getItem()));
        return rewardAdd(ctx, Reward.item(id, held.getCount()));
    }

    private static int rewardRemove(CommandContext<CommandSourceStack> ctx, int number) {
        List<Reward> rewards = new ArrayList<>(rank(ctx).rewards());
        if (number > rewards.size()) {
            ctx.getSource().sendFailure(Component.literal(rank(ctx).displayName() + " has only " + rewards.size() + " rewards"));
            return 0;
        }
        Reward removed = rewards.remove(number - 1);
        return edit(ctx, "Removed from " + rank(ctx).displayName() + ": " + describe(removed), b -> b.setRank(index(ctx), rank(ctx).withRewards(rewards)));
    }

    static String describe(Reward r) {
        return switch (r.kind()) {
            case COINS -> "coins worth " + r.amount() + " Copper";
            case PIECES -> r.amount() + " pieces into the bank";
            case ITEM -> r.amount() + " x " + r.value();
            case TITLE -> "the title " + r.value();
            case COMMAND -> "the command /" + r.value();
        };
    }

    // ------------------------------------------------------------------ signals

    private static int signalList(CommandSourceStack source) {
        Settings settings = SettingsLedger.settings(source.getServer());
        for (Signal s : Signal.values()) {
            SignalSetting v = settings.signal(s);
            String line = s.key() + ": " + (v.on() ? "on, " + v.points() + " points per " + v.per() + " " + s.unit() + (v.per() == 1 ? "" : "s") : "off") + (s.source() == Signal.Source.RANKUP ? "" : " (" + s.source().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Signal.values().length;
    }

    private static int signal(CommandContext<CommandSourceStack> ctx, UnaryOperator<SignalSetting> change) {
        String key = StringArgumentType.getString(ctx, "signal").toLowerCase(Locale.ROOT);
        Signal s = Signal.byKey(key);
        if (s == null) {
            ctx.getSource().sendFailure(Component.literal("No signal called " + key + ". /rankup signal lists them."));
            return 0;
        }
        SignalSetting next = change.apply(SettingsLedger.settings(ctx.getSource().getServer()).signal(s));
        return edit(ctx, s.key() + ": " + (next.on() ? "on, " + next.points() + " points per " + next.per() + " " + s.unit() + (next.per() == 1 ? "" : "s") : "off"), b -> b.signal(s, next));
    }

    // ------------------------------------------------------------------ info, reload, files

    private static int info(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        List<String> lines = List.of(
                "BSP RankUp: track " + settings.track() + ", " + settings.ranks().size() + " rungs, " + settings.signals().values().stream().filter(SignalSetting::on).count() + " of " + Signal.values().length + " signals on",
                "LuckPerms: " + switch (LuckPermsBridge.route()) { case FORGE -> "Forge edition"; case BUKKIT -> "Bukkit plugin"; default -> "not reachable (ranks are recorded here only)"; }
                        + (LuckPermsBridge.isPresent() ? ", track " + settings.track() + (LuckPermsBridge.trackGroups(settings.track()).isEmpty() ? " missing or empty" : ": " + String.join(" > ", LuckPermsBridge.trackGroups(settings.track()))) : ""),
                "Players with progress on this server: " + ledger.all().size() + "; announcements: " + settings.announce().name().toLowerCase(Locale.ROOT) + "; kill cooldown " + settings.killCooldownMinutes() + " min; titles at priority " + settings.titlePriority());
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        LuckPermsBridge.reset();
        RankEngine.checkOnline(source.getServer());
        source.sendSuccess(() -> Component.literal("LuckPerms looked up again (" + LuckPermsBridge.route().name().toLowerCase(Locale.ROOT) + "); everyone online re-checked"), true);
        return 1;
    }

    private static int export(CommandSourceStack source, String name) {
        try {
            Path path = SettingsFiles.export(SettingsLedger.settings(source.getServer()), name);
            source.sendSuccess(() -> Component.literal("Settings written to " + path), true);
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            source.sendFailure(Component.literal("Could not write " + name + ": " + e.getMessage()));
            return 0;
        }
    }

    private static int importFile(CommandSourceStack source, String name) {
        try {
            Settings settings = SettingsFiles.read(name);
            SettingsLedger.get(source.getServer()).set(settings);
            source.sendSuccess(() -> Component.literal("Settings read from " + SettingsFiles.file(name) + ": " + settings.ranks().size() + " rungs on track " + settings.track() + "; everyone online re-checked"), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not read " + name + ": " + e.getMessage()));
            BSPRankUp.LOGGER.warn("settings import {} failed", name, e);
            return 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> group() {
        return Commands.argument("group", StringArgumentType.word()).suggests(GROUPS);
    }

    private static String group(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "group").toLowerCase(Locale.ROOT);
    }

    private static int index(CommandContext<CommandSourceStack> ctx) {
        return SettingsLedger.settings(ctx.getSource().getServer()).indexOf(group(ctx));
    }

    private static Rank rank(CommandContext<CommandSourceStack> ctx) {
        return SettingsLedger.settings(ctx.getSource().getServer()).ranks().get(index(ctx));
    }

    /** Applies an edit to the settings and replies; refuses when the command names a rung that does not exist. */
    private static int edit(CommandContext<CommandSourceStack> ctx, String reply, UnaryOperator<Settings.Builder> change) {
        if (ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("group")) && index(ctx) < 0) {
            ctx.getSource().sendFailure(Component.literal("No rung called " + group(ctx) + ". /rankup ladder list shows them."));
            return 0;
        }
        SettingsLedger.get(ctx.getSource().getServer()).update(change);
        ctx.getSource().sendSuccess(() -> Component.literal(reply), true);
        return 1;
    }
}
