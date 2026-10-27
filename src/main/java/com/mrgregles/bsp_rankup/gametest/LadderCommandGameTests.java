package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.api.LadderInfo;
import com.mrgregles.bsp_rankup.api.RankUpApi;
import com.mrgregles.bsp_rankup.command.RankCommand;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Commands, {@code /rank} and the api for several ladders and awards (owner decisions D49 to D53, RU-14d): ladders
 * are added, counted, given rungs and removed by command; rung and reward commands reach any ladder's rung and any
 * award; the first ladder cannot be removed and only it can be renamed; {@code /rank} gives a line per ladder and the
 * awards reached; the api lists ladders and awards.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class LadderCommandGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "ladder-cmd")
    public void ladders_and_awards_are_made_and_changed_by_command(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        CommandGameTests.Collector out = new CommandGameTests.Collector();
        CommandSourceStack console = CommandGameTests.console(h, out);
        SettingsLedger.get(server).set(Settings.defaults());
        try {
            String first = SettingsLedger.settings(server).first().name();
            h.assertTrue(CommandGameTests.run(console, "rankup ladders add ru_lc_raider ru_lc_raid") == 1, "a ladder is added: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup ladders add ru_lc_raider ru_lc_other") == 0, "the same name twice is refused: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup ladders add ru_lc_two " + Settings.DEFAULT_TRACK) == 0, "a track another ladder follows is refused: " + out.last());
            Ladder raider = SettingsLedger.settings(server).ladder("ru_lc_raider");
            h.assertTrue(raider != null && raider.titlePriority() == Settings.DEFAULT_TITLE_PRIORITY - 1 && raider.counts().isEmpty(), "on its track, every signal, titles one below: " + raider);

            h.assertTrue(CommandGameTests.run(console, "rankup ladders counts ru_lc_raider player_kills,totems_stolen") == 1, "its signals set: " + out.last());
            h.assertTrue(SettingsLedger.settings(server).ladder("ru_lc_raider").counts().equals(Set.of(Signal.PLAYER_KILLS, Signal.TOTEMS_STOLEN)), "two signals counted");
            h.assertTrue(CommandGameTests.run(console, "rankup ladders counts ru_lc_raider kills") == 0, "an unknown signal is refused: " + out.last());

            h.assertTrue(CommandGameTests.run(console, "rankup ladders addrung ru_lc_raider ru_lc_r1 0 Cutpurse") == 1, "a rung on the second ladder: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup ladders addrung nope ru_lc_r2 0") == 0, "on a ladder that does not exist: refused");
            h.assertTrue(CommandGameTests.run(console, "rankup ladder add ru_lc_r1 5") == 0, "the same group on the first ladder is refused: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup ladder points ru_lc_r1 25") == 1 && SettingsLedger.settings(server).ladder("ru_lc_raider").rank("ru_lc_r1").points() == 25,
                    "a rung command reaches the second ladder's rung");
            h.assertTrue(SettingsLedger.settings(server).first().ranks().isEmpty(), "and the first ladder is untouched");
            h.assertTrue(CommandGameTests.run(console, "rankup reward add ru_lc_r1 coins 5") == 1
                    && SettingsLedger.settings(server).ladder("ru_lc_raider").rank("ru_lc_r1").rewards().equals(List.of(Reward.coins(5))), "and so does a reward command");
            h.assertTrue(CommandGameTests.run(console, "rankup ladders titlepriority ru_lc_raider 900") == 1 && SettingsLedger.settings(server).ladder("ru_lc_raider").titlePriority() == 900, "its title priority");
            h.assertTrue(CommandGameTests.run(console, "rankup ladders rungs ru_lc_raider") == 1, "its rungs listed");

            h.assertTrue(CommandGameTests.run(console, "rankup ladders rename ru_lc_raider ru_lc_x") == 0, "a later ladder cannot be renamed: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup ladders rename " + first + " ru_lc_main") == 1 && SettingsLedger.settings(server).first().name().equals("ru_lc_main"), "the first can: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup ladders remove ru_lc_main") == 0, "the first ladder cannot be removed: " + out.last());

            h.assertTrue(CommandGameTests.run(console, "rankup award add ru_lc_apple 5000 Apple Picker") == 1, "an award: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup award add ru_lc_r1 10") == 0, "a ladder's rung cannot be an award: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup ladders addrung ru_lc_raider ru_lc_apple 0") == 0, "nor an award a rung: " + out.last());
            h.assertTrue(CommandGameTests.run(console, "rankup award counts ru_lc_apple coins_earned") == 1, "what it counts");
            h.assertTrue(CommandGameTests.run(console, "rankup award seasonal ru_lc_apple true") == 1, "seasonal");
            h.assertTrue(CommandGameTests.run(console, "rankup award points ru_lc_apple 4000") == 1, "its points");
            h.assertTrue(CommandGameTests.run(console, "rankup award name ru_lc_apple Apple Master") == 1, "its name");
            h.assertTrue(CommandGameTests.run(console, "rankup reward add ru_lc_apple item minecraft:apple 16") == 1, "a reward on the award: " + out.last());
            Award apple = SettingsLedger.settings(server).award("ru_lc_apple");
            h.assertTrue(apple != null && apple.counts().equals(Set.of(Signal.COINS_EARNED)) && apple.seasonal() && apple.points() == 4000 && apple.displayName().equals("Apple Master")
                    && apple.rewards().equals(List.of(Reward.item("minecraft:apple", 16))), "the award as set: " + apple);
            h.assertTrue(CommandGameTests.run(console, "rankup reward list ru_lc_apple") == 1 && CommandGameTests.run(console, "rankup reward remove ru_lc_apple 1") == 1
                    && SettingsLedger.settings(server).award("ru_lc_apple").rewards().isEmpty(), "rewards listed and removed on an award");
            h.assertTrue(CommandGameTests.run(console, "rankup award") == 1 && CommandGameTests.run(console, "rankup ladders") == 2, "the lists");
            h.assertTrue(CommandGameTests.run(console, "rankup award remove ru_lc_apple") == 1 && SettingsLedger.settings(server).awards().isEmpty(), "the award removed");
            h.assertTrue(CommandGameTests.run(console, "rankup ladders remove ru_lc_raider") == 1 && SettingsLedger.settings(server).ladders().size() == 1, "the second ladder removed");
        } finally {
            SettingsLedger.get(server).set(Settings.defaults());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "ladder-rank-text")
    public void rank_lists_every_ladder_and_the_awards_reached_and_the_api_sees_them(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID();
        Ladder play = new Ladder("playtime", "ru_lt_play", Set.of(Signal.PLAYTIME), List.of(Rank.of("ru_lt_p1", "Settler", 0), Rank.of("ru_lt_p2", "Scout", 600)), 1000);
        Ladder raid = new Ladder("raider", "ru_lt_raid", Set.of(Signal.PLAYER_KILLS), List.of(Rank.of("ru_lt_r1", "Cutpurse", 0)), 999);
        Award apple = Award.of("ru_lt_apple", "Apple Picker", Set.of(Signal.COINS_EARNED), 5000);
        SettingsLedger.get(server).set(Settings.defaults().toBuilder().ladders(List.of(play, raid)).award(apple).build());
        try {
            ledger.setRank(player, "ru_lt_p1", 1L);
            ledger.setOtherRank(player, "raider", "ru_lt_r1", 2L);
            ledger.setAward(player, "ru_lt_apple", true, 3L);
            List<Component> lines = RankCommand.summary(server, player);
            TranslatableContents title = (TranslatableContents) lines.get(0).getContents();
            TranslatableContents l1 = (TranslatableContents) lines.get(1).getContents();
            TranslatableContents l2 = (TranslatableContents) lines.get(2).getContents();
            h.assertTrue(title.getKey().equals("message.bsp_rankup.rank.title"), "a title line first: " + title.getKey());
            h.assertTrue(l1.getKey().equals("message.bsp_rankup.rank.ladder") && l1.getArgs()[0].equals("Playtime") && l1.getArgs()[1].equals("Settler"), "a line for the first ladder: " + List.of(l1.getArgs()));
            h.assertTrue(lines.get(1).getSiblings().stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t && t.getKey().equals("message.bsp_rankup.rank.ladder_next")), "with its next rung");
            h.assertTrue(l2.getKey().equals("message.bsp_rankup.rank.ladder") && l2.getArgs()[0].equals("Raider") && l2.getArgs()[1].equals("Cutpurse"), "a line for the second: " + List.of(l2.getArgs()));
            h.assertTrue(lines.stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t && t.getKey().equals("message.bsp_rankup.rank.awards") && t.getArgs()[0].equals("Apple Picker")), "the awards reached");

            List<LadderInfo> ladders = RankUpApi.ladders(server);
            h.assertTrue(ladders.size() == 2 && ladders.get(1).name().equals("raider") && ladders.get(1).counts().equals(List.of("player_kills")) && ladders.get(1).titlePriority() == 999, "the api lists the ladders: " + ladders);
            h.assertTrue(RankUpApi.ladder(server).size() == 2 && RankUpApi.ladder(server).get(0).group().equals("ru_lt_p1"), "ladder(server) is still the first ladder");
            h.assertTrue(RankUpApi.rankOf(server, player, "raider").map(r -> r.group().equals("ru_lt_r1")).orElse(false), "a rung on a named ladder");
            h.assertTrue(RankUpApi.hasRank(server, player, "playtime", "ru_lt_p1") && !RankUpApi.hasRank(server, player, "playtime", "ru_lt_p2"), "hasRank on a named ladder");
            h.assertTrue(RankUpApi.progressOf(server, player, "playtime").map(p -> p.next() != null && p.next().group().equals("ru_lt_p2")).orElse(false) && RankUpApi.progressOf(server, player, "nope").isEmpty(), "progress on a named ladder");
            h.assertTrue(RankUpApi.awards(server).size() == 1 && RankUpApi.hasAward(server, player, "ru_lt_apple") && RankUpApi.awardsOf(server, player).equals(List.of("ru_lt_apple")), "the awards");
        } finally {
            ledger.reset(player);
            SettingsLedger.get(server).set(Settings.defaults());
        }
        h.succeed();
    }
}
