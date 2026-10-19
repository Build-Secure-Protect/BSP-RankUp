package com.mrgregles.bsp_rankup.admin;

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

    public static Reply ladderAdd(MinecraftServer server, String group, long points, @Nullable String name) {
        String g = group.toLowerCase(Locale.ROOT).trim();
        if (!g.matches("[a-z0-9_-]{1,64}")) {
            return Reply.no("A group name is letters, digits, - and _");
        }
        Settings settings = SettingsLedger.settings(server);
        if (settings.indexOf(g) >= 0) {
            return Reply.no("The ladder already has " + g);
        }
        String shown = name == null || name.isBlank() ? g : name.trim();
        SettingsLedger.get(server).update(b -> b.addRank(Rank.of(g, shown, Math.max(0, points))));
        return Reply.ok("Added rung " + shown + " (" + g + ") at " + Math.max(0, points) + " points" + (points == 0 ? "; a rung at 0 is the silent start" : ""));
    }

    public static Reply ladderRemove(MinecraftServer server, String group) {
        return edit(server, group, (i, r) -> b -> b.removeRank(i), r -> "Removed rung " + r.displayName() + " (" + r.group() + "); players keep their points");
    }

    public static Reply ladderPoints(MinecraftServer server, String group, long points) {
        return edit(server, group, (i, r) -> b -> b.setRank(i, r.withPoints(Math.max(0, points))), r -> r.displayName() + " now needs " + Math.max(0, points) + " points");
    }

    public static Reply ladderName(MinecraftServer server, String group, String name) {
        if (name.isBlank()) {
            return Reply.no("A name cannot be empty");
        }
        return edit(server, group, (i, r) -> b -> b.setRank(i, new Rank(r.group(), name.trim(), r.points(), r.seasonal(), r.rewards())), r -> r.group() + " is shown as " + name.trim());
    }

    public static Reply ladderSeasonal(MinecraftServer server, String group, boolean seasonal) {
        return edit(server, group, (i, r) -> b -> b.setRank(i, r.withSeasonal(seasonal)), r -> r.displayName() + (seasonal ? " resets each season" : " is permanent"));
    }

    /** Moves a rung to a 1-based position. */
    public static Reply ladderMove(MinecraftServer server, String group, int rung) {
        Settings settings = SettingsLedger.settings(server);
        int i = settings.indexOf(group);
        if (i < 0) {
            return noRung(group);
        }
        int j = rung - 1;
        if (j < 0 || j >= settings.ranks().size()) {
            return Reply.no("The ladder has " + settings.ranks().size() + " rungs");
        }
        List<Rank> ranks = new ArrayList<>(settings.ranks());
        Rank r = ranks.remove(i);
        ranks.add(j, r);
        SettingsLedger.get(server).update(b -> b.ranks(ranks));
        return Reply.ok(r.displayName() + " is now rung " + (j + 1) + " (mind the points order)");
    }

    /** Makes rungs from the LuckPerms track's groups that the ladder does not have yet, at 0 points. */
    public static Reply ladderImport(MinecraftServer server) {
        Settings settings = SettingsLedger.settings(server);
        if (!LuckPermsBridge.isPresent()) {
            return Reply.no("LuckPerms is not reachable");
        }
        List<String> groups = LuckPermsBridge.trackGroups(settings.track());
        if (groups.isEmpty()) {
            return Reply.no("LuckPerms has no track called " + settings.track() + " (or it is empty)");
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
        SettingsLedger.get(server).update(b -> b.ranks(finalRanks));
        return Reply.ok("Imported " + added + " rung" + (added == 1 ? "" : "s") + " from track " + settings.track() + " at 0 points; set their points next");
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
        return edit(server, group, (i, r) -> {
            List<Reward> rewards = new ArrayList<>(r.rewards());
            rewards.add(reward);
            return b -> b.setRank(i, r.withRewards(rewards));
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

    /** Removes the 1-based reward of a rung. */
    public static Reply rewardRemove(MinecraftServer server, String group, int number) {
        Settings settings = SettingsLedger.settings(server);
        int i = settings.indexOf(group);
        if (i < 0) {
            return noRung(group);
        }
        Rank r = settings.ranks().get(i);
        if (number < 1 || number > r.rewards().size()) {
            return Reply.no(r.displayName() + " has " + r.rewards().size() + " reward" + (r.rewards().size() == 1 ? "" : "s"));
        }
        List<Reward> rewards = new ArrayList<>(r.rewards());
        Reward removed = rewards.remove(number - 1);
        SettingsLedger.get(server).update(b -> b.setRank(i, r.withRewards(rewards)));
        return Reply.ok("Removed from " + r.displayName() + ": " + describe(removed));
    }

    public static Reply rewardClear(MinecraftServer server, String group) {
        return edit(server, group, (i, r) -> b -> b.setRank(i, r.withRewards(List.of())), r -> "Cleared the rewards of " + r.displayName());
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
        Rank rank = settings.rank(group);
        if (rank == null) {
            then.accept(noRung(group));
            return;
        }
        String name = Players.name(server, player);
        RankEngine.assign(server, player, rank).thenAccept(ok -> then.accept(ok
                ? Reply.ok(name + " is now " + rank.displayName() + " (" + rank.group() + "), without rewards")
                : Reply.no("LuckPerms did not put " + name + " on " + rank.group() + ". Is the group on track " + settings.track() + "?")));
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

    private interface RungEdit {
        UnaryOperator<Settings.Builder> change(int index, Rank rank);
    }

    private static Reply edit(MinecraftServer server, String group, RungEdit edit, java.util.function.Function<Rank, String> reply) {
        Settings settings = SettingsLedger.settings(server);
        int i = settings.indexOf(group);
        if (i < 0) {
            return noRung(group);
        }
        Rank r = settings.ranks().get(i);
        SettingsLedger.get(server).update(edit.change(i, r));
        return Reply.ok(reply.apply(r));
    }

    private static Reply noRung(String group) {
        return Reply.no("No rung called " + group + ". The ladder's rungs: see /rankup ladder list");
    }

    /** The points a player has now, for replies and the screen. */
    public static long pointsOf(MinecraftServer server, UUID player) {
        Progress p = ProgressLedger.get(server).peek(player);
        return p == null ? 0 : RankEngine.points(SettingsLedger.settings(server), p);
    }
}
