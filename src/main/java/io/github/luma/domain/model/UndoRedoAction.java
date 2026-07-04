package io.github.luma.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

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
    private long version;
    private final LinkedHashMap<BlockPoint, StoredBlockChange> changes = new LinkedHashMap<>();
    private final LinkedHashMap<BlockPoint, StatePayload> latestBlockStates = new LinkedHashMap<>();
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
        copy.version = this.version;
        for (StoredBlockChange change : this.changes.values()) {
            copy.changes.put(key(change), change);
        }
        copy.latestBlockStates.putAll(this.latestBlockStates);
        copy.entityChanges.putAll(this.entityChanges);
        return copy;
    }

    public UndoRedoAction previewCopy(int maxEntries) {
        UndoRedoAction copy = new UndoRedoAction(
                this.id,
                this.actor,
                this.projectId,
                this.dimensionId,
                this.startedAt,
                this.updatedAt
        );
        copy.version = this.version;
        int remaining = Math.max(0, maxEntries);
        for (StoredBlockChange change : this.changes.values()) {
            if (remaining <= 0) {
                return copy;
            }
            copy.changes.put(key(change), change);
            remaining -= 1;
        }
        copy.latestBlockStates.putAll(this.latestBlockStates);
        for (var entry : this.entityChanges.entrySet()) {
            if (remaining <= 0) {
                return copy;
            }
            copy.entityChanges.put(entry.getKey(), entry.getValue());
            remaining -= 1;
        }
        return copy;
    }

    public boolean recordChange(StoredBlockChange change, Instant now) {
        if (change == null || change.isNoOp()) {
            return false;
        }

        StoredBlockChange before = this.changes.get(key(change));
        StoredChangeAccumulator.mergeBlockChange(this.changes, change);
        this.latestBlockStates.put(key(change), change.newValue());
        StoredBlockChange after = this.changes.get(key(change));
        if (Objects.equals(before, after)) {
            return false;
        }
        this.updatedAt = now;
        this.version += 1;
        return true;
    }

    public boolean recordEntityChange(StoredEntityChange change, Instant now) {
        if (change == null || change.isNoOp() || change.entityId() == null || change.entityId().isBlank()) {
            return false;
        }

        StoredEntityChange before = this.entityChanges.get(change.entityId());
        StoredChangeAccumulator.mergeUndoableEntityChange(this.entityChanges, change);
        StoredEntityChange after = this.entityChanges.get(change.entityId());
        if (Objects.equals(before, after)) {
            return false;
        }
        this.updatedAt = now;
        this.version += 1;
        return true;
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

    StoredBlockChange blockChangeAt(BlockPoint pos) {
        return pos == null ? null : this.changes.get(pos);
    }

    StatePayload appliedStateAt(BlockPoint pos) {
        return pos == null ? null : this.latestBlockStates.get(pos);
    }

    public List<StoredBlockChange> undoChanges() {
        List<StoredBlockChange> ordered = new ArrayList<>(this.changes.values());
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    public List<StoredBlockChange> inverseChanges() {
        List<StoredBlockChange> inverse = new ArrayList<>();
        for (StoredBlockChange change : this.undoChanges()) {
            inverse.add(change.inverse());
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

    public long version() {
        return this.version;
    }

    private static BlockPoint key(StoredBlockChange change) {
        return change.pos();
    }
}
