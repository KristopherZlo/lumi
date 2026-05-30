package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates baseline payload availability before restore planning hands work to
 * the tick-time world apply path.
 */
final class RestoreBaselineRequirementValidator {

    private final BaselineChunkRepository baselineChunkRepository;

    RestoreBaselineRequirementValidator() {
        this(new BaselineChunkRepository());
    }

    RestoreBaselineRequirementValidator(BaselineChunkRepository baselineChunkRepository) {
        this.baselineChunkRepository = baselineChunkRepository;
    }

    List<ChunkPoint> requirePresent(
            ProjectLayout layout,
            Collection<ChunkPoint> requiredChunks,
            String context
    ) {
        List<ChunkPoint> chunks = unique(requiredChunks);
        List<ChunkPoint> missing = new ArrayList<>();
        for (ChunkPoint chunk : chunks) {
            if (!this.baselineChunkRepository.contains(layout, chunk)) {
                missing.add(chunk);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing baseline chunks for " + context + ": " + format(missing)
            );
        }
        return chunks;
    }

    private static List<ChunkPoint> unique(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Map<String, ChunkPoint> unique = new LinkedHashMap<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk != null) {
                unique.put(chunk.x() + ":" + chunk.z(), chunk);
            }
        }
        return List.copyOf(unique.values());
    }

    private static String format(List<ChunkPoint> chunks) {
        return chunks.stream()
                .map(chunk -> chunk.x() + ":" + chunk.z())
                .toList()
                .toString();
    }
}
