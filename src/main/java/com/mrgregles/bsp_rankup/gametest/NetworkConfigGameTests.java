package com.mrgregles.bsp_rankup.gametest;

import com.mrgregles.bsp_rankup.BSPRankUp;
import com.mrgregles.bsp_rankup.RankUpConfig;
import com.mrgregles.bsp_rankup.RankUpNetworkConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The database and Redis connection out of the synced server config (owner decisions D123 to D128): a value still set
 * in {@code bsp_rankup-server.toml} moves once into {@code config/bsp_rankup-network.toml}, a value already there wins,
 * the old key goes back to its default but stays in the spec, and the log line names keys, never a password.
 */
@GameTestHolder(BSPRankUp.MODID)
@PrefixGameTestTemplate(false)
public class NetworkConfigGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY, batch = "network-config")
    public void connection_settings_move_out_of_the_server_config_once(GameTestHelper h) {
        List<Runnable> restore = new ArrayList<>();
        for (ForgeConfigSpec.ConfigValue<?> v : List.of(RankUpConfig.STORAGE_HOST, RankUpConfig.STORAGE_PORT, RankUpConfig.STORAGE_DATABASE, RankUpConfig.STORAGE_USER,
                RankUpConfig.STORAGE_PASSWORD, RankUpConfig.STORAGE_TABLE_PREFIX, RankUpConfig.REDIS_HOST, RankUpConfig.REDIS_PORT, RankUpConfig.REDIS_USERNAME,
                RankUpConfig.REDIS_PASSWORD, RankUpNetworkConfig.STORAGE_HOST, RankUpNetworkConfig.STORAGE_PORT, RankUpNetworkConfig.STORAGE_DATABASE,
                RankUpNetworkConfig.STORAGE_USER, RankUpNetworkConfig.STORAGE_PASSWORD, RankUpNetworkConfig.STORAGE_TABLE_PREFIX, RankUpNetworkConfig.REDIS_HOST,
                RankUpNetworkConfig.REDIS_PORT, RankUpNetworkConfig.REDIS_USERNAME, RankUpNetworkConfig.REDIS_PASSWORD)) {
            restore.add(keep(v));
        }
        try {
            // both files as they start: every setting at its default (the run's own files may hold anything)
            for (ForgeConfigSpec.ConfigValue<?> v : List.of(RankUpConfig.STORAGE_HOST, RankUpConfig.STORAGE_PORT, RankUpConfig.STORAGE_PASSWORD, RankUpConfig.REDIS_PASSWORD,
                    RankUpNetworkConfig.STORAGE_HOST, RankUpNetworkConfig.STORAGE_PORT, RankUpNetworkConfig.STORAGE_DATABASE, RankUpNetworkConfig.STORAGE_USER,
                    RankUpNetworkConfig.STORAGE_PASSWORD, RankUpNetworkConfig.STORAGE_TABLE_PREFIX, RankUpNetworkConfig.REDIS_HOST, RankUpNetworkConfig.REDIS_PORT,
                    RankUpNetworkConfig.REDIS_USERNAME, RankUpNetworkConfig.REDIS_PASSWORD)) {
                toDefault(v);
            }
            // an older server config with its connection in it, and one value already in the network file
            RankUpConfig.STORAGE_HOST.set("db.example.net");
            RankUpConfig.STORAGE_PORT.set(3307);
            RankUpConfig.STORAGE_PASSWORD.set("old-db-secret");
            RankUpConfig.REDIS_PASSWORD.set("old-redis-secret");
            RankUpNetworkConfig.REDIS_PASSWORD.set("already-here");

            RankUpNetworkConfig.MoveResult moved = RankUpNetworkConfig.moveFromServerConfig();
            h.assertTrue(moved.copied().equals(List.of("storage.host", "storage.port", "storage.password")), "what was set is copied: " + moved.copied());
            h.assertTrue(moved.keptHere().equals(List.of("storage.redis.password")), "a value already in the network file wins: " + moved.keptHere());
            h.assertTrue(RankUpNetworkConfig.STORAGE_HOST.get().equals("db.example.net") && RankUpNetworkConfig.STORAGE_PORT.get() == 3307
                    && RankUpNetworkConfig.STORAGE_PASSWORD.get().equals("old-db-secret"), "the connection now comes from the network file");
            h.assertTrue(RankUpNetworkConfig.REDIS_PASSWORD.get().equals("already-here"), "the network file's own value stays");
            h.assertTrue(RankUpConfig.STORAGE_HOST.get().equals("localhost") && RankUpConfig.STORAGE_PORT.get() == 3306
                    && RankUpConfig.STORAGE_PASSWORD.get().isEmpty() && RankUpConfig.REDIS_PASSWORD.get().isEmpty(),
                    "the server config, sent to every player, holds no password now (D126)");

            String line = moved.message();
            h.assertFalse(line.contains("old-db-secret") || line.contains("old-redis-secret") || line.contains("already-here") || line.contains("db.example.net"),
                    "the log line never shows a value (D128): " + line);
            h.assertTrue(line.contains("storage.password") && line.contains(RankUpNetworkConfig.FILE), "it names what moved and where: " + line);

            h.assertTrue(RankUpNetworkConfig.moveFromServerConfig().isEmpty(), "a second start moves nothing");
            h.assertTrue(RankUpConfig.SPEC.getSpec().contains("storage.password") && RankUpConfig.SPEC.getSpec().contains("storage.redis.password"),
                    "the old keys stay in the server spec as placeholders, so Forge doesn't delete a value before it moves");
            h.assertFalse(RankUpNetworkConfig.SPEC.getSpec().contains("mode") || RankUpNetworkConfig.SPEC.getSpec().contains("storage.mode"),
                    "storage.mode and the other harmless settings stay in the server config");
        } finally {
            restore.forEach(Runnable::run);
            RankUpConfig.SPEC.save();
            RankUpNetworkConfig.SPEC.save();
        }
        h.succeed();
    }

    private static <T> void toDefault(ForgeConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }

    /** Puts a value back as it was. */
    private static <T> Runnable keep(ForgeConfigSpec.ConfigValue<T> value) {
        T was = value.get();
        return () -> value.set(was);
    }
}
