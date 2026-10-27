package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SettingsFiles;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * Several ladders and awards in the settings (owner decisions D49 to D51, RU-14a): settings from before load as one
 * ladder named after its track counting every signal, ladders and awards survive saving and the JSON files, the
 * first ladder is also written the old way, and the single-ladder edits touch only the first ladder.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class LaddersGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "ladders-model")
    public void settings_from_before_load_as_one_ladder_named_after_its_track(GameTestHelper h) {
        CompoundTag old = new CompoundTag(); // RankUp 0.2.2's form
        old.putString("Track", "Rank-Up");
        ListTag ranks = new ListTag();
        ranks.add(Rank.of("settler", "Settler", 0).save());
        ranks.add(new Rank("warden", "Warden", 2100, true, List.of(Reward.coins(64))).save());
        old.put("Ranks", ranks);
        old.putInt("TitlePriority", 900);
        CompoundTag sig = new CompoundTag();
        sig.put(Signal.PLAYTIME.key(), new SignalSetting(true, 2, 1).save());
        old.put("Signals", sig);
        Settings s = Settings.load(old);
        h.assertTrue(s.ladders().size() == 1, "one ladder: " + s.ladders());
        Ladder l = s.first();
        h.assertTrue(l.name().equals("rank_up") && l.track().equals("Rank-Up") && l.counts().isEmpty() && l.titlePriority() == 900, "named after its track, every signal counted, its priority kept: " + l);
        h.assertTrue(l.ranks().size() == 2 && l.rank("warden").seasonal() && l.rank("warden").rewards().equals(List.of(Reward.coins(64))), "its rungs kept: " + l.ranks());
        h.assertTrue(s.track().equals("Rank-Up") && s.ranks().equals(l.ranks()) && s.titlePriority() == 900, "the single-ladder answers are the first ladder's");
        h.assertTrue(s.signal(Signal.PLAYTIME).points() == 2 && s.awards().isEmpty(), "signals kept, no awards");
        h.assertTrue(Settings.defaults().ladders().size() == 1 && Settings.defaults().first().name().equals("rankup"), "the defaults have one ladder, rankup");
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "ladders-model")
    public void ladders_and_awards_survive_saving_and_the_files(GameTestHelper h) throws Exception {
        Ladder play = new Ladder("playtime", "rankup", Set.of(Signal.PLAYTIME), List.of(Rank.of("wanderer", "Wanderer", 0), Rank.of("settler", "Settler", 120)), 1000);
        Ladder raid = new Ladder("raider", "raider", Set.of(Signal.PLAYER_KILLS, Signal.TOTEMS_STOLEN, Signal.TOTEMS_PROTECTED), List.of(Rank.of("cutpurse", "Cutpurse", 150)), 999);
        Award apple = new Award("apple_picker", "Apple Picker", Set.of(Signal.COINS_EARNED), 5000, false, List.of(Reward.title("&a[Apple Picker]")));
        Settings s = Settings.defaults().toBuilder().ladders(List.of(play, raid)).award(apple).build();
        Settings back = Settings.load(s.save());
        h.assertTrue(back.ladders().equals(List.of(play, raid)), "both ladders back: " + back.ladders());
        h.assertTrue(back.awards().equals(List.of(apple)), "the award back: " + back.awards());
        Settings fromFile = SettingsFiles.fromJson(SettingsFiles.toJson(s));
        h.assertTrue(fromFile.ladders().equals(s.ladders()) && fromFile.awards().equals(s.awards()), "and through the JSON form the files and the database hold");
        CompoundTag saved = s.save();
        h.assertTrue(saved.getString("Track").equals("rankup") && saved.getList("Ranks", 10).size() == 2 && saved.getInt("TitlePriority") == 1000,
                "the first ladder is also written the old way, for a backend on an older RankUp");
        h.assertTrue(back.ladderOf("cutpurse") == back.ladder("raider") && back.ladderOf("apple_picker") == null && back.award("APPLE_PICKER") != null, "lookups by group and name");
        h.assertTrue(play.counts(Signal.PLAYTIME) && !play.counts(Signal.PLAYER_KILLS) && back.first().counts(Signal.PLAYTIME), "a ladder counts only its chosen signals");
        h.assertTrue(Ladder.of("x", "x").counts(Signal.DEATHS), "a ladder with none chosen counts every signal");
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "ladders-model")
    public void the_single_ladder_edits_change_only_the_first_ladder(GameTestHelper h) {
        Ladder raid = new Ladder("raider", "raider", Set.of(Signal.PLAYER_KILLS), List.of(Rank.of("cutpurse", "Cutpurse", 150)), 999);
        Settings s = Settings.defaults().toBuilder().addLadder(raid).track("ranks").addRank(Rank.of("settler", "Settler", 0)).titlePriority(950).build();
        h.assertTrue(s.ladders().size() == 2 && s.first().track().equals("ranks") && s.first().ranks().size() == 1 && s.first().titlePriority() == 950, "the first ladder edited: " + s.first());
        h.assertTrue(s.ladder("raider").equals(raid), "the second untouched");
        Settings none = s.toBuilder().ladders(List.of()).build();
        h.assertTrue(none.ladders().size() == 1, "there is always a ladder");
        h.assertTrue(s.toBuilder().removeLadder("raider").build().ladders().size() == 1 && s.toBuilder().removeAward("nothing").build().awards().isEmpty(), "ladders and awards can be removed");
        h.succeed();
    }
}
