package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RestoreEntityTypeCount;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.SnapshotReader;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class RestoreEntitySummaryService {

    private final SnapshotReader snapshotReader;
    private final PartialRestoreRequestResolver partialRestoreRequestResolver = new PartialRestoreRequestResolver();

    public RestoreEntitySummaryService() {
        this(new SnapshotReader());
    }

    RestoreEntitySummaryService(SnapshotReader snapshotReader) {
        this.snapshotReader = snapshotReader;
    }

    public List<RestoreEntityTypeCount> summarize(ProjectLayout layout, ProjectVersion version) throws IOException {
        return this.summarize(layout, version, null, null, point -> true);
    }

    public List<RestoreEntityTypeCount> summarize(
            ProjectLayout layout,
            ProjectVersion version,
            PartialRestoreRequest request
    ) throws IOException {
        if (request == null) {
            return this.summarize(layout, version);
        }
        PartialRestoreRequestResolver.Resolved resolved = this.partialRestoreRequestResolver.resolve(layout, request);
        return this.summarize(
                layout,
                version,
                resolved.request().bounds(),
                resolved.request().restoreMode(),
                resolved.hardScope()
        );
    }

    List<RestoreEntityTypeCount> summarize(
            ProjectLayout layout,
            ProjectVersion version,
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) throws IOException {
        if (layout == null || version == null || version.entityCheckpointId() == null || version.entityCheckpointId().isBlank()) {
            return List.of();
        }
        Predicate<EntityPayload> filter = this.entityFilter(bounds, mode, hardScope);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SnapshotChunkData chunk : this.snapshotReader.readFile(
                layout.entityCheckpointFile(version.entityCheckpointId())
        ).chunks()) {
            for (EntityPayload entity : chunk.entitySnapshots()) {
                if (!filter.test(entity)) {
                    continue;
                }
                String type = entity == null || entity.entityType().isBlank() ? "unknown:entity" : entity.entityType();
                counts.merge(type, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new RestoreEntityTypeCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(RestoreEntityTypeCount::count).reversed()
                        .thenComparing(RestoreEntityTypeCount::entityType))
                .toList();
    }

    private Predicate<EntityPayload> entityFilter(
            Bounds3i bounds,
            PartialRestoreMode mode,
            Predicate<BlockPoint> hardScope
    ) {
        if (bounds == null) {
            return entity -> true;
        }
        PartialRestoreMode effectiveMode = mode == null ? PartialRestoreMode.SELECTED_AREA : mode;
        Predicate<BlockPoint> hardLimit = hardScope == null ? point -> true : hardScope;
        return entity -> {
            if (entity == null) {
                return false;
            }
            BlockPoint point = BlockPoint.from(entity.blockPos());
            return hardLimit.test(point) && effectiveMode.includes(bounds.contains(point));
        };
    }
}
