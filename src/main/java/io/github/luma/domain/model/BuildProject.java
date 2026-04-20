package io.github.luma.domain.model;

import java.time.Instant;
import java.util.UUID;

public record BuildProject(
        UUID id,
        String name,
        String description,
        String minecraftVersion,
        String modLoader,
        Bounds3i bounds,
        BlockPoint origin,
        String mainVariantId,
        Instant createdAt,
        Instant updatedAt,
        ProjectSettings settings,
        boolean favorite,
        boolean archived
) {

    public static BuildProject create(String name, Bounds3i bounds, BlockPoint origin, Instant now) {
        return new BuildProject(
                UUID.randomUUID(),
                name,
                "",
                "1.21.11",
                "fabric",
                bounds,
                origin,
                "main",
                now,
                now,
                ProjectSettings.defaults(),
                false,
                false
        );
    }
}
