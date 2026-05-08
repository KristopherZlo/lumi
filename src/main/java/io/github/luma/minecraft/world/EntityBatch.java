package io.github.luma.minecraft.world;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * Chunk-scoped entity changes prepared off-thread.
 *
 * <p>Entity diffs carry full persistent NBT payloads for non-player spawn,
 * removal, and update operations.
 */
public record EntityBatch(
        List<CompoundTag> entitiesToSpawn,
        List<String> entityIdsToRemove,
        List<CompoundTag> entitiesToUpdate,
        boolean replacePlacedEntities
) {

    public EntityBatch {
        entitiesToSpawn = copyTags(entitiesToSpawn);
        entityIdsToRemove = entityIdsToRemove == null ? List.of() : List.copyOf(entityIdsToRemove);
        entitiesToUpdate = copyTags(entitiesToUpdate);
    }

    public EntityBatch(List<CompoundTag> entitiesToSpawn, List<String> entityIdsToRemove) {
        this(entitiesToSpawn, entityIdsToRemove, List.of(), false);
    }

    public EntityBatch(
            List<CompoundTag> entitiesToSpawn,
            List<String> entityIdsToRemove,
            List<CompoundTag> entitiesToUpdate
    ) {
        this(entitiesToSpawn, entityIdsToRemove, entitiesToUpdate, false);
    }

    public static EntityBatch empty() {
        return new EntityBatch(List.of(), List.of(), List.of(), false);
    }

    public static EntityBatch replacePlacedEntities(List<CompoundTag> entitiesToUpdate) {
        return new EntityBatch(List.of(), List.of(), entitiesToUpdate, true);
    }

    public boolean isEmpty() {
        return this.entitiesToSpawn.isEmpty()
                && this.entityIdsToRemove.isEmpty()
                && this.entitiesToUpdate.isEmpty()
                && !this.replacePlacedEntities;
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
