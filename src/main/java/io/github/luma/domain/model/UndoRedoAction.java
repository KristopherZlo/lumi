package io.github.luma.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * One temporal player action that can be applied backward or forward.
 */
public final class UndoRedoAction {

    private final String id;
    private final String actor;
    private final String projectId;
    private final String dimensionId;
    private final Instant startedAt;
    private Instant updatedAt;
    private final LinkedHashMap<Long, StoredBlockChange> changes = new LinkedHashMap<>();
    private final LinkedHashMap<String, StoredEntityChange> entityChanges = new LinkedHashMap<>();

    public UndoRedoAction(
            String id,
            String actor,
            String projectId,
            String dimensionId,
            Instant startedAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.actor = actor == null || actor.isBlank() ? "player" : actor;
        this.projectId = projectId;
        this.dimensionId = dimensionId;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
    }

    public UndoRedoAction copy() {
        UndoRedoAction copy = new UndoRedoAction(
                this.id,
                this.actor,
                this.projectId,
                this.dimensionId,
                this.startedAt,
                this.updatedAt
        );
        for (StoredBlockChange change : this.changes.values()) {
            copy.changes.put(key(change), change);
        }
        copy.entityChanges.putAll(this.entityChanges);
        return copy;
    }

    public void recordChange(StoredBlockChange change, Instant now) {
        if (change == null) {
            return;
        }

        long key = key(change);
        StoredBlockChange current = this.changes.get(key);
        StoredBlockChange merged = current == null
                ? change
                : current.withLatestState(change.newValue());
        if (merged.isNoOp()) {
            this.changes.remove(key);
        } else {
            this.changes.put(key, merged);
        }
        this.updatedAt = now;
    }

    public void recordEntityChange(StoredEntityChange change, Instant now) {
        if (change == null || change.entityId() == null || change.entityId().isBlank()) {
            return;
        }

        StoredEntityChange current = this.entityChanges.get(change.entityId());
        StoredEntityChange merged = current == null
                ? change
                : current.withLatestState(change.newValue());
        if (merged.isNoOp()) {
            this.entityChanges.remove(change.entityId());
        } else {
            this.entityChanges.put(change.entityId(), merged);
        }
        this.updatedAt = now;
    }

    public boolean canAbsorbRelatedChange(
            String dimensionId,
            StoredBlockChange change,
            Instant now,
            Duration maxIdle,
            int chunkRadius
    ) {
        if (change == null || change.isNoOp() || now == null || maxIdle == null || this.isEmpty()) {
            return false;
        }
        if (!Objects.equals(this.dimensionId, dimensionId)) {
            return false;
        }
        if (Duration.between(this.updatedAt, now).compareTo(maxIdle) > 0) {
            return false;
        }

        int targetChunkX = change.pos().x() >> 4;
        int targetChunkZ = change.pos().z() >> 4;
        for (StoredBlockChange existing : this.changes.values()) {
            int chunkX = existing.pos().x() >> 4;
            int chunkZ = existing.pos().z() >> 4;
            if (Math.abs(chunkX - targetChunkX) <= chunkRadius
                    && Math.abs(chunkZ - targetChunkZ) <= chunkRadius) {
                return true;
            }
        }
        for (StoredEntityChange existing : this.entityChanges.values()) {
            ChunkPoint chunk = existing.chunk();
            if (Math.abs(chunk.x() - targetChunkX) <= chunkRadius
                    && Math.abs(chunk.z() - targetChunkZ) <= chunkRadius) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return this.changes.isEmpty() && this.entityChanges.isEmpty();
    }

    public int size() {
        return this.changes.size() + this.entityChanges.size();
    }

    public List<StoredBlockChange> redoChanges() {
        return List.copyOf(this.changes.values());
    }

    public List<StoredBlockChange> undoChanges() {
        List<StoredBlockChange> ordered = new ArrayList<>(this.changes.values());
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    public List<StoredBlockChange> inverseChanges() {
        List<StoredBlockChange> inverse = new ArrayList<>();
        for (StoredBlockChange change : this.undoChanges()) {
            inverse.add(new StoredBlockChange(change.pos(), change.newValue(), change.oldValue()));
        }
        return List.copyOf(inverse);
    }

    public List<StoredEntityChange> redoEntityChanges() {
        return List.copyOf(this.entityChanges.values());
    }

    public List<StoredEntityChange> undoEntityChanges() {
        List<StoredEntityChange> ordered = new ArrayList<>(this.entityChanges.values());
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    public List<StoredEntityChange> inverseEntityChanges() {
        List<StoredEntityChange> inverse = new ArrayList<>();
        for (StoredEntityChange change : this.undoEntityChanges()) {
            inverse.add(change.inverse());
        }
        return List.copyOf(inverse);
    }

    public String id() {
        return this.id;
    }

    public String actor() {
        return this.actor;
    }

    public String projectId() {
        return this.projectId;
    }

    public String dimensionId() {
        return this.dimensionId;
    }

    public Instant startedAt() {
        return this.startedAt;
    }

    public Instant updatedAt() {
        return this.updatedAt;
    }

    private static long key(StoredBlockChange change) {
        return BlockPos.asLong(change.pos().x(), change.pos().y(), change.pos().z());
    }
}
