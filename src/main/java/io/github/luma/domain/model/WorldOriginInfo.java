package io.github.luma.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * Durable world-level manifest shared by all dimension workspaces.
 *
 * <p>The manifest stores enough origin metadata to explain what the "Initial"
 * root means for every workspace in the world and to provide deterministic
 * regeneration context for tracked chunks.
 */
public record WorldOriginInfo(
        int schemaVersion,
        String levelName,
        String minecraftVersion,
        int dataVersion,
        long seed,
        boolean createdWithLumi,
        String datapackFingerprint,
        Map<String, DimensionOrigin> dimensions,
        Instant createdAt,
        Instant updatedAt
) {

    public record DimensionOrigin(
            String dimensionId,
            String generatorType,
            String biomeSourceType,
            int seaLevel,
            String generatorFingerprint
    ) {
    }
}
