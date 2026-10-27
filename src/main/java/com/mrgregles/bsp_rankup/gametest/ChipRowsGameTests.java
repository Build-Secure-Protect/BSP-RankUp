package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.client.ChipRows;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/**
 * The ladder chips past one row (owner decisions D116 to D118): one row as before while they fit, then more rows, the
 * trailing chip (AWARDS, + LADDER) always last, at most three rows with "+n" for the rest, nothing past the edge.
 * The layout is plain arithmetic shared by both screens, so it is checked here without a client.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class ChipRowsGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final int RIGHT = 504, TAIL = 52, MORE = 22, GAP = 3;

    @GameTest(template = EMPTY)
    public void ladder_chips_wrap_with_the_trailing_chip_last(GameTestHelper h) {
        ChipRows.Layout one = lay(3, TAIL);
        h.assertTrue(one.rows() == 1 && one.chips().size() == 3 && one.tailRow() == 0 && one.tailX() == 3 * 53 && one.hidden() == 0,
                "three ladders: one row as today, AWARDS after them: " + one);

        ChipRows.Layout two = lay(14, TAIL);
        h.assertTrue(two.rows() == 2 && two.chips().size() == 14 && two.hidden() == 0 && two.chips().get(8).row() == 0 && two.chips().get(9).row() == 1 && two.chips().get(9).x() == 0,
                "fourteen: nine on the first row, the rest on the second (D116): " + two);
        h.assertTrue(two.tailRow() == 1 && two.tailX() == 5 * 53, "AWARDS last, on the second row: " + two);

        ChipRows.Layout pushed = lay(9, TAIL);
        h.assertTrue(pushed.rows() == 2 && pushed.tailRow() == 1 && pushed.tailX() == 0, "nine fill the row: AWARDS starts the next: " + pushed);

        ChipRows.Layout many = lay(40, TAIL);
        h.assertTrue(many.rows() == 3 && many.chips().size() == 26 && many.hidden() == 14, "at most three rows, the rest counted (D118): " + many);
        h.assertTrue(many.moreRow() == 2 && many.moreX() == 8 * 53 && many.tailRow() == 2 && many.tailX() == many.moreX() + MORE + GAP && many.tailX() + TAIL <= RIGHT,
                "\"+n\" then AWARDS fit on the last row: " + many);

        ChipRows.Layout bare = lay(14, 0);
        h.assertTrue(bare.tailX() == -1 && bare.rows() == 2, "no awards: no trailing chip: " + bare);

        for (ChipRows.Layout l : new ChipRows.Layout[]{one, two, pushed, many, bare}) {
            for (ChipRows.Placed p : l.chips()) {
                h.assertTrue(p.x() + 50 <= RIGHT, "every chip inside the screen: " + p);
            }
        }
        h.succeed();
    }

    private static ChipRows.Layout lay(int ladders, int tail) {
        int[] widths = new int[ladders];
        Arrays.fill(widths, 50);
        return ChipRows.lay(widths, tail, 0, RIGHT, 3, MORE, GAP);
    }
}
