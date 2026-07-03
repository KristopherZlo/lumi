package io.github.luma.minecraft.capture;

/**
 * Mixin access for avoiding entity snapshots before vanilla construction ends.
 */
public interface EntityConstructionStateAccess {

    boolean luma$baseEntityConstructed();
}
