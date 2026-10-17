package com.mrgregles.bsp_rankup.settings;

import com.mrgregles.bsp_rankup.signal.KillCooldowns;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The current {@link Settings} on this server, saved with the overworld ({@code bsp_rankup_settings}). Every
 * change goes through {@link #update}, which tells the listeners (the ladder engine re-checks everyone online;
 * the shared storage writes through, RU-5). On a network the shared copy is the truth and this is the local one.
 */
public final class SettingsLedger extends SavedData {
    public static final String NAME = "bsp_rankup_settings";

    private static final List<Consumer<Settings>> LISTENERS = new CopyOnWriteArrayList<>();

    private Settings settings = Settings.defaults();

    public SettingsLedger() {
    }

    public static SettingsLedger get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("no overworld");
        }
        SettingsLedger ledger = overworld.getDataStorage().computeIfAbsent(SettingsLedger::load, SettingsLedger::new, NAME);
        KillCooldowns.setCooldownMs(ledger.settings.killCooldownMinutes() * 60_000L);
        return ledger;
    }

    public static Settings settings(MinecraftServer server) {
        return get(server).settings();
    }

    public Settings settings() {
        return settings;
    }

    /** Replaces the settings with the result of the edit and tells the listeners. */
    public void update(UnaryOperator<Settings.Builder> edit) {
        set(edit.apply(settings.toBuilder()).build());
    }

    public void set(Settings next) {
        settings = next;
        KillCooldowns.setCooldownMs(next.killCooldownMinutes() * 60_000L);
        setDirty();
        for (Consumer<Settings> l : LISTENERS) {
            l.accept(next);
        }
    }

    public static void onChange(Consumer<Settings> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<Settings> listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("Settings", settings.save());
        return tag;
    }

    public static SettingsLedger load(CompoundTag tag) {
        SettingsLedger ledger = new SettingsLedger();
        if (tag.contains("Settings")) {
            ledger.settings = Settings.load(tag.getCompound("Settings"));
        }
        return ledger;
    }
}
