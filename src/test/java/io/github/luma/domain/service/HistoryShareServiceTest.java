package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectArchiveRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryShareServiceTest {

    @TempDir
    Path tempDir;

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final ProjectArchiveRepository projectArchiveRepository = new ProjectArchiveRepository();
    private final HistoryShareService historyShareService = new HistoryShareService();

    @Test
    void importVariantPackageCreatesSingleVariantImportedProject() throws Exception {
        Path projectsRoot = this.tempDir.resolve("projects");
        UUID sharedProjectId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ProjectLayout targetLayout = this.seedProject(projectsRoot.resolve("tower.mbp"), sharedProjectId, "Tower", false);
        ProjectLayout sourceLayout = this.seedProject(projectsRoot.resolve("tower-share.mbp"), sharedProjectId, "Tower copy", true);
        BuildProject sourceProject = this.projectRepository.load(sourceLayout).orElseThrow();
        ProjectVariant sourceVariant = this.variantRepository.loadAll(sourceLayout).stream()
                .filter(variant -> variant.id().equals("roof-pass"))
                .findFirst()
                .orElseThrow();
        Path archiveFile = this.tempDir.resolve("roof-pass.zip");

        this.projectArchiveRepository.exportVariantArchive(
                sourceLayout,
                sourceProject,
                sourceVariant,
                this.versionRepository.loadAll(sourceLayout),
                archiveFile,
                false
        );

        BuildProject targetProject = this.projectRepository.load(targetLayout).orElseThrow();
        var result = this.historyShareService.importVariantPackage(projectsRoot, targetProject, archiveFile);
        ProjectLayout importedLayout = ProjectLayout.of(projectsRoot, result.importedProjectName());
        BuildProject importedProject = this.projectRepository.load(importedLayout).orElseThrow();
        List<ProjectVariant> importedVariants = this.variantRepository.loadAll(importedLayout);

        assertEquals("roof-pass", result.importedVariantId());
        assertTrue(result.importedProjectName().startsWith("Tower - Shared Roof pass"));
        assertEquals(result.importedProjectName(), importedProject.name());
        assertEquals("roof-pass", importedProject.activeVariantId());
        assertEquals(1, importedVariants.size());
        assertEquals("roof-pass", importedVariants.getFirst().id());
        assertTrue(Files.exists(importedLayout.versionFile("v0001")));
        assertTrue(Files.exists(importedLayout.versionFile("v0002")));
        assertTrue(Files.notExists(importedLayout.versionFile("v0003")));
    }

    private ProjectLayout seedProject(Path root, UUID projectId, String projectName, boolean withSharedVariant) throws Exception {
        ProjectLayout layout = new ProjectLayout(root);
        this.projectRepository.initializeLayout(layout);
        Instant now = Instant.parse("2026-04-23T08:00:00Z");
        BuildProject project = new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                projectId,
                projectName,
                "",
                "1.21.11",
                "fabric",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                "main",
                "main",
                now,
                now,
                io.github.luma.domain.model.ProjectSettings.defaults(),
                false,
                false
        );
        this.projectRepository.save(layout, project);
        List<ProjectVariant> variants = withSharedVariant
                ? List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, now),
                new ProjectVariant("roof-pass", "Roof pass", "v0001", "v0002", false, now.plusSeconds(60))
        )
                : List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, now));
        this.variantRepository.save(layout, variants);
        this.versionRepository.save(layout, version(project, "v0001", "main", "", VersionKind.INITIAL, now));
        if (withSharedVariant) {
            this.versionRepository.save(layout, version(project, "v0002", "roof-pass", "v0001", VersionKind.MANUAL, now.plusSeconds(60)));
            this.versionRepository.save(layout, version(project, "v0003", "main", "v0001", VersionKind.MANUAL, now.plusSeconds(120)));
        }
        Files.writeString(layout.patchMetaFile("patch-0001"), "{\"id\":\"patch-0001\"}", StandardCharsets.UTF_8);
        Files.write(layout.patchDataFile("patch-0001"), new byte[]{1});
        if (withSharedVariant) {
            Files.writeString(layout.patchMetaFile("patch-0002"), "{\"id\":\"patch-0002\"}", StandardCharsets.UTF_8);
            Files.write(layout.patchDataFile("patch-0002"), new byte[]{2});
            Files.writeString(layout.patchMetaFile("patch-0003"), "{\"id\":\"patch-0003\"}", StandardCharsets.UTF_8);
            Files.write(layout.patchDataFile("patch-0003"), new byte[]{3});
        }
        Files.write(layout.snapshotFile("snapshot-0001"), new byte[]{4});
        Files.createDirectories(layout.cacheDir().resolve("baseline-chunks"));
        Files.write(layout.cacheDir().resolve("baseline-chunks").resolve("chunk_0_0.bin.lz4"), new byte[]{10});
        Files.writeString(layout.recoveryJournalFile(), "[]", StandardCharsets.UTF_8);
        return layout;
    }

    private static ProjectVersion version(
            BuildProject project,
            String id,
            String variantId,
            String parentVersionId,
            VersionKind versionKind,
            Instant createdAt
    ) {
        return new ProjectVersion(
                id,
                project.id().toString(),
                variantId,
                parentVersionId,
                "v0001".equals(id) ? "snapshot-0001" : "",
                List.of("patch-" + id.substring(1)),
                versionKind,
                "tester",
                id,
                ChangeStats.empty(),
                io.github.luma.domain.model.PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                createdAt
        );
    }
}
