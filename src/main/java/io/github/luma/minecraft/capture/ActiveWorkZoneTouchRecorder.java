package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.WorkZoneService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

final class ActiveWorkZoneTouchRecorder {

    private static final int MAX_GROWTH_ACTIONS = 128;

    private final WorkZoneService workZoneService = new WorkZoneService();
    private final Map<String, String> growthZoneByAction = new ConcurrentHashMap<>();

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
            if (WorldMutationContext.currentSource() == WorldMutationSource.GROWTH
                    && this.recordGrowthTouch(trackedProject, point, now)) {
                return;
            }
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

    private boolean recordGrowthTouch(TrackedProject trackedProject, BlockPoint point, Instant now) throws IOException {
        WorkZoneCell cell = WorkZoneCell.from(point);
        String actionId = WorldMutationContext.currentActionId();
        if (actionId.isBlank()) {
            return this.zoneContainingCell(trackedProject, cell)
                    .map(zone -> this.addGrowthCell(trackedProject, zone.id(), cell, now))
                    .orElse(false);
        }

        String actionKey = trackedProject.project().id() + ":" + actionId;
        String knownZoneId = this.growthZoneByAction.get(actionKey);
        if (knownZoneId != null && this.addGrowthCell(trackedProject, knownZoneId, cell, now)) {
            return true;
        }

        return this.zoneContainingCell(trackedProject, cell)
                .map(zone -> {
                    this.rememberGrowthZone(actionKey, zone.id());
                    return this.addGrowthCell(trackedProject, zone.id(), cell, now);
                })
                .orElse(false);
    }

    private java.util.Optional<WorkZone> zoneContainingCell(TrackedProject trackedProject, WorkZoneCell cell) {
        try {
            return this.workZoneService.zoneContainingCell(trackedProject.layout(), cell);
        } catch (IOException exception) {
            return java.util.Optional.empty();
        }
    }

    private void rememberGrowthZone(String actionKey, String zoneId) {
        if (this.growthZoneByAction.size() > MAX_GROWTH_ACTIONS) {
            this.growthZoneByAction.clear();
        }
        this.growthZoneByAction.put(actionKey, zoneId);
    }

    private boolean addGrowthCell(TrackedProject trackedProject, String zoneId, WorkZoneCell cell, Instant now) {
        try {
            return this.workZoneService.addCellsToZone(trackedProject.layout(), zoneId, List.of(cell), now).isPresent();
        } catch (IOException exception) {
            return false;
        }
    }
}
