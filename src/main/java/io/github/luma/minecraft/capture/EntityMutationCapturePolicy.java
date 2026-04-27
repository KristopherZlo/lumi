package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.Optional;
import java.util.Set;

public final class EntityMutationCapturePolicy {

    private static final Set<String> BUILDER_RELEVANT_ENTITY_TYPES = Set.of(
            "minecraft:armor_stand",
            "minecraft:block_display",
            "minecraft:glow_item_frame",
            "minecraft:interaction",
            "minecraft:item_display",
            "minecraft:item_frame",
            "minecraft:painting",
            "minecraft:text_display"
    );

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

    boolean shouldCaptureMutation(WorldMutationSource source, EntityPayload oldValue, EntityPayload newValue) {
        if (source == null || source == WorldMutationSource.RESTORE || source == WorldMutationSource.SYSTEM) {
            return false;
        }
        if (source == WorldMutationSource.EXTERNAL_TOOL
                || source == WorldMutationSource.WORLDEDIT
                || source == WorldMutationSource.FAWE
                || source == WorldMutationSource.AXIOM) {
            return true;
        }
        if (source != WorldMutationSource.PLAYER) {
            return false;
        }
        return BUILDER_RELEVANT_ENTITY_TYPES.contains(this.entityType(oldValue, newValue));
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
