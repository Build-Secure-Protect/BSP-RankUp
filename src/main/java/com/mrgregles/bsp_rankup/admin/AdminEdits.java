package com.mrgregles.bsp_rankup.admin;

import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.prefix.PrefixImages;
import com.mrgregles.bsp_rankup.settings.PrefixImage;
import com.mrgregles.bsp_rankup.progress.Progress;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.rank.Rewards;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Every edit an admin can make, used by {@code /rankup} and by the admin screen alike (owner decision D11), so both
 * behave the same and one set of GameTests covers them. Each returns a {@link Reply}; every settings edit saves at
 * once (D30). Replies are plain English, as Region Guard's server text.
 */
public final class AdminEdits {
    private AdminEdits() {
    }

    // ------------------------------------------------------------------ ladder

    /** Adds a rung to the first ladder. */
    public static Reply ladderAdd(MinecraftServer server, String group, long points, @Nullable String name) {
        return ladderAdd(server, SettingsLedger.settings(server).first().name(), group, points, name);
    }

    /** Adds a rung to the named ladder. A group is on one ladder at most, and never also an award (D51). */
    public static Reply ladderAdd(MinecraftServer server, String ladderName, String group, long points, @Nullable String name) {
        String g = group.toLowerCase(Locale.ROOT).trim();
        if (!g.matches("[a-z0-9_-]{1,64}")) {
            return Reply.no("A group name is letters, digits, - and _");
        }
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(ladderName);
        if (ladder == null) {
            return noLadder(ladderName);
        }
        String taken = usedBy(settings, g);
        if (taken != null) {
            return Reply.no(g + " is already " + taken);
        }
        String shown = name == null || name.isBlank() ? g : name.trim();
        List<Rank> ranks = new ArrayList<>(ladder.ranks());
        ranks.add(Rank.of(g, shown, Math.max(0, points)));
        SettingsLedger.get(server).update(b -> b.ladder(ladder.withRanks(ranks)));
        return Reply.ok("Added rung " + shown + " (" + g + ") to " + ladder.name() + " at " + Math.max(0, points) + " points" + (points == 0 ? "; a rung at 0 is the silent start" : ""));
    }

    /** What already uses a group ("on ladder x", "an award"), or null when it is free. */
    @Nullable
    private static String usedBy(Settings settings, String group) {
        Ladder on = settings.ladderOf(group);
        if (on != null) {
            return "a rung of ladder " + on.name();
        }
        return settings.award(group) != null ? "an award" : null;
    }

    public static Reply ladderRemove(MinecraftServer server, String group) {
        return edit(server, group, r -> null, r -> "Removed rung " + r.displayName() + " (" + r.group() + "); players keep their points");
    }

    public static Reply ladderPoints(MinecraftServer server, String group, long points) {
        return edit(server, group, r -> r.withPoints(Math.max(0, points)), r -> r.displayName() + " now needs " + Math.max(0, points) + " points");
    }

    public static Reply ladderName(MinecraftServer server, String group, String name) {
        if (name.isBlank()) {
            return Reply.no("A name cannot be empty");
        }
        return edit(server, group, r -> new Rank(r.group(), name.trim(), r.points(), r.seasonal(), r.rewards()), r -> r.group() + " is shown as " + name.trim());
    }

    public static Reply ladderSeasonal(MinecraftServer server, String group, boolean seasonal) {
        return edit(server, group, r -> r.withSeasonal(seasonal), r -> r.displayName() + (seasonal ? " resets each season" : " is permanent"));
    }

