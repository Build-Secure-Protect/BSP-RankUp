package com.mrgregles.bsp_rankup.admin;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.progress.ProgressLedger;
import com.mrgregles.bsp_rankup.util.Players;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * RESET EVERYONE (owner decision D25): an admin asks for a code, the screen shows it, the admin types it back within
 * a minute, and every player's progress on this server is forgotten (on a network: every row this server knows;
 * the other servers hear of each reset). The code lives per admin, in memory.
 */
public final class ResetAll {
    public static final long CODE_MS = 60_000L;
    private static final java.util.Map<UUID, String> CODES = new java.util.HashMap<>();
    private static final java.util.Map<UUID, Long> ISSUED = new java.util.HashMap<>();
    private static final Random RANDOM = new Random();

    private ResetAll() {
    }

    /** A fresh code for the admin, four letters and digits. */
    public static String request(UUID admin) {
        String code = Integer.toString(1000 + RANDOM.nextInt(9000));
        CODES.put(admin, code);
        ISSUED.put(admin, System.currentTimeMillis());
        return code;
    }

    /** The code waiting for this admin, or empty when none or when its minute is over. */
    public static String pending(UUID admin) {
        Long at = ISSUED.get(admin);
        if (at == null || System.currentTimeMillis() - at > CODE_MS) {
            CODES.remove(admin);
            ISSUED.remove(admin);
            return "";
        }
        return CODES.getOrDefault(admin, "");
    }

    public static int secondsLeft(UUID admin) {
        Long at = ISSUED.get(admin);
        return at == null ? 0 : (int) Math.max(0, (CODE_MS - (System.currentTimeMillis() - at)) / 1000);
    }

    public static Reply confirm(MinecraftServer server, UUID admin, String code) {
        String expected = pending(admin);
        if (expected.isEmpty()) {
            return Reply.no("No reset is waiting; ask for a code first");
        }
        if (!expected.equals(code.trim().toUpperCase(Locale.ROOT))) {
            return Reply.no("That is not the code");
        }
        CODES.remove(admin);
        ISSUED.remove(admin);
        ProgressLedger ledger = ProgressLedger.get(server);
        List<UUID> players = new ArrayList<>(ledger.all().keySet());
        for (UUID p : players) {
            ledger.reset(p);
        }
        BSPRankUp.LOGGER.info("{} reset every player's RankUp progress ({} players)", Players.name(server, admin), players.size());
        return Reply.ok("Every player starts over: " + players.size() + " players' counts, bonus and ranks forgotten");
    }

    public static void cancel(UUID admin) {
        CODES.remove(admin);
        ISSUED.remove(admin);
    }
}
