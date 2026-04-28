package io.github.luma.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable runtime state for one active capture session.
 *
 * <p>The live tracked buffer remains the source of truth for pending block
 * changes, while chunk-level root and dirty sets drive deferred stabilization
 * for causal-only ambient fallout such as fluid spread or falling blocks.
 */
public final class CaptureSessionState {

    public static final int STABILIZATION_HALO_RADIUS = 1;

    private final TrackedChangeBuffer buffer;
    private final LinkedHashMap<ChunkPoint, List<StoredBlockChange>> startingChunkChanges;
    private final LinkedHashMap<ChunkPoint, ChunkSnapshotPayload> baselineChunkStates = new LinkedHashMap<>();
    private final LinkedHashSet<ChunkPoint> rootChunks = new LinkedHashSet<>();
    private final LinkedHashSet<ChunkPoint> dirtyChunks = new LinkedHashSet<>();
    private final LinkedHashSet<ChunkPoint> pendingReconcileChunks = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> trackedFallingEntities = new LinkedHashSet<>();
    private boolean reconciliationInFlight;

    private CaptureSessionState(TrackedChangeBuffer buffer) {
        this.buffer = buffer;
        this.startingChunkChanges = groupStartingChanges(buffer.orderedChanges());
    }

    public static CaptureSessionState create(TrackedChangeBuffer buffer) {
        return new CaptureSessionState(buffer);
    }

    public static CaptureSessionState resume(TrackedChangeBuffer buffer) {
        CaptureSessionState state = new CaptureSessionState(buffer);
        for (ChunkPoint chunk : buffer.touchedChunks()) {
            state.rootChunks.add(chunk);
        }
        return state;
    }

    public TrackedChangeBuffer buffer() {
        return this.buffer;
    }

    public boolean addRootChunk(ChunkPoint chunk) {
        if (chunk == null) {
            return false;
        }
        return this.rootChunks.add(chunk);
    }

    public boolean markDirtyChunk(ChunkPoint chunk) {
        if (chunk == null) {
            return false;
        }
        boolean dirtyChanged = this.dirtyChunks.add(chunk);
        boolean pendingChanged = this.pendingReconcileChunks.add(chunk);
        return dirtyChanged || pendingChanged;
    }

    public boolean isWithinStabilizationEnvelope(ChunkPoint chunk) {
        if (chunk == null) {
            return false;
        }
        for (ChunkPoint rootChunk : this.rootChunks) {
            if (Math.abs(rootChunk.x() - chunk.x()) <= STABILIZATION_HALO_RADIUS
                    && Math.abs(rootChunk.z() - chunk.z()) <= STABILIZATION_HALO_RADIUS) {
                return true;
            }
        }
        return false;
    }

    public List<ChunkPoint> rootChunks() {
        return List.copyOf(this.rootChunks);
    }

    public List<ChunkPoint> dirtyChunks() {
        return List.copyOf(this.dirtyChunks);
    }

    public List<ChunkPoint> drainPendingReconcileChunks() {
        List<ChunkPoint> drained = List.copyOf(this.pendingReconcileChunks);
        this.pendingReconcileChunks.clear();
        return drained;
    }

    public boolean hasPendingReconciliation() {
        return !this.pendingReconcileChunks.isEmpty();
    }

    public boolean reconciliationInFlight() {
        return this.reconciliationInFlight;
    }

    public boolean beginReconciliation() {
        if (this.reconciliationInFlight) {
            return false;
        }
        this.reconciliationInFlight = true;
        return true;
    }

    public void finishReconciliation(Collection<ChunkPoint> reconciledChunks) {
        this.reconciliationInFlight = false;
        if (reconciledChunks == null) {
            return;
        }
        this.dirtyChunks.removeAll(reconciledChunks);
    }

    public void requeuePendingChunks(Collection<ChunkPoint> chunks) {
        this.reconciliationInFlight = false;
        if (chunks == null) {
            return;
        }
        for (ChunkPoint chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            this.dirtyChunks.add(chunk);
            this.pendingReconcileChunks.add(chunk);
        }
    }

    public void replaceChunkChanges(Collection<ChunkPoint> chunks, Collection<StoredBlockChange> replacements, Instant now) {
        this.buffer.replaceChunks(chunks, replacements, now);
    }

    public void captureBaselineChunk(ChunkPoint chunk, ChunkSnapshotPayload snapshot) {
        if (chunk == null || snapshot == null || this.baselineChunkStates.containsKey(chunk)) {
            return;
        }
        this.baselineChunkStates.put(chunk, snapshot);
    }

    public boolean hasBaselineChunk(ChunkPoint chunk) {
        return chunk != null && this.baselineChunkStates.containsKey(chunk);
    }

    public ChunkSnapshotPayload baselineChunkState(ChunkPoint chunk) {
        return this.baselineChunkStates.get(chunk);
    }

    public List<StoredBlockChange> startingChunkChanges(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<StoredBlockChange> changes = new ArrayList<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            changes.addAll(this.startingChunkChanges.getOrDefault(chunk, List.of()));
        }
        return List.copyOf(changes);
    }

    public List<StoredBlockChange> currentChunkChanges(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<ChunkPoint> requestedChunks = new LinkedHashSet<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk != null) {
                requestedChunks.add(chunk);
            }
        }
        if (requestedChunks.isEmpty()) {
            return List.of();
        }
        List<StoredBlockChange> changes = new ArrayList<>();
        for (StoredBlockChange change : this.buffer.orderedChanges()) {
            if (requestedChunks.contains(ChunkPoint.from(change.pos()))) {
                changes.add(change);
            }
        }
        return List.copyOf(changes);
    }

    public boolean trackFallingEntity(UUID entityId) {
        return entityId != null && this.trackedFallingEntities.add(entityId);
    }

    public boolean untrackFallingEntity(UUID entityId) {
        return entityId != null && this.trackedFallingEntities.remove(entityId);
    }

    public boolean isTrackedFallingEntity(UUID entityId) {
        return entityId != null && this.trackedFallingEntities.contains(entityId);
    }

    private static LinkedHashMap<ChunkPoint, List<StoredBlockChange>> groupStartingChanges(List<StoredBlockChange> changes) {
        LinkedHashMap<ChunkPoint, List<StoredBlockChange>> grouped = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            ChunkPoint chunk = ChunkPoint.from(change.pos());
            grouped.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(change);
        }
        return grouped;
    }
}
