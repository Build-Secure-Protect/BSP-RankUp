package com.mrgregles.bsp_rankup.client;

import com.mrgregles.bsp_rankup.network.RankActionPacket;
import com.mrgregles.bsp_rankup.network.RankUpNetwork;
import com.mrgregles.bsp_rankup.network.RankViewPacket;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.mrgregles.bsp_rankup.client.AdminScreen.BAD;
import static com.mrgregles.bsp_rankup.client.AdminScreen.BG;
import static com.mrgregles.bsp_rankup.client.AdminScreen.GOLD;
import static com.mrgregles.bsp_rankup.client.AdminScreen.INSET;
import static com.mrgregles.bsp_rankup.client.AdminScreen.LINE;
import static com.mrgregles.bsp_rankup.client.AdminScreen.MUTED;
import static com.mrgregles.bsp_rankup.client.AdminScreen.TEXT;
import static com.mrgregles.bsp_rankup.client.AdminScreen.TQ;
import static com.mrgregles.bsp_rankup.client.AdminScreen.VIO;
import static com.mrgregles.bsp_rankup.client.AdminScreen.frame;
import static com.mrgregles.bsp_rankup.client.AdminScreen.inset;

/**
 * The player's rank screen (owner decisions D38 to D46, the "ladder card"): the ladder drawn on a track down the
 * left with the player's rung in gold, and on the right their rank, title, points, the bar to the next rung, what
 * it gives, the fastest ways there, and every signal that counts with its count, worth, share and points. Read
 * only; while it is open it asks the server for a fresh view once a second (counted in game ticks, not frames).
 */
public class RankScreen extends HoloScreen {
    static final int W = 520, H = 300;
    private static final int LADDER_X = 8, LADDER_Y = 24, LADDER_W = 172, LADDER_H = 260; // ends with the right pane, above the hint line
    private static final int PANE_X = 190, PANE_W = 322;
    private static final int REFRESH_TICKS = 20;

    private int ticks;
    private int ladderScroll;
    /** False until the ladder has been scrolled to the player's own rung once (it opens on the top rungs otherwise). */
    private boolean ladderPlaced;
    @Nullable
    private String hovered;
    /** What each rung gives, as text, for the view it was built from (rebuilt when a new view arrives). */
    private final Map<String, String> rewardText = new HashMap<>();
    @Nullable
    private RankViewPacket rewardTextFor;

    public RankScreen() {
        super(Component.literal(Tr.t("your_rank")), W, H);
    }

