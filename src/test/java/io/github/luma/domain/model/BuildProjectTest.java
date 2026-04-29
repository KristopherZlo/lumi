package io.github.luma.domain.model;

import java.time.Instant;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildProjectTest {

    @Test
    void mutatorsKeepProjectShapeAndUpdateFlags() {
        Instant createdAt = Instant.parse("2026-04-20T12:00:00Z");
        Instant updatedAt = Instant.parse("2026-04-20T12:05:00Z");
        BuildProject project = BuildProject.create(
                "tower",
                "minecraft:overworld",
                Bounds3i.of(new BlockPos(0, 64, 0), new BlockPos(15, 80, 15)),
                BlockPoint.from(new BlockPos(0, 64, 0)),
                createdAt
        );

        BuildProject updated = project
                .withFavorite(true, updatedAt)
                .withArchived(true, updatedAt)
                .withSettings(new ProjectSettings(true, 3, 9, 4, 0.5D, false, false, true, true, false), updatedAt);

        assertTrue(updated.favorite());
        assertTrue(updated.archived());
        assertEquals(updatedAt, updated.updatedAt());
        assertTrue(updated.settings().autoVersionsEnabled());
        assertEquals(3, updated.settings().autoVersionMinutes());
        assertTrue(updated.settings().debugLoggingEnabled());
        assertFalse(updated.settings().workspaceHudVisible());
        assertEquals("tower", updated.name());
        assertEquals("main", updated.activeVariantId());
        assertFalse(project.favorite());
    }

    @Test
    void worldWorkspaceUsesAutomaticTrackingWithoutBounds() {
        BuildProject workspace = BuildProject.createWorldWorkspace(
                "world",
                "minecraft:overworld",
                Instant.parse("2026-04-20T12:00:00Z")
        );

        assertTrue(workspace.tracksWholeDimension());
        assertEquals(BuildProject.CURRENT_SCHEMA_VERSION, workspace.schemaVersion());
        assertEquals("main", workspace.activeVariantId());
        assertEquals("minecraft:overworld", workspace.dimensionId());
    }

    @Test
    void olderWorldWorkspaceStillTracksWholeDimensionWhenBoundsAreNull() {
        Instant now = Instant.parse("2026-04-20T12:00:00Z");
        BuildProject workspace = new BuildProject(
                2,
                java.util.UUID.randomUUID(),
                "legacy-world",
                "",
                "1.21.11",
                "fabric",
                "minecraft:overworld",
                null,
                null,
                "main",
                "main",
                now,
                now,
                ProjectSettings.defaults(),
                false,
                false
        );

        assertTrue(workspace.tracksWholeDimension());
    }
}
