package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.BspCore;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminSync;
import com.mrgregles.bsp_rankup.network.AdminViewPacket;
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
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PLAYERS for every ladder (owner decisions D98 to D105, PL-1): the selected player's standing carries a line per
 * ladder (its rung, points and this season's) and per award (the points that count toward it, this season's for a
 * seasonal one, and whether it is reached), it survives the wire, and SET on a later ladder moves the player on that
 * ladder only.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class PlayersGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    /** Tracks and groups with the test's own suffix: GameTests share one LuckPerms. */
    private record Names(String sfx) {
        String playTrack() { return "ru_plt_play" + sfx; }
        String raidTrack() { return "ru_plt_raid" + sfx; }
        String pa() { return "ru_plp_a" + sfx; }
        String pb() { return "ru_plp_b" + sfx; }
        String ra() { return "ru_plr_a" + sfx; }
        String rb() { return "ru_plr_b" + sfx; }
        String award() { return "ru_plaw_x" + sfx; }
        String seasonal() { return "ru_plaw_y" + sfx; }
    }

    private static Settings settings(Names n) {
        Ladder play = new Ladder("ru_pl_play", n.playTrack(), Set.of(Signal.PLAYTIME), List.of(Rank.of(n.pa(), "Play A", 0), Rank.of(n.pb(), "Play B", 100)), Settings.DEFAULT_TITLE_PRIORITY);
        Ladder raid = new Ladder("ru_pl_raid", n.raidTrack(), Set.of(Signal.PLAYER_KILLS), List.of(Rank.of(n.ra(), "Raid A", 0), Rank.of(n.rb(), "Raid B", 50)), 990);
        Award award = new Award(n.award(), "Award X", Set.of(Signal.MOB_KILLS), 100, false, List.of());
        Award seasonal = new Award(n.seasonal(), "Award Y", Set.of(Signal.MOB_KILLS), 100, true, List.of());
        return Settings.defaults().toBuilder().ladders(List.of(play, raid)).awards(List.of(award, seasonal))
                .signal(Signal.PLAYTIME, new SignalSetting(true, 1, 1))
                .signal(Signal.PLAYER_KILLS, new SignalSetting(true, 25, 1))
                .signal(Signal.MOB_KILLS, new SignalSetting(true, 1, 1)).build();
    }

    @GameTest(template = EMPTY, batch = "players-ladders")
    public void the_selected_player_shows_every_ladder_and_award(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Names n = new Names("_1");
        UUID id = UUID.randomUUID();
        ProgressLedger ledger = ProgressLedger.get(server);
        SettingsLedger.get(server).set(settings(n));
        try {
            ledger.add(id, Signal.PLAYTIME, 120);
            ledger.add(id, Signal.PLAYER_KILLS, 2);
            ledger.add(id, Signal.MOB_KILLS, 2);
            ledger.addBonus(id, 5); // on every ladder and award (D102)
            long now = System.currentTimeMillis();
            ledger.setRank(id, n.pb(), now);
            ledger.setOtherRank(id, "ru_pl_raid", n.rb(), now);
            ledger.setAward(id, n.award(), true, now);
            ledger.setSeason(id, ledger.progress(id).season(), 0, Map.of(Progress.awardKey(n.seasonal()), 4L));

            AdminViewPacket.PlayerDetail d = AdminSync.detail(server, id);
            h.assertTrue(d.ladders().size() == 2, "a line per ladder: " + d.ladders());
            AdminViewPacket.LadderLine play = d.ladders().get(0), raid = d.ladders().get(1);
            h.assertTrue(play.ladder().equals("ru_pl_play") && play.rank().equals(n.pb()) && play.points() == 125,
                    "the first ladder: its rung and its own signals plus the bonus (120 + 5): " + play);
            h.assertTrue(raid.ladder().equals("ru_pl_raid") && raid.rank().equals(n.rb()) && raid.points() == 55 && raid.seasonPoints() == 55,
                    "the later ladder: its rung and its own signals plus the bonus (2 kills x 25 + 5): " + raid);
            h.assertTrue(d.rank().equals(n.pb()) && d.points() == 125, "the first ladder's fields are kept for the list: " + d);
            h.assertFalse(d.online(), "a player not on this server is away, whatever the list shows (AU-2b)");
            h.assertTrue(d.awards().size() == 2, "a line per award: " + d.awards());
            AdminViewPacket.AwardLine x = d.awards().get(0), y = d.awards().get(1);
            h.assertTrue(x.group().equals(n.award()) && x.points() == 7 && x.reached(), "an award reached, with its points (2 + 5): " + x);
            h.assertTrue(y.group().equals(n.seasonal()) && y.points() == 3 && !y.reached(), "a seasonal award counts this season's points only (7 - 4): " + y);

            ledger.reset(id);
            AdminViewPacket.PlayerDetail fresh = AdminSync.detail(server, id);
            h.assertTrue(fresh.ladders().stream().allMatch(l -> l.rank().isEmpty() && l.points() == 0) && fresh.awards().stream().noneMatch(AdminViewPacket.AwardLine::reached),
                    "RESET forgets every ladder and award (D104): " + fresh);

            AdminViewPacket view = new AdminViewPacket(new CompoundTag(), Map.of(), List.of(), d, List.of(), "", 0, "", true, false, Map.of(), List.of(), List.of(), List.of());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            AdminViewPacket.encode(view, buf);
            AdminViewPacket back = AdminViewPacket.decode(buf);
            h.assertTrue(back.selected().ladders().equals(d.ladders()) && back.selected().awards().equals(d.awards()), "the lines survive the wire: " + back.selected());
            h.assertTrue(AdminViewPacket.PlayerDetail.none().ladders().isEmpty() && AdminViewPacket.PlayerDetail.none().awards().isEmpty(), "nobody selected: no lines");

            // a name longer than the wire takes (settings read from a file are not length-checked) is cut, never thrown (AU-2b)
            String longGroup = "ru_plaw_" + "z".repeat(70);
            AdminViewPacket.PlayerDetail wide = new AdminViewPacket.PlayerDetail(id, "n".repeat(60), Map.of(), 0, longGroup, 0, 0, 0, 0, true,
                    List.of(new AdminViewPacket.LadderLine("ru_pl_play", longGroup, 1, 1)), List.of(new AdminViewPacket.AwardLine(longGroup, 1, false, false)));
            FriendlyByteBuf wideBuf = new FriendlyByteBuf(Unpooled.buffer());
            AdminViewPacket.encode(new AdminViewPacket(new CompoundTag(), Map.of(), List.of(), wide, List.of(), "", 0, "", true, false, Map.of(), List.of(), List.of(), List.of()), wideBuf);
            AdminViewPacket.PlayerDetail cut = AdminViewPacket.decode(wideBuf).selected();
            h.assertTrue(cut.online() && cut.rank().length() == 64 && cut.name().length() == 48 && cut.ladders().get(0).rank().length() == 64
                    && cut.awards().get(0).group().length() == 64, "long names cut to the wire's limits: " + cut);
        } finally {
            ledger.reset(id);
            SettingsLedger.get(server).set(Settings.defaults());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "players-set")
    public void set_on_a_later_ladder_moves_the_player_on_that_ladder_only(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        Names n = new Names("_2");
        if (LuckPermsBridge.isPresent()) {
            LuckPermsBridge.ensureTrack(n.playTrack(), List.of(n.pa(), n.pb())).join();
            LuckPermsBridge.ensureTrack(n.raidTrack(), List.of(n.ra(), n.rb())).join();
        }
        SettingsLedger.get(server).set(settings(n));
        List<? extends String> previousAdmins = BspCore.setAdminNames(List.of(player.getGameProfile().getName()));
        level.addNewPlayer(player);
        RankEngine.onLogin(player);
        ProgressLedger ledger = ProgressLedger.get(server);
        h.startSequence()
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(n.pa().equals(p.rank()) && p.otherRank("ru_pl_raid") != null && n.ra().equals(p.otherRank("ru_pl_raid").group()),
                            "each ladder's first rung (0 points): " + p);
                })
                .thenExecute(() -> AdminSync.apply(player, AdminActionPacket.of(AdminActionPacket.PLAYER_SET, id.toString(), n.rb())))
                .thenWaitUntil(() -> {
                    Progress p = ledger.progress(id);
                    h.assertTrue(n.rb().equals(p.otherRank("ru_pl_raid").group()), "SET on the raid ladder (D100): " + p);
                    h.assertTrue(n.pa().equals(p.rank()), "the first ladder is where it was: " + p);
                    AdminViewPacket.PlayerDetail detail = AdminSync.detail(server, id);
                    h.assertTrue(detail.online(), "a player on this server shows online");
                    AdminViewPacket.LadderLine raid = detail.ladders().get(1);
                    h.assertTrue(raid.rank().equals(n.rb()), "the screen's raid line shows it: " + raid);
                    h.assertTrue(p.pending().isEmpty(), "no rewards, as before");
                })
                .thenExecute(() -> {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                    BspCore.setAdminNames(List.copyOf(previousAdmins));
                    SettingsLedger.get(server).set(Settings.defaults());
                    ledger.reset(id);
                    if (LuckPermsBridge.isPresent()) {
                        var lp = net.luckperms.api.LuckPermsProvider.get();
                        var user = lp.getUserManager().getUser(id);
                        if (user != null) {
                            lp.getUserManager().cleanupUser(user);
                        }
                        for (String t : List.of(n.playTrack(), n.raidTrack())) {
                            var track = lp.getTrackManager().getTrack(t);
                            if (track != null) {
                                lp.getTrackManager().deleteTrack(track).join();
                            }
                        }
                        for (String g : List.of(n.pa(), n.pb(), n.ra(), n.rb())) {
                            var group = lp.getGroupManager().getGroup(g);
                            if (group != null) {
                                lp.getGroupManager().deleteGroup(group).join();
                            }
                        }
                    }
                })
                .thenSucceed();
    }
}