    @Nullable
    private RankViewPacket view() {
        return RankUpClient.rankView();
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void text(GuiGraphics g, String s, int x, int y, int colour, float scale) {
        AdminScreen.text(g, font, s, x, y, colour, scale);
    }

    private void textRight(GuiGraphics g, String s, int right, int y, int colour, float scale) {
        text(g, s, right - (int) (font.width(s) * scale), y, colour, scale);
    }

    private String clip(String s, int maxW, float scale) {
        return font.plainSubstrByWidth(s, (int) (maxW / scale));
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, BG);
        frame(g, x, y, W, H, TQ);
        text(g, Tr.t("your_rank"), x + 10, y + 8, TQ, 1f);
        RankViewPacket v = view();
        if (v == null) {
            text(g, Tr.t("waiting_for_the_server"), x + 10, y + 40, MUTED, 0.75f);
            super.draw(g, mouseX, mouseY, partial);
            return;
        }
        Settings s = RankUpClient.rankSettings();
        if (s == null) {
            return;
        }
        String who = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getGameProfile().getName();
        String head = Tr.t("name_server_season", who, v.server().isEmpty() ? Tr.t("this_server") : v.server(), v.season());
        int headRoom = W - 20 - (int) font.width(Tr.t("your_rank")) - 8;
        textRight(g, clip(head, headRoom, 0.6f), x + W - 10, y + 10, MUTED, 0.6f);
        hovered = null;
        drawLadder(g, x, y, mouseX, mouseY, s, v);
        drawStanding(g, x, y, s, v);
        super.draw(g, mouseX, mouseY, partial);
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) {
            RankUpNetwork.CHANNEL.sendToServer(new RankActionPacket(RankActionPacket.REFRESH));
        }
    }

    // ------------------------------------------------------------------ the ladder

    private void drawLadder(GuiGraphics g, int x, int y, int mouseX, int mouseY, Settings s, RankViewPacket v) {
        int lx = x + LADDER_X, ly = y + LADDER_Y;
        inset(g, lx, ly, LADDER_W, LADDER_H);
        List<Rank> ranks = s.ranks();
        if (ranks.isEmpty()) {
            text(g, Tr.t("no_ladder_yet"), lx + 8, ly + 10, MUTED, 0.75f);
            text(g, clip(Tr.t("an_admin_sets_the_ranks_up"), LADDER_W - 16, 0.6f), lx + 8, ly + 22, MUTED, 0.6f);
            return;
        }
        int current = s.indexOf(v.rank());
        int n = ranks.size();
        int step = Mth.clamp((LADDER_H - 12) / n, 30, 44);
        int visible = Math.max(1, (LADDER_H - 12) / step);
        if (!ladderPlaced) {
            // open with the player's rung and the next one in view: the top row shows the rung half a screen above theirs
            ladderPlaced = true;
            int top = Math.min(n - 1, Math.max(current + 1, 0) + visible / 2);
            ladderScroll = n - 1 - top;
        }
        ladderScroll = Mth.clamp(ladderScroll, 0, Math.max(0, n - visible));
        int trackX = lx + 16;
        // the track: top rung first (highest), the lit part from the bottom up to the player's rung
        int firstShown = n - 1 - ladderScroll; // index of the rung drawn at the top
        int lastShown = Math.max(0, firstShown - visible + 1);
        int top = ly + 6 + step / 2, bottom = ly + 6 + (firstShown - lastShown) * step + step / 2;
        g.fill(trackX - 1, top, trackX + 1, bottom, LINE);
        if (current >= lastShown) {
            int litTop = ly + 6 + (firstShown - Math.min(current, firstShown)) * step + step / 2;
            g.fill(trackX - 1, litTop, trackX + 1, bottom, TQ);
        }
        for (int row = 0; row < visible; row++) {
            int i = firstShown - row;
            if (i < 0) {
                break;
            }
            Rank r = ranks.get(i);
            int cy = ly + 6 + row * step;
            boolean mine = i == current, below = i < current;
            int nodeColour = mine ? GOLD : below ? TQ : r.seasonal() ? VIO : LINE;
            g.fill(trackX - 3, cy + step / 2 - 3, trackX + 3, cy + step / 2 + 3, nodeColour);
            int cx = trackX + 10, cw = LADDER_W - 34, ch = step - 6;
            boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + ch;
            g.fill(cx, cy, cx + cw, cy + ch, mine ? 0x22FFD23A : hover ? 0x14FFFFFF : INSET);
            frame(g, cx, cy, cw, ch, mine ? GOLD : hover ? TEXT : LINE);
            // the threshold at the right, the season mark just left of it, the name in what is left
            String threshold = String.valueOf(r.points());
            int pointsLeft = cx + cw - 4 - (int) (font.width(threshold) * 0.7f);
            int nameRight = pointsLeft - 4;
            if (r.seasonal()) {
                textRight(g, Tr.t("season_short"), pointsLeft - 3, cy + 4, VIO, 0.6f);
                nameRight -= 3 + (int) (font.width(Tr.t("season_short")) * 0.6f);
            }
            text(g, clip(r.displayName(), nameRight - cx - 4, 0.85f), cx + 4, cy + 3, mine ? GOLD : below ? TEXT : MUTED, 0.85f);
            textRight(g, threshold, cx + cw - 4, cy + 4, GOLD, 0.7f);
            int holders = v.holders().getOrDefault(r.group(), 0);
            String line;
            if (mine) {
                line = v.rankedAt() > 0 ? Tr.t("you_since", ago(v.rankedAt())) : Tr.t("you");
            } else if (i == current + 1) {
                long toGo = r.points() - (r.seasonal() ? v.seasonPoints() : v.points());
                line = Tr.t("next_to_go", Math.max(0, toGo));
            } else {
                line = Tr.n("players_hold_it", holders);
            }
            if (ch > 20) {
                text(g, clip(line, cw - 8, 0.6f), cx + 4, cy + 14, mine ? GOLD : MUTED, 0.6f);
            }
            if (hover) {
                hovered = r.group();
            }
        }
        if (n > visible) {
            int barH = Math.max(6, (LADDER_H - 2) * visible / n);
            int barY = ly + 1 + (LADDER_H - 2 - barH) * ladderScroll / Math.max(1, n - visible);
            g.fill(lx + LADDER_W - 3, barY, lx + LADDER_W - 1, barY + barH, LINE);
        }
        Rank h = hovered == null ? null : s.rank(hovered);
        if (h != null) {
            String gives = h.rewards().isEmpty() ? Tr.t("gives_nothing") : Tr.t("gives", rewards(h));
            text(g, clip(h.displayName() + ": " + gives, W - 20, 0.6f), x + 10, y + H - 10, MUTED, 0.6f);
        } else {
            text(g, Tr.t("hover_a_rung_esc_closes"), x + 10, y + H - 10, MUTED, 0.6f);
        }
    }

    private static String ago(long ms) {
        long days = (System.currentTimeMillis() - ms) / 86_400_000L;
        return days < 1 ? Tr.t("today") : Tr.n("days", days);
    }

    private String rewards(Rank r) {
        RankViewPacket v = view();
        if (v != rewardTextFor) {
            rewardText.clear();
            rewardTextFor = v;
        }
        return rewardText.computeIfAbsent(r.group(), k -> describeRewards(r, v));
    }

    private static String describeRewards(Rank r, @Nullable RankViewPacket v) {
        List<String> parts = new ArrayList<>();
        for (Reward rw : r.rewards()) {
            parts.add(switch (rw.kind()) {
                case COINS -> Tr.t("coins_worth_copper", rw.amount());
                case PIECES -> Tr.t("pieces_into_the_bank", rw.amount());
                case ITEM -> rw.amount() + " x " + itemName(rw.value());
                case TITLE -> Tr.t("the_title", rw.value());
                case COMMAND -> Tr.t("a_reward_from_the_server");
                case KIT -> Tr.t("the_kit", v == null ? rw.value() : v.kits().getOrDefault(rw.value(), rw.value()));
            });
        }
        return String.join(" - ", parts);
    }

    private static String itemName(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
        return item == null || item == Items.AIR ? id : new ItemStack(item).getHoverName().getString();
    }

    // ------------------------------------------------------------------ the standing

    private void drawStanding(GuiGraphics g, int x, int y, Settings s, RankViewPacket v) {
        int px = x + PANE_X, py = y + LADDER_Y;
        int current = s.indexOf(v.rank());
        Rank mine = current < 0 ? null : s.ranks().get(current);
        String big = mine == null ? Tr.t("no_rank_yet") : mine.displayName().toUpperCase(Locale.ROOT);
        text(g, clip(big, 200, 1.5f), px, py, mine == null ? MUTED : TEXT, 1.5f);
        int bx = px + (int) (font.width(clip(big, 200, 1.5f)) * 1.5f) + 6;
        if (mine != null) {
            text(g, Tr.t("rank_i_of_n", current + 1, s.ranks().size()), bx, py + 6, MUTED, 0.6f);
            bx += (int) (font.width(Tr.t("rank_i_of_n", current + 1, s.ranks().size())) * 0.6f) + 6;
        }
        if (!v.title().isEmpty()) {
            String t = clip(v.title().replaceAll("[&§]#[0-9a-fA-F]{6}", "").replaceAll("[&§].", "").trim(), PANE_W - (bx - px) - 6, 0.6f);
            int tw = (int) (font.width(t) * 0.6f) + 6;
            frame(g, bx, py + 3, tw, 11, VIO);
            text(g, t, bx + 3, py + 5, VIO, 0.6f);
        }
        String pts = v.seasonStarted() ? Tr.t("points_lifetime_season", v.points(), v.seasonPoints()) : Tr.n("points", v.points());
        text(g, pts, px, py + 18, GOLD, 0.75f);

        int ny = py + 34;
        if (current + 1 < s.ranks().size()) {
            Rank next = s.ranks().get(current + 1);
            long have = next.seasonal() ? v.seasonPoints() : v.points();
            text(g, clip(Tr.t("next_rank_at_points", next.displayName().toUpperCase(Locale.ROOT), next.points()) + (next.seasonal() ? "  " + Tr.t("this_season_counts") : ""), PANE_W, 0.6f), px, ny, TQ, 0.6f);
            int bw = PANE_W, bh = 12;
            inset(g, px, ny + 9, bw, bh);
            int fill = (int) ((bw - 2) * Mth.clamp((double) have / Math.max(1, next.points()), 0, 1));
            g.fill(px + 1, ny + 10, px + 1 + fill, ny + 9 + bh - 1, 0xCC19D3B0);
            String label = have + " / " + next.points();
            text(g, label, px + (bw - (int) (font.width(label) * 0.7f)) / 2, ny + 12, TEXT, 0.7f);
            String gives = next.rewards().isEmpty() ? Tr.t("gives_nothing") : Tr.t("gives", rewards(next));
            text(g, clip(next.displayName() + " " + gives, PANE_W, 0.6f), px, ny + 24, TEXT, 0.6f);
            if (!v.fastest().isEmpty()) {
                List<String> ways = new ArrayList<>();
                for (RankViewPacket.Fastest f : v.fastest()) {
                    ways.add(f.units() + " " + I18n.get("signal.bsp_rankup." + f.signal() + ".unit"));
                }
                text(g, clip(Tr.t("fastest", String.join(Tr.t("or"), ways)), PANE_W, 0.6f), px, ny + 33, MUTED, 0.6f);
            }
        } else if (mine != null) {
            text(g, Tr.t("top_of_the_ladder"), px, ny, GOLD, 0.75f);
        }

        int cy = ny + 46;
        text(g, Tr.t("what_counts"), px, cy, TQ, 0.6f);
        int ly = cy + 8, lh = y + H - 16 - ly;
        inset(g, px, ly, PANE_W, lh);
        text(g, Tr.t("signal"), px + 6, ly + 3, MUTED, 0.5f);
        textRight(g, Tr.t("count"), px + 150, ly + 3, MUTED, 0.5f);
        textRight(g, Tr.t("worth"), px + 214, ly + 3, MUTED, 0.5f);
        textRight(g, Tr.t("points_header"), px + PANE_W - 6, ly + 3, MUTED, 0.5f);
        long total = Math.max(1, v.points() - v.bonus());
        int ry = ly + 11, rowH = 10;
        List<Signal> shown = new ArrayList<>();
        for (Signal sig : Signal.values()) {
            SignalSetting ss = s.signal(sig);
            long count = v.counts().getOrDefault(sig.key(), 0L);
            if (ss.on() || count > 0) {
                shown.add(sig);
            }
        }
        for (Signal sig : shown) {
            if (ry + rowH > ly + lh - 22) {
                break;
            }
            SignalSetting ss = s.signal(sig);
            long count = v.counts().getOrDefault(sig.key(), 0L);
            long pts2 = ss.pointsFor(count);
            int colour = ss.on() ? TEXT : MUTED;
            text(g, clip(I18n.get("signal.bsp_rankup." + sig.key()), 100, 0.6f), px + 6, ry + 1, colour, 0.6f);
            textRight(g, String.valueOf(count), px + 150, ry + 1, ss.on() ? MUTED : LINE, 0.6f);
            textRight(g, ss.on() ? Tr.t("n_per_m", ss.points(), ss.per()) : Tr.t("off"), px + 214, ry + 1, MUTED, 0.6f);
            int barX = px + 222, barW = PANE_W - 222 - 40;
            g.fill(barX, ry + 2, barX + barW, ry + 6, 0x1A19D3B0);
            if (ss.on() && pts2 > 0) {
                g.fill(barX, ry + 2, barX + (int) (barW * Mth.clamp((double) pts2 / total, 0, 1)), ry + 6, TQ);
            }
            textRight(g, String.valueOf(pts2), px + PANE_W - 6, ry + 1, ss.on() ? GOLD : LINE, 0.6f);
            ry += rowH;
        }
        int fy = ly + lh - 20;
        g.fill(px + 1, fy - 2, px + PANE_W - 1, fy - 1, LINE);
        if (v.bonus() != 0) {
            text(g, Tr.t("bonus_from_admins"), px + 6, fy + 1, MUTED, 0.6f);
            textRight(g, String.valueOf(v.bonus()), px + PANE_W - 6, fy + 1, v.bonus() < 0 ? BAD : GOLD, 0.6f);
        }
        text(g, Tr.t("total"), px + 6, fy + 10, TQ, 0.6f);
        textRight(g, String.valueOf(v.points()), px + PANE_W - 6, fy + 10, GOLD, 0.6f);
        if (v.pending() > 0) {
            int totalLeft = px + PANE_W - 6 - (int) (font.width(String.valueOf(v.points())) * 0.6f);
            text(g, clip(Tr.n("rewards_waiting_for_you", v.pending()), totalLeft - 4 - (px + 60), 0.6f), px + 60, fy + 10, GOLD, 0.6f);
        }
    }

    @Override
    protected boolean onScroll(double mx, double my, double delta) {
        int x = left(), y = top();
        if (mx >= x + LADDER_X && mx < x + LADDER_X + LADDER_W && my >= y + LADDER_Y && my < y + LADDER_Y + LADDER_H) {
            ladderScroll -= (int) Math.signum(delta);
            return true;
        }
        return super.onScroll(mx, my, delta);
    }
}
