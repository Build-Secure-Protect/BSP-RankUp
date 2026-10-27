package com.mrgregles.bsp_rankup.client;

import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.network.RankUpNetwork;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.Rank;
import com.mrgregles.bsp_rankup.settings.Reward;
import com.mrgregles.bsp_rankup.settings.Settings;
import com.mrgregles.bsp_rankup.settings.SignalSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The admin screen (owner decisions D22 to D31): tabs LADDER, SIGNALS, PLAYERS and OPTIONS; on LADDER the rungs on
 * the left and the chosen rung's fields and rewards on the right. Drawn in BSP Core's Holo HUD style with fills, at
 * the bigger size of D29. The server owns every value: each edit is sent as an {@link AdminActionPacket} and the
 * screen redraws from the view that comes back. Destructive buttons ask for a second click within five seconds;
 * RESET EVERYONE wants its code typed back (D25).
 */
public class AdminScreen extends HoloScreen {
    static final int W = 520, H = 300;
    static final int BG = 0xF010151C, INSET = 0xFF0C0E12, LINE = 0xFF2A2F3A, TQ = 0xFF19D3B0, VIO = 0xFFB58CFF, GOLD = 0xFFFFD23A,
            BAD = 0xFFFF6B5C, TEXT = 0xFFE6EAF2, MUTED = 0xFF9AA3B5;
    private static final String[] TABS = {"LADDER", "GROUPS", "SIGNALS", "PLAYERS", "OPTIONS"};
    private static final int T_LADDER = 0, T_GROUPS = 1, T_SIGNALS = 2, T_PLAYERS = 3, T_OPTIONS = 4;
    /** On LADDER (D51, mock-up A): the ladder chips, the chosen ladder's strip, then its rungs and the rung editor. */
    private static final int CHIPS_Y = 40, STRIP_Y = 56, RUNGS_Y = 74, RUNGS_H = 182, EDIT_Y = 76, REWARD_ROWS_H = 76;
    /** The item browser opens over the editor, down to the input line. */
    private static final int BROWSER_Y = EDIT_Y, BROWSER_H = 244 - EDIT_Y;
    private static final int LIST_X = 8, LIST_Y = 40, LIST_W = 164, LIST_H = 214, ROW = 14;
    /** On PLAYERS the search box sits at the top of the list column, below the tabs, and the list starts under it. */
    private static final int SEARCH_H = 14, PLAYERS_Y = LIST_Y + SEARCH_H + 2, PLAYERS_H = LIST_H - SEARCH_H - 2;
    private static final int PANE_X = 180, PANE_W = 332, PANE_Y = 40, PANE_H = 240;
    private static final long ARM_MS = 5000;
    private static final int REFRESH_TICKS = 40;
    /** Rows of rung chips under SET RANK (room down to the input line). */
    private static final int SET_RANK_ROWS = 3;
    /** Rows of kit chips in the kit picker (room down to the input line). */
    private static final int KIT_ROWS = 3;

