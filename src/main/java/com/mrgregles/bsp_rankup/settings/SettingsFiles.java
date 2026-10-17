package com.mrgregles.bsp_rankup.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import com.mojang.serialization.JsonOps;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Settings as JSON files for backups and moving between servers (owner decision D19): {@code config/bsp_rankup/<name>.json}.
 * The file is the settings' NBT written as JSON, so it holds exactly what the ledger holds.
 */
public final class SettingsFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private SettingsFiles() {
    }

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve("bsp_rankup");
    }

    /** The file a name stands for; letters, digits, dashes and underscores only. */
    public static Path file(String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("a file name is letters, digits, - and _");
        }
        return directory().resolve(name.toLowerCase(Locale.ROOT) + ".json");
    }

    public static Path export(Settings settings, String name) throws IOException {
        Path path = file(name);
        Files.createDirectories(path.getParent());
        JsonElement json = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, settings.save());
        Files.writeString(path, GSON.toJson(json) + "\n");
        return path;
    }

    public static Settings read(String name) throws IOException {
        Path path = file(name);
        JsonElement json = JsonParser.parseString(Files.readString(path));
        Tag tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
        if (!(tag instanceof CompoundTag compound)) {
            throw new IOException(path.getFileName() + " does not hold settings");
        }
        return Settings.load(compound);
    }
}
