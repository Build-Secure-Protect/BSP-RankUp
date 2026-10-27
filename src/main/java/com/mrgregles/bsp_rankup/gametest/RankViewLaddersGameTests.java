package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.network.RankSync;
import com.mrgregles.bsp_rankup.network.RankViewPacket;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsLedger;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The rank screen's view for several ladders and awards (owner decision D52, RU-16): one standing per ladder, with that
 * ladder's own points and fastest ways, one entry per award (reached, or the points toward it), the holders of every
 * ladder's rungs and every award, all through the packet.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class RankViewLaddersGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "rank-view-ladders")
    public void the_rank_view_has_every_ladders_standing_and_every_award(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ProgressLedger ledger = ProgressLedger.get(server);
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        Ladder play = new Ladder("ru_rv_play", "ru_rv_ptrack", Set.of(Signal.PLAYTIME), List.of(Rank.of("ru_rv_p1", "Settler", 0), Rank.of("ru_rv_p2", "Scout", 600)), 1000);
        Ladder raid = new Ladder("ru_rv_raid", "ru_rv_rtrack", Set.of(Signal.PLAYER_KILLS), List.of(Rank.of("ru_rv_r1", "Cutpurse", 0), Rank.of("ru_rv_r2", "Raider", 750)), 999);
        Award picked = Award.of("ru_rv_picked", "Picked", Set.of(Signal.MOB_KILLS), 3);
        Award open = Award.of("ru_rv_open", "Open", Set.of(Signal.MOB_KILLS), 10);
        SettingsLedger.get(server).set(Settings.defaults().toBuilder().ladders(List.of(play, raid)).awards(List.of(picked, open))
                .signal(Signal.PLAYTIME, new SignalSetting(true, 1, 1)).signal(Signal.PLAYER_KILLS, new SignalSetting(true, 25, 1))
                .signal(Signal.MOB_KILLS, new SignalSetting(true, 1, 1)).build());
        try {
            ledger.setSeason(player, ledger.lastEndedSeason(), 0); // a season's end already applied: nothing moves while the view is read
            ledger.add(player, Signal.PLAYTIME, 300);
            ledger.add(player, Signal.PLAYER_KILLS, 2);
            ledger.add(player, Signal.MOB_KILLS, 4);
            ledger.setRank(player, "ru_rv_p1", 5L);
            ledger.setOtherRank(player, "ru_rv_raid", "ru_rv_r1", 6L);
            ledger.setOtherRank(other, "ru_rv_raid", "ru_rv_r1", 6L);
            ledger.setAward(player, "ru_rv_picked", true, 7L);
            RankViewPacket v = RankSync.view(server, player, false);
            h.assertTrue(v.ladders().size() == 2, "a standing per ladder: " + v.ladders());
            RankViewPacket.LadderView p = v.ladders().get(0), r = v.ladders().get(1);
            h.assertTrue(p.name().equals("ru_rv_play") && p.rank().equals("ru_rv_p1") && p.points() == 300, "the playtime ladder counts only minutes: " + p);
            h.assertTrue(r.name().equals("ru_rv_raid") && r.rank().equals("ru_rv_r1") && r.rankedAt() == 6L && r.points() == 50, "the raid ladder only kills: " + r);
            h.assertTrue(r.fastest().stream().allMatch(f -> f.signal().equals("player_kills")) && !r.fastest().isEmpty(), "its fastest way is its own signal: " + r.fastest());
            h.assertTrue(p.fastest().stream().allMatch(f -> f.signal().equals("playtime")), "and the playtime ladder's: " + p.fastest());
            RankViewPacket.AwardView a1 = v.awards().get(0), a2 = v.awards().get(1);
            h.assertTrue(a1.group().equals("ru_rv_picked") && a1.reached() && a1.reachedAt() == 7L, "a reached award: " + a1);
            h.assertTrue(a2.group().equals("ru_rv_open") && !a2.reached() && a2.have() == 4, "an open award with the points toward it: " + a2);
            h.assertTrue(v.holders().getOrDefault("ru_rv_r1", 0) == 2 && v.holders().getOrDefault("ru_rv_picked", 0) == 1, "holders on a later ladder and an award: " + v.holders());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RankViewPacket.encode(v, buf);
                RankViewPacket back = RankViewPacket.decode(buf);
                h.assertTrue(back.ladders().equals(v.ladders()) && back.awards().equals(v.awards()), "both lists survive the packet");
            } finally {
                buf.release();
            }
        } finally {
            ledger.reset(player);
            ledger.reset(other);
            SettingsLedger.get(server).set(Settings.defaults());
        }
        h.succeed();
    }
}