    /**
     * A clickable rectangle registered while drawing and consumed by the next click. The last one registered is on
     * top, so clicks are tried newest first.
     */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        boolean overlaps(int ox, int oy, int ow, int oh) {
            return x < ox + ow && ox < x + w && y < oy + oh && oy < y + h;
        }
    }

    private enum Input { NONE, NEW_RANK, NAME, POINTS, REWARD, ITEM_COUNT, SIGNAL, PLAYER_POINTS, OPTION, FILE, RESET_CODE,
        NEW_LADDER, LADDER_TRACK, LADDER_COUNTS, LADDER_PRIORITY, LADDER_RENAME, AWARD_NEW, AWARD_NAME, AWARD_POINTS, AWARD_COUNTS }

    private final List<Hit> hits = new ArrayList<>();
    private int tab;
    /** The ladder chosen on LADDER (by name; the first when null or gone). */
    @Nullable
    private String selectedLadder;
    @Nullable
    private String selectedGroup;
    /** The group chosen on GROUPS. */
    @Nullable
    private String groupChoice;
    private int groupScroll;
    private int listScroll, rewardScroll, playerScroll, signalScroll;
    private EditBox input;
    private EditBox search;
    private Input inputMode = Input.NONE;
    /** What the open input edits: the reward kind, the signal key, the option name or the file action. */
    private String inputTarget = "";
    private boolean exporting;
    /** The destructive button waiting for its second click, and when it was armed. */
    @Nullable
    private String armed;
    private long armedAt;
    /** The item browser (D24), open over the editor pane. */
    private boolean browsing;
    private int browseScroll;
    @Nullable
    private String browseItem;
    private List<Item> browseItems = List.of();
    /** One stack per browse result, made with the results rather than every frame. */
    private List<ItemStack> browseStacks = List.of();
    /** Every item with its stack and the text a search is matched against, made once when the browser first opens. */
    private record Browsable(Item item, ItemStack stack, String text) {
    }

    @Nullable
    private List<Browsable> browseIndex;
    private String browseQuery = "\u0000";
    private boolean settingRank;
    /** The kit picker (K-1) open under the reward kinds. */
    private boolean pickingKit;
    private int ticks;
    private String lastSearch = "";

    public AdminScreen() {
        super(Component.literal(Tr.t("rankup_admin")), W, H);
    }

    // ------------------------------------------------------------------ data

    @Nullable
    private AdminViewPacket view() {
        return RankUpClient.adminView();
    }

    @Nullable
    private Settings settings() {
        return RankUpClient.adminSettings();
    }

    /** The ladder chosen on LADDER, or the first. */
    private Ladder ladder(Settings s) {
        Ladder l = selectedLadder == null ? null : s.ladder(selectedLadder);
        return l == null ? s.first() : l;
    }

    @Nullable
    private Rank selected() {
        Settings s = settings();
        return s == null || selectedGroup == null ? null : ladder(s).rank(selectedGroup);
    }

    /** The group reward edits go to: the chosen rung on LADDER, the chosen award on GROUPS. */
    private String rewardGroup() {
        String g = tab == T_GROUPS ? groupChoice : selectedGroup;
        return g == null ? "" : g;
    }

    void onView(AdminViewPacket msg) {
        Settings s = settings();
        if (s == null) {
            return;
        }
        Ladder l = ladder(s);
        selectedLadder = l.name();
        if (selectedGroup == null || l.indexOf(selectedGroup) < 0) {
            selectedGroup = l.ranks().isEmpty() ? null : l.ranks().get(0).group();
        }
        if (!msg.resetCode().isEmpty() && inputMode != Input.RESET_CODE) {
            openInput(Input.RESET_CODE, "", Tr.t("type_the_code_back"));
        } else if (msg.resetCode().isEmpty() && inputMode == Input.RESET_CODE) {
            closeInput();
        }
    }

    private static void send(AdminActionPacket packet) {
        RankUpNetwork.CHANNEL.sendToServer(packet);
    }

    private void act(String action, String a) {
        send(AdminActionPacket.of(action, a));
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void layout() {
        int x = left(), y = top();
        input = new EditBox(font, x + PANE_X + 4, y + H - 30, PANE_W - 8, 10, Component.literal(Tr.t("input")));
        input.setBordered(false);
        input.setMaxLength(256);
        input.setVisible(inputMode != Input.NONE);
        input.setResponder(v -> {
            if (browsing) {
                browseScroll = 0;
            }
        });
        addRenderableWidget(input);
        search = new EditBox(font, x + LIST_X + 4, y + LIST_Y + 3, LIST_W - 8, 10, Component.literal(Tr.t("find_player")));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setVisible(tab == T_PLAYERS || tab == T_GROUPS);
        addRenderableWidget(search);
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

    @Override
    public boolean shouldCloseOnEsc() {
        return inputMode == Input.NONE && !browsing;
    }

    private void openInput(Input mode, String current, String hint) {
        inputMode = mode;
        input.setVisible(true);
        input.setValue(current);
        input.setHint(Component.literal(hint).withStyle(s -> s.withColor(0x9AA3B5)));
        setFocused(input);
        input.setFocused(true);
    }

    private void closeInput() {
        inputMode = Input.NONE;
        inputTarget = "";
        input.setVisible(false);
        input.setFocused(false);
        input.setValue("");
    }

    private void submitInput() {
        String value = input.getValue().trim();
        String group = selectedGroup == null ? "" : selectedGroup;
        String ladderName = selectedLadder == null ? "" : selectedLadder;
        String award = groupChoice == null ? "" : groupChoice;
        switch (inputMode) {
            case NEW_RANK -> {
                String[] parts = value.split("\\s+", 2);
                if (!parts[0].isEmpty()) {
                    send(new AdminActionPacket(AdminActionPacket.RUNG_ADD, ladderName, parts[0], parts.length > 1 ? parts[1] : "0"));
                    selectedGroup = parts[0].toLowerCase(Locale.ROOT);
                }
            }
            case NEW_LADDER -> {
                String[] parts = value.split("\\s+", 2);
                if (!parts[0].isEmpty()) {
                    send(AdminActionPacket.of(AdminActionPacket.LADDERS_ADD, parts[0], parts.length > 1 ? parts[1] : parts[0]));
                    selectedLadder = parts[0].toLowerCase(Locale.ROOT);
                    selectedGroup = null;
                }
            }
            case LADDER_TRACK -> send(AdminActionPacket.of(AdminActionPacket.LADDERS_TRACK, ladderName, value));
            case LADDER_COUNTS -> send(AdminActionPacket.of(AdminActionPacket.LADDERS_COUNTS, ladderName, value));
            case LADDER_PRIORITY -> send(AdminActionPacket.of(AdminActionPacket.LADDERS_PRIORITY, ladderName, value));
            case LADDER_RENAME -> {
                send(AdminActionPacket.of(AdminActionPacket.LADDERS_RENAME, ladderName, value));
                selectedLadder = value.toLowerCase(Locale.ROOT);
            }
            case AWARD_NEW -> send(AdminActionPacket.of(AdminActionPacket.AWARD_ADD, award, value));
            case AWARD_NAME -> send(AdminActionPacket.of(AdminActionPacket.AWARD_NAME, award, value));
            case AWARD_POINTS -> send(AdminActionPacket.of(AdminActionPacket.AWARD_POINTS, award, value));
            case AWARD_COUNTS -> send(AdminActionPacket.of(AdminActionPacket.AWARD_COUNTS, award, value));
            case NAME -> send(AdminActionPacket.of(AdminActionPacket.LADDER_NAME, group, value));
            case POINTS -> send(AdminActionPacket.of(AdminActionPacket.LADDER_POINTS, group, value));
            case REWARD -> send(new AdminActionPacket(AdminActionPacket.REWARD_ADD, rewardGroup(), inputTarget, value));
            case ITEM_COUNT -> {
                if (browseItem != null) {
                    send(new AdminActionPacket(AdminActionPacket.REWARD_ADD, rewardGroup(), "item", browseItem + " " + (value.isEmpty() ? "1" : value)));
                }
                browsing = false;
                browseItem = null;
            }
            case SIGNAL -> {
                String[] parts = value.split("\\s+", 2);
                send(new AdminActionPacket(AdminActionPacket.SIGNAL_POINTS, inputTarget, parts[0], parts.length > 1 ? parts[1] : ""));
            }
            case PLAYER_POINTS -> {
                AdminViewPacket v = view();
                if (v != null && !v.selected().isEmpty()) {
                    send(AdminActionPacket.of(AdminActionPacket.PLAYER_ADD, v.selected().uuid().toString(), value));
                }
            }
            case OPTION -> send(AdminActionPacket.of(AdminActionPacket.OPTION, inputTarget, value));
            case FILE -> act(exporting ? AdminActionPacket.EXPORT : AdminActionPacket.IMPORT, value);
            case RESET_CODE -> act(AdminActionPacket.RESET_ALL_CONFIRM, value);
            default -> {
            }
        }
        closeInput();
    }

    /** A destructive button: the first click arms it for five seconds, the second runs it. */
    private boolean confirm(String key) {
        long now = System.currentTimeMillis();
        if (key.equals(armed) && now - armedAt < ARM_MS) {
            armed = null;
            return true;
        }
        armed = key;
        armedAt = now;
        return false;
    }

    private boolean isArmed(String key) {
        return key.equals(armed) && System.currentTimeMillis() - armedAt < ARM_MS;
    }

    // ------------------------------------------------------------------ drawing helpers

    static void text(GuiGraphics g, Font font, String s, int x, int y, int colour, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private void text(GuiGraphics g, String s, int x, int y, int colour, float scale) {
        text(g, font, s, x, y, colour, scale);
    }

    private void textRight(GuiGraphics g, String s, int right, int y, int colour, float scale) {
        text(g, s, right - (int) (font.width(s) * scale), y, colour, scale);
    }

    private String clip(String s, int maxW, float scale) {
        return font.plainSubstrByWidth(s, (int) (maxW / scale));
    }

    static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, INSET);
        frame(g, x, y, w, h, LINE);
    }

    /** A bordered label that acts as a button; {@code on} draws it in the colour. */
    private void chip(GuiGraphics g, int x, int y, int w, int h, String label, boolean on, int colour, float scale, @Nullable Runnable action) {
        g.fill(x, y, x + w, y + h, on ? (colour & 0xFFFFFF) | 0x22000000 : INSET);
        frame(g, x, y, w, h, on ? colour : LINE);
        int tw = (int) (font.width(label) * scale);
        text(g, label, x + (w - tw) / 2, y + (int) ((h - 8 * scale) / 2) + 1, on ? colour : MUTED, scale);
        if (action != null) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int colour, @Nullable Runnable action) {
        chip(g, x, y, w, h, label, true, colour, 0.75f, action);
    }

    /** A red button that wants a second click. */
    private void dangerButton(GuiGraphics g, int x, int y, int w, int h, String label, String key, Runnable action) {
        boolean armedNow = isArmed(key);
        button(g, x, y, w, h, armedNow ? Tr.t("click_again") : label, BAD, () -> {
            if (confirm(key)) {
                action.run();
            }
        });
    }

    /** A labelled value that opens the input when clicked. */
    private void field(GuiGraphics g, int x, int y, int w, String label, String value, @Nullable Runnable action) {
        text(g, label, x, y, MUTED, 0.6f);
        inset(g, x, y + 7, w, 13);
        text(g, clip(value, w - 8, 0.85f), x + 4, y + 10, action == null ? MUTED : TEXT, 0.85f);
        if (action != null) {
            hits.add(new Hit(x, y + 7, w, 13, action));
        }
    }

    private void scrollbar(GuiGraphics g, int x, int y, int h, int rows, int visible, int scroll) {
        if (rows > visible) {
            int barH = Math.max(6, h * visible / rows);
            int barY = y + 1 + (h - 2 - barH) * scroll / Math.max(1, rows - visible);
            g.fill(x - 3, barY, x - 1, barY + barH, LINE);
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partial) {
        hits.clear();
        renderBackground(g);
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, BG);
        frame(g, x, y, W, H, TQ);
        text(g, Tr.t("rankup_admin"), x + 10, y + 8, TQ, 1f);
        AdminViewPacket v = view();
        Settings s = settings();
        if (v != null && s != null) {
            Ladder shown = ladder(s);
            String status = Tr.t("status_line", shown.track(), shown.ranks().size(), v.info().isEmpty() ? "" : v.info().get(0));
            textRight(g, clip(status, 300, 0.6f), x + W - 10, y + 10, MUTED, 0.6f);
        }
        int tx = x + 8;
        for (int i = 0; i < TABS.length; i++) {
            int ti = i;
            chip(g, tx, y + 22, 54, 12, Tr.t("tab." + TABS[i].toLowerCase(Locale.ROOT)), tab == i, TQ, 0.75f, () -> switchTab(ti));
            tx += 57;
        }
        search.setVisible(tab == T_PLAYERS || tab == T_GROUPS);
        if (s == null) {
            text(g, Tr.t("waiting_for_the_server"), x + 10, y + 50, MUTED, 0.75f);
        } else {
            switch (tab) {
                case T_LADDER -> drawLadder(g, x, y, mouseX, mouseY, s, v);
                case T_GROUPS -> drawGroups(g, x, y, mouseX, mouseY, s, v);
                case T_SIGNALS -> drawSignals(g, x, y, mouseX, mouseY, s);
                case T_PLAYERS -> drawPlayers(g, x, y, mouseX, mouseY, s, v);
                default -> drawOptions(g, x, y, s, v);
            }
        }
        if (browsing) {
            drawBrowser(g, x, y, mouseX, mouseY);
        }
        drawInput(g, x, y);
        if (v != null && !v.message().isEmpty() && inputMode == Input.NONE) {
            text(g, clip(v.message(), W - 20, 0.75f), x + 10, y + H - 12, v.ok() ? TQ : BAD, 0.75f);
        } else if (inputMode == Input.NONE) {
            text(g, Tr.t("changes_save_at_once"), x + 10, y + H - 12, MUTED, 0.6f);
        }
        super.draw(g, mouseX, mouseY, partial);
    }

    /** Every two seconds (game ticks, not frames): the player search when it changed, else a fresh view on PLAYERS, OPTIONS and GROUPS. */
    @Override
    public void tick() {
        super.tick();
        input.tick();
        search.tick();
        if (++ticks % REFRESH_TICKS == 0) {
            if (tab == T_PLAYERS && !search.getValue().equals(lastSearch)) {
                lastSearch = search.getValue();
                act(AdminActionPacket.PLAYER_SEARCH, lastSearch);
            } else if (tab == T_PLAYERS || tab == T_OPTIONS || tab == T_GROUPS) {
                act(AdminActionPacket.REFRESH, "");
            }
        }
    }

    private void switchTab(int t) {
        tab = t;
        closeInput();
        browsing = false;
        pickingKit = false;
        armed = null;
        rewardScroll = 0;
        if (t == T_PLAYERS && view() != null && view().players().isEmpty()) {
            act(AdminActionPacket.PLAYER_SEARCH, search.getValue());
        }
    }

    // ------------------------------------------------------------------ LADDER

    private void drawLadder(GuiGraphics g, int x, int y, int mouseX, int mouseY, Settings s, AdminViewPacket v) {
        Ladder ladder = ladder(s);
        drawLadderChips(g, x, y, s, ladder);
        drawLadderStrip(g, x, y, s, ladder);
        inset(g, x + LIST_X, y + RUNGS_Y, LIST_W, RUNGS_H);
        List<Rank> ranks = ladder.ranks();
        int visible = RUNGS_H / ROW;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, ranks.size() - visible));
        for (int i = 0; i < visible && i + listScroll < ranks.size(); i++) {
            Rank r = ranks.get(i + listScroll);
            int ry = y + RUNGS_Y + 1 + i * ROW;
            boolean sel = r.group().equals(selectedGroup);
            boolean hover = mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_W && mouseY >= ry && mouseY < ry + ROW;
            if (sel) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x2219D3B0);
                g.fill(x + LIST_X + 1, ry, x + LIST_X + 3, ry + ROW, TQ);
            } else if (hover) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x14FFFFFF);
            }
            text(g, (i + listScroll + 1) + ".", x + LIST_X + 5, ry + 3, MUTED, 0.75f);
            text(g, clip(r.displayName(), 78, 0.85f), x + LIST_X + 18, ry + 3, sel ? TEXT : MUTED, 0.85f);
            if (r.seasonal()) {
                text(g, Tr.t("season_short"), x + LIST_X + 100, ry + 4, VIO, 0.6f);
            }
            textRight(g, String.valueOf(r.points()), x + LIST_X + LIST_W - 6, ry + 4, GOLD, 0.75f);
            hits.add(new Hit(x + LIST_X, ry, LIST_W, ROW, () -> {
                selectedGroup = r.group();
                closeInput();
                browsing = false;
                pickingKit = false;
                armed = null;
            }));
        }
        if (ranks.isEmpty()) {
            text(g, Tr.t("the_ladder_is_empty"), x + LIST_X + 6, y + RUNGS_Y + 8, MUTED, 0.75f);
            text(g, clip(Tr.t("add_a_rung_or_import_from_the_track"), LIST_W - 12, 0.6f), x + LIST_X + 6, y + RUNGS_Y + 20, MUTED, 0.6f);
        }
        scrollbar(g, x + LIST_X + LIST_W, y + RUNGS_Y, RUNGS_H, ranks.size(), visible, listScroll);
        int by = y + RUNGS_Y + RUNGS_H + 6;
        button(g, x + LIST_X, by, 72, 13, inputMode == Input.NEW_RANK ? Tr.t("cancel") : Tr.t("add_rank"), TQ, () -> {
            if (inputMode == Input.NEW_RANK) {
                closeInput();
            } else {
                openInput(Input.NEW_RANK, "", Tr.t("group_points_name_enter_adds_the_rung"));
            }
        });
        Rank sel = selected();
        int idx = sel == null ? -1 : ladder.indexOf(sel.group());
        button(g, x + LIST_X + 75, by, 24, 13, Tr.t("up"), idx > 0 ? TEXT : LINE, idx > 0 ? () -> send(AdminActionPacket.of(AdminActionPacket.LADDER_MOVE, sel.group(), String.valueOf(idx))) : null);
        button(g, x + LIST_X + 102, by, 24, 13, Tr.t("down"), idx >= 0 && idx < ranks.size() - 1 ? TEXT : LINE, idx >= 0 && idx < ranks.size() - 1 ? () -> send(AdminActionPacket.of(AdminActionPacket.LADDER_MOVE, sel.group(), String.valueOf(idx + 2))) : null);
        button(g, x + LIST_X + 129, by, 35, 13, Tr.t("import"), MUTED, () -> act(AdminActionPacket.LADDERS_IMPORT, ladder.name()));

        if (sel == null) {
            text(g, Tr.t("pick_a_rung_or_add_one"), x + PANE_X + 4, y + EDIT_Y + 8, MUTED, 0.75f);
            return;
        }
        int px = x + PANE_X, py = y + EDIT_Y;
        int holders = v.holders().getOrDefault(sel.group(), 0);
        text(g, clip(sel.displayName().toUpperCase(Locale.ROOT), 180, 1f), px, py, TEXT, 1f);
        textRight(g, Tr.t("rung_i_of_n_holders", idx + 1, ranks.size(), holders), px + PANE_W, py + 2, MUTED, 0.6f);
        field(g, px, py + 14, 100, Tr.t("group"), sel.group(), null);
        field(g, px + 106, py + 14, 110, Tr.t("display_name"), sel.displayName(), () -> openInput(Input.NAME, sel.displayName(), Tr.t("the_name_players_see")));
        field(g, px + 222, py + 14, 50, Tr.t("points"), String.valueOf(sel.points()), () -> openInput(Input.POINTS, String.valueOf(sel.points()), Tr.t("points_needed")));
        text(g, Tr.t("season"), px + 278, py + 14, MUTED, 0.6f);
        chip(g, px + 278, py + 21, 26, 13, Tr.t("keep"), !sel.seasonal(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.LADDER_SEASONAL, sel.group(), "false")));
        chip(g, px + 305, py + 21, 27, 13, Tr.t("reset"), sel.seasonal(), VIO, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.LADDER_SEASONAL, sel.group(), "true")));

        text(g, Tr.t("rewards_when_reached"), px, py + 42, TQ, 0.6f);
        int ay = drawRewards(g, px, py + 50, REWARD_ROWS_H, sel.group(), sel.rewards(), Tr.t("this_rung_gives_nothing_yet"));
        drawRewardKinds(g, px, ay, sel.group());
        if (pickingKit) {
            drawKitPicker(g, px, ay + 17, sel.group(), v);
        } else {
            dangerButton(g, px + PANE_W - 76, ay + 18, 76, 13, Tr.t("remove_rank"), "remove-rank", () -> act(AdminActionPacket.LADDER_REMOVE, sel.group()));
            text(g, Tr.t("removing_keeps_players_points"), px, ay + 21, MUTED, 0.6f);
        }
    }

    /** One chip per ladder, then + LADDER; a click chooses the ladder (D51). */
    private void drawLadderChips(GuiGraphics g, int x, int y, Settings s, Ladder chosen) {
        int cx = x + LIST_X;
        int end = x + W - 8 - 60;
        for (Ladder l : s.ladders()) {
            String label = clip(RankEngine.label(l).toUpperCase(Locale.ROOT), 90, 0.75f);
            int w = Math.max(40, (int) (font.width(label) * 0.75f) + 10);
            if (cx + w > end) {
                break; // more ladders than fit: /rankup ladders lists them all
            }
            boolean on = l.name().equals(chosen.name());
            chip(g, cx, y + CHIPS_Y, w, 12, label, on, TQ, 0.75f, () -> {
                selectedLadder = l.name();
                selectedGroup = l.ranks().isEmpty() ? null : l.ranks().get(0).group();
                listScroll = 0;
                rewardScroll = 0;
                closeInput();
                browsing = false;
                pickingKit = false;
                armed = null;
            });
            cx += w + 3;
        }
        chip(g, cx, y + CHIPS_Y, 60, 12, inputMode == Input.NEW_LADDER ? Tr.t("cancel") : Tr.t("add_ladder"), false, TQ, 0.75f, () -> {
            if (inputMode == Input.NEW_LADDER) {
                closeInput();
            } else {
                openInput(Input.NEW_LADDER, "", Tr.t("hint.new_ladder"));
            }
        });
    }

    /** The chosen ladder's track, what it counts, its title priority, and RENAME (the first) or REMOVE (the others). */
    private void drawLadderStrip(GuiGraphics g, int x, int y, Settings s, Ladder l) {
        int sy = y + STRIP_Y;
        inset(g, x + 8, sy, W - 16, 14);
        text(g, Tr.t("track"), x + 13, sy + 4, MUTED, 0.6f);
        int tx = x + 13 + (int) (font.width(Tr.t("track")) * 0.6f) + 4;
        chip(g, tx, sy + 1, 60, 12, clip(l.track(), 54, 0.75f), false, TEXT, 0.75f, () -> openInput(Input.LADDER_TRACK, l.track(), Tr.t("hint.ladder_track")));
        int cx = tx + 66;
        text(g, Tr.t("counts"), cx, sy + 4, MUTED, 0.6f);
        cx += (int) (font.width(Tr.t("counts")) * 0.6f) + 4;
        String keys = keysOf(l.counts());
        cx = drawCountChips(g, cx, sy + 1, x + 380, l.counts(),
                sig -> send(AdminActionPacket.of(AdminActionPacket.LADDERS_COUNTS, l.name(), without(l.counts(), sig))),
                () -> openInput(Input.LADDER_COUNTS, keys, Tr.t("hint.counts")));
        String pr = Tr.t("titles_at", l.titlePriority());
        int pw = (int) (font.width(pr) * 0.6f) + 8;
        chip(g, x + W - 8 - 54 - 4 - pw, sy + 1, pw, 12, pr, false, MUTED, 0.6f, () -> openInput(Input.LADDER_PRIORITY, String.valueOf(l.titlePriority()), Tr.t("hint.priority")));
        if (RankEngine.isFirst(s, l)) {
            chip(g, x + W - 8 - 54, sy + 1, 52, 12, Tr.t("rename"), false, TEXT, 0.6f, () -> openInput(Input.LADDER_RENAME, l.name(), Tr.t("hint.rename")));
        } else {
            boolean armedNow = isArmed("remove-ladder");
            chip(g, x + W - 8 - 54, sy + 1, 52, 12, armedNow ? Tr.t("click_again") : Tr.t("remove_ladder"), true, BAD, 0.6f, () -> {
                if (confirm("remove-ladder")) {
                    act(AdminActionPacket.LADDERS_REMOVE, l.name());
                    selectedLadder = null;
                }
            });
        }
    }

    /**
     * The counted signals as chips (a click takes one off) and + signal (opens the input); "every signal" when none is
     * chosen. Stops at {@code end}, naming how many more. Returns the x after the last chip.
     */
    private int drawCountChips(GuiGraphics g, int cx, int cy, int end, Set<Signal> counts, java.util.function.Consumer<Signal> remove, Runnable add) {
        if (counts.isEmpty()) {
            String all = Tr.t("every_signal");
            int w = (int) (font.width(all) * 0.6f) + 8;
            chip(g, cx, cy, w, 12, all, true, TQ, 0.6f, null);
            cx += w + 3;
        } else {
            int shown = 0;
            for (Signal sig : Signal.values()) {
                if (!counts.contains(sig)) {
                    continue;
                }
                String label = clip(I18n.get("signal.bsp_rankup." + sig.key()), 66, 0.6f);
                int w = (int) (font.width(label) * 0.6f) + 8;
                if (cx + w > end - 50) {
                    text(g, Tr.t("n_more", counts.size() - shown), cx, cy + 3, MUTED, 0.6f);
                    cx += (int) (font.width(Tr.t("n_more", counts.size() - shown)) * 0.6f) + 4;
                    break;
                }
                chip(g, cx, cy, w, 12, label, true, TQ, 0.6f, () -> remove.accept(sig));
                cx += w + 3;
                shown++;
            }
        }
        chip(g, cx, cy, 46, 12, Tr.t("add_signal"), false, MUTED, 0.6f, add);
        return cx + 49;
    }

    private static String keysOf(Set<Signal> counts) {
        List<String> keys = new ArrayList<>();
        for (Signal sig : Signal.values()) {
            if (counts.contains(sig)) {
                keys.add(sig.key());
            }
        }
        return keys.isEmpty() ? "all" : String.join(",", keys);
    }

    /** The counted signals less one, as the counts action takes them ({@code all} when none is left). */
    private static String without(Set<Signal> counts, Signal gone) {
        Set<Signal> rest = new java.util.LinkedHashSet<>(counts);
        rest.remove(gone);
        return keysOf(rest);
    }

    /** A reward list with its remove chips; returns the y under it, where the reward kinds go. */
    private int drawRewards(GuiGraphics g, int lx, int ly, int lh, String group, List<Reward> rewards, String empty) {
        int lw = PANE_W;
        inset(g, lx, ly, lw, lh);
        int rv = (lh - 2) / 12;
        rewardScroll = Mth.clamp(rewardScroll, 0, Math.max(0, rewards.size() - rv));
        for (int i = 0; i < rv && i + rewardScroll < rewards.size(); i++) {
            Reward r = rewards.get(i + rewardScroll);
            int n = i + rewardScroll + 1;
            int ry = ly + 1 + i * 12;
            text(g, Tr.t("kind." + r.kind().name().toLowerCase(Locale.ROOT)), lx + 4, ry + 3, VIO, 0.6f);
            text(g, clip(describe(r), lw - 88, 0.75f), lx + 50, ry + 2, TEXT, 0.75f); // ends before the item icon at lw - 32
            if (r.kind() == Reward.Kind.ITEM) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(r.value()));
                if (item != null && item != Items.AIR) {
                    g.pose().pushPose();
                    g.pose().translate(lx + lw - 32, ry + 1, 0);
                    g.pose().scale(0.6f, 0.6f, 1);
                    g.renderItem(new ItemStack(item), 0, 0);
                    g.pose().popPose();
                }
            }
            chip(g, lx + lw - 14, ry + 1, 11, 10, "x", false, BAD, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.REWARD_REMOVE, group, String.valueOf(n))));
        }
        if (rewards.isEmpty()) {
            text(g, empty, lx + 6, ly + 6, MUTED, 0.75f);
        }
        scrollbar(g, lx + lw, ly, lh, rewards.size(), rv, rewardScroll);
        return ly + lh + 5;
    }

    /** + COINS, + PIECES, + ITEM (the browser), + TITLE, + COMMAND, + KIT (the picker) and FROM HAND, for one group's rewards. */
    private void drawRewardKinds(GuiGraphics g, int px, int ay, String group) {
        String[] kinds = {"coins", "pieces", "item", "title", "command", "kit"};
        int ax = px;
        for (String kind : kinds) {
            boolean on = inputMode == Input.REWARD && inputTarget.equals(kind) || kind.equals("item") && browsing || kind.equals("kit") && pickingKit;
            chip(g, ax, ay, 44, 13, "+ " + Tr.t("kind." + kind), on, TQ, 0.6f, () -> {
                pickingKit = false;
                if (kind.equals("item")) {
                    closeInput();
                    openBrowser();
                } else if (kind.equals("kit")) {
                    closeInput();
                    browsing = false;
                    pickingKit = true;
                } else {
                    browsing = false;
                    inputTarget = kind;
                    openInput(Input.REWARD, "", Tr.t("hint." + kind));
                    inputTarget = kind;
                }
            });
            ax += 47;
        }
        button(g, ax, ay, 50, 13, Tr.t("from_hand"), GOLD, () -> act(AdminActionPacket.REWARD_HAND, group));
    }

    /** BSP-Core's kits as chips; one click adds the kit as a reward of the group (K-1). */
    private void drawKitPicker(GuiGraphics g, int px, int y, String group, AdminViewPacket v) {
        if (v.kits().isEmpty()) {
            text(g, Tr.t("no_kits_yet"), px, y + 3, MUTED, 0.6f);
        } else {
            text(g, Tr.t("pick_a_kit"), px, y + 3, MUTED, 0.6f);
        }
        // the kits flow over up to KIT_ROWS rows, left of CANCEL on the first; the rest are named by count
        int bx = px + 50, row = 0, shown = 0;
        for (Map.Entry<String, String> kit : v.kits().entrySet()) {
            String label = clip(kit.getValue(), 70, 0.6f);
            int w = (int) (font.width(label) * 0.6f) + 10;
            int rowEnd = row == 0 ? px + PANE_W - 44 : px + PANE_W;
            if (bx + w > rowEnd) {
                if (++row >= KIT_ROWS) {
                    break;
                }
                bx = px + 50;
            }
            chip(g, bx, y + row * 15, w, 13, label, false, VIO, 0.6f, () -> {
                pickingKit = false;
                send(new AdminActionPacket(AdminActionPacket.REWARD_ADD, group, "kit", kit.getKey()));
            });
            bx += w + 3;
            shown++;
        }
        if (shown < v.kits().size()) {
            text(g, clip(Tr.t("more_kits_by_command", v.kits().size() - shown), PANE_W - 50, 0.6f), px + 50, y + KIT_ROWS * 15 + 2, MUTED, 0.6f);
        }
        button(g, px + PANE_W - 40, y, 40, 13, Tr.t("cancel"), MUTED, () -> pickingKit = false);
    }

    // ------------------------------------------------------------------ GROUPS (D50, D51)

    /** One line of the GROUPS list: a heading (group null) or a group with its tag and colour. */
    private record GroupRow(@Nullable String heading, @Nullable String group, String tag, int colour) {
    }

    /** The list, built when the view or the search text changes rather than every frame. */
    private List<GroupRow> groupRows = List.of();
    @Nullable
    private AdminViewPacket groupRowsFor;
    private String groupRowsQuery = "";

    private List<GroupRow> groupRows(Settings s, AdminViewPacket v) {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (v == groupRowsFor && q.equals(groupRowsQuery)) {
            return groupRows;
        }
        groupRowsFor = v;
        groupRowsQuery = q;
        List<GroupRow> rows = new ArrayList<>();
        Set<String> used = new java.util.HashSet<>();
        List<GroupRow> part = new ArrayList<>();
        for (Ladder l : s.ladders()) {
            for (int i = 0; i < l.ranks().size(); i++) {
                String g = l.ranks().get(i).group();
                used.add(g);
                if (g.contains(q)) {
                    part.add(new GroupRow(null, g, RankEngine.label(l) + " " + (i + 1), TQ));
                }
            }
        }
        section(rows, Tr.t("groups.on_ladder"), part);
        part = new ArrayList<>();
        Set<String> inLuckPerms = new java.util.HashSet<>(v.groups());
        for (Award a : s.awards()) {
            used.add(a.group());
            if (a.group().contains(q)) {
                boolean missing = !v.groups().isEmpty() && !inLuckPerms.contains(a.group());
                part.add(new GroupRow(null, a.group(), missing ? Tr.t("tag.missing") : keysOf(a.counts()), missing ? BAD : GOLD));
            }
        }
        section(rows, Tr.t("groups.awards"), part);
        part = new ArrayList<>();
        for (String g : v.groups()) {
            if (!used.contains(g) && g.contains(q)) {
                part.add(new GroupRow(null, g, Tr.t("tag.not_used"), MUTED));
            }
        }
        section(rows, Tr.t("groups.other"), part);
        groupRows = rows;
        return rows;
    }

    private static void section(List<GroupRow> rows, String heading, List<GroupRow> part) {
        if (!part.isEmpty()) {
            rows.add(new GroupRow(heading, null, "", TQ));
            rows.addAll(part);
        }
    }

    private void drawGroups(GuiGraphics g, int x, int y, int mouseX, int mouseY, Settings s, AdminViewPacket v) {
        inset(g, x + LIST_X, y + LIST_Y, LIST_W, SEARCH_H);
        if (search.getValue().isEmpty() && !search.isFocused()) {
            text(g, Tr.t("find_group"), x + LIST_X + 5, y + LIST_Y + 4, LINE, 0.75f);
        }
        inset(g, x + LIST_X, y + PLAYERS_Y, LIST_W, PLAYERS_H);
        List<GroupRow> rows = groupRows(s, v);
        int visible = PLAYERS_H / ROW;
        groupScroll = Mth.clamp(groupScroll, 0, Math.max(0, rows.size() - visible));
        for (int i = 0; i < visible && i + groupScroll < rows.size(); i++) {
            GroupRow r = rows.get(i + groupScroll);
            int ry = y + PLAYERS_Y + 1 + i * ROW;
            if (r.group() == null) {
                text(g, r.heading(), x + LIST_X + 6, ry + 4, TQ, 0.6f);
                continue;
            }
            boolean sel = r.group().equals(groupChoice);
            boolean hover = mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_W && mouseY >= ry && mouseY < ry + ROW;
            if (sel) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x2219D3B0);
                g.fill(x + LIST_X + 1, ry, x + LIST_X + 3, ry + ROW, TQ);
            } else if (hover) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x14FFFFFF);
            }
            String tag = clip(r.tag(), 60, 0.6f);
            int tagLeft = x + LIST_X + LIST_W - 6 - (int) (font.width(tag) * 0.6f);
            text(g, clip(r.group(), tagLeft - 4 - (x + LIST_X + 8), 0.85f), x + LIST_X + 8, ry + 3, sel ? TEXT : MUTED, 0.85f);
            textRight(g, tag, x + LIST_X + LIST_W - 6, ry + 4, r.colour(), 0.6f);
            String group = r.group();
            hits.add(new Hit(x + LIST_X, ry, LIST_W, ROW, () -> {
                groupChoice = group;
                rewardScroll = 0;
                closeInput();
                browsing = false;
                pickingKit = false;
                armed = null;
            }));
        }
        if (rows.isEmpty()) {
            text(g, clip(Tr.t("no_group_matches"), LIST_W - 12, 0.75f), x + LIST_X + 6, y + PLAYERS_Y + 8, MUTED, 0.75f);
        }
        scrollbar(g, x + LIST_X + LIST_W, y + PLAYERS_Y, PLAYERS_H, rows.size(), visible, groupScroll);
        int count = (int) rows.stream().filter(r -> r.group() != null).count();
        text(g, Tr.n("groups_shown", count), x + LIST_X, y + LIST_Y + LIST_H + 8, MUTED, 0.6f);
        button(g, x + LIST_X + LIST_W - 50, y + LIST_Y + LIST_H + 5, 50, 13, Tr.t("refresh"), MUTED, () -> act(AdminActionPacket.REFRESH, ""));

        int px = x + PANE_X, py = y + LIST_Y;
        if (groupChoice == null) {
            text(g, clip(Tr.t("pick_a_group"), PANE_W, 0.75f), px + 4, py + 8, MUTED, 0.75f);
            return;
        }
        String group = groupChoice;
        Ladder on = s.ladderOf(group);
        Award award = s.award(group);
        int holders = v.holders().getOrDefault(group, 0);
        String name = group.toUpperCase(Locale.ROOT);
        text(g, clip(name, 200, 1f), px, py, TEXT, 1f);
        int after = px + Math.min(200, font.width(name)) + 6;
        if (on != null) {
            String chipLabel = Tr.t("on_a_ladder");
            chip(g, after, py - 1, (int) (font.width(chipLabel) * 0.6f) + 10, 11, chipLabel, true, TQ, 0.6f, null);
            Rank r = on.rank(group);
            textRight(g, Tr.n("players_hold_it", holders), px + PANE_W, py + 2, MUTED, 0.6f);
            String counts = on.counts().isEmpty() ? Tr.t("every_signal") : countNames(on.counts());
            text(g, clip(Tr.t("rung_of_ladder", on.indexOf(group) + 1, RankEngine.label(on), r.points(), counts), PANE_W, 0.6f), px, py + 16, MUTED, 0.6f);
            text(g, clip(Tr.t("changed_on_ladder"), PANE_W, 0.6f), px, py + 26, MUTED, 0.6f);
            button(g, px, py + 40, 90, 13, Tr.t("open_on_ladder"), TQ, () -> {
                selectedLadder = on.name();
                selectedGroup = group;
                switchTab(T_LADDER);
            });
            return;
        }
        if (award == null) {
            String chipLabel = Tr.t("not_used");
            chip(g, after, py - 1, (int) (font.width(chipLabel) * 0.6f) + 10, 11, chipLabel, false, MUTED, 0.6f, null);
            text(g, clip(Tr.t("not_used_text"), PANE_W, 0.6f), px, py + 16, MUTED, 0.6f);
            button(g, px, py + 30, 80, 13, inputMode == Input.AWARD_NEW ? Tr.t("cancel") : Tr.t("make_award"), GOLD, () -> {
                if (inputMode == Input.AWARD_NEW) {
                    closeInput();
                } else {
                    openInput(Input.AWARD_NEW, "", Tr.t("hint.award_points"));
                }
            });
            return;
        }
        String chipLabel = Tr.t("award_chip");
        chip(g, after, py - 1, (int) (font.width(chipLabel) * 0.6f) + 10, 11, chipLabel, true, GOLD, 0.6f, null);
        textRight(g, Tr.n("players_hold_it", holders), px + PANE_W, py + 2, MUTED, 0.6f);
        boolean missing = !v.groups().isEmpty() && !v.groups().contains(group);
        text(g, clip(missing ? Tr.t("award_missing") : award.seasonal() ? Tr.t("award_seasonal") : Tr.t("award_kept"), PANE_W, 0.6f), px, py + 12, missing ? BAD : MUTED, 0.6f);
        field(g, px, py + 22, 120, Tr.t("display_name"), award.displayName(), () -> openInput(Input.AWARD_NAME, award.displayName(), Tr.t("the_name_players_see")));
        field(g, px + 126, py + 22, 70, Tr.t("needs_points"), String.valueOf(award.points()), () -> openInput(Input.AWARD_POINTS, String.valueOf(award.points()), Tr.t("points_needed")));
        text(g, Tr.t("season"), px + 278, py + 22, MUTED, 0.6f);
        chip(g, px + 278, py + 29, 26, 13, Tr.t("keep"), !award.seasonal(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.AWARD_SEASONAL, group, "false")));
        chip(g, px + 305, py + 29, 27, 13, Tr.t("reset"), award.seasonal(), VIO, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.AWARD_SEASONAL, group, "true")));
        text(g, Tr.t("counts"), px, py + 47, MUTED, 0.6f);
        String keys = keysOf(award.counts());
        drawCountChips(g, px + (int) (font.width(Tr.t("counts")) * 0.6f) + 4, py + 44, px + PANE_W, award.counts(),
                sig -> send(AdminActionPacket.of(AdminActionPacket.AWARD_COUNTS, group, without(award.counts(), sig))),
                () -> openInput(Input.AWARD_COUNTS, keys, Tr.t("hint.counts")));
        text(g, Tr.t("rewards_when_reached"), px, py + 62, TQ, 0.6f);
        int ay = drawRewards(g, px, py + 70, REWARD_ROWS_H, group, award.rewards(), Tr.t("this_award_gives_nothing_yet"));
        drawRewardKinds(g, px, ay, group);
        if (pickingKit) {
            drawKitPicker(g, px, ay + 17, group, v);
        } else {
            dangerButton(g, px + PANE_W - 80, ay + 18, 80, 13, Tr.t("stop_awarding"), "stop-award", () -> {
                act(AdminActionPacket.AWARD_REMOVE, group);
            });
            text(g, clip(Tr.t("stopping_keeps"), PANE_W - 86, 0.6f), px, ay + 21, MUTED, 0.6f);
        }
    }

    /** Signal names for a counted set, joined. */
    private static String countNames(Set<Signal> counts) {
        List<String> names = new ArrayList<>();
        for (Signal sig : Signal.values()) {
            if (counts.contains(sig)) {
                names.add(I18n.get("signal.bsp_rankup." + sig.key()));
            }
        }
        return String.join(", ", names);
    }

    private String describe(Reward r) {
        return switch (r.kind()) {
            case COINS -> Tr.t("coins_worth_copper", r.amount());
            case PIECES -> Tr.t("pieces_into_the_bank", r.amount());
            case ITEM -> r.amount() + " x " + itemName(r.value());
            case TITLE -> r.value();
            case COMMAND -> "/" + r.value();
            case KIT -> Tr.t("the_kit", view() == null ? r.value() : view().kits().getOrDefault(r.value(), r.value()));
        };
    }

    private static String itemName(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
        return item == null || item == Items.AIR ? id : new ItemStack(item).getHoverName().getString();
    }

    // ------------------------------------------------------------------ the item browser (D24)

    private void openBrowser() {
        browsing = true;
        browseItem = null;
        browseScroll = 0;
        openInput(Input.ITEM_COUNT, "", Tr.t("type_to_search_items_click_one"));
        inputMode = Input.NONE; // the box is the search until an item is picked
        input.setVisible(true);
        input.setFocused(true);
        setFocused(input);
    }

    private List<Item> browseResults(String query) {
        if (query.equals(browseQuery)) {
            return browseItems;
        }
        browseQuery = query;
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (browseIndex == null) {
            List<Browsable> index = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                if (item != Items.AIR) {
                    ItemStack stack = new ItemStack(item);
                    index.add(new Browsable(item, stack, (ForgeRegistries.ITEMS.getKey(item) + " " + stack.getHoverName().getString()).toLowerCase(Locale.ROOT)));
                }
            }
            browseIndex = index;
        }
        List<Item> out = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (Browsable b : browseIndex) {
            if (q.isEmpty() || b.text().contains(q)) {
                out.add(b.item());
                stacks.add(b.stack());
                if (out.size() >= 2000) {
                    break;
                }
            }
        }
        browseItems = out;
        browseStacks = stacks;
        return out;
    }

    private void drawBrowser(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int bx = x + PANE_X, by = y + BROWSER_Y, bw = PANE_W, bh = BROWSER_H;
        // the browser covers the rung's fields, reward chips and REMOVE RANK: nothing under it takes a click
        hits.removeIf(h -> h.overlaps(bx, by, bw, bh));
        hits.add(new Hit(bx, by, bw, bh, () -> {
        }));
        g.fill(bx, by, bx + bw, by + bh, BG);
        frame(g, bx, by, bw, bh, GOLD);
        text(g, Tr.t("pick_an_item"), bx + 6, by + 5, GOLD, 0.75f);
        button(g, bx + bw - 50, by + 3, 46, 11, Tr.t("close"), MUTED, () -> {
            browsing = false;
            browseItem = null;
            closeInput();
        });
        if (browseItem != null) {
            text(g, clip(Tr.t("how_many_of", itemName(browseItem)), bw - 12, 0.75f), bx + 6, by + 18, TEXT, 0.75f);
            return;
        }
        List<Item> items = inputMode == Input.NONE ? browseResults(input.getValue()) : browseItems;
        List<ItemStack> stacks = browseStacks;
        int cols = (bw - 12) / 18, gy = by + 18, rows = (bh - 22) / 18;
        int total = (items.size() + cols - 1) / cols;
        browseScroll = Mth.clamp(browseScroll, 0, Math.max(0, total - rows));
        for (int i = 0; i < rows * cols; i++) {
            int index = i + browseScroll * cols;
            if (index >= items.size()) {
                break;
            }
            Item item = items.get(index);
            int ix = bx + 6 + (i % cols) * 18, iy = gy + (i / cols) * 18;
            boolean hover = mouseX >= ix && mouseX < ix + 18 && mouseY >= iy && mouseY < iy + 18;
            if (hover) {
                g.fill(ix, iy, ix + 18, iy + 18, 0x3319D3B0);
            }
            g.renderItem(stacks.get(index), ix + 1, iy + 1);
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            hits.add(new Hit(ix, iy, 18, 18, () -> {
                browseItem = String.valueOf(id);
                openInput(Input.ITEM_COUNT, "1", Tr.t("count_enter_adds_it"));
            }));
            if (hover) {
                textRight(g, clip(stacks.get(index).getHoverName().getString() + "  " + id, 200, 0.6f), bx + bw - 54, by + 6, MUTED, 0.6f);
            }
        }
        scrollbar(g, bx + bw, gy, bh - 22, total, rows, browseScroll);
        if (items.isEmpty()) {
            text(g, Tr.t("no_item_matches"), bx + 6, gy + 6, MUTED, 0.75f);
        }
    }

    // ------------------------------------------------------------------ SIGNALS

    private void drawSignals(GuiGraphics g, int x, int y, int mouseX, int mouseY, Settings s) {
        int lx = x + 8, ly = y + LIST_Y, lw = W - 16, lh = 230;
        inset(g, lx, ly, lw, lh);
        text(g, Tr.t("signal"), lx + 6, ly + 4, MUTED, 0.6f);
        text(g, Tr.t("on"), lx + 150, ly + 4, MUTED, 0.6f);
        text(g, Tr.t("worth"), lx + 200, ly + 4, MUTED, 0.6f);
        text(g, Tr.t("source"), lx + 380, ly + 4, MUTED, 0.6f);
        Signal[] all = Signal.values();
        int rowH = 17, visible = (lh - 14) / rowH;
        signalScroll = Mth.clamp(signalScroll, 0, Math.max(0, all.length - visible));
        for (int i = 0; i < visible && i + signalScroll < all.length; i++) {
            Signal sig = all[i + signalScroll];
            SignalSetting v = s.signal(sig);
            int ry = ly + 14 + i * rowH;
            if (i % 2 == 0) {
                g.fill(lx + 1, ry, lx + lw - 1, ry + rowH, 0x0AFFFFFF);
            }
            text(g, clip(I18n.get("signal.bsp_rankup." + sig.key()), 136, 0.85f), lx + 6, ry + 5, v.on() ? TEXT : MUTED, 0.85f);
            chip(g, lx + 148, ry + 2, 22, 12, v.on() ? Tr.t("on") : Tr.t("off"), v.on(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.SIGNAL_ON, sig.key(), String.valueOf(!v.on()))));
            String worth = Tr.t("n_points_per_m_units", v.points(), v.per(), I18n.get("signal.bsp_rankup." + sig.key() + ".unit"));
            inset(g, lx + 198, ry + 2, 176, 12);
            text(g, clip(worth, 168, 0.75f), lx + 202, ry + 4, v.on() ? GOLD : MUTED, 0.75f);
            hits.add(new Hit(lx + 198, ry + 2, 176, 12, () -> {
                inputTarget = sig.key();
                openInput(Input.SIGNAL, v.points() + " " + v.per(), Tr.t("points_per_enter_sets_them"));
                inputTarget = sig.key();
            }));
            text(g, Tr.t("source." + sig.source().name().toLowerCase(Locale.ROOT)), lx + 380, ry + 5, MUTED, 0.6f);
        }
        scrollbar(g, lx + lw, ly + 14, lh - 14, all.length, visible, signalScroll);
    }

    // ------------------------------------------------------------------ PLAYERS

    private void drawPlayers(GuiGraphics g, int x, int y, int mouseX, int mouseY, Settings s, AdminViewPacket v) {
        inset(g, x + LIST_X, y + LIST_Y, LIST_W, SEARCH_H);
        inset(g, x + LIST_X, y + PLAYERS_Y, LIST_W, PLAYERS_H);
        List<AdminViewPacket.PlayerRow> rows = v.players();
        int visible = PLAYERS_H / ROW;
        playerScroll = Mth.clamp(playerScroll, 0, Math.max(0, rows.size() - visible));
        AdminViewPacket.PlayerDetail d = v.selected();
        for (int i = 0; i < visible && i + playerScroll < rows.size(); i++) {
            AdminViewPacket.PlayerRow r = rows.get(i + playerScroll);
            int ry = y + PLAYERS_Y + 1 + i * ROW;
            boolean sel = !d.isEmpty() && d.uuid().equals(r.uuid());
            boolean hover = mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_W && mouseY >= ry && mouseY < ry + ROW;
            if (sel) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x2219D3B0);
                g.fill(x + LIST_X + 1, ry, x + LIST_X + 3, ry + ROW, TQ);
            } else if (hover) {
                g.fill(x + LIST_X + 1, ry, x + LIST_X + LIST_W - 1, ry + ROW, 0x14FFFFFF);
            }
            g.fill(x + LIST_X + 6, ry + 5, x + LIST_X + 9, ry + 8, r.online() ? TQ : LINE);
            text(g, clip(r.name(), 78, 0.85f), x + LIST_X + 13, ry + 3, sel ? TEXT : MUTED, 0.85f);
            Rank rank = r.rank().isEmpty() ? null : s.rank(r.rank());
            String rowPoints = String.valueOf(r.points());
            int pointsLeft = x + LIST_X + LIST_W - 6 - (int) (font.width(rowPoints) * 0.6f);
            text(g, clip(rank == null ? "-" : rank.displayName(), Math.min(44, pointsLeft - 4 - (x + LIST_X + 94)), 0.6f), x + LIST_X + 94, ry + 4, MUTED, 0.6f);
            textRight(g, rowPoints, x + LIST_X + LIST_W - 6, ry + 4, GOLD, 0.6f);
            hits.add(new Hit(x + LIST_X, ry, LIST_W, ROW, () -> {
                settingRank = false;
                armed = null;
                act(AdminActionPacket.PLAYER_SELECT, r.uuid().toString());
            }));
        }
        if (rows.isEmpty()) {
            text(g, Tr.t("no_player_matches"), x + LIST_X + 6, y + PLAYERS_Y + 8, MUTED, 0.75f);
        }
        scrollbar(g, x + LIST_X + LIST_W, y + PLAYERS_Y, PLAYERS_H, rows.size(), visible, playerScroll);
        text(g, Tr.n("players_shown", rows.size()), x + LIST_X, y + LIST_Y + LIST_H + 8, MUTED, 0.6f);

        int px = x + PANE_X, py = y + PANE_Y;
        if (d.isEmpty()) {
            text(g, Tr.t("pick_a_player"), px + 4, py + 8, MUTED, 0.75f);
            return;
        }
        Rank rank = d.rank().isEmpty() ? null : s.rank(d.rank());
        String shownName = clip(d.name(), 160, 1f);
        text(g, shownName, px, py, TEXT, 1f);
        int afterName = 4 + font.width(shownName);
        String rankLine = rank == null ? Tr.t("no_rank_yet") : Tr.t("rank_i_of_n_name", s.indexOf(rank.group()) + 1, s.ranks().size(), rank.displayName());
        text(g, clip(rankLine, PANE_W - afterName, 0.6f), px + afterName, py + 2, MUTED, 0.6f);
        text(g, Tr.t("points_lifetime_season", d.points(), d.seasonPoints()), px, py + 14, GOLD, 0.75f);
        int cy = py + 28;
        inset(g, px, cy, PANE_W, 120);
        int col = 0, row = 0;
        for (Signal sig : Signal.values()) {
            Long n = d.counts().get(sig.key());
            SignalSetting ss = s.signal(sig);
            if ((n == null || n == 0) && !ss.on()) {
                continue;
            }
            int cx = px + 6 + col * 164, ry = cy + 4 + row * 11;
            String value = (n == null ? 0 : n) + "  (" + ss.pointsFor(n == null ? 0 : n) + ")";
            int valueW = (int) (font.width(value) * 0.6f);
            text(g, clip(I18n.get("signal.bsp_rankup." + sig.key()), Math.min(90, 156 - valueW - 4), 0.6f), cx, ry, ss.on() ? TEXT : MUTED, 0.6f);
            textRight(g, value, cx + 156, ry, ss.on() ? GOLD : MUTED, 0.6f);
            if (++row >= 10) {
                row = 0;
                col++;
            }
        }
        text(g, clip(Tr.t("bonus_points", d.bonus()) + (d.pending() > 0 ? "  " + Tr.n("rewards_waiting", d.pending()) : ""), PANE_W, 0.6f), px, cy + 124, MUTED, 0.6f);
        int by = cy + 136;
        if (settingRank) {
            // the rungs flow over up to SET_RANK_ROWS rows, left of CANCEL on the first; the rest are named by count
            text(g, Tr.t("set_rank_to"), px, by + 3, MUTED, 0.6f);
            int bx = px + 50, row2 = 0, shown = 0;
            for (Rank r : s.ranks()) {
                int w = Math.min(70, (int) (font.width(r.displayName()) * 0.6f) + 10);
                int rowEnd = row2 == 0 ? px + PANE_W - 44 : px + PANE_W;
                if (bx + w > rowEnd) {
                    if (++row2 >= SET_RANK_ROWS) {
                        break;
                    }
                    bx = px + 50;
                }
                chip(g, bx, by + row2 * 15, w, 13, clip(r.displayName(), w - 6, 0.6f), r.group().equals(d.rank()), TQ, 0.6f, () -> {
                    settingRank = false;
                    send(AdminActionPacket.of(AdminActionPacket.PLAYER_SET, d.uuid().toString(), r.group()));
                });
                bx += w + 3;
                shown++;
            }
            if (shown < s.ranks().size()) {
                text(g, clip(Tr.t("more_ranks_by_command", s.ranks().size() - shown), PANE_W - 50, 0.6f), px + 50, by + SET_RANK_ROWS * 15 + 2, MUTED, 0.6f);
            }
            button(g, px + PANE_W - 40, by, 40, 13, Tr.t("cancel"), MUTED, () -> settingRank = false);
        } else {
            button(g, px, by, 70, 13, Tr.t("set_rank"), TQ, () -> settingRank = true);
            button(g, px + 74, by, 76, 13, Tr.t("add_points"), GOLD, () -> openInput(Input.PLAYER_POINTS, "", Tr.t("points_to_add_negative_takes")));
            dangerButton(g, px + PANE_W - 70, by, 70, 13, Tr.t("reset_player"), "reset-player", () -> act(AdminActionPacket.PLAYER_RESET, d.uuid().toString()));
        }
    }

    // ------------------------------------------------------------------ OPTIONS

    private void drawOptions(GuiGraphics g, int x, int y, Settings s, AdminViewPacket v) {
        int px = x + 8, py = y + PANE_Y, w = W - 16;
        field(g, px, py, 150, Tr.t("luckperms_track"), s.track(), () -> {
            inputTarget = "track";
            openInput(Input.OPTION, s.track(), Tr.t("the_track_name_in_luckperms"));
            inputTarget = "track";
        });
        text(g, Tr.t("announce_rank_ups"), px + 160, py, MUTED, 0.6f);
        int ax = px + 160;
        for (Settings.Announce a : Settings.Announce.values()) {
            String name = a.name().toLowerCase(Locale.ROOT);
            chip(g, ax, py + 7, 50, 13, Tr.t("announce." + name), s.announce() == a, a == Settings.Announce.NETWORK ? VIO : TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.OPTION, "announce", name)));
            ax += 53;
        }
        field(g, px, py + 28, 150, Tr.t("kill_cooldown_minutes"), String.valueOf(s.killCooldownMinutes()), () -> {
            inputTarget = "cooldown";
            openInput(Input.OPTION, String.valueOf(s.killCooldownMinutes()), Tr.t("minutes_before_the_same_victim_counts_again"));
            inputTarget = "cooldown";
        });
        field(g, px + 160, py + 28, 100, Tr.t("title_priority"), String.valueOf(s.titlePriority()), () -> {
            inputTarget = "titlepriority";
            openInput(Input.OPTION, String.valueOf(s.titlePriority()), Tr.t("luckperms_prefix_priority_for_titles"));
            inputTarget = "titlepriority";
        });
        text(g, Tr.t("seasonal_rewards"), px + 270, py + 28, MUTED, 0.6f);
        chip(g, px + 270, py + 35, 60, 13, Tr.t("each_season"), s.rewardsAgainEachSeason(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.OPTION, "rewardsagain", "true")));
        chip(g, px + 333, py + 35, 40, 13, Tr.t("once"), !s.rewardsAgainEachSeason(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.OPTION, "rewardsagain", "false")));

        int by = py + 60;
        dangerButton(g, px, by, 150, 13, Tr.t("make_track_in_luckperms"), "make-track", () -> act(AdminActionPacket.MAKE_TRACK, ""));
        text(g, clip(Tr.t("makes_the_track_and_missing_groups"), 200, 0.6f), px + 156, by + 3, MUTED, 0.6f);
        button(g, px, by + 18, 72, 13, Tr.t("export"), TQ, () -> {
            exporting = true;
            openInput(Input.FILE, "", Tr.t("file_name_enter_writes_config_bsp_rankup"));
        });
        button(g, px + 76, by + 18, 72, 13, Tr.t("import"), TQ, () -> {
            exporting = false;
            openInput(Input.FILE, "", Tr.t("file_name_enter_reads_config_bsp_rankup"));
        });
        text(g, Tr.t("files_live_in_config_bsp_rankup"), px + 156, by + 21, MUTED, 0.6f);

        int iy = by + 42;
        inset(g, px, iy, w, 60);
        int ly = iy + 4;
        for (String line : v.info()) {
            text(g, clip(line, w - 12, 0.6f), px + 6, ly, MUTED, 0.6f);
            ly += 9;
            if (ly > iy + 52) {
                break;
            }
        }
        int ry = iy + 70;
        if (!v.resetCode().isEmpty()) {
            frame(g, px, ry, w, 36, BAD);
            text(g, Tr.t("reset_everyone_code", v.resetCode(), v.resetSeconds()), px + 6, ry + 5, BAD, 0.75f);
            text(g, Tr.t("every_players_counts_bonus_and_rank_are_forgotten"), px + 6, ry + 16, MUTED, 0.6f);
            button(g, px + w - 50, ry + 4, 46, 12, Tr.t("cancel"), MUTED, () -> act(AdminActionPacket.RESET_ALL_CANCEL, ""));
        } else {
            button(g, px, ry, 120, 13, Tr.t("reset_everyone"), BAD, () -> act(AdminActionPacket.RESET_ALL_REQUEST, ""));
            text(g, Tr.t("asks_for_a_code_first"), px + 126, ry + 3, MUTED, 0.6f);
        }
    }

    // ------------------------------------------------------------------ input

    private void drawInput(GuiGraphics g, int x, int y) {
        boolean show = inputMode != Input.NONE || browsing;
        input.setVisible(show);
        if (!show) {
            return;
        }
        int ix = x + PANE_X, iy = y + H - 34;
        g.fill(ix, iy, ix + PANE_W, iy + 16, INSET);
        frame(g, ix, iy, PANE_W, 16, browsing && browseItem == null ? GOLD : TQ);
        input.setX(ix + 4);
        input.setY(iy + 4);
        input.setWidth(PANE_W - 8);
        if (inputMode != Input.NONE) {
            text(g, Tr.t("enter_applies_esc_cancels"), x + 10, y + H - 12, MUTED, 0.6f);
        }
    }

    // ------------------------------------------------------------------ mouse and keys

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (super.onClick(mx, my, button)) {
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) { // newest first: what was drawn last is on top
            Hit h = hits.get(i);
            if (h.contains(mx, my)) {
                h.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onScroll(double mx, double my, double delta) {
        int x = left(), y = top();
        int step = (int) Math.signum(delta);
        if (browsing) {
            browseScroll -= step;
            return true;
        }
        if (mx >= x + LIST_X && mx < x + LIST_X + LIST_W && my >= y + LIST_Y && my < y + LIST_Y + LIST_H) {
            if (tab == T_LADDER && my >= y + RUNGS_Y) {
                listScroll -= step;
            } else if (tab == T_PLAYERS && my >= y + PLAYERS_Y) {
                playerScroll -= step;
            } else if (tab == T_GROUPS && my >= y + PLAYERS_Y) {
                groupScroll -= step;
            }
            return true;
        }
        if (tab == T_SIGNALS) {
            signalScroll -= step;
            return true;
        }
        if ((tab == T_LADDER || tab == T_GROUPS) && mx >= x + PANE_X) {
            rewardScroll -= step;
            return true;
        }
        return super.onScroll(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((inputMode != Input.NONE || browsing) && input.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                if (inputMode != Input.NONE) {
                    submitInput();
                }
                return true;
            }
            if (keyCode == 256) { // Esc
                if (inputMode == Input.RESET_CODE) {
                    act(AdminActionPacket.RESET_ALL_CANCEL, "");
                }
                closeInput();
                browsing = false;
                browseItem = null;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (view() != null && !view().resetCode().isEmpty()) {
            act(AdminActionPacket.RESET_ALL_CANCEL, "");
        }
        super.onClose();
    }
}
