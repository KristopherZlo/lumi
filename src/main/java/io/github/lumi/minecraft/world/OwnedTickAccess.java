package io.github.lumi.minecraft.world;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** Mixin access for removing invalidated vanilla scheduled ticks. */
public interface OwnedTickAccess<T> {
    void lumi$remove(BlockPos position, T type);

    void lumi$removeWhere(Predicate<BlockPos> matches);
}
