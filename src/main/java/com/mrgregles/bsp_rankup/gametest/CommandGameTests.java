package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.command.RankCommand;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.signal.KillCooldowns;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** {@code /rankup} and {@code /rank}, run through the command dispatcher with a source that collects the replies. */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class CommandGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "cmd-ladder")
    public void rankup_commands_build_the_ladder_rewards_signals_and_options(GameTestHelper h) throws IOException {
        MinecraftServer server = h.getLevel().getServer();
        Collector out = new Collector();
        CommandSourceStack console = console(h, out);
        try {
            h.assertTrue(run(console, "rankup ladder add ru_c_a 0 Alpha Rank") == 1, "a rung is added: " + out);
            h.assertTrue(run(console, "rankup ladder add ru_c_b 50") == 1, "a rung without a name");
            h.assertTrue(run(console, "rankup ladder add ru_c_b 50") == 0, "the same group twice is refused: " + out.last());
            Settings s = settings(server);
            h.assertTrue(s.ranks().size() == 2 && s.ranks().get(0).displayName().equals("Alpha Rank") && s.ranks().get(1).displayName().equals("ru_c_b"), "two rungs, the second named after its group: " + s.ranks());

            h.assertTrue(run(console, "rankup reward add ru_c_b pieces 10") == 1, "pieces reward");
            h.assertTrue(run(console, "rankup reward add ru_c_b item minecraft:stick 3") == 1, "item reward");
            h.assertTrue(run(console, "rankup reward add ru_c_b item minecraft:no_such_thing") == 0, "an unknown item is refused: " + out.last());
            h.assertTrue(run(console, "rankup reward add ru_c_b title [B]") == 1, "title reward");
            h.assertTrue(run(console, "rankup reward add ru_c_b command say hi there") == 1, "command reward");
            h.assertTrue(run(console, "rankup reward add ru_c_zzz coins 5") == 0, "a rung that does not exist is refused: " + out.last());
            s = settings(server);
            h.assertTrue(s.ranks().get(1).rewards().size() == 4 && s.ranks().get(1).rewards().get(3).equals(Reward.command("say hi there")), "four rewards, the command whole: " + s.ranks().get(1).rewards());
            out.lines.clear();
            run(console, "rankup reward list ru_c_b");
            h.assertTrue(out.lines.size() == 5, "a header and four lines: " + out);
            h.assertTrue(run(console, "rankup reward remove ru_c_b 4") == 1 && settings(server).ranks().get(1).rewards().size() == 3, "the fourth reward removed");
            h.assertTrue(run(console, "rankup reward remove ru_c_b 9") == 0, "removing a reward that is not there is refused");

            h.assertTrue(run(console, "rankup signal mob_kills off") == 1 && !settings(server).signal(Signal.MOB_KILLS).on(), "a signal switched off");
            h.assertTrue(run(console, "rankup signal playtime points 3 per 5") == 1 && settings(server).signal(Signal.PLAYTIME).points() == 3 && settings(server).signal(Signal.PLAYTIME).per() == 5, "points per units set");
            h.assertTrue(run(console, "rankup signal nope on") == 0, "an unknown signal is refused: " + out.last());
            out.lines.clear();
            run(console, "rankup signal");
            h.assertTrue(out.lines.size() == Signal.values().length, "one line per signal: " + out.lines.size());

            h.assertTrue(run(console, "rankup option announce network") == 1 && settings(server).announce() == Settings.Announce.NETWORK, "announce set");
            h.assertTrue(run(console, "rankup option track ru_cmd_track") == 1 && settings(server).track().equals("ru_cmd_track"), "track set");
            h.assertTrue(run(console, "rankup option cooldown 2") == 1 && KillCooldowns.cooldownMs() == 120_000L, "the cooldown reaches the kill rule: " + KillCooldowns.cooldownMs());
            h.assertTrue(run(console, "rankup ladder seasonal ru_c_b true") == 1 && settings(server).ranks().get(1).seasonal(), "seasonal set");
            h.assertTrue(run(console, "rankup ladder points ru_c_b 75") == 1 && settings(server).ranks().get(1).points() == 75, "points set");
            h.assertTrue(run(console, "rankup ladder name ru_c_b Beta") == 1 && settings(server).ranks().get(1).displayName().equals("Beta"), "name set");
            h.assertTrue(run(console, "rankup ladder move ru_c_b 1") == 1 && settings(server).indexOf("ru_c_b") == 0, "moved to the first rung");
            h.assertTrue(run(console, "rankup ladder move ru_c_b 9") == 0, "cannot move past the end: " + out.last());
            h.assertTrue(run(console, "rankup ladder move ru_c_b 2") == 1 && settings(server).indexOf("ru_c_b") == 1, "moved back to the second");
            out.lines.clear();
            run(console, "rankup ladder list");
            h.assertTrue(out.lines.size() == 3 && out.lines.get(2).contains("Beta (ru_c_b) at 75 points, 3 rewards, seasonal"), "the list: " + out);

            h.assertTrue(run(console, "rankup export cmdtest") == 1 && Files.exists(SettingsFiles.file("cmdtest")), "exported to " + SettingsFiles.file("cmdtest"));
            h.assertTrue(run(console, "rankup ladder remove ru_c_a") == 1 && settings(server).ranks().size() == 1, "a rung removed");
            h.assertTrue(run(console, "rankup ladder remove ru_c_zzz") == 0, "removing a rung that is not there is refused");
            h.assertTrue(run(console, "rankup import cmdtest") == 1, "imported: " + out.last());
            s = settings(server);
            h.assertTrue(s.ranks().size() == 2 && s.ranks().get(1).rewards().size() == 3 && s.track().equals("ru_cmd_track") && s.signal(Signal.PLAYTIME).per() == 5, "the export holds everything: " + s.ranks());
            h.assertTrue(run(console, "rankup import no_such_file") == 0, "a missing file is refused: " + out.last());
            h.assertTrue(run(console, "rankup export bad/name") == 0, "a bad file name is refused");
            out.lines.clear();
            run(console, "rankup info");
            h.assertTrue(out.lines.size() == 3 && out.lines.get(0).contains("2 rungs"), "info: " + out);
        } finally {
            SettingsLedger.get(server).set(Settings.defaults());
            Files.deleteIfExists(SettingsFiles.file("cmdtest"));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cmd-player")
    public void rankup_set_add_reset_and_rank_show_a_players_progress(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        String name = player.getGameProfile().getName();
        Collector out = new Collector();
        CommandSourceStack console = console(h, out);
        Collector mine = new Collector();
        CommandSourceStack asPlayer = asPlayer(h, player, mine);
        LadderGameTests.install(server);
        level.addNewPlayer(player);
        ProgressLedger ledger = ProgressLedger.get(server);

        h.assertTrue(run(asPlayer, "rankup info") == 0, "a plain player cannot use /rankup");
        h.assertTrue(run(console, "rankup add nobody_at_all_xyz 5") == 0, "an unknown player is refused: " + out.last());
        h.assertTrue(run(console, "rankup add " + name + " 60") == 1, "points added: " + out.last());
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue("ru_l_b".equals(ledger.progress(player.getUUID()).rank()), "60 points reach the second rung: " + ledger.progress(player.getUUID())))
                .thenExecute(() -> {
                    List<Component> lines = RankCommand.summary(server, player.getUUID());
                    TranslatableContents header = (TranslatableContents) lines.get(0).getContents();
                    h.assertTrue(header.getKey().equals("message.bsp_rankup.rank.header") && header.getArgs()[1].equals("B") && header.getArgs()[4].equals(60L), "the header names the rank and points: " + List.of(header.getArgs()));
                    TranslatableContents next = (TranslatableContents) lines.get(1).getContents();
                    h.assertTrue(next.getKey().equals("message.bsp_rankup.rank.next") && next.getArgs()[0].equals("C") && next.getArgs()[2].equals(40L), "the next rung and the points to go: " + List.of(next.getArgs()));
                    h.assertTrue(lines.stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t && t.getKey().equals("message.bsp_rankup.rank.bonus")), "the bonus line shows");
                    mine.lines.clear();
                    h.assertTrue(run(asPlayer, "rank") == 1 && mine.lines.size() == lines.size(), "/rank as the player prints the same lines: " + mine);
                    out.lines.clear();
                    h.assertTrue(run(console, "rank " + name) == 1 && out.lines.size() == lines.size(), "/rank <player> from the console: " + out);
                    h.assertTrue(run(console, "rankup set " + name + " ru_l_zzz") == 0, "setting a rung that is not there is refused: " + out.last());
                    h.assertTrue(run(console, "rankup set " + name + " ru_l_c") == 1, "set to the top rung");
                })
                .thenWaitUntil(() -> {
                    h.assertTrue("ru_l_c".equals(ledger.progress(player.getUUID()).rank()), "set took: " + ledger.progress(player.getUUID()));
                    h.assertTrue(player.getInventory().countItem(Items.BREAD) == 0, "an admin's set gives no rewards");
                })
                .thenExecute(() -> h.assertTrue(run(console, "rankup reset " + name) == 1, "reset: " + out.last()))
                .thenWaitUntil(() -> {
                    h.assertTrue("ru_l_a".equals(ledger.progress(player.getUUID()).rank()) && ledger.progress(player.getUUID()).bonus() == 0, "after a reset the player starts over on the first rung: " + ledger.progress(player.getUUID()));
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    LadderGameTests.uninstall(server, player.getUUID());
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ helpers

    private static Settings settings(MinecraftServer server) {
        return SettingsLedger.settings(server);
    }

    private static int run(CommandSourceStack source, String command) {
        return source.getServer().getCommands().performPrefixedCommand(source, command);
    }

    private static CommandSourceStack console(GameTestHelper h, Collector out) {
        MinecraftServer server = h.getLevel().getServer();
        return new CommandSourceStack(out, Vec3.ZERO, Vec2.ZERO, h.getLevel(), 4, "test console", Component.literal("test console"), server, null);
    }

    private static CommandSourceStack asPlayer(GameTestHelper h, ServerPlayer player, Collector out) {
        MinecraftServer server = h.getLevel().getServer();
        return new CommandSourceStack(out, player.position(), Vec2.ZERO, h.getLevel(), 0, player.getGameProfile().getName(), player.getDisplayName(), server, player);
    }

    /** Collects what a command says, success and failure alike. */
    static final class Collector implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            lines.add(component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        String last() {
            return lines.isEmpty() ? "(nothing)" : lines.get(lines.size() - 1);
        }

        @Override
        public String toString() {
            return String.join(" | ", lines);
        }
    }
}
