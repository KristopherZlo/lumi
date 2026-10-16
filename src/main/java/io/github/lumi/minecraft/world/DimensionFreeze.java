package io.github.lumi.minecraft.world;

/** Stops simulation and new mutation in exactly one server dimension. */
public interface DimensionFreeze {
    Lease acquire();

    default boolean isReleaseReady() { return true; }

    interface Lease {
        void release();
    }
}
