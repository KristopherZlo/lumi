package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.Optional;
import java.util.Set;

public final class EntityMutationCapturePolicy {

    private static final Set<String> FALLBACK_INSPECTED_ENTITY_TYPES = Set.of(
            "minecraft:armor_stand",
            "minecraft:block_display",
            "minecraft:glow_item_frame",
            "minecraft:interaction",
            "minecraft:item_display",
            "minecraft:item_frame",
            "minecraft:painting",
            "minecraft:text_display"
    );
    private static final Set<String> EXCLUDED_ENTITY_TYPES = Set.of("minecraft:player");
    private final PlacedEntityHistoryPolicy placedEntityHistoryPolicy = new PlacedEntityHistoryPolicy();

    public Optional<StoredEntityChange> capture(
            WorldMutationSource source,
            EntityPayload oldValue,
            EntityPayload newValue
    ) {
        if (!this.shouldCaptureMutation(source, oldValue, newValue)) {
            return Optional.empty();
        }

        String entityId = this.entityId(oldValue, newValue);
        if (entityId.isBlank()) {
            return Optional.empty();
        }

        StoredEntityChange change = new StoredEntityChange(
                entityId,
                this.entityType(oldValue, newValue),
                oldValue,
                newValue
        );
        return change.isNoOp() ? Optional.empty() : Optional.of(change);
    }

    Optional<StoredEntityChange> captureUndoRedo(
            WorldMutationSource source,
            EntityPayload oldValue,
            EntityPayload newValue
    ) {
        String entityType = this.entityType(oldValue, newValue);
        if (!HistoryCaptureManager.shouldCaptureMutation(source)
                || entityType.isBlank()
                || EXCLUDED_ENTITY_TYPES.contains(entityType)) {
            return Optional.empty();
        }
        String entityId = this.entityId(oldValue, newValue);
        if (entityId.isBlank()) {
            return Optional.empty();
        }
        StoredEntityChange change = new StoredEntityChange(entityId, entityType, oldValue, newValue);
        return change.isNoOp() ? Optional.empty() : Optional.of(change);
    }

    boolean shouldInspectMutation(WorldMutationSource source, String entityType) {
        if (source == null || source == WorldMutationSource.RESTORE || source == WorldMutationSource.SYSTEM) {
            return false;
        }
        if (EXCLUDED_ENTITY_TYPES.contains(entityType)) {
            return false;
        }
        if (source == WorldMutationSource.PLAYER
                || source == WorldMutationSource.ENTITY
                || source == WorldMutationSource.EXPLOSIVE
                || source == WorldMutationSource.EXPLOSION
                || source == WorldMutationSource.MOB) {
            return this.placedEntityHistoryPolicy.shouldPersist(entityType);
        }
        if (source == WorldMutationSource.EXTERNAL_TOOL
                || source == WorldMutationSource.WORLDEDIT
                || source == WorldMutationSource.FAWE
                || source == WorldMutationSource.AXIOM) {
            return true;
        }
        return false;
    }

    boolean shouldInspectSpawnMutation(WorldMutationSource source, String entityType) {
        if (source == WorldMutationSource.PLAYER) {
            return this.placedEntityHistoryPolicy.shouldPersist(entityType);
        }
        return this.shouldInspectMutation(source, entityType);
    }

    boolean shouldInspectUndoRedo(WorldMutationSource source, String entityType) {
        return HistoryCaptureManager.shouldCaptureMutation(source)
                && entityType != null
                && !entityType.isBlank()
                && !EXCLUDED_ENTITY_TYPES.contains(entityType);
    }

    boolean shouldInspectExternalToolFallback(String entityType) {
        return FALLBACK_INSPECTED_ENTITY_TYPES.contains(entityType)
                || this.placedEntityHistoryPolicy.shouldPersist(entityType);
    }

    boolean shouldCaptureMutation(WorldMutationSource source, EntityPayload oldValue, EntityPayload newValue) {
        return this.shouldInspectMutation(source, this.entityType(oldValue, newValue));
    }

    private String entityId(EntityPayload oldValue, EntityPayload newValue) {
        if (newValue != null && !newValue.entityId().isBlank()) {
            return newValue.entityId();
        }
        return oldValue == null ? "" : oldValue.entityId();
    }

    private String entityType(EntityPayload oldValue, EntityPayload newValue) {
        if (newValue != null && !newValue.entityType().isBlank()) {
            return newValue.entityType();
        }
        return oldValue == null ? "" : oldValue.entityType();
    }
}
