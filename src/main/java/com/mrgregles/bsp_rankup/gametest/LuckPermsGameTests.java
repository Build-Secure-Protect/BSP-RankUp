package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge.Outcome;
import com.mrgregles.bsp_rankup.compat.LuckPermsBridge.RankChange;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The LuckPerms bridge against the real LuckPerms Forge mod (5.4.102, dev runs only: see build.gradle and
 * tools/make_luckperms_dev_jar.py). Skips itself with a note when LuckPerms is not on the classpath, so the suite
 * still passes in a checkout without the jar. The bridge talks to LuckPerms by reflection on every route, so this
 * run through the Forge edition exercises the same code the Bukkit plugin on Mohist will see.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class LuckPermsGameTests {
    private static final String TRACK = "ru_test_track";
    private static final List<String> LADDER = List.of("ru_test_a", "ru_test_b", "ru_test_c");
    private static final int TITLE_PRIORITY = 1000;

    @GameTest(template = SetupGameTests.EMPTY, timeoutTicks = 600)
    public void ranks_move_along_a_track_and_titles_are_prefixes(GameTestHelper h) {
        if (!LuckPermsBridge.isPresent()) {
            BSPRankUp.LOGGER.warn("LuckPerms is not loaded in this run; the LuckPerms test did nothing.");
            h.succeed();
            return;
        }
        h.assertTrue(LuckPermsBridge.route() == LuckPermsBridge.Route.FORGE, "dev runs reach the Forge edition: " + LuckPermsBridge.route());
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        try {
            // The track and its groups come from the bridge itself (the D2 option), so an existing track is reordered.
            h.assertTrue(LuckPermsBridge.ensureTrack(TRACK, LADDER).join(), "ensureTrack makes the track and groups");
            h.assertTrue(LuckPermsBridge.trackGroups(TRACK).equals(LADDER), "the track's groups in order: " + LuckPermsBridge.trackGroups(TRACK));
            h.assertTrue(LuckPermsBridge.groupNames().containsAll(LADDER), "the groups exist: " + LuckPermsBridge.groupNames());
            h.assertTrue(LuckPermsBridge.rankOn(id, TRACK).isEmpty(), "a new player is on no rank");

            RankChange first = LuckPermsBridge.promote(id, TRACK).join();
            h.assertTrue(first.outcome() == Outcome.ADDED_TO_FIRST && "ru_test_a".equals(first.to()), "first promotion lands on the first group: " + first);
            h.assertTrue(LuckPermsBridge.rankOn(id, TRACK).equals(Optional.of("ru_test_a")), "rankOn after the first promotion: " + LuckPermsBridge.rankOn(id, TRACK));

            RankChange second = LuckPermsBridge.promote(id, TRACK).join();
            h.assertTrue(second.outcome() == Outcome.PROMOTED && "ru_test_a".equals(second.from()) && "ru_test_b".equals(second.to()), "second promotion moves a to b: " + second);
            h.assertTrue(LuckPermsBridge.groupsOf(id).contains("ru_test_b") && !LuckPermsBridge.groupsOf(id).contains("ru_test_a"), "the old group is left behind: " + LuckPermsBridge.groupsOf(id));

            LuckPermsBridge.promote(id, TRACK).join();
            RankChange top = LuckPermsBridge.promote(id, TRACK).join();
            h.assertTrue(top.outcome() == Outcome.END_OF_TRACK, "a promotion from the top is END_OF_TRACK: " + top);
            h.assertTrue(LuckPermsBridge.rankOn(id, TRACK).equals(Optional.of("ru_test_c")), "still on the top rank: " + LuckPermsBridge.rankOn(id, TRACK));

            RankChange down = LuckPermsBridge.demote(id, TRACK).join();
            h.assertTrue(down.outcome() == Outcome.DEMOTED && "ru_test_b".equals(down.to()), "a demotion moves c to b: " + down);

            h.assertTrue(LuckPermsBridge.setGroup(id, TRACK, "ru_test_c").join(), "setGroup puts the player on c");
            h.assertTrue(LuckPermsBridge.rankOn(id, TRACK).equals(Optional.of("ru_test_c")) && !LuckPermsBridge.groupsOf(id).contains("ru_test_b"), "only c remains of the track: " + LuckPermsBridge.groupsOf(id));
            // The dev server's LuckPerms computes the primary group by parents-by-weight; ensureTrack gave the created groups rising weights, so it follows.
            h.assertTrue(LuckPermsBridge.primaryGroupOf(id).equals(Optional.of("ru_test_c")), "the primary group follows: " + LuckPermsBridge.primaryGroupOf(id));
            h.assertFalse(LuckPermsBridge.setGroup(id, TRACK, "ru_test_nowhere").join(), "a group off the track is refused");

            h.assertTrue(LuckPermsBridge.setPrefix(id, TITLE_PRIORITY, "[Hero] ").join(), "setPrefix adds the title");
            h.assertTrue(LuckPermsBridge.prefixOf(id, TITLE_PRIORITY).equals(Optional.of("[Hero] ")), "prefixOf reads it back: " + LuckPermsBridge.prefixOf(id, TITLE_PRIORITY));
            h.assertTrue(LuckPermsBridge.setPrefix(id, TITLE_PRIORITY, "[Legend] ").join(), "setPrefix replaces the title");
            h.assertTrue(LuckPermsBridge.prefixOf(id, TITLE_PRIORITY).equals(Optional.of("[Legend] ")), "one prefix at the priority: " + LuckPermsBridge.prefixOf(id, TITLE_PRIORITY));
            h.assertTrue(LuckPermsBridge.setPrefix(id, TITLE_PRIORITY, null).join(), "a null prefix clears the title");
            h.assertTrue(LuckPermsBridge.prefixOf(id, TITLE_PRIORITY).isEmpty(), "no prefix left");

            RankChange missing = LuckPermsBridge.promote(id, "ru_no_such_track").join();
            h.assertTrue(missing.outcome() == Outcome.NO_TRACK, "an unknown track is NO_TRACK: " + missing);
        } finally {
            net.luckperms.api.model.user.User user = lp.getUserManager().getUser(id);
            if (user != null) {
                lp.getUserManager().cleanupUser(user);
            }
            net.luckperms.api.track.Track track = lp.getTrackManager().getTrack(TRACK);
            if (track != null) {
                lp.getTrackManager().deleteTrack(track).join();
            }
            for (String name : LADDER) {
                net.luckperms.api.model.group.Group group = lp.getGroupManager().getGroup(name);
                if (group != null) {
                    lp.getGroupManager().deleteGroup(group).join();
                }
            }
            player.discard();
        }
        h.succeed();
    }
}
