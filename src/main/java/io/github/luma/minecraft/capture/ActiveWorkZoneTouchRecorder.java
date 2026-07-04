package io.github.luma.minecraft.capture;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.WorkZoneService;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            WorldMutationSource source = WorldMutationContext.currentSource();
            String actor = WorldMutationContext.currentActor();
            if (source == WorldMutationSource.GROWTH
                    && this.recordGrowthTouch(trackedProject, point, now)) {
                return;
            }
            if (this.recordActorTouch(trackedProject, actor, point, now)) {
                return;
            }
            if (this.isExternalTool(source)) {
                this.recordExternalToolTouch(trackedProject, actor, point, now);
            }
        } catch (IOException exception) {
            LumaMod.LOGGER.warn(
                    "Failed to update active work zone for project {} at {}",
                    trackedProject.project().name(),
                    point,
                    exception
            );
        }
    }

    private boolean recordActorTouch(
            TrackedProject trackedProject,
            String actor,
            BlockPoint point,
            Instant now
    ) throws IOException {
        return this.workZoneService.touchBlock(trackedProject.layout(), actor, point, now).isPresent();
    }

    private boolean recordExternalToolTouch(
            TrackedProject trackedProject,
            String actor,
            BlockPoint point,
            Instant now
    ) throws IOException {
        String ownerActor = this.ownerActor(actor);
        if (!ownerActor.isBlank() && this.recordActorTouch(trackedProject, ownerActor, point, now)) {
            return true;
        }
        return this.recordSingleActiveZoneTouch(trackedProject, WorkZoneCell.from(point), now);
    }

    private boolean recordSingleActiveZoneTouch(
            TrackedProject trackedProject,
            WorkZoneCell cell,
            Instant now
    ) throws IOException {
        String zoneId = this.singleActiveZoneId(this.workZoneService.load(trackedProject.layout()));
        if (zoneId.isBlank()) {
            return false;
        }
        return this.workZoneService.addCellsToZone(trackedProject.layout(), zoneId, List.of(cell), now).isPresent();
    }

    private String singleActiveZoneId(WorkZoneState state) {
        Set<String> zoneIds = new LinkedHashSet<>();
        state.activeZoneByActor().values().forEach(zoneId -> {
            if (zoneId != null && !zoneId.isBlank()) {
                zoneIds.add(zoneId);
            }
        });
        return zoneIds.size() == 1 ? zoneIds.iterator().next() : "";
    }

    private String ownerActor(String actor) {
        String normalized = actor == null ? "" : actor;
        int separator = normalized.indexOf(':');
        return separator >= 0 && separator + 1 < normalized.length()
                ? normalized.substring(separator + 1)
                : "";
    }

    private boolean isExternalTool(WorldMutationSource source) {
        return switch (source) {
            case EXTERNAL_TOOL, WORLDEDIT, FAWE, AXIOM -> true;
            default -> false;
        };
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

    private Optional<WorkZone> zoneContainingCell(TrackedProject trackedProject, WorkZoneCell cell) {
        try {
            return this.workZoneService.zoneContainingCell(trackedProject.layout(), cell);
        } catch (IOException exception) {
            return Optional.empty();
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
