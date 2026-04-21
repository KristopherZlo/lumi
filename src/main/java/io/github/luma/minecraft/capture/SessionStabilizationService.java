package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reconciles causal envelopes against the live world after ambient fallout.
 *
 * <p>The session baseline is captured lazily when a chunk first enters the
 * stabilization envelope. Reconciliation compares that baseline to the current
 * world and composes the resulting delta on top of the immutable draft state
 * that existed when the session started or resumed.
 */
public final class SessionStabilizationService {

    public ReconciliationResult stabilizePendingChunks(
            ServerLevel level,
            BuildProject project,
            CaptureSessionState session
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
            List<StoredBlockChange> deltaChanges = this.deltaChanges(level, project, session, pendingChunks);
            List<StoredBlockChange> composedChanges = composeChanges(session.startingChunkChanges(pendingChunks), deltaChanges);
            int bufferBefore = session.buffer().size();
            session.replaceChunkChanges(pendingChunks, composedChanges, Instant.now());
            int bufferAfter = session.buffer().size();
            session.finishReconciliation(pendingChunks);
            return new ReconciliationResult(
                    pendingChunks.size(),
                    deltaChanges.size(),
                    composedChanges.size(),
                    bufferBefore,
                    bufferAfter,
                    false
            );
        } catch (RuntimeException exception) {
            session.requeuePendingChunks(pendingChunks);
            throw exception;
        }
    }

    public Map<BlockPoint, StatePayload> captureBaselineChunkState(
            ServerLevel level,
            BuildProject project,
            ChunkPoint chunk,
            BlockPos overridePos,
            BlockState overrideState,
            CompoundTag overrideBlockEntity
    ) {
        Map<BlockPoint, StatePayload> overrides = overridePos == null || overrideState == null
                ? Map.of()
                : Map.of(
                        BlockPoint.from(overridePos),
                        StatePayload.capture(overrideState, overrideBlockEntity)
                );
        return this.captureChunkState(level, project.bounds(), chunk, overrides);
    }

    private List<StoredBlockChange> deltaChanges(
            ServerLevel level,
            BuildProject project,
            CaptureSessionState session,
            List<ChunkPoint> chunks
    ) {
        Map<BlockPoint, StatePayload> baselineStates = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            baselineStates.putAll(session.baselineChunkState(chunk));
        }

        Map<BlockPoint, StatePayload> liveStates = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            liveStates.putAll(this.captureChunkState(level, project.bounds(), chunk, Map.of()));
        }

        Set<BlockPoint> positions = new LinkedHashSet<>();
        positions.addAll(baselineStates.keySet());
        positions.addAll(liveStates.keySet());

        List<StoredBlockChange> changes = new ArrayList<>();
        for (BlockPoint pos : positions) {
            StatePayload baseline = baselineStates.getOrDefault(pos, StatePayload.air());
            StatePayload live = liveStates.getOrDefault(pos, StatePayload.air());
            if (statesEqual(baseline, live)) {
                continue;
            }
            changes.add(new StoredBlockChange(pos, baseline, live));
        }
        return List.copyOf(changes);
    }

    private Map<BlockPoint, StatePayload> captureChunkState(
            ServerLevel level,
            Bounds3i bounds,
            ChunkPoint chunk,
            Map<BlockPoint, StatePayload> overrides
    ) {
        LinkedHashMap<BlockPoint, StatePayload> states = new LinkedHashMap<>();
        int minX = chunk.x() << 4;
        int maxX = minX + 15;
        int minZ = chunk.z() << 4;
        int maxZ = minZ + 15;
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        if (bounds != null) {
            minX = Math.max(minX, bounds.min().x());
            maxX = Math.min(maxX, bounds.max().x());
            minZ = Math.max(minZ, bounds.min().z());
            maxZ = Math.min(maxZ, bounds.max().z());
            minY = Math.max(minY, bounds.min().y());
            maxY = Math.min(maxY, bounds.max().y());
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return states;
        }

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPoint point = new BlockPoint(x, y, z);
                    StatePayload override = overrides.get(point);
                    if (override != null) {
                        applyState(states, point, override);
                        continue;
                    }

                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    CompoundTag blockEntityTag = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
                    applyState(states, point, StatePayload.capture(state, blockEntityTag));
                }
            }
        }
        return states;
    }

    private static void applyState(Map<BlockPoint, StatePayload> states, BlockPoint pos, StatePayload payload) {
        if (payload == null || isAir(payload)) {
            states.remove(pos);
            return;
        }
        states.put(pos, payload);
    }

    private static boolean isAir(StatePayload payload) {
        return payload == null || "minecraft:air".equals(payload.blockId());
    }

    private static boolean statesEqual(StatePayload first, StatePayload second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.equalsState(second);
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
            boolean inFlight
    ) {

        public static ReconciliationResult noOp() {
            return new ReconciliationResult(0, 0, 0, 0, 0, false);
        }

        public static ReconciliationResult busy() {
            return new ReconciliationResult(0, 0, 0, 0, 0, true);
        }
    }
}
