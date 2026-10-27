package com.mrgregles.bsp_rankup.client;

import com.mrgregles.bsp_rankup.network.AdminActionPacket;
import com.mrgregles.bsp_rankup.network.AdminViewPacket;
import com.mrgregles.bsp_rankup.network.RankUpNetwork;
import com.mrgregles.bsp_rankup.progress.Signal;
import com.mrgregles.bsp_rankup.rank.RankEngine;
import com.mrgregles.bsp_rankup.settings.Award;
import com.mrgregles.bsp_rankup.settings.Ladder;
import com.mrgregles.bsp_rankup.settings.PrefixImage;
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
 * The admin screen (owner decisions D22 to D31, D51, D65): tabs LADDER, GROUPS, SIGNALS, PLAYERS, PREFIXES and OPTIONS;
 * on LADDER the rungs on the left and the chosen rung's fields and rewards on the right. Drawn in BSP Core's Holo HUD style with fills, at
 * the bigger size of D29. The server owns every value: each edit is sent as an {@link AdminActionPacket} and the
 * screen redraws from the view that comes back. Destructive buttons ask for a second click within five seconds;
 * RESET EVERYONE wants its code typed back (D25).
 */
public class AdminScreen extends HoloScreen {
    static final int W = 520, H = 300;
    static final int BG = 0xF010151C, INSET = 0xFF0C0E12, LINE = 0xFF2A2F3A, TQ = 0xFF19D3B0, VIO = 0xFFB58CFF, GOLD = 0xFFFFD23A,
            BAD = 0xFFFF6B5C, TEXT = 0xFFE6EAF2, MUTED = 0xFF9AA3B5;
    private static final String[] TABS = {"LADDER", "GROUPS", "SIGNALS", "PLAYERS", "PREFIXES", "OPTIONS"};
    private static final int T_LADDER = 0, T_GROUPS = 1, T_SIGNALS = 2, T_PLAYERS = 3, T_PREFIXES = 4, T_OPTIONS = 5;
    /** On PREFIXES (D65, mock-up A): the prefix list on the left, the chosen prefix and the folder's files on the right. */
    private static final int PFX_LIST_W = 200, PFX_PANE_X = 216, PFX_PANE_W = 296, FILE_ROW = 12, FILE_ROWS = 5;
    /** On LADDER (D51, mock-up A): the ladder chips, the chosen ladder's strip, then its rungs and the rung editor. */
    private static final int CHIPS_Y = 40, STRIP_Y = 56, RUNGS_Y = 88, RUNGS_H = 168, EDIT_Y = 90, REWARD_ROWS_H = 76;
    /** The strip is two lines (D81): the track and title priority, then PROGRESS FROM; a rung's rewards list is shorter to make room. */
    private static final int STRIP_H = 28, LADDER_REWARDS_H = 62;
    /** On SIGNALS (D81): the matrix's ladder and award columns, so many at a time. */
    private static final int MATRIX_X = 318, MATRIX_COL = 60, MATRIX_COLS = 3;
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
    /** PLAYERS (D98): ladder rows shown before the table scrolls, their height, award chips, the counts' bottom (from the pane's top). */
    private static final int LADDER_LINES = 3, LADDER_LINE = 15, AWARD_W = 80, AWARD_ROWS = 2, COUNTS_BOTTOM = 222;
    /** The ladders' letters on the counts, in turn. */
    private static final int[] TAG_COLOURS = {TQ, VIO, GOLD, 0xFF6EC1FF};
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
        NEW_LADDER, LADDER_TRACK, LADDER_COUNTS, LADDER_PRIORITY, LADDER_RENAME, AWARD_NEW, AWARD_NAME, AWARD_POINTS, AWARD_COUNTS, PREFIX_NOTE }

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
    /** On PLAYERS: the first ladder row and counts line shown, and where the counts start (for the wheel). */
    private int ladderLineScroll, countScroll, countsTop;
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
    /** The ladder whose rungs SET opened on PLAYERS (D100); null when closed. */
    @Nullable
    private String settingLadder;
    /** The kit picker (K-1) open under the reward kinds. */
    private boolean pickingKit;
    private int ticks;
    private String lastSearch = "";
    /** On PREFIXES: the chosen prefix (its rank key), the chosen file, the scrolls and the filter. */
    @Nullable
    private String prefixChoice;
    @Nullable
    private String fileChoice;
    private int prefixScroll, fileScroll;
    private boolean onlyWithoutImage;
    /** The SIGNALS matrix's first column shown, when there are more ladders and awards than fit. */
    private int matrixScroll;
    /** A line to show beside the mouse this frame (a counted signal's key, D82), or null. */
    @Nullable
    private String tooltip;
    /** The mouse this frame, in the screen's units, for hover hints drawn deep in the helpers. */
    private int lastMouseX, lastMouseY;
    /** A refusal the screen makes itself (the last counted signal, AU-2e), shown on the message line until {@link #warningUntil}. */
    private String warning = "";
    private int warningUntil;

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
            case PREFIX_NOTE -> send(AdminActionPacket.of(AdminActionPacket.PREFIX_NOTE, inputTarget, value));
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
        tooltip = null;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
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
                case T_PREFIXES -> drawPrefixes(g, x, y, mouseX, mouseY, s, v);
                default -> drawOptions(g, x, y, s, v);
            }
        }
        if (browsing) {
            drawBrowser(g, x, y, mouseX, mouseY);
        }
        drawInput(g, x, y);
        if (!warning.isEmpty() && ticks < warningUntil && inputMode == Input.NONE) {
            text(g, clip(warning, W - 20, 0.75f), x + 10, y + H - 12, BAD, 0.75f);
        } else if (v != null && !v.message().isEmpty() && inputMode == Input.NONE) {
            text(g, clip(v.message(), W - 20, 0.75f), x + 10, y + H - 12, v.ok() ? TQ : BAD, 0.75f);
        } else if (inputMode == Input.NONE) {
            text(g, Tr.t("changes_save_at_once"), x + 10, y + H - 12, MUTED, 0.6f);
        }
        super.draw(g, mouseX, mouseY, partial);
        if (tooltip != null) {
            g.renderTooltip(font, Component.literal(tooltip), mouseX, mouseY);
        }
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
            } else if (tab == T_PLAYERS || tab == T_OPTIONS || tab == T_GROUPS || tab == T_PREFIXES) {
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
        if (t == T_PREFIXES) {
            act(AdminActionPacket.PREFIX_SCAN, ""); // the folder read again and its previews sent
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
        String means = pointsMean(s, ladder.counts(), sel.points());
        if (means != null) {
            text(g, clip(means, 54, 0.5f), px + 222, py + 36, TQ, 0.5f);
        }
        text(g, Tr.t("season"), px + 278, py + 14, MUTED, 0.6f);
        chip(g, px + 278, py + 21, 26, 13, Tr.t("keep"), !sel.seasonal(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.LADDER_SEASONAL, sel.group(), "false")));
        chip(g, px + 305, py + 21, 27, 13, Tr.t("reset"), sel.seasonal(), VIO, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.LADDER_SEASONAL, sel.group(), "true")));

        text(g, Tr.t("rewards_when_reached"), px, py + 42, TQ, 0.6f);
        int ay = drawRewards(g, px, py + 50, LADDER_REWARDS_H, sel.group(), sel.rewards(), Tr.t("this_rung_gives_nothing_yet"));
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
        inset(g, x + 8, sy, W - 16, STRIP_H);
        text(g, Tr.t("track"), x + 13, sy + 4, MUTED, 0.6f);
        int tx = x + 13 + (int) (font.width(Tr.t("track")) * 0.6f) + 4;
        chip(g, tx, sy + 1, 60, 12, clip(l.track(), 54, 0.75f), false, TEXT, 0.75f, () -> openInput(Input.LADDER_TRACK, l.track(), Tr.t("hint.ladder_track")));
        // the second line (D81): what this ladder progresses from, each signal with its worth
        text(g, Tr.t("progress_from"), x + 13, sy + 18, TQ, 0.6f);
        String keys = keysOf(l.counts());
        drawCountChips(g, x + 13 + (int) (font.width(Tr.t("progress_from")) * 0.6f) + 4, sy + 15, x + W - 10, s, l.counts(),
                sig -> sendCounts(AdminActionPacket.LADDERS_COUNTS, l.name(), without(l.counts(), sig)),
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
     * The counted signals as chips (D81): each its name and worth ("off" when switched off, D84) with an x that takes
     * it off, its key on hover (D82); "every signal" in gold when none is chosen (D83); then + signal (opens the input).
     * Stops before {@code end}, naming how many more. Returns the x after the last chip.
     */
    private int drawCountChips(GuiGraphics g, int cx, int cy, int end, Settings s, Set<Signal> counts, java.util.function.Consumer<Signal> remove, Runnable add) {
        int addW = 46;
        if (counts.isEmpty()) {
            String all = Tr.t("every_signal");
            int w = (int) (font.width(all) * 0.6f) + 8;
            chip(g, cx, cy, w, 12, all, true, GOLD, 0.6f, null);
            cx += w + 3;
        } else {
            int shown = 0;
            for (Signal sig : Signal.values()) {
                if (!counts.contains(sig)) {
                    continue;
                }
                SignalSetting v = s.signal(sig);
                String name = I18n.get("signal.bsp_rankup." + sig.key());
                String worth = v.on() ? worth(sig, v) : Tr.t("signal_off");
                int nw = (int) (font.width(name) * 0.6f), ww = (int) (font.width(worth) * 0.6f);
                int w = nw + ww + 20;
                if (cx + w > end - addW - 3) {
                    String more = Tr.t("n_more", counts.size() - shown);
                    text(g, more, cx, cy + 3, MUTED, 0.6f);
                    cx += (int) (font.width(more) * 0.6f) + 4;
                    break;
                }
                g.fill(cx, cy, cx + w, cy + 12, v.on() ? 0x2219D3B0 : INSET);
                frame(g, cx, cy, w, 12, v.on() ? TQ : LINE);
                text(g, name, cx + 4, cy + 3, v.on() ? TQ : MUTED, 0.6f);
                text(g, worth, cx + 4 + nw + 4, cy + 3, v.on() ? GOLD : MUTED, 0.6f);
                text(g, "x", cx + w - 8, cy + 3, BAD, 0.6f);
                hits.add(new Hit(cx + w - 11, cy, 11, 12, () -> remove.accept(sig)));
                hoverHint(cx, cy, w, 12, sig, v);
                cx += w + 3;
                shown++;
            }
        }
        chip(g, cx, cy, addW, 12, Tr.t("add_signal"), false, MUTED, 0.6f, add);
        return cx + addW + 3;
    }

    /** A signal's worth in a few words: "1 pt / minute", "1 pt / 10 minutes". */
    private static String worth(Signal sig, SignalSetting v) {
        return v.per() == 1 ? Tr.t("worth_per_one", v.points(), I18n.get("signal.bsp_rankup." + sig.key() + ".unit_one"))
                : Tr.t("worth_per_many", v.points(), v.per(), I18n.get("signal.bsp_rankup." + sig.key() + ".unit"));
    }

    /** The mouse over a signal shows its key and where it comes from (D82), and "off: worth nothing now" when it is off (D84). */
    private void hoverHint(int bx, int by, int bw, int bh, Signal sig, SignalSetting v) {
        if (lastMouseX >= bx && lastMouseX < bx + bw && lastMouseY >= by && lastMouseY < by + bh) {
            String hint = Tr.t("signal_key_hint", I18n.get("signal.bsp_rankup." + sig.key()), sig.key(), Tr.t("source." + sig.source().name().toLowerCase(Locale.ROOT)));
            tooltip = v.on() ? hint : hint + " - " + Tr.t("signal_off_hint");
        }
    }

    /**
     * What a rung's or an award's points mean when one signal is counted and on: "= 120 minutes" (D81). Null otherwise
     * (several signals, every signal, or the signal off).
     */
    @Nullable
    private static String pointsMean(Settings s, Set<Signal> counts, long points) {
        if (counts.size() != 1 || points <= 0) {
            return null;
        }
        Signal sig = counts.iterator().next();
        SignalSetting v = s.signal(sig);
        if (!v.on() || v.points() <= 0 || v.per() <= 0) {
            return null;
        }
        long units = (points * v.per() + v.points() - 1) / v.points();
        return Tr.t("equals_units", units, I18n.get("signal.bsp_rankup." + sig.key() + (units == 1 ? ".unit_one" : ".unit")));
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

    /**
     * The counted signals less one, as the counts action takes them; null when it was the last (AU-2e: none left
     * would mean every signal, and a misclick would then promote players on everything they did).
     */
    @Nullable
    private static String without(Set<Signal> counts, Signal gone) {
        Set<Signal> rest = new java.util.LinkedHashSet<>(counts);
        rest.remove(gone);
        return rest.isEmpty() ? null : keysOf(rest);
    }

    /** Sends a ladder's or award's new counts, or says why not when the last counted signal would go. */
    private void sendCounts(String action, String target, @Nullable String keys) {
        if (keys == null) {
            warning = Tr.t("last_signal_stays");
            warningUntil = ticks + 100;
            return;
        }
        warning = "";
        send(AdminActionPacket.of(action, target, keys));
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
            boolean title = r.kind() == Reward.Kind.TITLE;
            text(g, clip(describe(r), lw - (title ? 118 : 88), 0.75f), lx + 50, ry + 2, TEXT, 0.75f); // ends before the item icon at lw - 32, or the image slot
            if (title) {
                drawImageSlot(g, lx + lw - 64, ry + 1, group);
            }
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

    /** A title's image, read-only (D65): the image or "image" in a frame; a click opens PREFIXES on it. */
    private void drawImageSlot(GuiGraphics g, int sx, int sy, String group) {
        Settings s = settings();
        String key = PrefixImage.titleKey(group);
        PrefixImage img = s == null ? null : s.prefixImage(key);
        frame(g, sx, sy, 48, 10, img == null ? LINE : VIO);
        if (img == null) {
            text(g, Tr.t("image_slot"), sx + (48 - (int) (font.width(Tr.t("image_slot")) * 0.5f)) / 2, sy + 3, MUTED, 0.5f);
        } else {
            drawComponent(g, PrefixFont.component(img.id(), ""), sx + 2, sy + 1, 1f, 44);
        }
        hits.add(new Hit(sx, sy, 48, 10, () -> {
            switchTab(T_PREFIXES);
            if (s != null) {
                choosePrefix(s, key);
            }
        }));
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
        String needsMean = pointsMean(s, award.counts(), award.points());
        if (needsMean != null) {
            text(g, clip(needsMean, 76, 0.5f), px + 200, py + 33, TQ, 0.5f);
        }
        text(g, Tr.t("progress_from"), px, py + 47, TQ, 0.6f);
        String keys = keysOf(award.counts());
        drawCountChips(g, px + (int) (font.width(Tr.t("progress_from")) * 0.6f) + 4, py + 44, px + PANE_W, s, award.counts(),
                sig -> sendCounts(AdminActionPacket.AWARD_COUNTS, group, without(award.counts(), sig)),
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

    /** A column of the SIGNALS matrix (D81): a ladder or an award, the signals it counts, and how to change them. */
    private record MatrixColumn(String label, int colour, Set<Signal> counts, java.util.function.Consumer<String> setCounts) {
    }

    private void drawSignals(GuiGraphics g, int x, int y, int mouseX, int mouseY, Settings s) {
        int lx = x + 8, ly = y + LIST_Y, lw = W - 16, lh = 230;
        inset(g, lx, ly, lw, lh);
        text(g, Tr.t("signal"), lx + 6, ly + 4, MUTED, 0.6f);
        text(g, Tr.t("on"), lx + 126, ly + 4, MUTED, 0.6f);
        text(g, Tr.t("worth"), lx + 154, ly + 4, MUTED, 0.6f);
        // a column per ladder, then per award; a click on a cell switches that signal there
        List<MatrixColumn> columns = new ArrayList<>();
        for (Ladder l : s.ladders()) {
            columns.add(new MatrixColumn(RankEngine.label(l).toUpperCase(Locale.ROOT), TQ, l.counts(), keys -> sendCounts(AdminActionPacket.LADDERS_COUNTS, l.name(), keys)));
        }
        for (Award a : s.awards()) {
            columns.add(new MatrixColumn(a.displayName().toUpperCase(Locale.ROOT), GOLD, a.counts(), keys -> sendCounts(AdminActionPacket.AWARD_COUNTS, a.group(), keys)));
        }
        matrixScroll = Mth.clamp(matrixScroll, 0, Math.max(0, columns.size() - MATRIX_COLS));
        int mx = lx + MATRIX_X;
        List<MatrixColumn> shownColumns = columns.subList(matrixScroll, Math.min(columns.size(), matrixScroll + MATRIX_COLS));
        for (int c = 0; c < shownColumns.size(); c++) {
            MatrixColumn col = shownColumns.get(c);
            String label = clip(col.label(), MATRIX_COL - 4, 0.5f);
            text(g, label, mx + c * MATRIX_COL + (MATRIX_COL - (int) (font.width(label) * 0.5f)) / 2, ly + 5, col.colour(), 0.5f);
        }
        // the pager sits under the matrix, right of the hint, clear of the column labels (AU-2f)
        int hintW = lw;
        if (columns.size() > MATRIX_COLS) {
            String more = Tr.t("matrix_more", matrixScroll + shownColumns.size(), columns.size());
            int moreX = lx + lw - 28 - 4 - (int) (font.width(more) * 0.5f);
            chip(g, lx + lw - 26, ly + lh + 2, 12, 9, "<", false, MUTED, 0.5f, matrixScroll > 0 ? () -> matrixScroll-- : null);
            chip(g, lx + lw - 12, ly + lh + 2, 12, 9, ">", false, MUTED, 0.5f, matrixScroll + MATRIX_COLS < columns.size() ? () -> matrixScroll++ : null);
            text(g, more, moreX, ly + lh + 4, MUTED, 0.5f);
            hintW = moreX - 6 - lx;
        }
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
            text(g, clip(I18n.get("signal.bsp_rankup." + sig.key()), 116, 0.85f), lx + 6, ry + 5, v.on() ? TEXT : MUTED, 0.85f);
            hoverHint(lx + 6, ry + 3, 116, 11, sig, v);
            chip(g, lx + 124, ry + 2, 22, 12, v.on() ? Tr.t("on") : Tr.t("off"), v.on(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.SIGNAL_ON, sig.key(), String.valueOf(!v.on()))));
            String worth = Tr.t("n_points_per_m_units", v.points(), v.per(), I18n.get("signal.bsp_rankup." + sig.key() + ".unit"));
            inset(g, lx + 152, ry + 2, 158, 12);
            text(g, clip(worth, 150, 0.75f), lx + 156, ry + 4, v.on() ? GOLD : MUTED, 0.75f);
            hits.add(new Hit(lx + 152, ry + 2, 158, 12, () -> {
                inputTarget = sig.key();
                openInput(Input.SIGNAL, v.points() + " " + v.per(), Tr.t("points_per_enter_sets_them"));
                inputTarget = sig.key();
            }));
            for (int c = 0; c < shownColumns.size(); c++) {
                MatrixColumn col = shownColumns.get(c);
                boolean every = col.counts().isEmpty();
                boolean counted = every || col.counts().contains(sig);
                int cx = mx + c * MATRIX_COL + (MATRIX_COL - 20) / 2;
                // "every signal" ticks in gold, dimmed (D83); an off signal's tick dimmed (D84)
                int colour = every ? GOLD : v.on() ? col.colour() : MUTED;
                chip(g, cx, ry + 2, 20, 12, counted ? "\u2713" : "", counted && !every && v.on(), colour, 0.75f, () -> col.setCounts().accept(toggled(col.counts(), sig)));
            }
        }
        scrollbar(g, lx + lw, ly + 14, lh - 14, all.length, visible, signalScroll);
        text(g, clip(Tr.t("matrix_hint"), hintW, 0.5f), lx, ly + lh + 4, MUTED, 0.5f);
    }

    /**
     * The counted signals with one switched, as the counts action takes them: on a ladder counting every signal a click
     * leaves every other one; ticking the last one missing makes it every signal again; taking off the last one is
     * refused (null, AU-2e).
     */
    @Nullable
    private static String toggled(Set<Signal> counts, Signal sig) {
        Set<Signal> next = new java.util.LinkedHashSet<>(counts.isEmpty() ? java.util.EnumSet.allOf(Signal.class) : counts);
        if (!next.remove(sig)) {
            next.add(sig);
        }
        if (next.isEmpty()) {
            return null;
        }
        return keysOf(next.size() == Signal.values().length ? Set.of() : next);
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
                settingLadder = null;
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
        String shownName = clip(d.name(), 150, 1f);
        text(g, shownName, px, py, TEXT, 1f);
        boolean online = d.online();
        text(g, online ? Tr.t("online") : Tr.t("away"), px + 4 + font.width(shownName), py + 2, online ? TQ : MUTED, 0.6f);
        String bonus = Tr.t("bonus_points", d.bonus()) + (d.pending() > 0 ? "  " + Tr.n("rewards_waiting", d.pending()) : "");
        textRight(g, clip(bonus, 170, 0.6f), px + PANE_W, py + 2, MUTED, 0.6f);

        // LADDERS (D98): one row per ladder, SET opening its rungs (D100)
        List<AdminViewPacket.LadderLine> ladders = d.ladders().stream().filter(l -> s.ladder(l.ladder()) != null).toList();
        int shownLadders = Mth.clamp(ladders.size(), 1, LADDER_LINES);
        ladderLineScroll = Mth.clamp(ladderLineScroll, 0, Math.max(0, ladders.size() - LADDER_LINES));
        text(g, Tr.t("ladders_heading"), px, py + 14, TQ, 0.5f);
        int ty = py + 21, th = 12 + shownLadders * LADDER_LINE + 1;
        inset(g, px, ty, PANE_W, th);
        String[] heads = {Tr.t("col.ladder"), Tr.t("col.rank"), Tr.t("col.points"), Tr.t("col.this_season"), Tr.t("col.to_next")};
        int[] cols = {4, 50, 124, 164, 214};
        for (int i = 0; i < heads.length; i++) {
            text(g, heads[i], px + cols[i], ty + 3, MUTED, 0.5f);
        }
        for (int i = 0; i < shownLadders && i + ladderLineScroll < ladders.size(); i++) {
            AdminViewPacket.LadderLine line = ladders.get(i + ladderLineScroll);
            Ladder l = s.ladder(line.ladder());
            int ry = ty + 12 + i * LADDER_LINE;
            boolean open = l.name().equals(settingLadder);
            if (open) {
                g.fill(px + 1, ry - 1, px + PANE_W - 1, ry + LADDER_LINE - 2, 0x2219D3B0);
            }
            text(g, clip(RankEngine.label(l), 44, 0.75f), px + 4, ry + 3, TEXT, 0.75f);
            int idx = l.indexOf(line.rank());
            if (idx < 0) {
                text(g, Tr.t("no_rank_yet"), px + 50, ry + 4, MUTED, 0.6f);
            } else {
                Rank rank = l.ranks().get(idx);
                drawPrefix(g, s, rankImageKey(s, rank.group()), rank.displayName(), px + 50, ry + 3, 0.75f, 52);
                text(g, Tr.t("rung_i_of_n", idx + 1, l.ranks().size()), px + 105, ry + 4, MUTED, 0.5f);
            }
            text(g, String.valueOf(line.points()), px + 124, ry + 4, GOLD, 0.6f);
            text(g, String.valueOf(line.seasonPoints()), px + 164, ry + 4, MUTED, 0.6f);
            if (idx + 1 < l.ranks().size()) {
                Rank next = l.ranks().get(idx + 1);
                long have = next.seasonal() ? line.seasonPoints() : line.points();
                text(g, clip(Tr.t("next_rung_at", next.displayName(), next.points()), 72, 0.5f), px + 214, ry + 1, MUTED, 0.5f);
                g.fill(px + 214, ry + 8, px + 286, ry + 11, LINE);
                g.fill(px + 214, ry + 8, px + 214 + (int) (72 * Math.min(1.0, next.points() <= 0 ? 1.0 : (double) Math.max(0, have) / next.points())), ry + 11, TQ);
            } else if (!l.ranks().isEmpty()) {
                text(g, Tr.t("top_rung"), px + 214, ry + 4, MUTED, 0.5f);
            }
            if (!l.ranks().isEmpty()) {
                chip(g, px + PANE_W - 38, ry, 32, 12, Tr.t("set"), open, TQ, 0.6f, () -> settingLadder = open ? null : l.name());
            }
        }
        scrollbar(g, px + PANE_W, ty + 11, th - 11, ladders.size(), LADDER_LINES, ladderLineScroll);
        int zy = ty + th + 5;

        Ladder setting = settingLadder == null ? null : s.ladder(settingLadder);
        AdminViewPacket.LadderLine settingLine = setting == null ? null : ladders.stream().filter(l -> l.ladder().equals(setting.name())).findFirst().orElse(null);
        if (setting == null || settingLine == null) {
            settingLadder = null;
            zy = drawPlayerAwards(g, px, zy, s, d);
        } else {
            // the rungs flow over up to SET_RANK_ROWS rows, left of CANCEL on the first; the rest are named by count
            text(g, clip(Tr.t("set_ladder_to", RankEngine.label(setting).toUpperCase(Locale.ROOT)), 52, 0.6f), px, zy + 3, MUTED, 0.6f);
            text(g, Tr.t("no_rewards"), px, zy + 11, MUTED, 0.5f);
            int bx = px + 56, row = 0, shown = 0;
            for (Rank r : setting.ranks()) {
                int w = Math.min(70, (int) (font.width(r.displayName()) * 0.6f) + 8);
                int rowEnd = row == 0 ? px + PANE_W - 44 : px + PANE_W;
                if (bx + w > rowEnd) {
                    if (++row >= SET_RANK_ROWS) {
                        break;
                    }
                    bx = px + 56;
                }
                chip(g, bx, zy + row * 15, w, 13, clip(r.displayName(), w - 4, 0.6f), r.group().equals(settingLine.rank()), TQ, 0.6f, () -> {
                    settingLadder = null;
                    send(AdminActionPacket.of(AdminActionPacket.PLAYER_SET, d.uuid().toString(), r.group()));
                });
                bx += w + 3;
                shown++;
            }
            // the loop stops either past the last rung (row is the last row used) or on a row it did not draw
            int rowsUsed = Math.min(row + 1, SET_RANK_ROWS);
            boolean more = shown < setting.ranks().size();
            if (more) {
                text(g, clip(Tr.t("more_ranks_by_command", setting.ranks().size() - shown), PANE_W - 56, 0.5f), px + 56, zy + rowsUsed * 15, MUTED, 0.5f);
            }
            button(g, px + PANE_W - 40, zy, 40, 13, Tr.t("cancel"), MUTED, () -> settingLadder = null);
            zy += rowsUsed * 15 + 2 + (more ? 8 : 0);
        }

        // COUNTS, each tagged with the ladders that count it (D103)
        List<Ladder> all = s.ladders();
        String[] tags = ladderTags(all);
        text(g, Tr.t("counts_heading"), px, zy, TQ, 0.5f);
        int lx = px + 4 + (int) (font.width(Tr.t("counts_heading")) * 0.5f) + 6;
        text(g, Tr.t("counts_letters"), lx, zy, MUTED, 0.5f);
        lx += (int) (font.width(Tr.t("counts_letters")) * 0.5f) + 6;
        for (int i = 0; i < all.size() && lx < px + PANE_W - 20; i++) {
            String legend = tags[i] + " " + RankEngine.label(all.get(i)).toUpperCase(Locale.ROOT);
            text(g, clip(legend, px + PANE_W - lx, 0.5f), lx, zy, TAG_COLOURS[i % TAG_COLOURS.length], 0.5f);
            lx += (int) (font.width(legend) * 0.5f) + 6;
        }
        List<Signal> shownSignals = new ArrayList<>();
        for (Signal sig : Signal.values()) {
            Long n = d.counts().get(sig.key());
            if (n != null && n != 0 || s.signal(sig).on()) {
                shownSignals.add(sig);
            }
        }
        int cy = zy + 7, ch = py + COUNTS_BOTTOM - cy;
        inset(g, px, cy, PANE_W, ch);
        int countRows = Math.max(1, (ch - 4) / 10), lines = (shownSignals.size() + 1) / 2;
        countScroll = Mth.clamp(countScroll, 0, Math.max(0, lines - countRows));
        countsTop = cy;
        for (int i = countScroll * 2; i < shownSignals.size() && i < (countScroll + countRows) * 2; i++) {
            Signal sig = shownSignals.get(i);
            long n = d.counts().getOrDefault(sig.key(), 0L);
            SignalSetting ss = s.signal(sig);
            int cx = px + 6 + (i % 2) * 164, ry = cy + 3 + (i / 2 - countScroll) * 10;
            String value = n + "  (" + ss.pointsFor(n) + ")";
            int valueW = (int) (font.width(value) * 0.6f);
            String name = clip(I18n.get("signal.bsp_rankup." + sig.key()), Math.min(90, 156 - valueW - 4), 0.6f);
            text(g, name, cx, ry, ss.on() ? TEXT : MUTED, 0.6f);
            int tx = cx + (int) (font.width(name) * 0.6f) + 3;
            for (int j = 0; j < all.size() && tx < cx + 156 - valueW - 6; j++) {
                if (all.get(j).counts(sig)) {
                    text(g, tags[j], tx, ry + 1, TAG_COLOURS[j % TAG_COLOURS.length], 0.5f);
                    tx += (int) (font.width(tags[j]) * 0.5f) + 2;
                }
            }
            textRight(g, value, cx + 156, ry, ss.on() ? GOLD : MUTED, 0.6f);
        }
        scrollbar(g, px + PANE_W, cy, ch, lines, countRows, countScroll);

        int by = py + COUNTS_BOTTOM + 4;
        button(g, px, by, 76, 13, Tr.t("add_points"), GOLD, () -> openInput(Input.PLAYER_POINTS, "", Tr.t("points_to_add_negative_takes")));
        text(g, Tr.t("adds_to_every_ladder"), px + 82, by + 4, MUTED, 0.5f);
        textRight(g, Tr.t("resets_every_ladder"), px + PANE_W - 76, by + 4, MUTED, 0.5f);
        dangerButton(g, px + PANE_W - 70, by, 70, 13, Tr.t("reset_player"), "reset-player", () -> act(AdminActionPacket.PLAYER_RESET, d.uuid().toString()));
    }

    /** The awards as chips (D101): reached ones lit, the rest with their points so far; returns the y after them. */
    private int drawPlayerAwards(GuiGraphics g, int px, int zy, Settings s, AdminViewPacket.PlayerDetail d) {
        List<AdminViewPacket.AwardLine> awards = d.awards().stream().filter(a -> s.award(a.group()) != null).toList();
        long reached = awards.stream().filter(AdminViewPacket.AwardLine::reached).count();
        text(g, Tr.t("awards_heading", reached, awards.size()), px, zy, VIO, 0.5f);
        if (awards.isEmpty()) {
            text(g, Tr.t("no_awards_set"), px, zy + 8, MUTED, 0.6f);
            return zy + 20;
        }
        int perRow = PANE_W / (AWARD_W + 4), max = perRow * AWARD_ROWS;
        if (awards.size() > max) {
            textRight(g, Tr.t("more_awards", awards.size() - max + 1), px + PANE_W, zy, MUTED, 0.5f);
        }
        int shown = awards.size() > max ? max - 1 : awards.size();
        for (int i = 0; i < shown; i++) {
            AdminViewPacket.AwardLine a = awards.get(i);
            Award award = s.award(a.group());
            int ax = px + (i % perRow) * (AWARD_W + 4), ay = zy + 7 + (i / perRow) * 23;
            g.fill(ax, ay, ax + AWARD_W, ay + 20, a.reached() ? 0x22B58CFF : INSET);
            frame(g, ax, ay, AWARD_W, 20, a.reached() ? VIO : LINE);
            drawPrefix(g, s, rankImageKey(s, award.group()), award.displayName(), ax + 3, ay + 3, 0.6f, AWARD_W - 6);
            if (a.reached()) {
                text(g, Tr.t("reached"), ax + 3, ay + 12, VIO, 0.5f);
            } else {
                String have = a.points() + "/" + award.points();
                text(g, have, ax + 3, ay + 12, GOLD, 0.5f);
                int bx = ax + 6 + (int) (font.width(have) * 0.5f);
                g.fill(bx, ay + 13, ax + AWARD_W - 4, ay + 15, LINE);
                g.fill(bx, ay + 13, bx + (int) ((ax + AWARD_W - 4 - bx) * Math.min(1.0, award.points() <= 0 ? 1.0 : (double) Math.max(0, a.points()) / award.points())), ay + 15, GOLD);
            }
        }
        return zy + 7 + ((shown + perRow - 1) / perRow) * 23 + 2;
    }

    /** The image a rank shows in chat: its title's when it has one, else its group's own prefix's (D57); text when neither. */
    private static String rankImageKey(Settings s, String group) {
        String title = PrefixImage.titleKey(group);
        return s.prefixImage(title) != null ? title : PrefixImage.groupKey(group);
    }

    /** One letter per ladder for the counts (D103): the first of its name, or its number when an earlier ladder has that letter. */
    static String[] ladderTags(List<Ladder> ladders) {
        String[] tags = new String[ladders.size()];
        Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < ladders.size(); i++) {
            String n = RankEngine.label(ladders.get(i));
            String tag = n.isEmpty() ? String.valueOf(i + 1) : n.substring(0, 1).toUpperCase(Locale.ROOT);
            tags[i] = used.add(tag) ? tag : String.valueOf(i + 1);
            used.add(tags[i]);
        }
        return tags;
    }

    // ------------------------------------------------------------------ PREFIXES (D65)

    /** One line of the PREFIXES list: a heading (row null) or a prefix. */
    private record PrefixLine(@Nullable String heading, @Nullable AdminViewPacket.PrefixRow row) {
    }

    /** The list, built when the view or the filter changes rather than every frame. */
    private List<PrefixLine> prefixLines = List.of();
    @Nullable
    private AdminViewPacket prefixLinesFor;
    private boolean prefixLinesFilter;

    /** The prefixes under a heading each: each ladder's titles, the awards' titles, LuckPerms groups' own prefixes, then their suffixes (D94). */
    private List<PrefixLine> prefixLines(Settings s, AdminViewPacket v) {
        if (v == prefixLinesFor && onlyWithoutImage == prefixLinesFilter) {
            return prefixLines;
        }
        prefixLinesFor = v;
        prefixLinesFilter = onlyWithoutImage;
        Map<String, List<PrefixLine>> sections = new java.util.LinkedHashMap<>();
        for (AdminViewPacket.PrefixRow r : v.prefixes()) {
            if (onlyWithoutImage && s.prefixImage(r.key()) != null) {
                continue;
            }
            sections.computeIfAbsent(sectionOf(s, r.key()), k -> new ArrayList<>()).add(new PrefixLine(null, r));
        }
        List<PrefixLine> lines = new ArrayList<>();
        sections.forEach((heading, rows) -> {
            lines.add(new PrefixLine(heading, null));
            lines.addAll(rows);
        });
        prefixLines = lines;
        return lines;
    }

    private static String sectionOf(Settings s, String key) {
        if (key.startsWith(PrefixImage.GROUP)) {
            return Tr.t("prefixes.groups");
        }
        if (key.startsWith(PrefixImage.SUFFIX)) {
            return Tr.t("prefixes.suffixes");
        }
        Ladder on = s.ladderOf(PrefixImage.groupOf(key));
        return on == null ? Tr.t("prefixes.awards") : Tr.t("prefixes.ladder", RankEngine.label(on).toUpperCase(Locale.ROOT));
    }

    /**
     * Draws a prefix as chat shows it (D61): the rank's image when it has one, else the coloured text; clipped to
     * {@code maxW} screen units at the scale. Returns the width drawn.
     */
    private int drawPrefix(GuiGraphics g, Settings s, String key, String text, int x, int y, float scale, int maxW) {
        PrefixImage img = key.isEmpty() ? null : s.prefixImage(key);
        return drawComponent(g, PrefixFont.component(img == null ? "" : img.id(), text), x, y, scale, maxW);
    }

    private int drawComponent(GuiGraphics g, Component c, int x, int y, float scale, int maxW) {
        return drawComponent(g, c, x, y, scale, maxW, false);
    }

    private int drawComponent(GuiGraphics g, Component c, int x, int y, float scale, int maxW, boolean shadow) {
        net.minecraft.network.chat.FormattedText cut = font.substrByWidth(c, (int) (maxW / scale));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(cut), 0, 0, TEXT, shadow);
        g.pose().popPose();
        return (int) (font.width(cut) * scale);
    }

    private void drawPrefixes(GuiGraphics g, int x, int y, int mouseX, int mouseY, Settings s, AdminViewPacket v) {
        int lx = x + LIST_X, ly = y + LIST_Y, lh = 216;
        inset(g, lx, ly, PFX_LIST_W, lh);
        List<PrefixLine> lines = prefixLines(s, v);
        int visible = (lh - 2) / ROW;
        prefixScroll = Mth.clamp(prefixScroll, 0, Math.max(0, lines.size() - visible));
        for (int i = 0; i < visible && i + prefixScroll < lines.size(); i++) {
            PrefixLine line = lines.get(i + prefixScroll);
            int ry = ly + 1 + i * ROW;
            if (line.row() == null) {
                text(g, clip(line.heading(), PFX_LIST_W - 12, 0.6f), lx + 6, ry + 5, line.heading().equals(Tr.t("prefixes.suffixes")) ? VIO : TQ, 0.6f);
                continue;
            }
            AdminViewPacket.PrefixRow r = line.row();
            boolean sel = r.key().equals(prefixChoice);
            boolean hover = mouseX >= lx && mouseX < lx + PFX_LIST_W && mouseY >= ry && mouseY < ry + ROW;
            if (sel) {
                g.fill(lx + 1, ry, lx + PFX_LIST_W - 1, ry + ROW, 0x2219D3B0);
                g.fill(lx + 1, ry, lx + 3, ry + ROW, TQ);
            } else if (hover) {
                g.fill(lx + 1, ry, lx + PFX_LIST_W - 1, ry + ROW, 0x14FFFFFF);
            }
            boolean image = s.prefixImage(r.key()) != null;
            int right = lx + PFX_LIST_W - 6;
            if (!image) {
                textRight(g, Tr.t("tag.text"), right, ry + 5, MUTED, 0.5f);
                right -= (int) (font.width(Tr.t("tag.text")) * 0.5f) + 4;
            }
            float scale = image ? 1f : 0.75f;
            int pw = Math.min(100, (int) (font.width(PrefixFont.component(image ? s.prefixImage(r.key()).id() : "", r.text())) * scale));
            drawPrefix(g, s, r.key(), r.text(), right - pw, ry + (image ? 3 : 4), scale, 100);
            String name = PrefixImage.isLuckPerms(r.key()) ? PrefixImage.groupOf(r.key()) : r.rankName().substring(r.rankName().indexOf(": ") + 1).strip();
            text(g, clip(name, right - pw - 6 - (lx + 8), 0.85f), lx + 8, ry + 3, sel ? TEXT : MUTED, 0.85f);
            hits.add(new Hit(lx, ry, PFX_LIST_W, ROW, () -> choosePrefix(s, r.key())));
        }
        if (lines.isEmpty()) {
            text(g, clip(Tr.t("no_prefixes"), PFX_LIST_W - 12, 0.6f), lx + 6, ly + 8, MUTED, 0.6f);
        }
        scrollbar(g, lx + PFX_LIST_W, ly, lh, lines.size(), visible, prefixScroll);
        button(g, lx, ly + lh + 6, 60, 13, Tr.t("refresh"), MUTED, () -> act(AdminActionPacket.PREFIX_SCAN, ""));
        chip(g, lx + 63, ly + lh + 6, 92, 13, Tr.t("only_without_image"), onlyWithoutImage, TQ, 0.6f, () -> onlyWithoutImage = !onlyWithoutImage);

        int px = x + PFX_PANE_X, py = y + PANE_Y, pw = PFX_PANE_W;
        AdminViewPacket.PrefixRow chosen = null;
        for (AdminViewPacket.PrefixRow r : v.prefixes()) {
            if (r.key().equals(prefixChoice)) {
                chosen = r;
            }
        }
        if (chosen == null) {
            if (v.prefixes().isEmpty()) {
                text(g, Tr.t("no_prefixes"), px + 4, py + 8, MUTED, 0.75f);
                text(g, clip(Tr.t("no_prefixes_how"), pw - 4, 0.6f), px + 4, py + 20, MUTED, 0.6f);
            } else {
                text(g, clip(Tr.t("pick_a_prefix"), pw, 0.75f), px + 4, py + 8, MUTED, 0.75f);
            }
            return;
        }
        String key = chosen.key(), group = PrefixImage.groupOf(key);
        PrefixImage img = s.prefixImage(key);
        boolean isGroup = PrefixImage.isLuckPerms(key), isSuffix = key.startsWith(PrefixImage.SUFFIX);
        String title = isGroup ? group : chosen.rankName().substring(chosen.rankName().indexOf(": ") + 1).strip();
        text(g, clip(title.toUpperCase(Locale.ROOT), 150, 1f), px, py, TEXT, 1f);
        Ladder on = isGroup ? null : s.ladderOf(group);
        String kind = isSuffix ? Tr.t("prefix_kind.suffix") : isGroup ? Tr.t("prefix_kind.group") : on != null ? Tr.t("prefix_kind.rung", on.indexOf(group) + 1, RankEngine.label(on)) : Tr.t("prefix_kind.award");
        textRight(g, clip(kind, pw - 156, 0.6f), px + pw, py + 2, isSuffix ? VIO : MUTED, 0.6f);

        text(g, isSuffix ? Tr.t("suffix_text") : Tr.t("prefix_text"), px, py + 14, MUTED, 0.6f);
        inset(g, px, py + 21, 150, 13);
        drawPrefix(g, s, "", chosen.text(), px + 4, py + 24, 0.85f, 142);
        if (isGroup) {
            text(g, clip(Tr.t("changed_in_luckperms"), pw - 156, 0.6f), px + 156, py + 25, MUTED, 0.6f);
        } else {
            button(g, px + 156, py + 21, 74, 13, on != null ? Tr.t("edit_on_ladder") : Tr.t("edit_on_groups"), MUTED, () -> {
                if (on != null) {
                    selectedLadder = on.name();
                    selectedGroup = group;
                    switchTab(T_LADDER);
                } else {
                    groupChoice = group;
                    switchTab(T_GROUPS);
                }
            });
        }

        text(g, Tr.t("image"), px, py + 40, MUTED, 0.6f);
        inset(g, px, py + 47, pw, 26);
        if (img == null) {
            text(g, Tr.t("no_image_text_shows"), px + 6, py + 56, MUTED, 0.75f);
        } else {
            int w1 = drawPrefix(g, s, key, chosen.text(), px + 6, py + 56, 1f, 64);
            text(g, Tr.t("line_size"), px + 6 + w1 + 4, py + 57, MUTED, 0.5f);
            int x2 = px + 6 + w1 + 8 + (int) (font.width(Tr.t("line_size")) * 0.5f);
            int w2 = drawPrefix(g, s, key, chosen.text(), x2, py + 51, 2f, 128);
            text(g, Tr.t("times_two"), x2 + w2 + 4, py + 57, MUTED, 0.5f);
            // the file's name and size, right of the x2 image (a 64 px wide image leaves about 50 units)
            int room = px + pw - 6 - (x2 + w2 + 4 + (int) (font.width(Tr.t("times_two")) * 0.5f) + 6);
            if (room > 20) {
                textRight(g, clip(Tr.t("image_file", img.file(), img.width(), img.height()), room, 0.6f), px + pw - 6, py + 51, MUTED, 0.6f);
                if (v.files().stream().noneMatch(f -> f.id().equals(img.id()))) {
                    textRight(g, clip(Tr.t("not_in_folder"), room, 0.6f), px + pw - 6, py + 62, BAD, 0.6f);
                }
            }
        }

        // the hover note (D94): typed in the box below, Enter saves it, an empty note takes it away
        String note = s.prefixNote(key);
        field(g, px, py + 78, 196, Tr.t("hover_note"), note.isEmpty() ? Tr.t("hover_note_none") : note, () -> {
            inputTarget = key;
            openInput(Input.PREFIX_NOTE, note, Tr.t("hint.hover_note"));
        });
        text(g, clip(Tr.t("hover_note_where"), pw - 202, 0.5f), px + 202, py + 89, MUTED, 0.5f);

        text(g, Tr.t("in_chat"), px, py + 103, MUTED, 0.6f);
        g.fill(px, py + 110, px + pw, py + 123, 0x80000000);
        String me = minecraft != null && minecraft.player != null ? minecraft.player.getGameProfile().getName() : "Steve";
        Component piece = img == null ? com.mrgregles.bsp_rankup.prefix.Legacy.parse(chosen.text().strip()) : PrefixFont.component(img.id(), chosen.text());
        Component chat = isSuffix
                ? Component.literal("<" + me + " ").append(piece).append(Component.literal("> " + Tr.t("chat_hello")))
                : Component.literal("<").append(piece).append(Component.literal(" " + me + "> " + Tr.t("chat_hello")));
        drawComponent(g, chat, px + 4, py + 113, 1f, pw - 8, true); // with chat's shadow

        drawFiles(g, px, py + 130, pw, mouseX, mouseY, s, v, key, img);
    }

    private void choosePrefix(Settings s, String key) {
        if (inputMode == Input.PREFIX_NOTE && !key.equals(prefixChoice)) {
            closeInput(); // the note box belongs to the prefix it was opened on: Enter must not save it to this one
        }
        prefixChoice = key;
        PrefixImage img = s.prefixImage(key);
        fileChoice = img == null ? null : img.file();
        armed = null;
    }

    /** The images folder's files with their previews; a usable one is chosen with a click, then USE IMAGE. */
    private void drawFiles(GuiGraphics g, int px, int fy, int pw, int mouseX, int mouseY, Settings s, AdminViewPacket v, String key, @Nullable PrefixImage img) {
        text(g, Tr.t("files_in_folder"), px, fy, TQ, 0.6f);
        int ly = fy + 8, lh = 2 + FILE_ROWS * FILE_ROW;
        inset(g, px, ly, pw, lh);
        List<AdminViewPacket.FileRow> files = v.files();
        fileScroll = Mth.clamp(fileScroll, 0, Math.max(0, files.size() - FILE_ROWS));
        for (int i = 0; i < FILE_ROWS && i + fileScroll < files.size(); i++) {
            AdminViewPacket.FileRow f = files.get(i + fileScroll);
            int ry = ly + 1 + i * FILE_ROW;
            boolean usable = f.problem().isEmpty();
            boolean sel = usable && f.name().equals(fileChoice);
            boolean hover = usable && mouseX >= px && mouseX < px + pw && mouseY >= ry && mouseY < ry + FILE_ROW;
            if (sel) {
                g.fill(px + 1, ry, px + pw - 1, ry + FILE_ROW, 0x2219D3B0);
                g.fill(px + 1, ry, px + 3, ry + FILE_ROW, TQ);
            } else if (hover) {
                g.fill(px + 1, ry, px + pw - 1, ry + FILE_ROW, 0x14FFFFFF);
            }
            if (usable) {
                drawComponent(g, PrefixFont.component(f.id(), ""), px + 6, ry + 2, 1f, 64);
                text(g, clip(f.name(), 110, 0.75f), px + 74, ry + 3, sel ? TEXT : MUTED, 0.75f);
                text(g, f.width() + " x " + f.height(), px + 74 + 114, ry + 3, MUTED, 0.5f);
                String tag = "";
                int colour = MUTED;
                if (img != null && img.id().equals(f.id())) {
                    tag = Tr.t("in_use_here");
                    colour = TQ;
                } else {
                    for (Map.Entry<String, PrefixImage> e : s.prefixImages().entrySet()) {
                        if (e.getValue().id().equals(f.id())) {
                            tag = Tr.t("on_rank", PrefixImage.groupOf(e.getKey()));
                            break;
                        }
                    }
                }
                textRight(g, clip(tag, pw - 230, 0.5f), px + pw - 6, ry + 4, colour, 0.5f);
                hits.add(new Hit(px, ry, pw, FILE_ROW, () -> fileChoice = f.name()));
            } else {
                text(g, clip(f.name(), 110, 0.75f), px + 74, ry + 3, BAD, 0.75f);
                textRight(g, clip(f.problem(), pw - 196, 0.5f), px + pw - 6, ry + 4, BAD, 0.5f);
            }
        }
        if (files.isEmpty()) {
            text(g, clip(Tr.t("no_files"), pw - 12, 0.6f), px + 6, ly + 6, MUTED, 0.6f);
        }
        scrollbar(g, px + pw, ly, lh, files.size(), FILE_ROWS, fileScroll);

        int by = ly + lh + 5;
        AdminViewPacket.FileRow picked = null;
        for (AdminViewPacket.FileRow f : files) {
            if (f.name().equals(fileChoice) && f.problem().isEmpty()) {
                picked = f;
            }
        }
        boolean canUse = picked != null && (img == null || !img.id().equals(picked.id()));
        AdminViewPacket.FileRow use = picked;
        button(g, px, by, 70, 13, Tr.t("use_image"), canUse ? TQ : LINE, canUse ? () -> send(AdminActionPacket.of(AdminActionPacket.PREFIX_SET, key, use.name())) : null);
        button(g, px + 73, by, 84, 13, Tr.t("show_text_again"), img != null ? BAD : LINE, img != null ? () -> act(AdminActionPacket.PREFIX_CLEAR, key) : null);
        String hint = picked == null && !files.isEmpty() ? Tr.t("pick_a_file") : Tr.t("saved_for_every_server");
        text(g, clip(hint, pw - 164, 0.6f), px + 164, by + 3, MUTED, 0.6f);
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
        text(g, Tr.t("prefixes_in_chat"), px + 384, py + 28, MUTED, 0.6f);
        chip(g, px + 384, py + 35, 30, 13, Tr.t("on"), s.showPrefixes(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.OPTION, "showprefixes", "true")));
        chip(g, px + 417, py + 35, 30, 13, Tr.t("off"), !s.showPrefixes(), TQ, 0.6f, () -> send(AdminActionPacket.of(AdminActionPacket.OPTION, "showprefixes", "false")));

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
        if (tab == T_PREFIXES) {
            if (mx < x + PFX_PANE_X) {
                prefixScroll -= step;
            } else {
                fileScroll -= step;
            }
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
        if (tab == T_PLAYERS && mx >= x + PANE_X) {
            if (my >= countsTop) {
                countScroll -= step;
            } else {
                ladderLineScroll -= step;
            }
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
