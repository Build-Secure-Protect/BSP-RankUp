package com.mrgregles.bsp_rankup.settings;

import com.mrgregles.bsp_rankup.progress.Signal;
import net.minecraft.nbt.CompoundTag;

/** How much a signal is worth: {@code points} for every {@code per} units counted, or nothing when off. */
public record SignalSetting(boolean on, long points, long per) {
    public static SignalSetting defaults(Signal signal) {
        return new SignalSetting(signal.defaultOn(), signal.defaultPoints(), signal.defaultPer());
    }

    /** The points a count is worth under this setting. */
    public long pointsFor(long count) {
        if (!on || per <= 0 || points == 0 || count <= 0) {
            return 0;
        }
        return (count / per) * points;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("On", on);
        tag.putLong("Points", points);
        tag.putLong("Per", per);
        return tag;
    }

    public static SignalSetting load(CompoundTag tag, Signal signal) {
        if (!tag.contains("Per")) {
            return defaults(signal);
        }
        return new SignalSetting(tag.getBoolean("On"), tag.getLong("Points"), Math.max(1, tag.getLong("Per")));
    }
}
