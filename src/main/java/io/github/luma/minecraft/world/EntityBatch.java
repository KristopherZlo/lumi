package io.github.luma.minecraft.world;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        boolean replaceEntities,
        Set<String> excludedEntityTypes
) {

    public EntityBatch {
        entitiesToSpawn = copyTags(entitiesToSpawn);
        entityIdsToRemove = entityIdsToRemove == null ? List.of() : List.copyOf(entityIdsToRemove);
        entitiesToUpdate = copyTags(entitiesToUpdate);
        excludedEntityTypes = copyTypes(excludedEntityTypes);
    }

    public EntityBatch(List<CompoundTag> entitiesToSpawn, List<String> entityIdsToRemove) {
        this(entitiesToSpawn, entityIdsToRemove, List.of(), false, Set.of());
    }

    public EntityBatch(
            List<CompoundTag> entitiesToSpawn,
            List<String> entityIdsToRemove,
            List<CompoundTag> entitiesToUpdate
    ) {
        this(entitiesToSpawn, entityIdsToRemove, entitiesToUpdate, false, Set.of());
    }

    public EntityBatch(
            List<CompoundTag> entitiesToSpawn,
            List<String> entityIdsToRemove,
            List<CompoundTag> entitiesToUpdate,
            boolean replaceEntities
    ) {
        this(entitiesToSpawn, entityIdsToRemove, entitiesToUpdate, replaceEntities, Set.of());
    }

    public static EntityBatch empty() {
        return new EntityBatch(List.of(), List.of(), List.of(), false, Set.of());
    }

    public static EntityBatch replaceEntities(List<CompoundTag> entitiesToUpdate) {
        return replaceEntities(entitiesToUpdate, Set.of());
    }

    public static EntityBatch replaceEntities(List<CompoundTag> entitiesToUpdate, Collection<String> excludedEntityTypes) {
        return new EntityBatch(List.of(), List.of(), entitiesToUpdate, true, copyTypes(excludedEntityTypes));
    }

    public boolean isEmpty() {
        return this.entitiesToSpawn.isEmpty()
                && this.entityIdsToRemove.isEmpty()
                && this.entitiesToUpdate.isEmpty()
                && !this.replaceEntities;
    }

    private static List<CompoundTag> copyTags(List<CompoundTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .map(tag -> tag == null ? new CompoundTag() : tag.copy())
                .toList();
    }

    private static Set<String> copyTypes(Collection<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String entityType : entityTypes) {
            if (entityType != null && !entityType.isBlank()) {
                normalized.add(entityType);
            }
        }
        return Set.copyOf(normalized);
    }
}
