package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.service.WorkZoneService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import net.minecraft.core.BlockPos;

final class ActiveWorkZoneTouchRecorder {

    private final WorkZoneService workZoneService = new WorkZoneService();

    void record(TrackedProject trackedProject, StoredBlockChange change, Instant now) {
        if (change == null) {
            return;
        }
        this.record(trackedProject, change.pos(), now);
    }

    void record(TrackedProject trackedProject, BlockPos pos, Instant now) {
        if (pos != null) {
            this.record(trackedProject, BlockPoint.from(pos), now);
        }
    }

    void record(TrackedProject trackedProject, List<BlockPos> positions, Instant now) {
        for (BlockPos pos : positions == null ? List.<BlockPos>of() : positions) {
            this.record(trackedProject, pos, now);
        }
    }

    private void record(TrackedProject trackedProject, BlockPoint point, Instant now) {
        if (point == null) {
            return;
        }
        try {
            this.workZoneService.touchBlock(trackedProject.layout(), WorldMutationContext.currentActor(), point, now);
        } catch (IOException exception) {
            LumaMod.LOGGER.warn(
                    "Failed to update active work zone for project {} at {}",
                    trackedProject.project().name(),
                    point,
                    exception
            );
        }
    }
}
