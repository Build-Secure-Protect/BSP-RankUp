package com.mrgregles.bsp_rankup.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Chips laid out in rows (owner decisions D116 to D118): the ladder chips of the rank screen and of the admin LADDER
 * tab flow onto more rows when one is full, up to {@code maxRows}; a trailing chip (AWARDS, + LADDER) always comes
 * last, and when chips are left over a "+n" chip says how many. The last row keeps room for both. Plain arithmetic
 * in GUI units, so a GameTest can check it.
 */
public final class ChipRows {
    /** A chip placed: its index in the widths given, its x from the row's left and its row. */
    public record Placed(int index, int x, int row) {
    }

    /** Where everything went: the chips placed, how many did not fit, the "+n" chip and the trailing chip, the rows used. */
    public record Layout(List<Placed> chips, int hidden, int moreX, int moreRow, int tailX, int tailRow, int rows) {
    }

    private ChipRows() {
    }

    /**
     * Lays out chips of {@code widths}, then a trailing chip {@code tailW} wide (0 for none), between {@code left} and
     * {@code right}, {@code gap} apart; a "+n" chip is {@code moreW} wide.
     */
    public static Layout lay(int[] widths, int tailW, int left, int right, int maxRows, int moreW, int gap) {
        List<Placed> placed = new ArrayList<>();
        int cx = left, row = 0, hidden = 0, n = widths.length;
        for (int i = 0; i < n; i++) {
            int w = widths[i];
            if (cx + w > right && cx > left) {
                if (row == maxRows - 1) {
                    hidden = n - i;
                    break;
                }
                row++;
                cx = left;
            }
            if (row == maxRows - 1) {
                // the last row keeps room for the trailing chip, and for "+n" unless this is the last chip
                int need = w + (tailW > 0 ? gap + tailW : 0) + (i == n - 1 ? 0 : gap + moreW);
                if (cx + need > right) {
                    hidden = n - i;
                    break;
                }
            }
            placed.add(new Placed(i, cx, row));
            cx += w + gap;
        }
        int moreX = -1, moreRow = row;
        if (hidden > 0) {
            moreX = cx;
            cx += moreW + gap;
        }
        if (tailW > 0 && cx + tailW > right && cx > left && row < maxRows - 1) {
            row++;
            cx = left;
        }
        return new Layout(placed, hidden, moreX, moreRow, tailW > 0 ? cx : -1, row, row + 1);
    }
}
