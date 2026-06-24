package io.github.luma.minecraft.capture;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marks accepted block mutations for final settled-world reconciliation.
 */
final class LiveBlockSectionReconciliationMarker {

    private final CaptureBaselineCoordinator baselineCoordinator;
    private final WorkingDraftSessionManager workingDrafts;

    LiveBlockSectionReconciliationMarker(
            CaptureBaselineCoordinator baselineCoordinator,
            WorkingDraftSessionManager workingDrafts
    ) {
        this.baselineCoordinator = baselineCoordinator;
        this.workingDrafts = workingDrafts;
    }

    void mark(
            TrackedProject trackedProject,
            ServerLevel level,
            io.github.luma.domain.model.WorldMutationSource source,
            BlockPos pos,
            ChunkPoint chunk,
            BlockState oldState,
            CompoundTag oldBlockEntity,
            CaptureSessionState.DeferredActionContext context,
            boolean explicitRootSource
    ) {
        String projectId = trackedProject.project().id().toString();
        CaptureSessionState session = this.workingDrafts.session(projectId);
        if (session == null) {
            return;
        }
        if (explicitRootSource) {
            session.addRootChunk(chunk);
        }
        this.baselineCoordinator.recordBaselineCorrection(session, pos, oldState, oldBlockEntity);
        this.baselineCoordinator.captureSessionChunkBaseline(
                trackedProject,
                level,
                session,
                chunk,
                pos,
                oldState,
                oldBlockEntity
        );
        int sectionY = Math.floorDiv(pos.getY(), 16);
        session.markDirtySection(new ChunkSectionPoint(chunk, sectionY), context, level.getGameTime());
        this.workingDrafts.markDirty(projectId);
        LumaDebugLog.log(
                trackedProject.project(),
                "capture",
                "Marked chunk {}:{} section {} dirty for final live-state reconciliation in project {} from {}",
                chunk.x(),
                chunk.z(),
                sectionY,
                trackedProject.project().name(),
                source
        );
    }
}
