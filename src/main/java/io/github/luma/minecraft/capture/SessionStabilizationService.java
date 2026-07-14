package io.github.luma.minecraft.capture;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Reconciles causal envelopes against the live world after ambient fallout.
 *
 * <p>The session baseline is captured lazily when a chunk first enters the
 * stabilization envelope. Reconciliation compares that baseline to the current
 * world and composes the resulting delta on top of the immutable draft state
 * that existed when the session started or resumed.
 */
public final class SessionStabilizationService {

    private static final int DEFERRED_SETTLE_TICKS = 4;
    private static final int MAX_CHUNKS_PER_TICK = 4;
    private static final CompoundTag AIR_STATE = airState();
    private final ChunkSnapshotCaptureService chunkSnapshotCaptureService = new ChunkSnapshotCaptureService();

    public ReconciliationResult stabilizePendingChunks(
            ServerLevel level,
            BuildProject project,
            CaptureSessionState session,
            boolean requireLoadedChunks
    ) {
        if (session == null || !session.hasPendingReconciliation()) {
            return ReconciliationResult.noOp();
        }
        if (!session.beginReconciliation()) {
            return ReconciliationResult.busy();
        }

        List<ChunkPoint> pendingChunks = requireLoadedChunks
                ? session.drainPendingReconcileChunks()
                : session.drainPendingReconcileChunks(
                        level.getGameTime(),
                        DEFERRED_SETTLE_TICKS,
                        MAX_CHUNKS_PER_TICK
                );
        if (pendingChunks.isEmpty()) {
            session.finishReconciliation(List.of());
            return ReconciliationResult.noOp();
        }

        try {
            CapturedChunks capturedChunks = this.captureLiveChunks(level, pendingChunks);
            List<ChunkPoint> processedChunks = capturedChunks.captured().keySet().stream().toList();
            if (processedChunks.isEmpty()) {
                session.finishReconciliation(List.of());
                if (!capturedChunks.missingChunks().isEmpty()) {
                    session.requeuePendingChunks(capturedChunks.missingChunks());
                }
                if (!capturedChunks.transientChunks().isEmpty()) {
                    session.requeuePendingChunks(capturedChunks.transientChunks());
                }
                return ReconciliationResult.noOp();
            }

            Map<BlockPoint, StatePayload> baselineCorrections = session.baselineCorrections(processedChunks);
            Map<ChunkPoint, Set<Integer>> dirtySections = session.dirtySections(processedChunks);
            Map<ChunkPoint, Set<BlockPoint>> reconciliationPositions =
                    session.reconciliationPositions(processedChunks);
            Set<ChunkPoint> hiddenChunks = session.hiddenReconciliationChunks(processedChunks);
            List<StoredBlockChange> deltaChanges = this.deltaChanges(
                    project,
                    session,
                    capturedChunks.captured(),
                    baselineCorrections,
                    dirtySections,
                    reconciliationPositions
            );
            deltaChanges = this.applyDeferredVisibility(deltaChanges, hiddenChunks);
            List<StoredBlockChange> startingChanges = session.startingChunkChanges(processedChunks);
            List<StoredBlockChange> currentChanges = session.currentChunkChanges(processedChunks);
            List<StoredBlockChange> persistentDeltaChanges = this.persistentDeltaChanges(currentChanges, deltaChanges);
            List<StoredBlockChange> composedChanges = this.composeStabilizedChanges(
                    startingChanges,
                    currentChanges,
                    persistentDeltaChanges,
                    capturedChunks.captured()
            );
            int bufferBefore = session.buffer().size();
            boolean bufferChanged = !currentChanges.equals(composedChanges);
            if (bufferChanged) {
                session.replaceChunkChanges(processedChunks, composedChanges, Instant.now());
            }
            int bufferAfter = bufferChanged ? session.buffer().size() : bufferBefore;
            session.finishReconciliation(processedChunks);
            if (!capturedChunks.missingChunks().isEmpty()) {
                session.requeuePendingChunks(capturedChunks.missingChunks());
            }
            if (!capturedChunks.transientChunks().isEmpty()) {
                session.requeuePendingChunks(capturedChunks.transientChunks());
            }
            return new ReconciliationResult(
                    processedChunks.size(),
                    processedChunks,
                    deltaChanges.size(),
                    composedChanges.size(),
                    bufferBefore,
                    bufferAfter,
                    false,
                    bufferChanged
            );
        } catch (RuntimeException exception) {
            session.requeuePendingChunks(pendingChunks);
            throw exception;
        }
    }

