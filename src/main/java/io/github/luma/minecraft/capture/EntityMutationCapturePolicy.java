package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.EnumSet;
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
    private static final Set<String> MECHANISM_ENTITY_TYPES = Set.of(
            "minecraft:arrow",
            "minecraft:breeze_wind_charge",
            "minecraft:chest_minecart",
            "minecraft:command_block_minecart",
            "minecraft:egg",
            "minecraft:experience_bottle",
            "minecraft:fireball",
            "minecraft:firework_rocket",
            "minecraft:furnace_minecart",
            "minecraft:hopper_minecart",
            "minecraft:minecart",
            "minecraft:potion",
            "minecraft:small_fireball",
            "minecraft:spawner_minecart",
            "minecraft:snowball",
            "minecraft:spectral_arrow",
            "minecraft:trident",
            "minecraft:tnt_minecart"
    );
    private static final Set<WorldMutationSource> UNDO_ONLY_ITEM_DROP_SOURCES = EnumSet.of(
            WorldMutationSource.EXPLOSION,
            WorldMutationSource.EXPLOSIVE,
            WorldMutationSource.FLUID,
            WorldMutationSource.FALLING_BLOCK,
            WorldMutationSource.BLOCK_UPDATE
    );
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

    public Optional<StoredEntityChange> captureUndoOnly(
            WorldMutationSource source,
            EntityPayload oldValue,
            EntityPayload newValue
    ) {
        if (!this.shouldCaptureUndoOnlyMutation(source, oldValue, newValue)) {
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

    boolean shouldInspectMutation(WorldMutationSource source, String entityType) {
        if (source == null || source == WorldMutationSource.RESTORE || source == WorldMutationSource.SYSTEM) {
            return false;
        }
        if (EXCLUDED_ENTITY_TYPES.contains(entityType)) {
            return false;
        }
        if (source == WorldMutationSource.BLOCK_UPDATE && MECHANISM_ENTITY_TYPES.contains(entityType)) {
            return true;
        }
        if (source == WorldMutationSource.EXTERNAL_TOOL
                || source == WorldMutationSource.WORLDEDIT
                || source == WorldMutationSource.FAWE
                || source == WorldMutationSource.AXIOM
                || source == WorldMutationSource.PLAYER
                || source == WorldMutationSource.ENTITY) {
            return true;
        }
        return false;
    }

    boolean shouldInspectExternalToolFallback(String entityType) {
        return FALLBACK_INSPECTED_ENTITY_TYPES.contains(entityType)
                || this.placedEntityHistoryPolicy.shouldPersist(entityType);
    }

    boolean shouldInspectUndoOnlyMutation(WorldMutationSource source, String entityType) {
        return UNDO_ONLY_ITEM_DROP_SOURCES.contains(source) && "minecraft:item".equals(entityType);
    }

    boolean shouldCaptureMutation(WorldMutationSource source, EntityPayload oldValue, EntityPayload newValue) {
        return this.shouldInspectMutation(source, this.entityType(oldValue, newValue));
    }

    boolean shouldCaptureUndoOnlyMutation(WorldMutationSource source, EntityPayload oldValue, EntityPayload newValue) {
        return this.shouldInspectUndoOnlyMutation(source, this.entityType(oldValue, newValue));
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
