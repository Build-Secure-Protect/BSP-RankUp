package com.mrgregles.bsp_rankup.progress;

import java.util.Locale;

/**
 * The things RankUp counts for a player. Counts are raw (minutes, kills, totems); the points each one is worth
 * come from the settings (owner decisions D3, D5), so a changed weight never loses data. {@link #defaultOn},
 * {@link #defaultPoints} and {@link #defaultPer} seed the settings: a signal is worth {@code defaultPoints} for
 * every {@code defaultPer} units counted.
 *
 * <p>New entries go at the end; the key is what the ledger and the storage use.
 */
public enum Signal {
    /** Minutes online on any server of the network (D14: RankUp's own counter, not the vanilla stat). */
    PLAYTIME("playtime", "minute", true, 1, 10, Source.RANKUP),
    PLAYER_KILLS("player_kills", "kill", true, 25, 1, Source.RANKUP),
    MOB_KILLS("mob_kills", "kill", true, 1, 1, Source.RANKUP),
    DEATHS("deaths", "death", false, 0, 1, Source.RANKUP),
    /** Successful steals by this player (BSP-Core {@code TotemEvent.StealEnded}, success). */
    TOTEMS_STOLEN("totems_stolen", "totem", true, 150, 1, Source.BSP_CORE),
    /** Failed steals on this player's totem while they were online on that server (D4). */
    TOTEMS_PROTECTED("totems_protected", "totem", true, 60, 1, Source.BSP_CORE),
    /** Successful steals of this player's totem. */
    TOTEMS_LOST("totems_lost", "totem", false, 0, 1, Source.BSP_CORE),
    TOTEMS_PLACED("totems_placed", "totem", false, 0, 1, Source.BSP_CORE),
    /** Seasons that ended while the player held progress. Counted from RU-8 on. */
    SEASONS_SURVIVED("seasons_survived", "season", false, 0, 1, Source.BSP_CORE),
    /** BSP-Core's points (D27, D48; BSP-Core 1.4.5's {@code BspScores}): each season's peak, added up. See {@code signal/ScoreSignal}. */
    SCORE("score", "point", false, 1, 100, Source.BSP_CORE),
    /** Shatter Pieces earned (needs a bank event from BSP-Core). */
    COINS_EARNED("coins_earned", "piece", false, 1, 1000, Source.BSP_CORE),
    /** Land claims bought (Region Guard {@code ClaimPurchasedEvent}). */
    CLAIMS_BOUGHT("claims_bought", "claim", false, 0, 1, Source.REGION_GUARD);

    /** Which mod the raw events come from. */
    public enum Source { RANKUP, BSP_CORE, REGION_GUARD }

    private final String key;
    private final String unit;
    private final boolean defaultOn;
    private final int defaultPoints;
    private final int defaultPer;
    private final Source source;

    Signal(String key, String unit, boolean defaultOn, int defaultPoints, int defaultPer, Source source) {
        this.key = key;
        this.unit = unit;
        this.defaultOn = defaultOn;
        this.defaultPoints = defaultPoints;
        this.defaultPer = defaultPer;
        this.source = source;
    }

    public String key() {
        return key;
    }

    /** What one count is: "minute", "kill", "totem" ... */
    public String unit() {
        return unit;
    }

    public boolean defaultOn() {
        return defaultOn;
    }

    public int defaultPoints() {
        return defaultPoints;
    }

    public int defaultPer() {
        return defaultPer;
    }

    public Source source() {
        return source;
    }

    /** The signal with this key, or null. */
    public static Signal byKey(String key) {
        for (Signal s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }

    public String displayName() {
        return key.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
