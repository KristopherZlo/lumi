package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.HistoryTombstoneRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryEditServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void renameVersionUpdatesMessageOnly() throws Exception {
        ProjectLayout layout = this.seedProject();
        HistoryEditService service = new HistoryEditService((server, projectName) -> layout, (server, projectId) -> {
        });

        ProjectVersion renamed = service.renameVersion(null, "Tower", "v0002", "Roof pass");

        assertEquals("Roof pass", renamed.message());
        assertEquals("Roof pass", new VersionRepository().load(layout, "v0002").orElseThrow().message());
        assertEquals("v0001", renamed.parentVersionId());
    }

    @Test
    void deleteHeadVersionMovesBranchToParentAndTombstonesVersion() throws Exception {
        ProjectLayout layout = this.seedProject();
        HistoryEditService service = new HistoryEditService((server, projectName) -> layout, (server, projectId) -> {
        });

        service.deleteVersion(null, "Tower", "v0003");

        assertEquals("v0002", new VariantRepository().loadAll(layout).stream()
                .filter(variant -> variant.id().equals("feature"))
                .findFirst()
                .orElseThrow()
                .headVersionId());
        assertTrue(new HistoryTombstoneRepository().load(layout).versionDeleted("v0003"));
    }

    @Test
    void deleteVersionRejectsNonLeafSaves() throws Exception {
        ProjectLayout layout = this.seedProject();
        HistoryEditService service = new HistoryEditService((server, projectName) -> layout, (server, projectId) -> {
        });

        assertThrows(IllegalArgumentException.class, () -> service.deleteVersion(null, "Tower", "v0002"));
    }

    @Test
    void deleteVariantTombstonesInactiveBranch() throws Exception {
        ProjectLayout layout = this.seedProject();
        HistoryEditService service = new HistoryEditService((server, projectName) -> layout, (server, projectId) -> {
        });

        service.deleteVariant(null, "Tower", "feature");

        assertTrue(new HistoryTombstoneRepository().load(layout).variantDeleted("feature"));
    }

    @Test
    void deleteVariantRejectsMainAndActiveBranches() throws Exception {
        ProjectLayout layout = this.seedProject();
        HistoryEditService service = new HistoryEditService((server, projectName) -> layout, (server, projectId) -> {
        });

        assertThrows(IllegalArgumentException.class, () -> service.deleteVariant(null, "Tower", "main"));
    }

    private ProjectLayout seedProject() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Tower",
                "",
                "1.21.11",
                "fabric",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                "main",
                "main",
                NOW,
                NOW,
                io.github.luma.domain.model.ProjectSettings.defaults(),
                false,
                false
        );
        new ProjectRepository().save(layout, project);
        new VariantRepository().save(layout, List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, NOW),
                new ProjectVariant("feature", "Feature", "v0002", "v0003", false, NOW)
        ));
        VersionRepository versions = new VersionRepository();
        versions.save(layout, version(project, "v0001", "main", "", VersionKind.INITIAL));
        versions.save(layout, version(project, "v0002", "main", "v0001", VersionKind.MANUAL));
        versions.save(layout, version(project, "v0003", "feature", "v0002", VersionKind.MANUAL));
        return layout;
    }

    private static ProjectVersion version(
            BuildProject project,
            String id,
            String variantId,
            String parentVersionId,
            VersionKind kind
    ) {
        return new ProjectVersion(
                id,
                project.id().toString(),
                variantId,
                parentVersionId,
                "",
                List.of(),
                kind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW.plusSeconds(Integer.parseInt(id.substring(1)))
        );
    }
}