    public ChunkSnapshotPayload captureBaselineChunkState(
            ServerLevel level,
            BuildProject project,
            ChunkPoint chunk,
            net.minecraft.core.BlockPos overridePos,
            net.minecraft.world.level.block.state.BlockState overrideState,
            CompoundTag overrideBlockEntity
    ) {
        List<ChunkSnapshotCaptureService.BlockStateOverride> overrides = overridePos == null || overrideState == null
                ? List.of()
                : List.of(new ChunkSnapshotCaptureService.BlockStateOverride(
                        overridePos,
                        overrideState,
                        overrideBlockEntity
                ));
        return this.captureBaselineChunkState(level, project, chunk, overrides);
    }

    public ChunkSnapshotPayload captureBaselineChunkState(
            ServerLevel level,
            BuildProject project,
            ChunkPoint chunk,
            List<ChunkSnapshotCaptureService.BlockStateOverride> overrides
    ) {
        return this.chunkSnapshotCaptureService.captureLoadedChunk(
                        level,
                        chunk,
                        overrides
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Chunk %d:%d is not loaded for session baseline capture in %s".formatted(
                                chunk.x(),
                                chunk.z(),
                                project == null ? "unknown-project" : project.name()
                        )
                ));
    }

    private List<StoredBlockChange> deltaChanges(
            BuildProject project,
            CaptureSessionState session,
            Map<ChunkPoint, ChunkSnapshotPayload> liveChunks,
            Map<BlockPoint, StatePayload> baselineCorrections,
            Map<ChunkPoint, Set<Integer>> dirtySections,
            Map<ChunkPoint, Set<BlockPoint>> reconciliationPositions
    ) {
        List<StoredBlockChange> changes = new ArrayList<>();
        for (Map.Entry<ChunkPoint, ChunkSnapshotPayload> entry : liveChunks.entrySet()) {
            ChunkSnapshotPayload baseline = session.baselineChunkState(entry.getKey());
            if (baseline == null) {
                continue;
            }
            changes.addAll(this.diffChunk(
                    baseline,
                    entry.getValue(),
                    project == null ? null : project.bounds(),
                    baselineCorrections,
                    dirtySections == null ? null : dirtySections.get(entry.getKey()),
                    reconciliationPositions == null ? null : reconciliationPositions.get(entry.getKey())
            ));
        }
        return List.copyOf(changes);
    }

    List<StoredBlockChange> diffChunk(
            ChunkSnapshotPayload baseline,
            ChunkSnapshotPayload live,
            Bounds3i bounds
    ) {
        return this.diffChunk(baseline, live, bounds, Map.of());
    }

    List<StoredBlockChange> diffChunk(
            ChunkSnapshotPayload baseline,
            ChunkSnapshotPayload live,
            Bounds3i bounds,
            Map<BlockPoint, StatePayload> baselineCorrections
    ) {
        return this.diffChunk(baseline, live, bounds, baselineCorrections, null);
    }

    List<StoredBlockChange> diffChunk(
            ChunkSnapshotPayload baseline,
            ChunkSnapshotPayload live,
            Bounds3i bounds,
            Map<BlockPoint, StatePayload> baselineCorrections,
            Set<Integer> candidateSections
    ) {
        return this.diffChunk(
                baseline,
                live,
                bounds,
                baselineCorrections,
                candidateSections,
                null
        );
    }

    List<StoredBlockChange> diffChunk(
            ChunkSnapshotPayload baseline,
            ChunkSnapshotPayload live,
            Bounds3i bounds,
            Map<BlockPoint, StatePayload> baselineCorrections,
            Set<Integer> candidateSections,
            Set<BlockPoint> candidatePositions
    ) {
        List<StoredBlockChange> changes = new ArrayList<>();
        int minX = baseline.chunkX() << 4;
        int maxX = minX + 15;
        int minZ = baseline.chunkZ() << 4;
        int maxZ = minZ + 15;
        int minY = Math.max(baseline.minBuildHeight(), live.minBuildHeight());
        int maxY = Math.min(baseline.maxBuildHeight(), live.maxBuildHeight());
        if (bounds != null) {
            minX = Math.max(minX, bounds.min().x());
            maxX = Math.min(maxX, bounds.max().x());
            minZ = Math.max(minZ, bounds.min().z());
            maxZ = Math.min(maxZ, bounds.max().z());
            minY = Math.max(minY, bounds.min().y());
            maxY = Math.min(maxY, bounds.max().y());
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return changes;
        }

        Map<Integer, ChunkSectionSnapshotPayload> baselineSections = indexSections(baseline);
        Map<Integer, ChunkSectionSnapshotPayload> liveSections = indexSections(live);
        int minSectionY = minY >> 4;
        int maxSectionY = maxY >> 4;
        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            ChunkSectionSnapshotPayload baselineSection = baselineSections.get(sectionY);
            ChunkSectionSnapshotPayload liveSection = liveSections.get(sectionY);
            boolean hasBaselineCorrection = this.hasBaselineCorrectionInSection(baselineCorrections, sectionY, bounds);
            boolean positionScoped = candidatePositions != null;
            boolean candidateSection = positionScoped
                    ? this.hasCandidatePositionInSection(candidatePositions, sectionY, bounds)
                    : candidateSections == null || candidateSections.contains(sectionY);
            if (!candidateSection && !hasBaselineCorrection) {
                continue;
            }
            if (this.sectionsEqual(baselineSection, liveSection)
                    && this.blockEntitiesEqualInSection(baseline, live, sectionY)
                    && !hasBaselineCorrection) {
                continue;
            }

            int sectionMinY = Math.max(minY, sectionY << 4);
            int sectionMaxY = Math.min(maxY, (sectionY << 4) + 15);
            for (int y = sectionMinY; y <= sectionMaxY; y++) {
                int localY = y & 15;
                for (int z = minZ; z <= maxZ; z++) {
                    int localZ = z & 15;
                    for (int x = minX; x <= maxX; x++) {
                        int localX = x & 15;
                        BlockPoint worldPos = new BlockPoint(x, y, z);
                        boolean correctedCell = baselineCorrections != null
                                && baselineCorrections.containsKey(worldPos);
                        if ((positionScoped && !candidatePositions.contains(worldPos) && !correctedCell)
                                || (!positionScoped && !candidateSection && !correctedCell)) {
                            continue;
                        }
                        CompoundTag baselineState = this.readStateTag(baselineSection, localX, localY, localZ);
                        CompoundTag baselineBlockEntity = this.readBlockEntityTag(baseline, y, localX, localZ);
                        StatePayload correctedBaseline = baselineCorrections == null
                                ? null
                                : baselineCorrections.get(worldPos);
                        if (correctedBaseline != null) {
                            baselineState = correctedBaseline.stateTag();
                            baselineBlockEntity = correctedBaseline.blockEntityTag();
                        }
                        CompoundTag liveState = this.readStateTag(liveSection, localX, localY, localZ);
                        CompoundTag liveBlockEntity = this.readBlockEntityTag(live, y, localX, localZ);
                        if (this.samePersistentCell(
                                baselineState,
                                liveState,
                                baselineBlockEntity,
                                liveBlockEntity
                        )) {
                            continue;
                        }
                        changes.add(new StoredBlockChange(
                                worldPos,
                                payload(baselineState, baselineBlockEntity),
                                payload(liveState, liveBlockEntity)
                        ));
                    }
                }
            }
        }
        return changes;
    }

    private boolean hasCandidatePositionInSection(
            Set<BlockPoint> candidatePositions,
            int sectionY,
            Bounds3i bounds
    ) {
        if (candidatePositions == null || candidatePositions.isEmpty()) {
            return false;
        }
        for (BlockPoint pos : candidatePositions) {
            if ((pos.y() >> 4) == sectionY && (bounds == null || bounds.contains(pos))) {
                return true;
            }
        }
        return false;
    }

    List<StoredBlockChange> applyDeferredVisibility(
            List<StoredBlockChange> changes,
            Set<ChunkPoint> hiddenChunks
    ) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        if (hiddenChunks == null || hiddenChunks.isEmpty()) {
            return List.copyOf(changes);
        }

        List<StoredBlockChange> rewrittenChanges = new ArrayList<>(changes.size());
        boolean changed = false;
        for (StoredBlockChange change : changes) {
            if (change == null) {
                changed = true;
                continue;
            }
            if (hiddenChunks.contains(ChunkPoint.from(change.pos())) && !change.hidden()) {
                rewrittenChanges.add(change.asHidden());
                changed = true;
            } else {
                rewrittenChanges.add(change);
            }
        }
        return changed ? List.copyOf(rewrittenChanges) : List.copyOf(changes);
    }

    private boolean hasBaselineCorrectionInSection(
            Map<BlockPoint, StatePayload> baselineCorrections,
            int sectionY,
            Bounds3i bounds
    ) {
        if (baselineCorrections == null || baselineCorrections.isEmpty()) {
            return false;
        }
        for (BlockPoint pos : baselineCorrections.keySet()) {
            if ((pos.y() >> 4) != sectionY) {
                continue;
            }
            if (bounds != null && !bounds.contains(pos)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean samePersistentCell(
            CompoundTag baselineState,
            CompoundTag liveState,
            CompoundTag baselineBlockEntity,
            CompoundTag liveBlockEntity
    ) {
        if (Objects.equals(baselineState, liveState) && Objects.equals(baselineBlockEntity, liveBlockEntity)) {
            return true;
        }
        return false;
    }

    List<StoredBlockChange> persistentDeltaChanges(
            List<StoredBlockChange> currentChanges,
            List<StoredBlockChange> deltaChanges
    ) {
        if (deltaChanges == null || deltaChanges.isEmpty()) {
            return List.of();
        }
        return List.copyOf(deltaChanges);
    }

    List<StoredBlockChange> composeStabilizedChanges(
            List<StoredBlockChange> startingChanges,
            List<StoredBlockChange> currentChanges,
            List<StoredBlockChange> deltaChanges
    ) {
        return this.composeStabilizedChanges(
                startingChanges,
                currentChanges,
                this.persistentDeltaChanges(currentChanges, deltaChanges),
                Map.of()
        );
    }

    List<StoredBlockChange> composeStabilizedChanges(
            List<StoredBlockChange> startingChanges,
            List<StoredBlockChange> currentChanges,
            List<StoredBlockChange> persistentDeltaChanges,
            Map<ChunkPoint, ChunkSnapshotPayload> liveChunks
    ) {
        List<StoredBlockChange> preservedCurrentChanges = this.preservedCurrentChanges(
                currentChanges,
                persistentDeltaChanges,
                liveChunks
        );
        return composeChanges(composeChanges(startingChanges, preservedCurrentChanges), persistentDeltaChanges);
    }

    private List<StoredBlockChange> preservedCurrentChanges(
            List<StoredBlockChange> currentChanges,
            List<StoredBlockChange> persistentDeltaChanges,
            Map<ChunkPoint, ChunkSnapshotPayload> liveChunks
    ) {
        if (currentChanges == null || currentChanges.isEmpty()) {
            return List.of();
        }

        Set<BlockPoint> deltaPositions = new LinkedHashSet<>();
        for (StoredBlockChange deltaChange : persistentDeltaChanges == null
                ? List.<StoredBlockChange>of()
                : persistentDeltaChanges) {
            deltaPositions.add(deltaChange.pos());
        }
        boolean canReadLiveState = liveChunks != null && !liveChunks.isEmpty();
        if (!canReadLiveState && deltaPositions.isEmpty()) {
            return List.of();
        }

        List<StoredBlockChange> preserved = new ArrayList<>();
        Map<ChunkPoint, Map<Integer, ChunkSectionSnapshotPayload>> liveSectionIndexes = new HashMap<>();
        for (StoredBlockChange currentChange : currentChanges) {
            if (deltaPositions.contains(currentChange.pos())) {
                preserved.add(currentChange);
                continue;
            }
            if (!canReadLiveState) {
                continue;
            }
            StatePayload livePayload = this.livePayload(currentChange.pos(), liveChunks, liveSectionIndexes);
            if (this.matchesLiveTarget(currentChange.newValue(), livePayload)) {
                preserved.add(currentChange);
            }
        }
        return List.copyOf(preserved);
    }

    private StatePayload livePayload(
            BlockPoint pos,
            Map<ChunkPoint, ChunkSnapshotPayload> liveChunks,
            Map<ChunkPoint, Map<Integer, ChunkSectionSnapshotPayload>> liveSectionIndexes
    ) {
        ChunkPoint chunk = ChunkPoint.from(pos);
        ChunkSnapshotPayload liveChunk = liveChunks.get(chunk);
        if (liveChunk == null) {
            return null;
        }
        if (pos.y() < liveChunk.minBuildHeight() || pos.y() > liveChunk.maxBuildHeight()) {
            return StatePayload.air();
        }

        Map<Integer, ChunkSectionSnapshotPayload> sections = liveSectionIndexes.computeIfAbsent(
                chunk,
                ignored -> indexSections(liveChunk)
        );
        ChunkSectionSnapshotPayload section = sections.get(pos.y() >> 4);
        int localX = pos.x() & 15;
        int localY = pos.y() & 15;
        int localZ = pos.z() & 15;
        return payload(
                this.readStateTag(section, localX, localY, localZ),
                this.readBlockEntityTag(liveChunk, pos.y(), localX, localZ)
        );
    }

    private boolean matchesLiveTarget(StatePayload target, StatePayload live) {
        if (target == null || live == null) {
            return Objects.equals(target, live);
        }
        if (target.equalsState(live)) {
            return true;
        }
        return false;
    }

    private boolean sectionsEqual(ChunkSectionSnapshotPayload baseline, ChunkSectionSnapshotPayload live) {
        if (baseline == live) {
            return true;
        }
        if (baseline == null || live == null) {
            return false;
        }
        return baseline.sectionY() == live.sectionY()
                && baseline.bitsPerEntry() == live.bitsPerEntry()
                && Objects.equals(baseline.palette(), live.palette())
                && Arrays.equals(baseline.packedStorage(), live.packedStorage());
    }

    private boolean blockEntitiesEqualInSection(
            ChunkSnapshotPayload baseline,
            ChunkSnapshotPayload live,
            int sectionY
    ) {
        return Objects.equals(
                this.blockEntitiesInSection(baseline, sectionY),
                this.blockEntitiesInSection(live, sectionY)
        );
    }

    private Map<Integer, CompoundTag> blockEntitiesInSection(ChunkSnapshotPayload chunk, int sectionY) {
        LinkedHashMap<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
        for (Map.Entry<Integer, CompoundTag> entry : chunk.blockEntities().entrySet()) {
            int worldY = chunk.minBuildHeight() + (entry.getKey() >> 8);
            if ((worldY >> 4) == sectionY) {
                blockEntities.put(entry.getKey(), entry.getValue());
            }
        }
        return blockEntities;
    }

    private CapturedChunks captureLiveChunks(ServerLevel level, List<ChunkPoint> chunks) {
        LinkedHashMap<ChunkPoint, ChunkSnapshotPayload> captured = new LinkedHashMap<>();
        List<ChunkPoint> missingChunks = new ArrayList<>();
        List<ChunkPoint> transientChunks = new ArrayList<>();
        for (ChunkPoint chunk : chunks) {
            ChunkSnapshotCaptureService.LoadedBlockStateCapture capture =
                    this.chunkSnapshotCaptureService.captureLoadedStableBlockState(level, chunk);
            if (capture.transientState()) {
                transientChunks.add(chunk);
                continue;
            }
            if (capture.payload() == null) {
                missingChunks.add(chunk);
            } else {
                captured.put(chunk, capture.payload());
            }
        }
        return new CapturedChunks(captured, List.copyOf(missingChunks), List.copyOf(transientChunks));
    }

    private static Map<Integer, ChunkSectionSnapshotPayload> indexSections(ChunkSnapshotPayload chunk) {
        HashMap<Integer, ChunkSectionSnapshotPayload> sections = new HashMap<>();
        for (ChunkSectionSnapshotPayload section : chunk.sections()) {
            sections.put(section.sectionY(), section);
        }
        return sections;
    }

    private CompoundTag readStateTag(ChunkSectionSnapshotPayload section, int localX, int localY, int localZ) {
        if (section == null || section.palette().isEmpty()) {
            return AIR_STATE;
        }
        int paletteIndex = section.paletteIndexAt(localX, localY, localZ);
        if (paletteIndex < 0 || paletteIndex >= section.palette().size()) {
            return AIR_STATE;
        }
        CompoundTag tag = section.palette().get(paletteIndex);
        return tag == null ? AIR_STATE : tag;
    }

    private CompoundTag readBlockEntityTag(ChunkSnapshotPayload chunk, int worldY, int localX, int localZ) {
        return chunk.blockEntities().get(
                io.github.luma.storage.repository.SnapshotWriter.packVerticalIndex(
                        worldY - chunk.minBuildHeight(),
                        localX,
                        localZ
                )
        );
    }

    private static StatePayload payload(CompoundTag stateTag, CompoundTag blockEntityTag) {
        return new StatePayload(
                stateTag == null ? AIR_STATE.copy() : stateTag.copy(),
                blockEntityTag == null ? null : blockEntityTag.copy()
        );
    }

    private static CompoundTag airState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:air");
        return tag;
    }

    private static List<StoredBlockChange> composeChanges(
            List<StoredBlockChange> startingChanges,
            List<StoredBlockChange> deltaChanges
    ) {
        Instant now = Instant.now();
        TrackedChangeBuffer buffer = TrackedChangeBuffer.create(
                "reconcile",
                "project",
                "variant",
                "",
                "reconcile",
                io.github.luma.domain.model.WorldMutationSource.SYSTEM,
                now
        );
        for (StoredBlockChange change : startingChanges == null ? List.<StoredBlockChange>of() : startingChanges) {
            buffer.addChange(change, now);
        }
        for (StoredBlockChange change : deltaChanges == null ? List.<StoredBlockChange>of() : deltaChanges) {
            buffer.addChange(change, now);
        }
        return buffer.orderedChanges();
    }

    public record ReconciliationResult(
            int chunkCount,
            List<ChunkPoint> chunks,
            int deltaChangeCount,
            int composedChangeCount,
            int bufferBefore,
            int bufferAfter,
            boolean inFlight,
            boolean bufferChanged
    ) {

        public ReconciliationResult {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }

        public static ReconciliationResult noOp() {
            return new ReconciliationResult(0, List.of(), 0, 0, 0, 0, false, false);
        }

        public static ReconciliationResult busy() {
            return new ReconciliationResult(0, List.of(), 0, 0, 0, 0, true, false);
        }
    }

    private record CapturedChunks(
            Map<ChunkPoint, ChunkSnapshotPayload> captured,
            List<ChunkPoint> missingChunks,
            List<ChunkPoint> transientChunks
    ) {
    }
}
