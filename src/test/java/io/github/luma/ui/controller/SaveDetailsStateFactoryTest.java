package io.github.luma.ui.controller;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SaveDetailsStateFactoryTest {

    private final SaveDetailsStateFactory factory = new SaveDetailsStateFactory();

    @Test
    void createsDetailsStateFromManifestStatsWithoutDiffPayload() {
        Instant now = Instant.parse("2026-05-01T12:00:00Z");
        BuildProject project = BuildProject.createWorldWorkspace("Mega build", "minecraft:overworld", now);
        ProjectVersion root = version("v0001", "", new ChangeStats(0, 0, 0), now);
        ProjectVersion large = version("v0002", "v0001", new ChangeStats(369_000, 1_600, 42), now.plusSeconds(60));

        var state = this.factory.create(
                project,
                List.of(large, root),
                List.of(ProjectVariant.main(large.id(), now)),
                large.id(),
                null,
                null,
                "luma.status.project_ready"
        );

        assertSame(large, state.selectedVersion());
        assertEquals(369_000, state.selectedVersion().stats().changedBlocks());
        assertEquals(1_600, state.selectedVersion().stats().changedChunks());
        assertEquals(42, state.selectedVersion().stats().distinctBlockTypes());
    }

    @Test
    void fallsBackToActiveHeadAndDefaultStatus() {
        Instant now = Instant.parse("2026-05-01T12:00:00Z");
        BuildProject project = BuildProject.createWorldWorkspace("Branch build", "minecraft:overworld", now);
        ProjectVersion root = version("v0001", "", new ChangeStats(0, 0, 0), now);
        ProjectVersion head = version("v0002", "v0001", new ChangeStats(60_000, 320, 12), now.plusSeconds(60));

        var state = this.factory.create(
                project,
                List.of(head, root),
                List.of(ProjectVariant.main(head.id(), now)),
                "missing",
                null,
                null,
                ""
        );

        assertSame(head, state.selectedVersion());
        assertEquals("luma.status.project_ready", state.status());
    }

    private static ProjectVersion version(String id, String parentId, ChangeStats stats, Instant createdAt) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentId,
                "",
                List.of(),
                VersionKind.MANUAL,
                "Tester",
                id,
                stats,
                null,
                null,
                createdAt
        );
    }
}
