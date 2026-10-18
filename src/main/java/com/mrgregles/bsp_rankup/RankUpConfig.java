package com.mrgregles.bsp_rankup;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The server config: only what must exist before the shared storage is up (owner decision D19), the MySQL and
 * Redis connections and this server's name. Everything else (the ladder, signals, rewards, options) lives in
 * RankUp's own storage and is edited in game.
 */
public final class RankUpConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> STORAGE_MODE, STORAGE_HOST, STORAGE_DATABASE, STORAGE_USER, STORAGE_PASSWORD, STORAGE_TABLE_PREFIX, STORAGE_SERVER_ID;
    public static final ForgeConfigSpec.IntValue STORAGE_PORT, STORAGE_POLL_SECONDS;
    public static final ForgeConfigSpec.BooleanValue REDIS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST, REDIS_USERNAME, REDIS_PASSWORD, REDIS_CHANNEL;
    public static final ForgeConfigSpec.IntValue REDIS_PORT, REDIS_SAFETY_POLL_SECONDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Shared storage for several servers (optional). Counts, bonus points, ranks and the settings live in MySQL",
                "under RankUp's own tables, so playtime and the rest add up across the network. No MySQL driver is bundled:",
                "BSP Core's full jar provides one on Forge servers; Mohist ships its own.").push("storage");
        STORAGE_MODE = b.comment("none or mysql").define("mode", "none");
        STORAGE_HOST = b.define("host", "localhost");
        STORAGE_PORT = b.defineInRange("port", 3306, 1, 65535);
        STORAGE_DATABASE = b.define("database", "bsp");
        STORAGE_USER = b.define("user", "bsp");
        STORAGE_PASSWORD = b.comment("Plain text, as BSP Core's; keep the config file private.").define("password", "");
        STORAGE_TABLE_PREFIX = b.define("tablePrefix", "bsp_ru_");
        STORAGE_SERVER_ID = b.comment("This server's name in notices to the other servers. Empty: BSP Region Guard's serverId when it is installed, else server-1.").define("serverId", "");
        STORAGE_POLL_SECONDS = b.comment("How often notices from other servers are read from the table when Redis is off.").defineInRange("pollSeconds", 5, 1, 300);
        b.comment("Redis carries notices between servers within milliseconds (needs mode = mysql): settings changes and",
                "network-wide rank-up announcements. Every notice is also written to the MySQL table, so nothing is lost",
                "while Redis is down. RankUp has its own small Redis client; nothing is bundled. Keep Redis on the local network.").push("redis");
        REDIS_ENABLED = b.define("enabled", false);
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_USERNAME = b.comment("Leave empty unless Redis uses ACL users; the password alone is the usual requirepass.").define("username", "");
        REDIS_PASSWORD = b.comment("Plain text; keep the config file private.").define("password", "");
        REDIS_CHANNEL = b.comment("Servers that share notices use the same channel. RankUp adds the prefix bsp_ru: to it.").define("channel", "messages");
        REDIS_SAFETY_POLL_SECONDS = b.comment("How often the MySQL notice table is still read while Redis is connected.").defineInRange("safetyPollSeconds", 30, 5, 600);
        b.pop();
        b.pop();
        SPEC = b.build();
    }

    private RankUpConfig() {
    }

    /** The value, or the fallback while the config is not loaded yet. */
    public static <T> T getOr(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException | NullPointerException e) {
            return fallback;
        }
    }
}
