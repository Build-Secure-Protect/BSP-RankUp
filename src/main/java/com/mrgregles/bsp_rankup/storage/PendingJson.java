package com.mrgregles.bsp_rankup.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mrgregles.bsp_rankup.settings.Reward;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Waiting rewards as JSON text for the players table: the rewards' NBT list converted. */
public final class PendingJson {
    private static final Gson GSON = new Gson();

    private PendingJson() {
    }

    @Nullable
    public static String toJson(List<Reward> rewards) {
        if (rewards.isEmpty()) {
            return null;
        }
        ListTag list = new ListTag();
        for (Reward r : rewards) {
            list.add(r.save());
        }
        return GSON.toJson(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, list));
    }

    public static List<Reward> fromJson(@Nullable String text) {
        List<Reward> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        try {
            JsonElement json = JsonParser.parseString(text);
            Tag tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, json);
            if (tag instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    out.add(Reward.load(list.getCompound(i)));
                }
            }
        } catch (RuntimeException e) {
            // a damaged row loses its waiting rewards rather than the login
        }
        return out;
    }
}
