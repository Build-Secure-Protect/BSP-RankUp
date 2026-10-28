package com.mrgregles.bsp_rankup;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The connection to the shared MySQL and Redis (owner decisions D123 to D128): a COMMON config of its own,
 * {@code config/bsp_rankup-network.toml}, because Forge sends every SERVER config file to each player at login and the
 * passwords must not travel with it. Read when the shared storage starts; nothing here is synced to clients.
 *
 * <p>Older RankUp kept these keys in {@code serverconfig/bsp_rankup-server.toml}. They stay there as "moved"
 * placeholders (Forge deletes keys a spec does not have before anything could read them), and {@link #moveFromServerConfig}
 * carries any value set there into this file once, as the server is about to start.
 */
public final class RankUpNetworkConfig {
    /** The file name, under {@code config/}. */
    public static final String FILE = "bsp_rankup-network.toml";
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> STORAGE_HOST, STORAGE_DATABASE, STORAGE_USER, STORAGE_PASSWORD, STORAGE_TABLE_PREFIX;
    public static final ForgeConfigSpec.IntValue STORAGE_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST, REDIS_USERNAME, REDIS_PASSWORD;
    public static final ForgeConfigSpec.IntValue REDIS_PORT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Where RankUp's shared MySQL and Redis are. Kept out of serverconfig/bsp_rankup-server.toml, which Forge sends to",
                "every player who joins. Used only when storage.mode = mysql (and redis.enabled = true) in that server config.",
                "Never ship this file in a client pack.").push("storage");
        STORAGE_HOST = b.define("host", "localhost");
        STORAGE_PORT = b.defineInRange("port", 3306, 1, 65535);
        STORAGE_DATABASE = b.define("database", "bsp");
        STORAGE_USER = b.define("user", "bsp");
        STORAGE_PASSWORD = b.comment("Plain text; keep this file private.").define("password", "");
        STORAGE_TABLE_PREFIX = b.define("tablePrefix", "bsp_ru_");
        b.pop();
        b.push("redis");
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_USERNAME = b.comment("Leave empty unless Redis uses ACL users; the password alone is the usual requirepass.").define("username", "");
        REDIS_PASSWORD = b.comment("Plain text; keep this file private.").define("password", "");
        b.pop();
        SPEC = b.build();
    }

    private RankUpNetworkConfig() {
    }

    /** One setting that moved: its key, the placeholder in the server config, the value in this file. */
    private record Moved<T>(String key, ForgeConfigSpec.ConfigValue<T> old, ForgeConfigSpec.ConfigValue<T> now) {
    }

    private static List<Moved<?>> moved() {
        return List.of(
                new Moved<>("storage.host", RankUpConfig.STORAGE_HOST, STORAGE_HOST),
                new Moved<>("storage.port", RankUpConfig.STORAGE_PORT, STORAGE_PORT),
                new Moved<>("storage.database", RankUpConfig.STORAGE_DATABASE, STORAGE_DATABASE),
                new Moved<>("storage.user", RankUpConfig.STORAGE_USER, STORAGE_USER),
                new Moved<>("storage.password", RankUpConfig.STORAGE_PASSWORD, STORAGE_PASSWORD),
                new Moved<>("storage.tablePrefix", RankUpConfig.STORAGE_TABLE_PREFIX, STORAGE_TABLE_PREFIX),
                new Moved<>("storage.redis.host", RankUpConfig.REDIS_HOST, REDIS_HOST),
                new Moved<>("storage.redis.port", RankUpConfig.REDIS_PORT, REDIS_PORT),
                new Moved<>("storage.redis.username", RankUpConfig.REDIS_USERNAME, REDIS_USERNAME),
                new Moved<>("storage.redis.password", RankUpConfig.REDIS_PASSWORD, REDIS_PASSWORD));
    }

    /**
     * What a move did: the keys copied into this file, and the keys whose value this file already had (that one
     * kept, the old one dropped). The message names keys only, never a value.
     */
    public record MoveResult(List<String> copied, List<String> keptHere) {
        public boolean isEmpty() {
            return copied.isEmpty() && keptHere.isEmpty();
        }

        public String message() {
            StringBuilder sb = new StringBuilder("RankUp moved its database and Redis settings out of serverconfig/bsp_rankup-server.toml ")
                    .append("(Forge sends that file to every player) into config/").append(FILE).append(": ");
            sb.append(copied.isEmpty() ? "nothing copied" : "copied " + String.join(", ", copied));
            if (!keptHere.isEmpty()) {
                sb.append("; kept the value already in ").append(FILE).append(" for ").append(String.join(", ", keptHere));
            }
            return sb.append(". Change the database and Redis passwords: the old ones may have reached players.").toString();
        }
    }

    /**
     * The one-time move (D123): each setting still set in the server config (not its default) is copied here, unless
     * this file already has a value of its own, which wins; the server config's copy goes back to its default. Both
     * files are saved when anything moved. Run as the server is about to start, when both are loaded and nobody has
     * joined.
     */
    public static MoveResult moveFromServerConfig() {
        List<String> copied = new ArrayList<>(), keptHere = new ArrayList<>();
        for (Moved<?> m : moved()) {
            move(m, copied, keptHere);
        }
        MoveResult result = new MoveResult(List.copyOf(copied), List.copyOf(keptHere));
        if (!result.isEmpty()) {
            SPEC.save();
            RankUpConfig.SPEC.save();
        }
        return result;
    }

    private static <T> void move(Moved<T> m, List<String> copied, List<String> keptHere) {
        T old = m.old().get();
        if (old.equals(m.old().getDefault())) {
            return;
        }
        if (m.now().get().equals(m.now().getDefault())) {
            m.now().set(old);
            copied.add(m.key());
        } else {
            keptHere.add(m.key());
        }
        m.old().set(m.old().getDefault());
    }

    /** Moves the settings and says so in the log, by key name only. */
    public static void onServerAboutToStart() {
        try {
            MoveResult result = moveFromServerConfig();
            if (!result.isEmpty()) {
                BSPRankUp.LOGGER.warn(result.message());
            }
        } catch (RuntimeException e) {
            BSPRankUp.LOGGER.warn("RankUp could not move its database and Redis settings to config/{}: {}", FILE, e.toString());
        }
    }
}
