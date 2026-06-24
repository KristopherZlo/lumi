package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    static boolean isMissingBaselineChunks(IllegalArgumentException exception) {
        return exception != null
                && exception.getMessage() != null
                && exception.getMessage().startsWith("Missing baseline chunks");
    }

    private static List<ChunkPoint> unique(Collection<ChunkPoint> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Set<ChunkPoint> unique = new LinkedHashSet<>();
        for (ChunkPoint chunk : chunks) {
            if (chunk != null) {
                unique.add(chunk);
            }
        }
        return List.copyOf(unique);
    }

    private static String format(List<ChunkPoint> chunks) {
        return chunks.stream()
                .map(chunk -> chunk.x() + ":" + chunk.z())
                .toList()
                .toString();
    }
}
