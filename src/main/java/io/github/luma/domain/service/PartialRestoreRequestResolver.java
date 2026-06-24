package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.util.Set;
import java.util.function.Predicate;

final class PartialRestoreRequestResolver {

    private final WorkZoneService workZoneService = new WorkZoneService();

    Resolved resolve(ProjectLayout layout, PartialRestoreRequest request) throws IOException {
        String zoneId = request.metadata().getOrDefault(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "");
        if (zoneId == null || zoneId.isBlank()) {
            if (request.bounds() == null) {
                throw new IllegalArgumentException("Partial restore requires bounds");
            }
            return new Resolved(request, point -> true);
        }

        WorkZone zone = this.workZoneService.load(layout).zones().stream()
                .filter(candidate -> candidate.id().equals(zoneId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown zone: " + zoneId));
        if (zone.cells().isEmpty()) {
            throw new IllegalArgumentException("Zone has no cells: " + zoneId);
        }

        Bounds3i bounds = request.bounds() == null ? this.zoneBounds(zone) : request.bounds();
        Set<WorkZoneCell> cells = Set.copyOf(zone.cells());
        return new Resolved(
                new PartialRestoreRequest(
                        request.projectName(),
                        request.targetVersionId(),
                        bounds,
                        request.restoreMode(),
                        request.regionSource(),
                        request.actor(),
                        request.metadata()
                ),
                point -> point != null && cells.contains(WorkZoneCell.from(point))
        );
    }

    private Bounds3i zoneBounds(WorkZone zone) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (WorkZoneCell cell : zone.cells()) {
            int cellMinX = cell.x() * WorkZoneCell.SIZE;
            int cellMinY = cell.y() * WorkZoneCell.SIZE;
            int cellMinZ = cell.z() * WorkZoneCell.SIZE;
            int cellMaxX = cellMinX + WorkZoneCell.SIZE - 1;
            int cellMaxY = cellMinY + WorkZoneCell.SIZE - 1;
            int cellMaxZ = cellMinZ + WorkZoneCell.SIZE - 1;
            minX = Math.min(minX, cellMinX);
            minY = Math.min(minY, cellMinY);
            minZ = Math.min(minZ, cellMinZ);
            maxX = Math.max(maxX, cellMaxX);
            maxY = Math.max(maxY, cellMaxY);
            maxZ = Math.max(maxZ, cellMaxZ);
        }
        return new Bounds3i(new BlockPoint(minX, minY, minZ), new BlockPoint(maxX, maxY, maxZ));
    }

    record Resolved(
            PartialRestoreRequest request,
            Predicate<BlockPoint> hardScope
    ) {

        Resolved {
            hardScope = hardScope == null ? point -> true : hardScope;
        }
    }
}
