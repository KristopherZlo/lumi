package io.github.lumi.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.OptionalLong;

/** Measures the same draw with native elapsed queries or timestamp pools. */
final class LumiGpuTimer implements AutoCloseable {
    //? if >=26.2 {
    /*private final com.mojang.blaze3d.systems.GpuQueryPool query;
    *///?} else {
    private final com.mojang.blaze3d.systems.GpuQuery query;
    //?}

    LumiGpuTimer() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        //? if >=26.2 {
        /*query = RenderSystem.getDevice().createTimestampQueryPool(2);
        encoder.writeTimestamp(query, 0);
        *///?} else {
        query = encoder.timerQueryBegin();
        //?}
    }

    void finish() {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        //? if >=26.2 {
        /*encoder.writeTimestamp(query, 1);
        *///?} else {
        encoder.timerQueryEnd(query);
        //?}
    }

    OptionalLong getValue() {
        //? if >=26.2 {
        /*var values = query.getValues(0, 2);
        if (values[0].isEmpty() || values[1].isEmpty()) return OptionalLong.empty();
        long ticks = values[1].getAsLong() - values[0].getAsLong();
        return OptionalLong.of(Math.round(ticks * (double) RenderSystem.getDevice().getDeviceInfo().timestampPeriod()));
        *///?} else {
        return query.getValue();
        //?}
    }

    @Override public void close() { query.close(); }
}
