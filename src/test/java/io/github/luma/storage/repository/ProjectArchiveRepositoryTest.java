package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectArchiveScopeType;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectArchiveRepositoryTest {

    @TempDir
    Path tempDir;

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final ProjectArchiveRepository projectArchiveRepository = new ProjectArchiveRepository();

    @Test
    void exportArchiveExcludesRecoveryDraftsAndOptionalPreviewsByDefault() throws Exception {
        ProjectLayout layout = this.seedProject(this.tempDir.resolve("source").resolve("tower.mbp"));
        Path archiveFile = this.tempDir.resolve("tower.zip");

        var manifest = this.projectArchiveRepository.exportArchive(
                layout,
                this.projectRepository.load(layout).orElseThrow(),
                archiveFile,
                false
        );

        assertEquals(ProjectArchiveScopeType.PROJECT, manifest.scopeOrDefault().type());
        assertFalse(manifest.includesPreviews());
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/project.json")));
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/cache/baseline-chunks/chunk_0_0.bin.lz4")));
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/recovery/journal.json")));
        assertFalse(manifest.entries().stream().anyMatch(entry -> entry.path().startsWith("project/previews/")));
        assertFalse(manifest.entries().stream().anyMatch(entry -> entry.path().contains("draft")));

        try (ZipFile zip = new ZipFile(archiveFile.toFile(), StandardCharsets.UTF_8)) {
            assertTrue(zip.getEntry("manifest.json") != null);
            assertTrue(zip.getEntry("project/project.json") != null);
            assertTrue(zip.getEntry("project/recovery/journal.json") != null);
            assertTrue(zip.getEntry("project/cache/baseline-chunks/chunk_0_0.bin.lz4") != null);
            assertTrue(zip.getEntry("project/previews/v0001.png") == null);
            assertTrue(zip.getEntry("project/recovery/draft.bin.lz4") == null);
            assertTrue(zip.getEntry("project/recovery/operation-draft.bin.lz4") == null);
        }
    }

    @Test
    void importArchiveRestoresProjectFilesAndOptionalPreviews() throws Exception {
        ProjectLayout sourceLayout = this.seedProject(this.tempDir.resolve("source").resolve("tower.mbp"));
        Path archiveFile = this.tempDir.resolve("tower-with-previews.zip");

        this.projectArchiveRepository.exportArchive(
                sourceLayout,
                this.projectRepository.load(sourceLayout).orElseThrow(),
                archiveFile,
                true
        );

        Path targetRoot = this.tempDir.resolve("target-projects");
        ProjectLayout importedLayout = this.projectArchiveRepository.importArchive(targetRoot, archiveFile);

        assertTrue(Files.exists(importedLayout.projectFile()));
        assertTrue(Files.exists(importedLayout.variantsFile()));
        assertTrue(Files.exists(importedLayout.versionFile("v0001")));
        assertTrue(Files.exists(importedLayout.patchMetaFile("patch-0001")));
        assertTrue(Files.exists(importedLayout.patchDataFile("patch-0001")));
        assertTrue(Files.exists(importedLayout.snapshotFile("snapshot-0001")));
        assertTrue(Files.exists(importedLayout.previewFile("v0001")));
        assertTrue(Files.exists(importedLayout.cacheDir().resolve("baseline-chunks").resolve("chunk_0_0.bin.lz4")));
        assertTrue(Files.exists(importedLayout.recoveryJournalFile()));
        assertFalse(Files.exists(importedLayout.recoveryDraftFile()));
        assertFalse(Files.exists(importedLayout.recoveryOperationDraftFile()));

        BuildProject imported = this.projectRepository.load(importedLayout).orElseThrow();
        assertEquals("Tower", imported.name());
        assertEquals(List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, Instant.parse("2026-04-21T08:00:00Z"))),
                this.variantRepository.loadAll(importedLayout));
        assertEquals("v0001", this.versionRepository.loadAll(importedLayout).getFirst().id());
    }

    @Test
    void exportVariantArchiveIncludesOnlySelectedVariantLineage() throws Exception {
        ProjectLayout layout = this.seedProjectWithVariant(this.tempDir.resolve("variant-source").resolve("tower.mbp"));
        BuildProject project = this.projectRepository.load(layout).orElseThrow();
        ProjectVariant sharedVariant = this.variantRepository.loadAll(layout).stream()
                .filter(variant -> variant.id().equals("roof-pass"))
                .findFirst()
                .orElseThrow();
        Path archiveFile = this.tempDir.resolve("tower-roof-pass.zip");

        var manifest = this.projectArchiveRepository.exportVariantArchive(
                layout,
                project,
                sharedVariant,
                this.versionRepository.loadAll(layout),
                archiveFile,
                false
        );

        assertEquals(ProjectArchiveScopeType.VARIANT, manifest.scopeOrDefault().type());
        assertEquals("roof-pass", manifest.scopeOrDefault().variantId());
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/versions/v0001.json")));
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/versions/v0002.json")));
        assertFalse(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/versions/v0003.json")));
    }

    private ProjectLayout seedProject(Path root) throws Exception {
        ProjectLayout layout = new ProjectLayout(root);
        this.projectRepository.initializeLayout(layout);
        Instant now = Instant.parse("2026-04-21T08:00:00Z");
        BuildProject project = BuildProject.create(
                "Tower",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                now
        );
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, now)));
        this.versionRepository.save(layout, new ProjectVersion(
                "v0001",
                project.id().toString(),
                "main",
                "",
                "snapshot-0001",
                List.of("patch-0001"),
                VersionKind.INITIAL,
                "tester",
                "Initial",
                ChangeStats.empty(),
                new PreviewInfo("v0001.png", 32, 32),
                ExternalSourceInfo.manual(),
                now
        ));
        Files.writeString(layout.patchMetaFile("patch-0001"), "{\"id\":\"patch-0001\"}", StandardCharsets.UTF_8);
        Files.write(layout.patchDataFile("patch-0001"), new byte[]{1, 2, 3});
        Files.write(layout.snapshotFile("snapshot-0001"), new byte[]{4, 5, 6});
        Files.write(layout.previewFile("v0001"), new byte[]{7, 8, 9});
        Files.createDirectories(layout.cacheDir().resolve("baseline-chunks"));
        Files.write(layout.cacheDir().resolve("baseline-chunks").resolve("chunk_0_0.bin.lz4"), new byte[]{10});
        Files.writeString(layout.recoveryJournalFile(), "[]", StandardCharsets.UTF_8);
        Files.write(layout.recoveryDraftFile(), new byte[]{11});
        Files.write(layout.recoveryOperationDraftFile(), new byte[]{12});
        return layout;
    }

    private ProjectLayout seedProjectWithVariant(Path root) throws Exception {
        ProjectLayout layout = new ProjectLayout(root);
        this.projectRepository.initializeLayout(layout);
        Instant now = Instant.parse("2026-04-21T08:00:00Z");
        BuildProject project = BuildProject.create(
                "Tower",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                now
        );
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, now),
                new ProjectVariant("roof-pass", "Roof pass", "v0001", "v0002", false, now.plusSeconds(60))
        ));
        this.versionRepository.save(layout, version(project, "v0001", "main", "", VersionKind.INITIAL, now));
        this.versionRepository.save(layout, version(project, "v0002", "roof-pass", "v0001", VersionKind.MANUAL, now.plusSeconds(60)));
        this.versionRepository.save(layout, version(project, "v0003", "main", "v0001", VersionKind.MANUAL, now.plusSeconds(120)));
        Files.writeString(layout.patchMetaFile("patch-0001"), "{\"id\":\"patch-0001\"}", StandardCharsets.UTF_8);
        Files.write(layout.patchDataFile("patch-0001"), new byte[]{1});
        Files.writeString(layout.patchMetaFile("patch-0002"), "{\"id\":\"patch-0002\"}", StandardCharsets.UTF_8);
        Files.write(layout.patchDataFile("patch-0002"), new byte[]{2});
        Files.writeString(layout.patchMetaFile("patch-0003"), "{\"id\":\"patch-0003\"}", StandardCharsets.UTF_8);
        Files.write(layout.patchDataFile("patch-0003"), new byte[]{3});
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
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                createdAt
        );
    }
}
