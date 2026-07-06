package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.minecraft.world.PersistentBlockStatePolicy;
import io.github.luma.minecraft.world.BlockStateNbtCodec;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Coordinates session baseline snapshots and per-position baseline corrections.
 */
final class CaptureBaselineCoordinator {

    private final SessionStabilizationService stabilizationService;
    private final PersistentBlockStatePolicy persistentBlockStatePolicy;

    CaptureBaselineCoordinator() {
        this(new SessionStabilizationService(), new PersistentBlockStatePolicy());
    }

    CaptureBaselineCoordinator(
            SessionStabilizationService stabilizationService,
            PersistentBlockStatePolicy persistentBlockStatePolicy
    ) {
        this.stabilizationService = stabilizationService;
        this.persistentBlockStatePolicy = persistentBlockStatePolicy;
    }

    void captureSessionChunkBaseline(
            TrackedProject trackedProject,
            ServerLevel level,
            CaptureSessionState session,
            ChunkPoint chunk,
            BlockPos changedPos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        if (session.hasBaselineChunk(chunk)) {
            return;
        }
        List<ChunkSnapshotCaptureService.BlockStateOverride> overrides =
                this.baselineOverrides(level, session, chunk, changedPos, oldState, oldBlockEntity);
        session.captureBaselineChunk(
                chunk,
                this.stabilizationService.captureBaselineChunkState(level, trackedProject.project(), chunk, overrides)
        );
    }

    void captureSessionChunkBaseline(
            CaptureSessionState session,
            ChunkPoint chunk,
            ChunkSnapshotPayload snapshot
    ) {
        if (session == null || chunk == null || snapshot == null || session.hasBaselineChunk(chunk)) {
            return;
        }
        session.captureBaselineChunk(chunk, snapshot);
    }

    void recordBaselineCorrection(
            CaptureSessionState session,
            BlockPos pos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        if (session == null || pos == null) {
            return;
        }
        PersistentBlockStatePolicy.PersistentBlockState persistentState =
                this.persistentBlockStatePolicy.normalize(oldState, oldBlockEntity);
        session.recordBaselineCorrection(
                BlockPoint.from(pos),
                StatePayload.capture(persistentState.state(), persistentState.blockEntityTag())
        );
    }

    List<ChunkSnapshotCaptureService.BlockStateOverride> baselineOverrides(
            ServerLevel level,
            CaptureSessionState session,
            ChunkPoint chunk,
            BlockPos changedPos,
            BlockState oldState,
            CompoundTag oldBlockEntity
    ) {
        LinkedHashMap<BlockPos, ChunkSnapshotCaptureService.BlockStateOverride> overrides = new LinkedHashMap<>();
        if (changedPos != null && oldState != null) {
            this.putOverride(overrides, new ChunkSnapshotCaptureService.BlockStateOverride(
                    changedPos,
                    oldState,
                    oldBlockEntity
            ));
        }
        if (session == null || chunk == null) {
            return List.copyOf(overrides.values());
        }
        for (StoredBlockChange change : session.currentChunkChanges(List.of(chunk))) {
            if (change == null || change.pos() == null || change.oldValue() == null) {
                continue;
            }
            this.putOverride(overrides, this.overrideFromPayload(level, change));
        }
        return List.copyOf(overrides.values());
    }

    private void putOverride(
            LinkedHashMap<BlockPos, ChunkSnapshotCaptureService.BlockStateOverride> overrides,
            ChunkSnapshotCaptureService.BlockStateOverride override
    ) {
        if (override != null && override.pos() != null && override.state() != null) {
            overrides.put(override.pos(), override);
        }
    }

    private ChunkSnapshotCaptureService.BlockStateOverride overrideFromPayload(
            ServerLevel level,
            StoredBlockChange change
    ) {
        try {
            StatePayload payload = change.oldValue();
            return new ChunkSnapshotCaptureService.BlockStateOverride(
                    change.pos().toBlockPos(),
                    BlockStateNbtCodec.deserializeBlockState(level, payload.stateTag()),
                    payload.blockEntityTag()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to decode working draft baseline override", exception);
        }
    }
}
