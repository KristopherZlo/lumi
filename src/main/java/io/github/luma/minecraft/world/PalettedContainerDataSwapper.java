package io.github.luma.minecraft.world;

import io.github.luma.LumaMod;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.chunk.PalettedContainer;

final class PalettedContainerDataSwapper {

    private static final AtomicBoolean LOGGED_UNAVAILABLE = new AtomicBoolean(false);
    private static final VarHandle DATA_HANDLE = resolveDataHandle();

    boolean available() {
        return DATA_HANDLE != null;
    }

    boolean swapData(PalettedContainer<?> target, PalettedContainer<?> replacement) {
        if (target == null || replacement == null || DATA_HANDLE == null) {
            this.logUnavailableOnce();
            return false;
        }

        Object replacementData = DATA_HANDLE.getVolatile(replacement);
        if (replacementData == null) {
            return false;
        }
        DATA_HANDLE.setVolatile(target, replacementData);
        return true;
    }

    private void logUnavailableOnce() {
        if (LOGGED_UNAVAILABLE.compareAndSet(false, true)) {
            LumaMod.LOGGER.warn("Section container rewrite is unavailable; falling back to direct section writes");
        }
    }

    private static VarHandle resolveDataHandle() {
        try {
            Class<?> dataClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup());
            return lookup.findVarHandle(PalettedContainer.class, "data", dataClass);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }
}
