package io.github.lumi.minecraft.world;

import net.minecraft.core.BlockPos;

/** Mixin access for removing one exact vanilla scheduled tick. */
public interface OwnedTickAccess<T> {
    void lumi$remove(BlockPos position, T type);
}
