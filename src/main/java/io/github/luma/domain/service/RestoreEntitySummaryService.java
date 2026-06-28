package io.github.luma.domain.service;

import io.github.luma.domain.model.EntityPayload;
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

public final class RestoreEntitySummaryService {

    private final SnapshotReader snapshotReader;

    public RestoreEntitySummaryService() {
        this(new SnapshotReader());
    }

    RestoreEntitySummaryService(SnapshotReader snapshotReader) {
        this.snapshotReader = snapshotReader;
    }

    public List<RestoreEntityTypeCount> summarize(ProjectLayout layout, ProjectVersion version) throws IOException {
        if (layout == null || version == null || version.entityCheckpointId() == null || version.entityCheckpointId().isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SnapshotChunkData chunk : this.snapshotReader.readFile(
                layout.entityCheckpointFile(version.entityCheckpointId())
        ).chunks()) {
            for (EntityPayload entity : chunk.entitySnapshots()) {
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
}
