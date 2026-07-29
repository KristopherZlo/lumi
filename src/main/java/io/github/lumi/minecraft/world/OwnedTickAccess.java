package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.SectionKey;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Mixin access for removing invalidated vanilla scheduled ticks. */
public interface OwnedTickAccess<T> {
    void lumi$remove(BlockPos position, T type);

    void lumi$removeSections(Set<SectionKey> sections);
}
