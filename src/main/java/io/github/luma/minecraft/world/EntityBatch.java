package io.github.luma.minecraft.world;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * Chunk-scoped entity changes prepared off-thread.
 *
 * <p>Entity diff capture is introduced incrementally, so the first pass keeps
 * the structure but may carry empty lists for save/restore/recovery.
 */
public record EntityBatch(
        List<CompoundTag> entitiesToSpawn,
        List<String> entityIdsToRemove
) {

    public static EntityBatch empty() {
        return new EntityBatch(List.of(), List.of());
    }

    public boolean isEmpty() {
        return this.entitiesToSpawn.isEmpty() && this.entityIdsToRemove.isEmpty();
    }
}
