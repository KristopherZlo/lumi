package io.github.lumi.minecraft.runtime;

import net.minecraft.server.level.ServerLevel;

/** Implemented by the EntityStorage mixin so the manager hook can find its dimension runtime. */
public interface EntityStorageLevelAccess {
    ServerLevel lumi$level();
}
