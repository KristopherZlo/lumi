package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.minecraft.world.PersistentBlockStatePolicy;
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
        session.captureBaselineChunk(
                chunk,
                this.stabilizationService.captureBaselineChunkState(
                        level,
                        trackedProject.project(),
                        chunk,
                        changedPos,
                        oldState,
                        oldBlockEntity
                )
        );
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
}
