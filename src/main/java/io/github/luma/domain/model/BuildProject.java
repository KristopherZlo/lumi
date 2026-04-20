package io.github.luma.domain.model;

import java.time.Instant;
import java.util.UUID;

public record BuildProject(
        int schemaVersion,
        UUID id,
        String name,
        String description,
        String minecraftVersion,
        String modLoader,
        String dimensionId,
        Bounds3i bounds,
        BlockPoint origin,
        String mainVariantId,
        String activeVariantId,
        Instant createdAt,
        Instant updatedAt,
        ProjectSettings settings,
        boolean favorite,
        boolean archived
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static BuildProject create(String name, String dimensionId, Bounds3i bounds, BlockPoint origin, Instant now) {
        return new BuildProject(
                CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(),
                name,
                "",
                "1.21.11",
                "fabric",
                dimensionId,
                bounds,
                origin,
                "main",
                "main",
                now,
                now,
                ProjectSettings.defaults(),
                false,
                false
        );
    }

    public boolean isLegacySnapshotProject() {
        return this.schemaVersion <= 0;
    }

    public BuildProject withSchemaVersion(int schemaVersion) {
        return new BuildProject(
                schemaVersion,
                this.id,
                this.name,
                this.description,
                this.minecraftVersion,
                this.modLoader,
                this.dimensionId,
                this.bounds,
                this.origin,
                this.mainVariantId,
                this.activeVariantId,
                this.createdAt,
                this.updatedAt,
                this.settings,
                this.favorite,
                this.archived
        );
    }

    public BuildProject withUpdatedAt(Instant updatedAt) {
        return new BuildProject(
                this.schemaVersion,
                this.id,
                this.name,
                this.description,
                this.minecraftVersion,
                this.modLoader,
                this.dimensionId,
                this.bounds,
                this.origin,
                this.mainVariantId,
                this.activeVariantId,
                this.createdAt,
                updatedAt,
                this.settings,
                this.favorite,
                this.archived
        );
    }

    public BuildProject withActiveVariantId(String activeVariantId, Instant updatedAt) {
        return new BuildProject(
                this.schemaVersion,
                this.id,
                this.name,
                this.description,
                this.minecraftVersion,
                this.modLoader,
                this.dimensionId,
                this.bounds,
                this.origin,
                this.mainVariantId,
                activeVariantId,
                this.createdAt,
                updatedAt,
                this.settings,
                this.favorite,
                this.archived
        );
    }
}