    /** Moves a rung to a 1-based position. */
    public static Reply ladderMove(MinecraftServer server, String group, int rung) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladderOf(group);
        if (ladder == null) {
            return noRung(group);
        }
        int i = ladder.indexOf(group);
        int j = rung - 1;
        if (j < 0 || j >= ladder.ranks().size()) {
            return Reply.no("Ladder " + ladder.name() + " has " + ladder.ranks().size() + " rungs");
        }
        List<Rank> ranks = new ArrayList<>(ladder.ranks());
        Rank r = ranks.remove(i);
        ranks.add(j, r);
        SettingsLedger.get(server).update(b -> b.ladder(ladder.withRanks(ranks)));
        return Reply.ok(r.displayName() + " is now rung " + (j + 1) + " (mind the points order)");
    }

    /** Makes rungs on the first ladder from its LuckPerms track's groups that it does not have yet, at 0 points. */
    public static Reply ladderImport(MinecraftServer server) {
        return ladderImport(server, SettingsLedger.settings(server).first().name());
    }

    /** Makes rungs on a ladder from its track's groups not used yet (by any ladder or award), at 0 points. */
    public static Reply ladderImport(MinecraftServer server, String ladderName) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(ladderName);
        if (ladder == null) {
            return noLadder(ladderName);
        }
        if (!LuckPermsBridge.isPresent()) {
            return Reply.no("LuckPerms is not reachable");
        }
        List<String> groups = LuckPermsBridge.trackGroups(ladder.track());
        if (groups.isEmpty()) {
            return Reply.no("LuckPerms has no track called " + ladder.track() + " (or it is empty)");
        }
        List<Rank> ranks = new ArrayList<>(ladder.ranks());
        int added = 0;
        for (String g : groups) {
            if (usedBy(settings, g) == null) {
                ranks.add(Rank.of(g, g.substring(0, 1).toUpperCase(Locale.ROOT) + g.substring(1), 0));
                added++;
            }
        }
        SettingsLedger.get(server).update(b -> b.ladder(ladder.withRanks(ranks)));
        return Reply.ok("Imported " + added + " rung" + (added == 1 ? "" : "s") + " from track " + ladder.track() + " into " + ladder.name() + " at 0 points; set their points next");
    }

    // ------------------------------------------------------------------ ladders (D49)

    /** Adds a ladder on its own LuckPerms track, counting every signal; its titles one priority below the lowest so far. */
    public static Reply laddersAdd(MinecraftServer server, String name, String track) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        String t = track.toLowerCase(Locale.ROOT).trim();
        if (!n.matches(Ladder.NAME_PATTERN)) {
            return Reply.no("A ladder name is up to 24 lower-case letters, digits and _");
        }
        if (!t.matches("[a-z0-9_-]{1,64}")) {
            return Reply.no("A track name is letters, digits, - and _");
        }
        Settings settings = SettingsLedger.settings(server);
        if (settings.ladder(n) != null) {
            return Reply.no("There is a ladder called " + n + " already");
        }
        for (Ladder l : settings.ladders()) {
            if (l.track().equalsIgnoreCase(t)) {
                return Reply.no("Ladder " + l.name() + " follows track " + t + " already");
            }
        }
        int priority = settings.ladders().stream().mapToInt(Ladder::titlePriority).min().orElse(Settings.DEFAULT_TITLE_PRIORITY + 1) - 1;
        SettingsLedger.get(server).update(b -> b.addLadder(Ladder.of(n, t).withTitlePriority(priority)));
        return Reply.ok("Added ladder " + n + " on track " + t + ", counting every signal, titles at priority " + priority + "; add rungs with /rankup ladders addrung " + n);
    }

    /** Removes a ladder other than the first (D53); players keep their points and their LuckPerms groups. */
    public static Reply laddersRemove(MinecraftServer server, String name) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(name);
        if (ladder == null) {
            return noLadder(name);
        }
        if (RankEngine.isFirst(settings, ladder)) {
            return Reply.no("The first ladder cannot be removed: it holds the ranks from before several ladders. Rename it, or empty it");
        }
        SettingsLedger.get(server).update(b -> b.removeLadder(ladder.name()));
        return Reply.ok("Removed ladder " + ladder.name() + "; players keep their points and their LuckPerms groups");
    }

    /**
     * Renames the first ladder (D53). The later ladders keep their names: what players hold on them is stored by
     * name, here and in the shared database, so a new name would lose it.
     */
    public static Reply laddersRename(MinecraftServer server, String name, String newName) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(name);
        String n = newName.toLowerCase(Locale.ROOT).trim();
        if (ladder == null) {
            return noLadder(name);
        }
        if (!RankEngine.isFirst(settings, ladder)) {
            return Reply.no("Only the first ladder can be renamed: the others keep what players hold under their names");
        }
        if (!n.matches(Ladder.NAME_PATTERN)) {
            return Reply.no("A ladder name is up to 24 lower-case letters, digits and _");
        }
        if (settings.ladder(n) != null) {
            return Reply.no("There is a ladder called " + n + " already");
        }
        List<Ladder> ladders = new ArrayList<>(settings.ladders());
        ladders.set(0, ladder.withName(n));
        SettingsLedger.get(server).update(b -> b.ladders(ladders));
        return Reply.ok("Ladder " + ladder.name() + " is now called " + n);
    }

    public static Reply laddersTrack(MinecraftServer server, String name, String track) {
        String t = track.toLowerCase(Locale.ROOT).trim();
        if (!t.matches("[a-z0-9_-]{1,64}")) {
            return Reply.no("A track name is letters, digits, - and _");
        }
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(name);
        if (ladder == null) {
            return noLadder(name);
        }
        for (Ladder l : settings.ladders()) {
            if (!l.name().equals(ladder.name()) && l.track().equalsIgnoreCase(t)) {
                return Reply.no("Ladder " + l.name() + " follows track " + t + " already");
            }
        }
        SettingsLedger.get(server).update(b -> b.ladder(ladder.withTrack(t)));
        return Reply.ok("Ladder " + ladder.name() + " follows the LuckPerms track " + t);
    }

    /** Sets what a ladder counts: {@code all}, or signal keys separated by commas (D49). */
    public static Reply laddersCounts(MinecraftServer server, String name, String signals) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(name);
        if (ladder == null) {
            return noLadder(name);
        }
        Set<Signal> counts;
        try {
            counts = parseSignals(signals);
        } catch (IllegalArgumentException e) {
            return Reply.no(e.getMessage());
        }
        SettingsLedger.get(server).update(b -> b.ladder(ladder.withCounts(counts)));
        return Reply.ok("Ladder " + ladder.name() + " counts " + describeCounts(counts));
    }

    public static Reply laddersTitlePriority(MinecraftServer server, String name, int priority) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladder(name);
        if (ladder == null) {
            return noLadder(name);
        }
        SettingsLedger.get(server).update(b -> b.ladder(ladder.withTitlePriority(priority)));
        return Reply.ok("Ladder " + ladder.name() + "'s titles are LuckPerms prefixes at priority " + priority);
    }

    // ------------------------------------------------------------------ awards (D50)

    /** Makes a LuckPerms group an award, reached at {@code points} from every signal until its counts are set. */
    public static Reply awardAdd(MinecraftServer server, String group, long points, @Nullable String name) {
        String g = group.toLowerCase(Locale.ROOT).trim();
        if (!g.matches("[a-z0-9_-]{1,64}")) {
            return Reply.no("A group name is letters, digits, - and _");
        }
        Settings settings = SettingsLedger.settings(server);
        String taken = usedBy(settings, g);
        if (taken != null) {
            return Reply.no(g + " is already " + taken);
        }
        String shown = name == null || name.isBlank() ? g : name.trim();
        SettingsLedger.get(server).update(b -> b.award(Award.of(g, shown, Set.of(), Math.max(0, points))));
        boolean inLuckPerms = !LuckPermsBridge.isPresent() || LuckPermsBridge.groupNames().stream().anyMatch(n -> n.equalsIgnoreCase(g));
        return Reply.ok("Added award " + shown + " (" + g + ") at " + Math.max(0, points) + " points from every signal; set what it counts with /rankup award counts " + g
                + (inLuckPerms ? "" : ". LuckPerms has no group " + g + " yet: the award waits until it exists"));
    }

    /** Stops giving an award; players who have its group keep it. */
    public static Reply awardRemove(MinecraftServer server, String group) {
        Award a = SettingsLedger.settings(server).award(group);
        if (a == null) {
            return noAward(group);
        }
        SettingsLedger.get(server).update(b -> b.removeAward(a.group()));
        return Reply.ok("Stopped awarding " + a.displayName() + "; players who have " + a.group() + " keep it");
    }

    public static Reply awardPoints(MinecraftServer server, String group, long points) {
        return editAward(server, group, a -> a.withPoints(Math.max(0, points)), a -> a.displayName() + " now needs " + Math.max(0, points) + " points");
    }

    public static Reply awardName(MinecraftServer server, String group, String name) {
        if (name.isBlank()) {
            return Reply.no("A name cannot be empty");
        }
        return editAward(server, group, a -> a.withDisplayName(name.trim()), a -> a.group() + " is shown as " + name.trim());
    }

    public static Reply awardSeasonal(MinecraftServer server, String group, boolean seasonal) {
        return editAward(server, group, a -> a.withSeasonal(seasonal), a -> a.displayName() + (seasonal ? " is taken back at each season's end and earned again" : " is kept once reached"));
    }

    public static Reply awardCounts(MinecraftServer server, String group, String signals) {
        Set<Signal> counts;
        try {
            counts = parseSignals(signals);
        } catch (IllegalArgumentException e) {
            return Reply.no(e.getMessage());
        }
        return editAward(server, group, a -> a.withCounts(counts), a -> a.displayName() + " counts " + describeCounts(counts));
    }

    private static Reply editAward(MinecraftServer server, String group, UnaryOperator<Award> change, java.util.function.Function<Award, String> reply) {
        Award a = SettingsLedger.settings(server).award(group);
        if (a == null) {
            return noAward(group);
        }
        SettingsLedger.get(server).update(b -> b.award(change.apply(a)));
        return Reply.ok(reply.apply(a));
    }

    private static Reply noAward(String group) {
        return Reply.no("No award called " + group + ". The awards: see /rankup award");
    }

    /** {@code all} (or empty) for every signal, else signal keys separated by commas. */
    public static Set<Signal> parseSignals(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("all")) {
            return Set.of();
        }
        Set<Signal> out = new java.util.LinkedHashSet<>();
        for (String key : t.split("[,\\s]+")) {
            if (key.isEmpty()) {
                continue;
            }
            Signal s = Signal.byKey(key);
            if (s == null) {
                throw new IllegalArgumentException("No signal called " + key + "; the signals: /rankup signal");
            }
            out.add(s);
        }
        return out;
    }

    public static String describeCounts(Set<Signal> counts) {
        return counts.isEmpty() ? "every signal" : counts.stream().map(Signal::key).sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ rewards

    public static Reply rewardAdd(MinecraftServer server, String group, Reward reward) {
        if (reward.kind() == Reward.Kind.ITEM && Rewards.stack(reward).isEmpty()) {
            return Reply.no("No item called " + reward.value());
        }
        if ((reward.kind() == Reward.Kind.COINS || reward.kind() == Reward.Kind.PIECES) && reward.amount() <= 0) {
            return Reply.no("The amount must be above 0");
        }
        if ((reward.kind() == Reward.Kind.TITLE || reward.kind() == Reward.Kind.COMMAND) && reward.value().isBlank()) {
            return Reply.no("The text cannot be empty");
        }
        if (reward.kind() == Reward.Kind.KIT) {
            if (!com.mrgregles.bsp_rankup.compat.BspCore.hasKits()) {
                return Reply.no("This BSP-Core has no kits (needs 1.3.0 or newer)");
            }
            if (com.mrgregles.bsp_rankup.compat.BspCore.kits(server).stream().noneMatch(k -> k.id().equals(reward.value()))) {
                return Reply.no("No kit called " + reward.value() + ". Kits are made in BSP-Core's admin panel, KITS tab");
            }
        }
        Award award = SettingsLedger.settings(server).award(group);
        if (award != null) {
            List<Reward> rewards = new ArrayList<>(award.rewards());
            rewards.add(reward);
            return editAward(server, group, a -> a.withRewards(rewards), a -> a.displayName() + " now also gives " + describe(reward));
        }
        return edit(server, group, r -> {
            List<Reward> rewards = new ArrayList<>(r.rewards());
            rewards.add(reward);
            return r.withRewards(rewards);
        }, r -> r.displayName() + " now also gives " + describe(reward));
    }

    /** The item in the admin's main hand as a reward. */
    public static Reply rewardFromHand(ServerPlayer admin, String group) {
        ItemStack held = admin.getMainHandItem();
        if (held.isEmpty()) {
            return Reply.no("Hold the item in your main hand");
        }
        return rewardAdd(admin.server, group, Reward.item(String.valueOf(ForgeRegistries.ITEMS.getKey(held.getItem())), held.getCount()));
    }

    /** Removes the 1-based reward of a rung or an award. */
    public static Reply rewardRemove(MinecraftServer server, String group, int number) {
        Settings settings = SettingsLedger.settings(server);
        Award award = settings.award(group);
        Ladder ladder = settings.ladderOf(group);
        if (award == null && ladder == null) {
            return noRung(group);
        }
        String shown = award != null ? award.displayName() : ladder.rank(group).displayName();
        List<Reward> rewards = new ArrayList<>(award != null ? award.rewards() : ladder.rank(group).rewards());
        if (number < 1 || number > rewards.size()) {
            return Reply.no(shown + " has " + rewards.size() + " reward" + (rewards.size() == 1 ? "" : "s"));
        }
        Reward removed = rewards.remove(number - 1);
        if (award != null) {
            SettingsLedger.get(server).update(b -> b.award(award.withRewards(rewards)));
        } else {
            edit(server, group, r -> r.withRewards(rewards), r -> "");
        }
        return Reply.ok("Removed from " + shown + ": " + describe(removed));
    }

    public static Reply rewardClear(MinecraftServer server, String group) {
        if (SettingsLedger.settings(server).award(group) != null) {
            return editAward(server, group, a -> a.withRewards(List.of()), a -> "Cleared the rewards of " + a.displayName());
        }
        return edit(server, group, r -> r.withRewards(List.of()), r -> "Cleared the rewards of " + r.displayName());
    }

    /** The rewards of a rung on any ladder or of an award, with what gives them; null when the group is neither. */
    @Nullable
    public static java.util.Map.Entry<String, List<Reward>> rewardsOf(Settings settings, String group) {
        Award award = settings.award(group);
        if (award != null) {
            return java.util.Map.entry(award.displayName(), award.rewards());
        }
        Ladder ladder = settings.ladderOf(group);
        return ladder == null ? null : java.util.Map.entry(ladder.rank(group).displayName(), ladder.rank(group).rewards());
    }

    public static String describe(Reward r) {
        return switch (r.kind()) {
            case COINS -> "coins worth " + r.amount() + " Copper";
            case PIECES -> r.amount() + " pieces into the bank";
            case ITEM -> r.amount() + " x " + r.value();
            case TITLE -> "the title " + r.value();
            case COMMAND -> "the command /" + r.value();
            case KIT -> "the kit " + r.value();
        };
    }

    // ------------------------------------------------------------------ signals

    public static Reply signal(MinecraftServer server, String key, UnaryOperator<SignalSetting> change) {
        Signal s = Signal.byKey(key.toLowerCase(Locale.ROOT));
        if (s == null) {
            return Reply.no("No signal called " + key);
        }
        SignalSetting next = change.apply(SettingsLedger.settings(server).signal(s));
        if (next.per() < 1) {
            return Reply.no("'per' must be at least 1");
        }
        SettingsLedger.get(server).update(b -> b.signal(s, next));
        return Reply.ok(s.key() + ": " + describe(s, next));
    }

    public static String describe(Signal s, SignalSetting v) {
        return v.on() ? "on, " + v.points() + " points per " + v.per() + " " + s.unit() + (v.per() == 1 ? "" : "s") : "off";
    }

    // ------------------------------------------------------------------ options

    public static Reply optionTrack(MinecraftServer server, String track) {
        String t = track.toLowerCase(Locale.ROOT).trim();
        if (!t.matches("[a-z0-9_-]{1,64}")) {
            return Reply.no("A track name is letters, digits, - and _");
        }
        SettingsLedger.get(server).update(b -> b.track(t));
        return Reply.ok("The ladder follows the LuckPerms track " + t);
    }

    public static Reply optionAnnounce(MinecraftServer server, String value) {
        Settings.Announce a;
        try {
            a = Settings.Announce.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Reply.no("announce is off, server or network");
        }
        SettingsLedger.get(server).update(b -> b.announce(a));
        return Reply.ok("Rank-ups are announced: " + a.name().toLowerCase(Locale.ROOT));
    }

    public static Reply optionCooldown(MinecraftServer server, int minutes) {
        if (minutes < 0) {
            return Reply.no("Minutes cannot be negative");
        }
        SettingsLedger.get(server).update(b -> b.killCooldownMinutes(minutes));
        return Reply.ok(minutes == 0 ? "Every kill counts, even the same player again" : "A repeat kill of the same player counts again after " + minutes + " minutes");
    }

    public static Reply optionTitlePriority(MinecraftServer server, int priority) {
        SettingsLedger.get(server).update(b -> b.titlePriority(priority));
        return Reply.ok("Titles are LuckPerms prefixes at priority " + priority);
    }

    public static Reply optionRewardsAgain(MinecraftServer server, boolean again) {
        SettingsLedger.get(server).update(b -> b.rewardsAgainEachSeason(again));
        return Reply.ok(again ? "Seasonal ranks reward again each season" : "Seasonal ranks reward once");
    }

    // ------------------------------------------------------------------ players

    public static Optional<UUID> find(MinecraftServer server, String who) {
        return Players.find(server, who);
    }

    /** Puts a player on a rung without rewards; the reply comes when LuckPerms has answered. */
    public static void playerSet(MinecraftServer server, UUID player, String group, Consumer<Reply> then) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladderOf(group);
        Rank rank = ladder == null ? null : ladder.rank(group);
        if (rank == null) {
            then.accept(noRung(group));
            return;
        }
        String name = Players.name(server, player);
        RankEngine.assign(server, player, rank).thenAccept(ok -> then.accept(ok
                ? Reply.ok(name + " is now " + rank.displayName() + " (" + rank.group() + ") on " + ladder.name() + ", without rewards")
                : Reply.no("LuckPerms did not put " + name + " on " + rank.group() + ". Is the group on track " + ladder.track() + "?")));
    }

    public static Reply playerAdd(MinecraftServer server, UUID player, long points) {
        if (points == 0) {
            return Reply.no("0 points changes nothing");
        }
        ProgressLedger ledger = ProgressLedger.get(server);
        ledger.addBonus(player, points);
        long total = RankEngine.points(SettingsLedger.settings(server), ledger.progress(player));
        String name = Players.name(server, player);
        return Reply.ok((points > 0 ? "Added " + points + " points to " : "Took " + (-points) + " points from ") + name + ": " + total + " points now");
    }

    public static Reply playerReset(MinecraftServer server, UUID player) {
        ProgressLedger.get(server).reset(player);
        return Reply.ok(Players.name(server, player) + " starts over: every count, bonus and rank forgotten" + (SettingsLedger.settings(server).ranks().isEmpty() ? "" : "; they land on the first rung"));
    }

    // ------------------------------------------------------------------ prefix images (D55 to D74)

    /**
     * Draws a file of the images folder in place of a rank's prefix or suffix: {@code key} is {@code title:<group>} for
     * a rung's or an award's title, {@code group:<group>} for a LuckPerms group's own prefix (D57), {@code
     * suffix:<group>} for its own suffix (D89). The image is kept here and in the shared storage, so the file may go
     * afterwards (D71); one image may serve several ranks (D70).
     */
    public static Reply prefixImage(MinecraftServer server, String key, String fileName) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        Reply bad = checkPrefixKey(server, k);
        if (bad != null) {
            return bad;
        }
        String name = fileName.trim();
        if (!name.matches("[^/\\\\]{1,128}") || name.startsWith(".")) {
            return Reply.no("A file name only, from " + PrefixImages.folder().getFileName() + "/");
        }
        PrefixImages.FileInfo file = PrefixImages.file(name);
        if (file == null) {
            PrefixImages.scan();
            file = PrefixImages.file(name);
        }
        if (file == null) {
            return Reply.no("No file " + name + " in config/bsp_rankup/prefix_images/");
        }
        if (!file.usable()) {
            return Reply.no(file.name() + " can't be used: " + file.problem());
        }
        byte[] png = PrefixImages.folderBytes(file.id());
        if (png == null) {
            return Reply.no(file.name() + " could not be read; press REFRESH");
        }
        PrefixImages.keep(server, file.id(), png);
        PrefixImage image = new PrefixImage(file.id(), file.name(), file.width(), file.height());
        SettingsLedger.get(server).update(b -> b.prefixImage(k, image));
        return Reply.ok(describeKey(k) + " now shows " + file.name() + " in chat and the tab list");
    }

    /** Takes a rank's image away: its text prefix shows again. */
    public static Reply prefixClear(MinecraftServer server, String key) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (SettingsLedger.settings(server).prefixImage(k) == null) {
            return Reply.no(describeKey(k) + " has no image");
        }
        SettingsLedger.get(server).update(b -> b.prefixImage(k, null));
        return Reply.ok(describeKey(k) + " shows its text again");
    }

    /** The longest hover note, in characters. */
    public static final int NOTE_MAX = 120;

    /**
     * Sets the note shown when a rank's prefix or suffix is hovered in chat (D94), such as how an achievement is
     * earned; an empty note takes it away. Shared with every server like the rest of the settings.
     */
    public static Reply prefixNote(MinecraftServer server, String key, String note) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        Reply bad = checkPrefixKey(server, k);
        if (bad != null) {
            return bad;
        }
        String n = note.strip().replace('\n', ' ').replace('\r', ' ');
        if (n.length() > NOTE_MAX) {
            return Reply.no("A note is at most " + NOTE_MAX + " characters (that one is " + n.length() + ")");
        }
        if (n.isEmpty() && SettingsLedger.settings(server).prefixNote(k).isEmpty()) {
            return Reply.no(describeKey(k) + " has no note");
        }
        SettingsLedger.get(server).update(b -> b.prefixNote(k, n));
        return Reply.ok(n.isEmpty() ? describeKey(k) + " has no note now" : "Hovering " + describeKey(k) + " shows: " + n);
    }

    /** Null when the key names a rank RankUp can draw an image for, else why not. */
    @Nullable
    private static Reply checkPrefixKey(MinecraftServer server, String key) {
        if (!PrefixImage.isKey(key)) {
            return Reply.no("A prefix is title:<group> (a rung's or an award's title), group:<group> (a LuckPerms group's own prefix) or suffix:<group> (its own suffix)");
        }
        String group = PrefixImage.groupOf(key);
        Settings settings = SettingsLedger.settings(server);
        if (key.startsWith(PrefixImage.TITLE)) {
            if (settings.ladderOf(group) == null && settings.award(group) == null) {
                return noRung(group);
            }
            return null;
        }
        if (LuckPermsBridge.isPresent() && LuckPermsBridge.groupNames().stream().noneMatch(g -> g.equalsIgnoreCase(group))) {
            return Reply.no("LuckPerms has no group " + group);
        }
        return null;
    }

    /** A rank key in words: "Warden's title", "group admin's prefix". */
    public static String describeKey(String key) {
        String group = PrefixImage.isKey(key) ? PrefixImage.groupOf(key) : key;
        return key.startsWith(PrefixImage.GROUP) ? "Group " + group + "'s prefix" : key.startsWith(PrefixImage.SUFFIX) ? "Group " + group + "'s suffix" : group + "'s title";
    }

    /** The D55 switch: RankUp puts prefixes before names and suffixes after them, or leaves names alone (a chat mod does it). */
    public static Reply optionShowPrefixes(MinecraftServer server, boolean show) {
        SettingsLedger.get(server).update(b -> b.showPrefixes(show));
        return Reply.ok(show ? "Prefixes and suffixes show around names in chat and the tab list" : "RankUp leaves names alone; prefixes and suffixes are not shown by RankUp");
    }

    // ------------------------------------------------------------------ LuckPerms, files

    /** The D2 option: makes the track and any missing groups in LuckPerms from the ladder. */
    public static CompletableFuture<Reply> makeTrack(MinecraftServer server) {
        Settings settings = SettingsLedger.settings(server);
        if (!LuckPermsBridge.isPresent()) {
            return CompletableFuture.completedFuture(Reply.no("LuckPerms is not reachable"));
        }
        if (settings.ranks().isEmpty()) {
            return CompletableFuture.completedFuture(Reply.no("The ladder is empty; add rungs first"));
        }
        List<String> groups = settings.ranks().stream().map(Rank::group).toList();
        return LuckPermsBridge.ensureTrack(settings.track(), groups).thenApply(ok -> ok
                ? Reply.ok("LuckPerms track " + settings.track() + " now holds " + String.join(" > ", groups) + "; groups that were missing were made with rising weights")
                : Reply.no("LuckPerms refused to make the track; see the log"));
    }

    public static Reply export(MinecraftServer server, String name) {
        try {
            Path path = SettingsFiles.export(SettingsLedger.settings(server), name);
            return Reply.ok("Settings written to " + path);
        } catch (IOException | IllegalArgumentException e) {
            return Reply.no("Could not write " + name + ": " + e.getMessage());
        }
    }

    public static Reply importFile(MinecraftServer server, String name) {
        try {
            Settings settings = SettingsFiles.read(name);
            SettingsLedger.get(server).set(settings);
            return Reply.ok("Settings read from " + SettingsFiles.file(name).getFileName() + ": " + settings.ranks().size() + " rungs on track " + settings.track() + "; everyone online re-checked");
        } catch (IOException | RuntimeException e) {
            return Reply.no("Could not read " + name + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Changes a rung on whichever ladder has it; the change gives the new rung, or null to remove it. */
    private static Reply edit(MinecraftServer server, String group, java.util.function.Function<Rank, Rank> change, java.util.function.Function<Rank, String> reply) {
        Settings settings = SettingsLedger.settings(server);
        Ladder ladder = settings.ladderOf(group);
        if (ladder == null) {
            return noRung(group);
        }
        int i = ladder.indexOf(group);
        Rank r = ladder.ranks().get(i);
        Rank next = change.apply(r);
        List<Rank> ranks = new ArrayList<>(ladder.ranks());
        if (next == null) {
            ranks.remove(i);
        } else {
            ranks.set(i, next);
        }
        SettingsLedger.get(server).update(b -> b.ladder(ladder.withRanks(ranks)));
        return Reply.ok(reply.apply(r));
    }

    private static Reply noRung(String group) {
        return Reply.no("No rung or award called " + group + ". See /rankup ladders and /rankup award");
    }

    private static Reply noLadder(String name) {
        return Reply.no("No ladder called " + name + ". See /rankup ladders");
    }

    /** The points a player has now, for replies and the screen. */
    public static long pointsOf(MinecraftServer server, UUID player) {
        Progress p = ProgressLedger.get(server).peek(player);
        return p == null ? 0 : RankEngine.points(SettingsLedger.settings(server), p);
    }
}
