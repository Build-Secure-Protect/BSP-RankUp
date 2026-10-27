package com.mrgregles.bsp_rankup.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_rankup.admin.AdminEdits;
import com.mrgregles.bsp_rankup.admin.Reply;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.prefix.Legacy;
import com.mrgregles.bsp_rankup.prefix.PrefixDisplay;
import com.mrgregles.bsp_rankup.prefix.PrefixImages;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.storage.Storage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /rankup}: the admin commands. Every edit goes through {@link AdminEdits}, which the admin screen uses too.
 * Players: {@code set, add, reset}. Rungs: {@code ladder add|list|import} (the first ladder) and
 * {@code ladder remove|points|name|seasonal|move} (any ladder's rung). Ladders (D49): {@code ladders} and
 * {@code ladders add|remove|rename|track|counts|titlepriority|rungs|addrung|import}. Awards (D50): {@code award} and
 * {@code award add|remove|points|name|counts|seasonal}. Rewards of any rung or award: {@code reward add|list|remove|clear}. Signals: {@code signal <signal> on|off|points <n> per <n>}.
 * Options: {@code option track|announce|cooldown|titlepriority|rewardsagain}. Files: {@code export, import}.
 * {@code admin} opens the screen; {@code info}, {@code reload}. Replies are plain English (server text is not translated, as in Region Guard).
 */
public final class AdminCommands {
    /** Every ladder's rungs and every award: what reward and rung commands take. */
    private static final SuggestionProvider<CommandSourceStack> GROUPS = (ctx, b) -> {
        Settings settings = SettingsLedger.settings(ctx.getSource().getServer());
        List<String> groups = new java.util.ArrayList<>();
        settings.ladders().forEach(l -> l.ranks().forEach(r -> groups.add(r.group())));
        settings.awards().forEach(a -> groups.add(a.group()));
        return SharedSuggestionProvider.suggest(groups, b);
    };
    private static final SuggestionProvider<CommandSourceStack> RUNGS = (ctx, b) -> {
        List<String> groups = new java.util.ArrayList<>();
        SettingsLedger.settings(ctx.getSource().getServer()).ladders().forEach(l -> l.ranks().forEach(r -> groups.add(r.group())));
        return SharedSuggestionProvider.suggest(groups, b);
    };
    private static final SuggestionProvider<CommandSourceStack> LADDERS = (ctx, b) ->
            SharedSuggestionProvider.suggest(SettingsLedger.settings(ctx.getSource().getServer()).ladders().stream().map(Ladder::name), b);
    private static final SuggestionProvider<CommandSourceStack> AWARDS = (ctx, b) ->
            SharedSuggestionProvider.suggest(SettingsLedger.settings(ctx.getSource().getServer()).awards().stream().map(Award::group), b);
    private static final SuggestionProvider<CommandSourceStack> COUNTS = (ctx, b) -> {
        List<String> options = new java.util.ArrayList<>(List.of("all"));
        for (Signal sig : Signal.values()) {
            options.add(sig.key());
        }
        return SharedSuggestionProvider.suggest(options, b);
    };
    private static final SuggestionProvider<CommandSourceStack> SIGNALS = (ctx, b) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(Signal.values()).map(Signal::key), b);
    private static final SuggestionProvider<CommandSourceStack> ITEMS = (ctx, b) ->
            SharedSuggestionProvider.suggestResource(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKeys(), b);
    private static final SuggestionProvider<CommandSourceStack> KITS = (ctx, b) ->
            SharedSuggestionProvider.suggest(BspCore.kits(ctx.getSource().getServer()).stream().map(BspCore.KitInfo::id), b);
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

        root.then(Commands.literal("admin").executes(ctx -> {
            AdminSync.open(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        root.then(Commands.literal("info").executes(ctx -> info(ctx.getSource())));
        root.then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())));

        // players
        root.then(Commands.literal("set")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .then(Commands.argument("group", StringArgumentType.word()).suggests(RUNGS)
                                .executes(ctx -> set(ctx.getSource(), StringArgumentType.getString(ctx, "player"), StringArgumentType.getString(ctx, "group"))))));
        root.then(Commands.literal("add")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .then(Commands.argument("points", LongArgumentType.longArg())
                                .executes(ctx -> withPlayer(ctx, who -> AdminEdits.playerAdd(ctx.getSource().getServer(), who, LongArgumentType.getLong(ctx, "points")))))));
        root.then(Commands.literal("reset")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(ctx -> withPlayer(ctx, who -> AdminEdits.playerReset(ctx.getSource().getServer(), who)))));

        // ladder
        LiteralArgumentBuilder<CommandSourceStack> ladder = Commands.literal("ladder");
        ladder.then(Commands.literal("list").executes(ctx -> ladderList(ctx.getSource())));
        ladder.then(Commands.literal("add")
                .then(Commands.argument("newgroup", StringArgumentType.word()) // not "group": that name means an existing rung
                        .then(Commands.argument("points", LongArgumentType.longArg(0))
                                .executes(ctx -> reply(ctx, AdminEdits.ladderAdd(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "newgroup"), LongArgumentType.getLong(ctx, "points"), null)))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> reply(ctx, AdminEdits.ladderAdd(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "newgroup"), LongArgumentType.getLong(ctx, "points"), StringArgumentType.getString(ctx, "name"))))))));
        ladder.then(Commands.literal("remove").then(group().executes(ctx -> reply(ctx, AdminEdits.ladderRemove(server(ctx), group(ctx))))));
        ladder.then(Commands.literal("points").then(group().then(Commands.argument("points", LongArgumentType.longArg(0))
                .executes(ctx -> reply(ctx, AdminEdits.ladderPoints(server(ctx), group(ctx), LongArgumentType.getLong(ctx, "points")))))));
        ladder.then(Commands.literal("name").then(group().then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> reply(ctx, AdminEdits.ladderName(server(ctx), group(ctx), StringArgumentType.getString(ctx, "name")))))));
        ladder.then(Commands.literal("seasonal").then(group().then(Commands.argument("seasonal", BoolArgumentType.bool())
                .executes(ctx -> reply(ctx, AdminEdits.ladderSeasonal(server(ctx), group(ctx), BoolArgumentType.getBool(ctx, "seasonal")))))));
        ladder.then(Commands.literal("move").then(group().then(Commands.argument("rung", IntegerArgumentType.integer(1))
                .executes(ctx -> reply(ctx, AdminEdits.ladderMove(server(ctx), group(ctx), IntegerArgumentType.getInteger(ctx, "rung")))))));
        ladder.then(Commands.literal("import").executes(ctx -> reply(ctx, AdminEdits.ladderImport(server(ctx)))));
        root.then(ladder);

        // ladders (D49)
        LiteralArgumentBuilder<CommandSourceStack> ladders = Commands.literal("ladders").executes(ctx -> laddersList(ctx.getSource()));
        ladders.then(Commands.literal("add").then(Commands.argument("newladder", StringArgumentType.word())
                .then(Commands.argument("track", StringArgumentType.word())
                        .executes(ctx -> reply(ctx, AdminEdits.laddersAdd(server(ctx), StringArgumentType.getString(ctx, "newladder"), StringArgumentType.getString(ctx, "track")))))));
        ladders.then(Commands.literal("remove").then(ladderArg().executes(ctx -> reply(ctx, AdminEdits.laddersRemove(server(ctx), ladderName(ctx))))));
        ladders.then(Commands.literal("rename").then(ladderArg().then(Commands.argument("newname", StringArgumentType.word())
                .executes(ctx -> reply(ctx, AdminEdits.laddersRename(server(ctx), ladderName(ctx), StringArgumentType.getString(ctx, "newname")))))));
        ladders.then(Commands.literal("track").then(ladderArg().then(Commands.argument("track", StringArgumentType.word())
                .executes(ctx -> reply(ctx, AdminEdits.laddersTrack(server(ctx), ladderName(ctx), StringArgumentType.getString(ctx, "track")))))));
        ladders.then(Commands.literal("counts").then(ladderArg().then(Commands.argument("signals", StringArgumentType.greedyString()).suggests(COUNTS)
                .executes(ctx -> reply(ctx, AdminEdits.laddersCounts(server(ctx), ladderName(ctx), StringArgumentType.getString(ctx, "signals")))))));
        ladders.then(Commands.literal("titlepriority").then(ladderArg().then(Commands.argument("priority", IntegerArgumentType.integer())
                .executes(ctx -> reply(ctx, AdminEdits.laddersTitlePriority(server(ctx), ladderName(ctx), IntegerArgumentType.getInteger(ctx, "priority")))))));
        ladders.then(Commands.literal("rungs").then(ladderArg().executes(ctx -> rungList(ctx.getSource(), ladderName(ctx)))));
        ladders.then(Commands.literal("addrung").then(ladderArg().then(Commands.argument("newgroup", StringArgumentType.word())
                .then(Commands.argument("points", LongArgumentType.longArg(0))
                        .executes(ctx -> reply(ctx, AdminEdits.ladderAdd(server(ctx), ladderName(ctx), StringArgumentType.getString(ctx, "newgroup"), LongArgumentType.getLong(ctx, "points"), null)))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> reply(ctx, AdminEdits.ladderAdd(server(ctx), ladderName(ctx), StringArgumentType.getString(ctx, "newgroup"), LongArgumentType.getLong(ctx, "points"), StringArgumentType.getString(ctx, "name")))))))));
        ladders.then(Commands.literal("import").then(ladderArg().executes(ctx -> reply(ctx, AdminEdits.ladderImport(server(ctx), ladderName(ctx))))));
        root.then(ladders);

        // awards (D50)
        LiteralArgumentBuilder<CommandSourceStack> award = Commands.literal("award").executes(ctx -> awardList(ctx.getSource()));
        award.then(Commands.literal("add").then(Commands.argument("newgroup", StringArgumentType.word())
                .then(Commands.argument("points", LongArgumentType.longArg(0))
                        .executes(ctx -> reply(ctx, AdminEdits.awardAdd(server(ctx), StringArgumentType.getString(ctx, "newgroup"), LongArgumentType.getLong(ctx, "points"), null)))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> reply(ctx, AdminEdits.awardAdd(server(ctx), StringArgumentType.getString(ctx, "newgroup"), LongArgumentType.getLong(ctx, "points"), StringArgumentType.getString(ctx, "name"))))))));
        award.then(Commands.literal("remove").then(awardArg().executes(ctx -> reply(ctx, AdminEdits.awardRemove(server(ctx), awardGroup(ctx))))));
        award.then(Commands.literal("points").then(awardArg().then(Commands.argument("points", LongArgumentType.longArg(0))
                .executes(ctx -> reply(ctx, AdminEdits.awardPoints(server(ctx), awardGroup(ctx), LongArgumentType.getLong(ctx, "points")))))));
        award.then(Commands.literal("name").then(awardArg().then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> reply(ctx, AdminEdits.awardName(server(ctx), awardGroup(ctx), StringArgumentType.getString(ctx, "name")))))));
        award.then(Commands.literal("counts").then(awardArg().then(Commands.argument("signals", StringArgumentType.greedyString()).suggests(COUNTS)
                .executes(ctx -> reply(ctx, AdminEdits.awardCounts(server(ctx), awardGroup(ctx), StringArgumentType.getString(ctx, "signals")))))));
        award.then(Commands.literal("seasonal").then(awardArg().then(Commands.argument("seasonal", BoolArgumentType.bool())
                .executes(ctx -> reply(ctx, AdminEdits.awardSeasonal(server(ctx), awardGroup(ctx), BoolArgumentType.getBool(ctx, "seasonal")))))));
        root.then(award);

        // rewards
        LiteralArgumentBuilder<CommandSourceStack> reward = Commands.literal("reward");
        reward.then(Commands.literal("list").then(group().executes(AdminCommands::rewardList)));
        reward.then(Commands.literal("clear").then(group().executes(ctx -> reply(ctx, AdminEdits.rewardClear(server(ctx), group(ctx))))));
        reward.then(Commands.literal("remove").then(group().then(Commands.argument("number", IntegerArgumentType.integer(1))
                .executes(ctx -> reply(ctx, AdminEdits.rewardRemove(server(ctx), group(ctx), IntegerArgumentType.getInteger(ctx, "number")))))));
        LiteralArgumentBuilder<CommandSourceStack> rewardAdd = Commands.literal("add");
        rewardAdd.then(group()
                .then(Commands.literal("coins").then(Commands.argument("copper", LongArgumentType.longArg(1))
                        .executes(ctx -> reply(ctx, AdminEdits.rewardAdd(server(ctx), group(ctx), Reward.coins(LongArgumentType.getLong(ctx, "copper")))))))
                .then(Commands.literal("pieces").then(Commands.argument("pieces", LongArgumentType.longArg(1))
                        .executes(ctx -> reply(ctx, AdminEdits.rewardAdd(server(ctx), group(ctx), Reward.pieces(LongArgumentType.getLong(ctx, "pieces")))))))
                .then(Commands.literal("item")
                        .then(Commands.argument("item", ResourceLocationArgument.id()).suggests(ITEMS)
                                .executes(ctx -> reply(ctx, AdminEdits.rewardAdd(server(ctx), group(ctx), Reward.item(ResourceLocationArgument.getId(ctx, "item").toString(), 1))))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64 * 36))
                                        .executes(ctx -> reply(ctx, AdminEdits.rewardAdd(server(ctx), group(ctx), Reward.item(ResourceLocationArgument.getId(ctx, "item").toString(), IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("hand").executes(ctx -> reply(ctx, AdminEdits.rewardFromHand(ctx.getSource().getPlayerOrException(), group(ctx))))))
                .then(Commands.literal("title").then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> reply(ctx, AdminEdits.rewardAdd(server(ctx), group(ctx), Reward.title(StringArgumentType.getString(ctx, "text")))))))
                .then(Commands.literal("command").then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(ctx -> reply(ctx, AdminEdits.rewardAdd(server(ctx), group(ctx), Reward.command(StringArgumentType.getString(ctx, "command")))))))
                .then(Commands.literal("kit").then(Commands.argument("kit", StringArgumentType.word()).suggests(KITS)
                        .executes(ctx -> reply(ctx, AdminEdits.rewardAdd(server(ctx), group(ctx), Reward.kit(StringArgumentType.getString(ctx, "kit"))))))));
        reward.then(rewardAdd);
        root.then(reward);

        // signals
        root.then(Commands.literal("signal")
                .executes(ctx -> signalList(ctx.getSource()))
                .then(Commands.argument("signal", StringArgumentType.word()).suggests(SIGNALS)
                        .then(Commands.literal("on").executes(ctx -> reply(ctx, AdminEdits.signal(server(ctx), signal(ctx), s -> new SignalSetting(true, s.points(), s.per())))))
                        .then(Commands.literal("off").executes(ctx -> reply(ctx, AdminEdits.signal(server(ctx), signal(ctx), s -> new SignalSetting(false, s.points(), s.per())))))
                        .then(Commands.literal("points").then(Commands.argument("points", LongArgumentType.longArg())
                                .executes(ctx -> reply(ctx, AdminEdits.signal(server(ctx), signal(ctx), s -> new SignalSetting(s.on(), LongArgumentType.getLong(ctx, "points"), s.per()))))
                                .then(Commands.literal("per").then(Commands.argument("per", LongArgumentType.longArg(1))
                                        .executes(ctx -> reply(ctx, AdminEdits.signal(server(ctx), signal(ctx), s -> new SignalSetting(s.on(), LongArgumentType.getLong(ctx, "points"), LongArgumentType.getLong(ctx, "per")))))))))));

        // options
        LiteralArgumentBuilder<CommandSourceStack> option = Commands.literal("option");
        option.then(Commands.literal("track").then(Commands.argument("track", StringArgumentType.word())
                .executes(ctx -> reply(ctx, AdminEdits.optionTrack(server(ctx), StringArgumentType.getString(ctx, "track"))))));
        LiteralArgumentBuilder<CommandSourceStack> announce = Commands.literal("announce");
        for (Settings.Announce a : Settings.Announce.values()) {
            announce.then(Commands.literal(a.name().toLowerCase(Locale.ROOT)).executes(ctx -> reply(ctx, AdminEdits.optionAnnounce(server(ctx), a.name()))));
        }
        option.then(announce);
        option.then(Commands.literal("cooldown").then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                .executes(ctx -> reply(ctx, AdminEdits.optionCooldown(server(ctx), IntegerArgumentType.getInteger(ctx, "minutes"))))));
        option.then(Commands.literal("titlepriority").then(Commands.argument("priority", IntegerArgumentType.integer())
                .executes(ctx -> reply(ctx, AdminEdits.optionTitlePriority(server(ctx), IntegerArgumentType.getInteger(ctx, "priority"))))));
        option.then(Commands.literal("rewardsagain").then(Commands.argument("again", BoolArgumentType.bool())
                .executes(ctx -> reply(ctx, AdminEdits.optionRewardsAgain(server(ctx), BoolArgumentType.getBool(ctx, "again"))))));
        option.then(Commands.literal("showprefixes").then(Commands.argument("show", BoolArgumentType.bool())
                .executes(ctx -> reply(ctx, AdminEdits.optionShowPrefixes(server(ctx), BoolArgumentType.getBool(ctx, "show"))))));
        root.then(option);

        // prefix images (D55 to D74): "title <group>" (a rung's or an award's title) or "group <group>" (a LuckPerms
        // group's own prefix), and a file name in config/bsp_rankup/prefix_images/
        LiteralArgumentBuilder<CommandSourceStack> prefix = Commands.literal("prefix");
        prefix.then(Commands.literal("list").executes(ctx -> prefixList(ctx.getSource())));
        prefix.then(Commands.literal("files").executes(ctx -> prefixFiles(ctx.getSource())));
        LiteralArgumentBuilder<CommandSourceStack> image = Commands.literal("image");
        LiteralArgumentBuilder<CommandSourceStack> clear = Commands.literal("clear");
        for (String kind : new String[]{"title", "group"}) {
            image.then(Commands.literal(kind).then(Commands.argument("group", StringArgumentType.word()).then(Commands.argument("file", StringArgumentType.greedyString())
                    .executes(ctx -> reply(ctx, AdminEdits.prefixImage(server(ctx), kind + ":" + StringArgumentType.getString(ctx, "group"), StringArgumentType.getString(ctx, "file")))))));
            clear.then(Commands.literal(kind).then(Commands.argument("group", StringArgumentType.word())
                    .executes(ctx -> reply(ctx, AdminEdits.prefixClear(server(ctx), kind + ":" + StringArgumentType.getString(ctx, "group"))))));
        }
        prefix.then(image);
        prefix.then(clear);
        root.then(prefix);

        // files
        root.then(Commands.literal("export").then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> reply(ctx, AdminEdits.export(server(ctx), StringArgumentType.getString(ctx, "name"))))));
        root.then(Commands.literal("import").then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> reply(ctx, AdminEdits.importFile(server(ctx), StringArgumentType.getString(ctx, "name"))))));

        d.register(root);
    }

    // ------------------------------------------------------------------ prefix images

    /** Every prefix RankUp lists, with its key and its image or "text". */
    private static int prefixList(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Settings settings = SettingsLedger.settings(server);
        List<PrefixDisplay.Source> sources = PrefixDisplay.sources(settings, PrefixDisplay.groupPrefixes());
        if (sources.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No prefixes: give a rung or an award a TITLE reward, or a LuckPerms group a prefix"), false);
            return 0;
        }
        for (PrefixDisplay.Source s : sources) {
            String shown = s.image() == null ? "text" : s.image().file() + (PrefixImages.inFolder(s.image().id()) ? "" : " (not in folder)");
            String key = s.key().replace(':', ' '); // as the image and clear commands take it
            source.sendSuccess(() -> Component.literal(key + "  ").append(Legacy.parse(s.text())).append(Component.literal("  " + s.rankName() + ": " + shown)), false);
        }
        return sources.size();
    }

    /** The images folder, read again, each file with its size or what is wrong with it. */
    private static int prefixFiles(CommandSourceStack source) {
        List<PrefixImages.FileInfo> files = PrefixImages.scan();
        if (files.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No PNG files in " + PrefixImages.folder()), false);
            return 0;
        }
        for (PrefixImages.FileInfo f : files) {
            source.sendSuccess(() -> Component.literal(f.name() + ": " + (f.usable() ? f.width() + " x " + f.height() + ", " + PrefixImages.drawnWidth(f.width(), f.height()) + " px wide in chat" : f.problem())), false);
        }
        return files.size();
    }

    // ------------------------------------------------------------------ players

    private static int set(CommandSourceStack source, String who, String group) {
        Optional<UUID> player = find(source, who);
        if (player.isEmpty()) {
            return 0;
        }
        if (SettingsLedger.settings(source.getServer()).ladderOf(group) == null) {
            source.sendFailure(Component.literal("No rung called " + group + ". /rankup ladders shows the ladders."));
            return 0;
        }
        AdminEdits.playerSet(source.getServer(), player.get(), group, reply -> {
            if (reply.ok()) {
                source.sendSuccess(() -> Component.literal(reply.text()), true);
            } else {
                source.sendFailure(Component.literal(reply.text()));
            }
        });
        return 1;
    }

    private static int withPlayer(CommandContext<CommandSourceStack> ctx, java.util.function.Function<UUID, Reply> edit) {
        Optional<UUID> player = find(ctx.getSource(), StringArgumentType.getString(ctx, "player"));
        return player.isEmpty() ? 0 : reply(ctx, edit.apply(player.get()));
    }

    private static Optional<UUID> find(CommandSourceStack source, String who) {
        Optional<UUID> player = AdminEdits.find(source.getServer(), who);
        if (player.isEmpty()) {
            source.sendFailure(Component.literal("No player called " + who + " (online here, known to this server, or a uuid)"));
        }
        return player;
    }

    // ------------------------------------------------------------------ lists

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

    private static int rewardList(CommandContext<CommandSourceStack> ctx) {
        Settings settings = SettingsLedger.settings(server(ctx));
        var entry = AdminEdits.rewardsOf(settings, group(ctx));
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("No rung or award called " + group(ctx) + ". /rankup ladders and /rankup award show them."));
            return 0;
        }
        String shown = entry.getKey();
        List<Reward> rewards = entry.getValue();
        if (rewards.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(shown + " gives nothing. /rankup reward add " + group(ctx) + " coins|pieces|item|title|command|kit ..."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(shown + " gives:"), false);
        for (int i = 0; i < rewards.size(); i++) {
            String line = (i + 1) + ". " + AdminEdits.describe(rewards.get(i));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return rewards.size();
    }

    private static int laddersList(CommandSourceStack source) {
        Settings settings = SettingsLedger.settings(source.getServer());
        source.sendSuccess(() -> Component.literal(settings.ladders().size() + " ladder" + (settings.ladders().size() == 1 ? "" : "s") + ":"), false);
        for (int i = 0; i < settings.ladders().size(); i++) {
            Ladder l = settings.ladders().get(i);
            String line = (i + 1) + ". " + l.name() + (i == 0 ? " (first)" : "") + " on track " + l.track() + ", " + l.ranks().size() + " rung" + (l.ranks().size() == 1 ? "" : "s")
                    + ", counts " + AdminEdits.describeCounts(l.counts()) + ", titles at priority " + l.titlePriority();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return settings.ladders().size();
    }

    private static int rungList(CommandSourceStack source, String ladderName) {
        Ladder l = SettingsLedger.settings(source.getServer()).ladder(ladderName);
        if (l == null) {
            source.sendFailure(Component.literal("No ladder called " + ladderName + ". /rankup ladders shows them."));
            return 0;
        }
        if (l.ranks().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Ladder " + l.name() + " is empty. /rankup ladders addrung " + l.name() + " <group> <points> [name], or /rankup ladders import " + l.name()), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Ladder " + l.name() + " on track " + l.track() + ", " + l.ranks().size() + " rungs:"), false);
        for (int i = 0; i < l.ranks().size(); i++) {
            Rank r = l.ranks().get(i);
            String line = (i + 1) + ". " + r.displayName() + " (" + r.group() + ") at " + r.points() + " points, " + r.rewards().size() + " reward" + (r.rewards().size() == 1 ? "" : "s") + (r.seasonal() ? ", seasonal" : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return l.ranks().size();
    }

    private static int awardList(CommandSourceStack source) {
        Settings settings = SettingsLedger.settings(source.getServer());
        if (settings.awards().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No awards. /rankup award add <group> <points> [name] makes a LuckPerms group on no ladder an award."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(settings.awards().size() + " award" + (settings.awards().size() == 1 ? "" : "s") + ":"), false);
        for (Award a : settings.awards()) {
            String line = a.displayName() + " (" + a.group() + ") at " + a.points() + " points from " + AdminEdits.describeCounts(a.counts()) + ", " + a.rewards().size() + " reward" + (a.rewards().size() == 1 ? "" : "s")
                    + (a.seasonal() ? ", taken back each season" : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return settings.awards().size();
    }

    private static int signalList(CommandSourceStack source) {
        Settings settings = SettingsLedger.settings(source.getServer());
        for (Signal s : Signal.values()) {
            String line = s.key() + ": " + AdminEdits.describe(s, settings.signal(s)) + (s.source() == Signal.Source.RANKUP ? "" : " (" + s.source().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Signal.values().length;
    }

    private static int info(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Settings settings = SettingsLedger.settings(server);
        ProgressLedger ledger = ProgressLedger.get(server);
        List<String> lines = List.of(
                "BSP RankUp: " + settings.ladders().size() + " ladder" + (settings.ladders().size() == 1 ? "" : "s") + " (first: track " + settings.track() + ", " + settings.ranks().size() + " rungs), " + settings.awards().size() + " award" + (settings.awards().size() == 1 ? "" : "s") + ", " + settings.signals().values().stream().filter(SignalSetting::on).count() + " of " + Signal.values().length + " signals on",
                "LuckPerms: " + switch (LuckPermsBridge.route()) { case FORGE -> "Forge edition"; case BUKKIT -> "Bukkit plugin"; default -> "not reachable (ranks are recorded here only)"; }
                        + (LuckPermsBridge.isPresent() ? ", track " + settings.track() + (LuckPermsBridge.trackGroups(settings.track()).isEmpty() ? " missing or empty" : ": " + String.join(" > ", LuckPermsBridge.trackGroups(settings.track()))) : ""),
                "Players with progress on this server: " + ledger.all().size() + "; announcements: " + settings.announce().name().toLowerCase(Locale.ROOT) + "; kill cooldown " + settings.killCooldownMinutes() + " min; titles at priority " + settings.titlePriority(),
                "Storage: " + (Storage.active() == null ? "this server only (storage.mode is none)" : Storage.active().status()));
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

    // ------------------------------------------------------------------ helpers

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> group() {
        return Commands.argument("group", StringArgumentType.word()).suggests(GROUPS);
    }

    private static String group(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "group").toLowerCase(Locale.ROOT);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> ladderArg() {
        return Commands.argument("ladder", StringArgumentType.word()).suggests(LADDERS);
    }

    private static String ladderName(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "ladder").toLowerCase(Locale.ROOT);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> awardArg() {
        return Commands.argument("award", StringArgumentType.word()).suggests(AWARDS);
    }

    private static String awardGroup(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "award").toLowerCase(Locale.ROOT);
    }

    private static String signal(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "signal");
    }

    private static MinecraftServer server(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getServer();
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, Reply reply) {
        if (reply.ok()) {
            ctx.getSource().sendSuccess(() -> Component.literal(reply.text()), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(reply.text()));
        return 0;
    }
}
