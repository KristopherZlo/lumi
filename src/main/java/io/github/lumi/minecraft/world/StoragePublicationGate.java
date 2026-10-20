package io.github.lumi.minecraft.world;

import java.util.function.BooleanSupplier;

/** Binds a native storage writer to its dimension's speculative replay hold. */
public interface StoragePublicationGate {
    void lumi$bindPublication(BooleanSupplier allowed);
}
