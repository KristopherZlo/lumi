package io.github.luma.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mutable in-memory accumulator for pending tracked edits.
 *
 * <p>The buffer is keyed by packed block position so repeated edits to the same
 * block collapse into one logical change. The first observed old state is
 * preserved, the latest new state wins, and no-op changes are removed.
 */
public final class TrackedChangeBuffer {

    private final String id;
    private final String projectId;
    private final String variantId;
    private final String baseVersionId;
    private final String actor;
    private final WorldMutationSource mutationSource;
    private final Instant startedAt;
    private Instant updatedAt;
    private final LinkedHashMap<Long, StoredBlockChange> changes = new LinkedHashMap<>();
    private final LinkedHashMap<String, StoredEntityChange> entityChanges = new LinkedHashMap<>();

    public TrackedChangeBuffer(
            String id,
            String projectId,
            String variantId,
            String baseVersionId,
            String actor,
            WorldMutationSource mutationSource,
            Instant startedAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.variantId = variantId;
        this.baseVersionId = baseVersionId;
        this.actor = actor;
        this.mutationSource = mutationSource;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
    }

    public static TrackedChangeBuffer create(
            String id,
            String projectId,
            String variantId,
            String baseVersionId,
            String actor,
            WorldMutationSource mutationSource,
            Instant now
    ) {
        return new TrackedChangeBuffer(id, projectId, variantId, baseVersionId, actor, mutationSource, now, now);
    }

    public static TrackedChangeBuffer fromDraft(String id, RecoveryDraft draft) {
        TrackedChangeBuffer buffer = new TrackedChangeBuffer(
                id,
                draft.projectId(),
                draft.variantId(),
                draft.baseVersionId(),
                draft.actor(),
                draft.mutationSource(),
                draft.startedAt(),
                draft.updatedAt()
        );
        for (StoredBlockChange change : draft.changes()) {
            buffer.addChange(change, draft.updatedAt());
        }
        for (StoredEntityChange change : draft.entityChanges()) {
            buffer.addEntityChange(change, draft.updatedAt());
        }
        return buffer;
    }

    public void recordChange(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CompoundTag oldBlockEntity,
            CompoundTag newBlockEntity,
            Instant now
    ) {
        this.addChange(
                new StoredBlockChange(
                        BlockPoint.from(pos),
                        StatePayload.capture(oldState, oldBlockEntity),
                        StatePayload.capture(newState, newBlockEntity)
                ),
                now
        );
    }

    public void addChange(StoredBlockChange change, Instant now) {
        long key = BlockPos.asLong(change.pos().x(), change.pos().y(), change.pos().z());
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

    public void addEntityChange(StoredEntityChange change, Instant now) {
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

    public void replaceChunks(Collection<ChunkPoint> chunks, Collection<StoredBlockChange> replacements, Instant now) {
        if (chunks != null) {
            for (ChunkPoint chunk : chunks) {
                if (chunk == null) {
                    continue;
                }
                this.removeChunk(chunk);
            }
        }

        if (replacements != null) {
            for (StoredBlockChange change : replacements) {
                this.addChange(change, now);
            }
        }
        this.updatedAt = now;
    }

    public boolean touchesChunk(ChunkPoint chunk) {
        if (chunk == null) {
            return false;
        }
        for (StoredBlockChange change : this.changes.values()) {
            if (ChunkPoint.from(change.pos()).equals(chunk)) {
                return true;
            }
        }
        for (StoredEntityChange change : this.entityChanges.values()) {
            if (change.chunk().equals(chunk)) {
                return true;
            }
        }
        return false;
    }

    public List<ChunkPoint> touchedChunks() {
        LinkedHashMap<String, ChunkPoint> chunks = new LinkedHashMap<>();
        for (StoredBlockChange change : this.changes.values()) {
            ChunkPoint chunk = ChunkPoint.from(change.pos());
            chunks.putIfAbsent(chunk.x() + ":" + chunk.z(), chunk);
        }
        for (StoredEntityChange change : this.entityChanges.values()) {
            ChunkPoint chunk = change.chunk();
            chunks.putIfAbsent(chunk.x() + ":" + chunk.z(), chunk);
        }
        List<ChunkPoint> ordered = new ArrayList<>(chunks.values());
        ordered.sort(java.util.Comparator.comparingInt(ChunkPoint::x).thenComparingInt(ChunkPoint::z));
        return List.copyOf(ordered);
    }

    public RecoveryDraft toDraft() {
        return new RecoveryDraft(
                this.projectId,
                this.variantId,
                this.baseVersionId,
                this.actor,
                this.mutationSource,
                this.startedAt,
                this.updatedAt,
                this.orderedChanges(),
                this.orderedEntityChanges()
        );
    }

    public boolean isEmpty() {
        return this.changes.isEmpty() && this.entityChanges.isEmpty();
    }

    public int size() {
        return this.changes.size() + this.entityChanges.size();
    }

    public int blockChangeCount() {
        return this.changes.size();
    }

    public int entityChangeCount() {
        return this.entityChanges.size();
    }

    public int contentFingerprint() {
        int result = 1;
        for (Map.Entry<Long, StoredBlockChange> entry : this.changes.entrySet()) {
            result = (31 * result) + Long.hashCode(entry.getKey());
            result = (31 * result) + entry.getValue().hashCode();
        }
        for (Map.Entry<String, StoredEntityChange> entry : this.entityChanges.entrySet()) {
            result = (31 * result) + entry.getKey().hashCode();
            result = (31 * result) + entry.getValue().hashCode();
        }
        return result;
    }

    public List<StoredBlockChange> orderedChanges() {
        return List.copyOf(this.changes.values());
    }

    public Map<Long, StoredBlockChange> rawChanges() {
        return Map.copyOf(this.changes);
    }

    public List<StoredEntityChange> orderedEntityChanges() {
        return List.copyOf(this.entityChanges.values());
    }

    public Map<String, StoredEntityChange> rawEntityChanges() {
        return Map.copyOf(this.entityChanges);
    }

    public String id() {
        return this.id;
    }

    public String projectId() {
        return this.projectId;
    }

    public String variantId() {
        return this.variantId;
    }

    public String baseVersionId() {
        return this.baseVersionId;
    }

    public String actor() {
        return this.actor;
    }

    public WorldMutationSource mutationSource() {
        return this.mutationSource;
    }

    public Instant startedAt() {
        return this.startedAt;
    }

    public Instant updatedAt() {
        return this.updatedAt;
    }

    public List<BlockChangeRecord> asDisplayChanges() {
        List<BlockChangeRecord> display = new ArrayList<>();
        for (StoredBlockChange change : this.changes.values()) {
            display.add(change.toRecord());
        }
        return display;
    }

    private void removeChunk(ChunkPoint chunk) {
        this.changes.entrySet().removeIf(entry -> {
            StoredBlockChange change = entry.getValue();
            return (change.pos().x() >> 4) == chunk.x()
                    && (change.pos().z() >> 4) == chunk.z();
        });
    }
}
