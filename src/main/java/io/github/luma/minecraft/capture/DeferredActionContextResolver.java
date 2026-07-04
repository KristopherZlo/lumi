package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import java.io.IOException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class DeferredActionContextResolver {

    private final TrackedProjectCatalog trackedProjectCatalog;
    private final WorkingDraftSessionManager workingDrafts;

    DeferredActionContextResolver(
            TrackedProjectCatalog trackedProjectCatalog,
            WorkingDraftSessionManager workingDrafts
    ) {
        this.trackedProjectCatalog = trackedProjectCatalog;
        this.workingDrafts = workingDrafts;
    }

    CaptureSessionState.DeferredActionContext near(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        ChunkPoint center = ChunkPoint.from(pos);
        try {
            for (TrackedProject trackedProject : this.trackedProjectCatalog.matching(level, pos)) {
                CaptureSessionState session = this.workingDrafts.session(trackedProject.project().id().toString());
                CaptureSessionState.DeferredActionContext context = this.near(session, center);
                if (context != null && context.hasAction()) {
                    return context;
                }
            }
        } catch (IOException exception) {
            LumaMod.LOGGER.warn("Failed to resolve deferred action context near {}", pos, exception);
        }
        return null;
    }

    private CaptureSessionState.DeferredActionContext near(CaptureSessionState session, ChunkPoint center) {
        if (session == null) {
            return null;
        }
        for (int dx = -1; dx <= 1; dx += 1) {
            for (int dz = -1; dz <= 1; dz += 1) {
                CaptureSessionState.DeferredActionContext context =
                        session.deferredActionContext(new ChunkPoint(center.x() + dx, center.z() + dz));
                if (context != null && context.hasAction()) {
                    return context;
                }
            }
        }
        return null;
    }
}
