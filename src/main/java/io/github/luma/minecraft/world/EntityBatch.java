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
        List<String> entityIdsToRemove,
        List<CompoundTag> entitiesToUpdate
) {

    public EntityBatch {
        entitiesToSpawn = copyTags(entitiesToSpawn);
        entityIdsToRemove = entityIdsToRemove == null ? List.of() : List.copyOf(entityIdsToRemove);
        entitiesToUpdate = copyTags(entitiesToUpdate);
    }

    public EntityBatch(List<CompoundTag> entitiesToSpawn, List<String> entityIdsToRemove) {
        this(entitiesToSpawn, entityIdsToRemove, List.of());
    }

    public static EntityBatch empty() {
        return new EntityBatch(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return this.entitiesToSpawn.isEmpty()
                && this.entityIdsToRemove.isEmpty()
                && this.entitiesToUpdate.isEmpty();
    }

    private static List<CompoundTag> copyTags(List<CompoundTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .map(tag -> tag == null ? new CompoundTag() : tag.copy())
                .toList();
    }
}
