package io.github.luma.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable runtime state for one active capture session.
 *
 * <p>The working-draft buffer remains the source of truth for pending block
 * changes, while chunk-level root and dirty sets drive coalesced stabilization.
 */
public final class CaptureSessionState {

    public static final int STABILIZATION_HALO_RADIUS = 1;

    private final TrackedChangeBuffer buffer;
    private final LinkedHashMap<ChunkPoint, List<StoredBlockChange>> startingChunkChanges;
    private final LinkedHashMap<ChunkPoint, ChunkSnapshotPayload> baselineChunkStates = new LinkedHashMap<>();
    private final LinkedHashMap<ChunkPoint, LinkedHashMap<BlockPoint, StatePayload>> baselineCorrections =
            new LinkedHashMap<>();
    private final LinkedHashSet<ChunkPoint> rootChunks = new LinkedHashSet<>();
    private final LinkedHashSet<ChunkPoint> dirtyChunks = new LinkedHashSet<>();
    private final LinkedHashMap<ChunkPoint, LinkedHashSet<Integer>> dirtySections = new LinkedHashMap<>();
    private final LinkedHashMap<ChunkPoint, LinkedHashSet<BlockPoint>> reconciliationPositions =
            new LinkedHashMap<>();
    private final LinkedHashSet<ChunkPoint> pendingReconcileChunks = new LinkedHashSet<>();
    private final LinkedHashMap<ChunkPoint, Long> pendingReconcileGameTimes = new LinkedHashMap<>();
    private final LinkedHashSet<ChunkPoint> hiddenReconciliationChunks = new LinkedHashSet<>();
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
        return this.markDirtyChunk(chunk, false);
    }

    public boolean markDirtyChunk(ChunkPoint chunk, boolean hidden) {
        return this.markDirtyChunk(chunk, hidden, Long.MIN_VALUE);
    }

    public boolean markDirtyChunk(
            ChunkPoint chunk,
            boolean hidden,
            long gameTime
    ) {
        return this.markDirtyChunk(chunk, hidden, gameTime, false);
    }

    public boolean markDirtySection(
            ChunkSectionPoint section,
            boolean hidden,
            long gameTime
    ) {
        if (section == null) {
            return false;
        }
        boolean chunkChanged = this.markDirtyChunk(section.chunk(), hidden, gameTime, true);
        boolean sectionChanged = this.dirtySections
                .computeIfAbsent(section.chunk(), ignored -> new LinkedHashSet<>())
                .add(section.sectionY());
        return chunkChanged || sectionChanged;
    }

    public boolean markDirtyPosition(BlockPoint pos, boolean hidden, long gameTime) {
        if (pos == null) {
            return false;
        }
        ChunkPoint chunk = ChunkPoint.from(pos);
        boolean positionChanged = this.reconciliationPositions
                .computeIfAbsent(chunk, ignored -> new LinkedHashSet<>())
                .add(pos);
        return this.markDirtySection(new ChunkSectionPoint(chunk, pos.y() >> 4), hidden, gameTime)
                || positionChanged;
    }

    private boolean markDirtyChunk(
            ChunkPoint chunk,
            boolean hidden,
            long gameTime,
            boolean preserveSectionTracking
    ) {
        if (chunk == null) {
            return false;
        }
        if (!preserveSectionTracking) {
            this.dirtySections.remove(chunk);
            this.reconciliationPositions.remove(chunk);
        }
        boolean dirtyChanged = this.dirtyChunks.add(chunk);
        boolean pendingChanged = this.pendingReconcileChunks.add(chunk);
        Long previousGameTime = this.pendingReconcileGameTimes.put(chunk, gameTime);
        boolean gameTimeChanged = previousGameTime == null || previousGameTime.longValue() != gameTime;
        boolean visibilityChanged = hidden && this.hiddenReconciliationChunks.add(chunk);
        return dirtyChanged || pendingChanged || gameTimeChanged || visibilityChanged;
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

    public boolean isRootChunk(ChunkPoint chunk) {
        return chunk != null && this.rootChunks.contains(chunk);
    }

    public List<ChunkPoint> dirtyChunks() {
        return List.copyOf(this.dirtyChunks);
    }

    public Map<ChunkPoint, Set<Integer>> dirtySections(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<ChunkPoint, Set<Integer>> sections = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            LinkedHashSet<Integer> dirty = this.dirtySections.get(chunk);
            if (dirty != null && !dirty.isEmpty()) {
                sections.put(chunk, Set.copyOf(dirty));
            }
        }
        return Map.copyOf(sections);
    }

    public Map<ChunkPoint, Set<BlockPoint>> reconciliationPositions(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<ChunkPoint, Set<BlockPoint>> positions = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            LinkedHashSet<BlockPoint> dirty = this.reconciliationPositions.get(chunk);
            if (dirty != null && !dirty.isEmpty()) {
                positions.put(chunk, Set.copyOf(dirty));
            }
        }
        return Map.copyOf(positions);
    }

    public List<ChunkPoint> pendingReconcileChunks() {
        return List.copyOf(this.pendingReconcileChunks);
    }

    public List<ChunkPoint> drainPendingReconcileChunks() {
        return this.drainPendingReconcileChunks(Integer.MAX_VALUE);
    }

    public List<ChunkPoint> drainPendingReconcileChunks(int maxChunks) {
        return this.drainPendingReconcileChunks(Long.MIN_VALUE, 0, maxChunks);
    }

    public List<ChunkPoint> drainPendingReconcileChunks(long gameTime, int settleTicks) {
        return this.drainPendingReconcileChunks(gameTime, settleTicks, Integer.MAX_VALUE);
    }

    public List<ChunkPoint> drainPendingReconcileChunks(long gameTime, int settleTicks, int maxChunks) {
        if (maxChunks <= 0) {
            return List.of();
        }
        List<ChunkPoint> drained = new ArrayList<>();
        Iterator<ChunkPoint> pending = this.pendingReconcileChunks.iterator();
        while (pending.hasNext() && drained.size() < maxChunks) {
            ChunkPoint chunk = pending.next();
            if (settleTicks <= 0 || this.isReadyForReconciliation(chunk, gameTime, settleTicks)) {
                drained.add(chunk);
                pending.remove();
            }
        }
        return List.copyOf(drained);
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
        for (ChunkPoint chunk : reconciledChunks) {
            this.dirtySections.remove(chunk);
            this.reconciliationPositions.remove(chunk);
            this.hiddenReconciliationChunks.remove(chunk);
            this.pendingReconcileGameTimes.remove(chunk);
        }
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
            this.pendingReconcileGameTimes.putIfAbsent(chunk, Long.MIN_VALUE);
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

    public void recordBaselineCorrection(BlockPoint pos, StatePayload originalValue) {
        if (pos == null || originalValue == null) {
            return;
        }
        ChunkPoint chunk = ChunkPoint.from(pos);
        this.baselineCorrections
                .computeIfAbsent(chunk, ignored -> new LinkedHashMap<>())
                .putIfAbsent(pos, originalValue);
    }

    public boolean hasBaselineChunk(ChunkPoint chunk) {
        return chunk != null && this.baselineChunkStates.containsKey(chunk);
    }

    public ChunkSnapshotPayload baselineChunkState(ChunkPoint chunk) {
        return this.baselineChunkStates.get(chunk);
    }

    public Map<BlockPoint, StatePayload> baselineCorrections(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<BlockPoint, StatePayload> corrections = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            corrections.putAll(this.baselineCorrections.getOrDefault(chunk, new LinkedHashMap<>()));
        }
        return Map.copyOf(corrections);
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
        return this.buffer.blockChangesInChunks(chunks);
    }

    public Set<ChunkPoint> hiddenReconciliationChunks(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ChunkPoint> hiddenChunks = new LinkedHashSet<>();
        for (ChunkPoint chunk : chunks) {
            if (this.hiddenReconciliationChunks.contains(chunk)) {
                hiddenChunks.add(chunk);
            }
        }
        return Set.copyOf(hiddenChunks);
    }

    public boolean isHiddenReconciliationChunk(ChunkPoint chunk) {
        return chunk != null && this.hiddenReconciliationChunks.contains(chunk);
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

    private boolean isReadyForReconciliation(ChunkPoint chunk, long gameTime, int settleTicks) {
        Long dirtyAt = this.pendingReconcileGameTimes.get(chunk);
        if (dirtyAt == null || dirtyAt == Long.MIN_VALUE) {
            return true;
        }
        return gameTime - dirtyAt >= settleTicks;
    }

}
