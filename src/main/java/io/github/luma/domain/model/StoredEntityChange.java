package io.github.luma.domain.model;

import java.util.Objects;

public record StoredEntityChange(
        String entityId,
        String entityType,
        EntityPayload oldValue,
        EntityPayload newValue
) {

    public StoredEntityChange {
        entityId = normalizeId(entityId, oldValue, newValue);
        entityType = normalizeType(entityType, oldValue, newValue);
    }

    public StoredEntityChange withLatestState(EntityPayload newValue) {
        return new StoredEntityChange(this.entityId, this.entityType, this.oldValue, newValue);
    }

    public StoredEntityChange inverse() {
        return new StoredEntityChange(this.entityId, this.entityType, this.newValue, this.oldValue);
    }

    public boolean isSpawn() {
        return this.oldValue == null && this.newValue != null;
    }

    public boolean isRemove() {
        return this.oldValue != null && this.newValue == null;
    }

    public boolean isUpdate() {
        return this.oldValue != null && this.newValue != null;
    }

    public boolean isNoOp() {
        return Objects.equals(this.oldValue, this.newValue);
    }

    public ChunkPoint chunk() {
        if (this.newValue != null) {
            return this.newValue.chunk();
        }
        if (this.oldValue != null) {
            return this.oldValue.chunk();
        }
        return new ChunkPoint(0, 0);
    }

    private static String normalizeId(String entityId, EntityPayload oldValue, EntityPayload newValue) {
        if (entityId != null && !entityId.isBlank()) {
            return entityId;
        }
        if (newValue != null && !newValue.entityId().isBlank()) {
            return newValue.entityId();
        }
        return oldValue == null ? "" : oldValue.entityId();
    }

    private static String normalizeType(String entityType, EntityPayload oldValue, EntityPayload newValue) {
        if (entityType != null && !entityType.isBlank()) {
            return entityType;
        }
        if (newValue != null && !newValue.entityType().isBlank()) {
            return newValue.entityType();
        }
        return oldValue == null ? "" : oldValue.entityType();
    }
}
