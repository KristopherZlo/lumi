package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.Bounds3i;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;

/**
 * Reconciles causal envelopes against the live world after ambient fallout.
 *
 * <p>The session baseline is captured lazily when a chunk first enters the
 * stabilization envelope. Reconciliation compares that baseline to the current
 * world and composes the resulting delta on top of the immutable draft state
 * that existed when the session started or resumed.
 */
public final class SessionStabilizationService {

    private static final CompoundTag AIR_STATE = airState();
    private final ChunkSnapshotCaptureService chunkSnapshotCaptureService = new ChunkSnapshotCaptureService();

    public ReconciliationResult stabilizePendingChunks(
            ServerLevel level,
            BuildProject project,
            CaptureSessionState session
    ) {
        return this.stabilizePendingChunks(level, project, session, false);
    }

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

        List<ChunkPoint> pendingChunks = session.drainPendingReconcileChunks();
        if (pendingChunks.isEmpty()) {
            session.finishReconciliation(List.of());
            return ReconciliationResult.noOp();
        }

        try {
            CapturedChunks capturedChunks = this.captureLiveChunks(level, pendingChunks);
            if (requireLoadedChunks && !capturedChunks.missingChunks().isEmpty()) {
                throw new IllegalStateException(
                        "Dirty chunks are not loaded for stabilization: " + capturedChunks.missingChunks()
                );
            }
            List<ChunkPoint> processedChunks = capturedChunks.captured().keySet().stream().toList();
            if (processedChunks.isEmpty()) {
                session.finishReconciliation(List.of());
                if (!capturedChunks.missingChunks().isEmpty()) {
                    session.requeuePendingChunks(capturedChunks.missingChunks());
                }
                return ReconciliationResult.noOp();
            }

            List<StoredBlockChange> deltaChanges = this.deltaChanges(project, session, capturedChunks.captured());
            List<StoredBlockChange> composedChanges = composeChanges(
                    session.currentChunkChanges(processedChunks),
                    deltaChanges
            );
            int bufferBefore = session.buffer().size();
            session.replaceChunkChanges(processedChunks, composedChanges, Instant.now());
            int bufferAfter = session.buffer().size();
            session.finishReconciliation(processedChunks);
            if (!capturedChunks.missingChunks().isEmpty()) {
                session.requeuePendingChunks(capturedChunks.missingChunks());
            }
            return new ReconciliationResult(
                    processedChunks.size(),
                    deltaChanges.size(),
                    composedChanges.size(),
                    bufferBefore,
                    bufferAfter,
                    false,
                    deltaChanges
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
        return this.chunkSnapshotCaptureService.captureLoadedChunk(
                        level,
                        chunk,
                        overridePos,
                        overrideState,
                        overrideBlockEntity
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
            Map<ChunkPoint, ChunkSnapshotPayload> liveChunks
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
                    project == null ? null : project.bounds()
            ));
        }
        return List.copyOf(changes);
    }

    private List<StoredBlockChange> diffChunk(
            ChunkSnapshotPayload baseline,
            ChunkSnapshotPayload live,
            Bounds3i bounds
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
        for (int y = minY; y <= maxY; y++) {
            int sectionY = y >> 4;
            ChunkSectionSnapshotPayload baselineSection = baselineSections.get(sectionY);
            ChunkSectionSnapshotPayload liveSection = liveSections.get(sectionY);
            int localY = y & 15;
            for (int z = minZ; z <= maxZ; z++) {
                int localZ = z & 15;
                for (int x = minX; x <= maxX; x++) {
                    int localX = x & 15;
                    CompoundTag baselineState = this.readStateTag(baselineSection, localX, localY, localZ);
                    CompoundTag liveState = this.readStateTag(liveSection, localX, localY, localZ);
                    CompoundTag baselineBlockEntity = this.readBlockEntityTag(baseline, y, localX, localZ);
                    CompoundTag liveBlockEntity = this.readBlockEntityTag(live, y, localX, localZ);
                    if (Objects.equals(baselineState, liveState)
                            && Objects.equals(baselineBlockEntity, liveBlockEntity)) {
                        continue;
                    }
                    changes.add(new StoredBlockChange(
                            new io.github.luma.domain.model.BlockPoint(x, y, z),
                            payload(baselineState, baselineBlockEntity),
                            payload(liveState, liveBlockEntity)
                    ));
                }
            }
        }
        return changes;
    }

    private CapturedChunks captureLiveChunks(ServerLevel level, List<ChunkPoint> chunks) {
        LinkedHashMap<ChunkPoint, ChunkSnapshotPayload> captured = new LinkedHashMap<>();
        List<ChunkPoint> missingChunks = new ArrayList<>();
        for (ChunkPoint chunk : chunks) {
            this.chunkSnapshotCaptureService.captureLoadedChunk(level, chunk)
                    .ifPresentOrElse(
                            snapshot -> captured.put(chunk, snapshot),
                            () -> missingChunks.add(chunk)
                    );
        }
        return new CapturedChunks(captured, List.copyOf(missingChunks));
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
            int deltaChangeCount,
            int composedChangeCount,
            int bufferBefore,
            int bufferAfter,
            boolean inFlight,
            List<StoredBlockChange> deltaChanges
    ) {

        public ReconciliationResult {
            deltaChanges = deltaChanges == null ? List.of() : List.copyOf(deltaChanges);
        }

        public static ReconciliationResult noOp() {
            return new ReconciliationResult(0, 0, 0, 0, 0, false, List.of());
        }

        public static ReconciliationResult busy() {
            return new ReconciliationResult(0, 0, 0, 0, 0, true, List.of());
        }
    }

    private record CapturedChunks(
            Map<ChunkPoint, ChunkSnapshotPayload> captured,
            List<ChunkPoint> missingChunks
    ) {
    }
}
