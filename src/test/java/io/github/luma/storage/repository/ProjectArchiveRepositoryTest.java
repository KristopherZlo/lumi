package io.github.luma.storage.repository;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectArchiveEntry;
import io.github.luma.domain.model.ProjectArchiveManifest;
import io.github.luma.domain.model.ProjectArchiveScope;
import io.github.luma.domain.model.ProjectArchiveScopeType;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Files.writeString(layout.versionFile("v0001").resolveSibling("v0002.json.83cf2152-3ea4-4342-957b-68a129df6197.tmp"), "{}", StandardCharsets.UTF_8);
        Files.write(layout.patchDataFile("patch-0001").resolveSibling("patch-0002.bin.lz4.83cf2152-3ea4-4342-957b-68a129df6197.tmp"), new byte[] {13});
        Files.write(layout.cacheDir().resolve("baseline-chunks").resolve("chunk_0_1.bin.lz4.83cf2152-3ea4-4342-957b-68a129df6197.tmp"), new byte[] {14});

        var manifest = this.projectArchiveRepository.exportArchive(
                layout,
                this.projectRepository.load(layout).orElseThrow(),
                archiveFile,
                false
        );

        assertEquals(ProjectArchiveScopeType.PROJECT, manifest.scopeOrDefault().type());
        assertFalse(manifest.includesPreviews());
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/project.json")));
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/work-zones.json")));
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/cache/baseline-chunks/chunk_0_0.bin.lz4")));
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("project/recovery/journal.json")));
        assertFalse(manifest.entries().stream().anyMatch(entry -> entry.path().startsWith("project/previews/")));
        assertFalse(manifest.entries().stream().anyMatch(entry -> entry.path().contains("draft")));
        assertFalse(manifest.entries().stream().anyMatch(entry -> entry.path().endsWith(".tmp")));

        try (ZipFile zip = new ZipFile(archiveFile.toFile(), StandardCharsets.UTF_8)) {
            assertTrue(zip.getEntry("manifest.json") != null);
            assertTrue(zip.getEntry("project/project.json") != null);
            assertTrue(zip.getEntry("project/work-zones.json") != null);
            assertTrue(zip.getEntry("project/recovery/journal.json") != null);
            assertTrue(zip.getEntry("project/cache/baseline-chunks/chunk_0_0.bin.lz4") != null);
            assertTrue(zip.getEntry("project/previews/v0001.png") == null);
            assertTrue(zip.getEntry("project/recovery/draft.bin.lz4") == null);
            assertTrue(zip.getEntry("project/recovery/operation-draft.bin.lz4") == null);
            assertTrue(zip.getEntry("project/versions/v0002.json.83cf2152-3ea4-4342-957b-68a129df6197.tmp") == null);
            assertTrue(zip.getEntry("project/patches/patch-0002.bin.lz4.83cf2152-3ea4-4342-957b-68a129df6197.tmp") == null);
            assertTrue(zip.getEntry("project/cache/baseline-chunks/chunk_0_1.bin.lz4.83cf2152-3ea4-4342-957b-68a129df6197.tmp") == null);
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
        assertTrue(Files.exists(importedLayout.workZonesFile()));
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

    @Test
    void exportArchiveHashesEntriesAndImportRejectsTamperedPayload() throws Exception {
        ProjectLayout sourceLayout = this.seedProject(this.tempDir.resolve("source-hash").resolve("tower.mbp"));
        Path archiveFile = this.tempDir.resolve("tower-hash.zip");

        ProjectArchiveManifest manifest = this.projectArchiveRepository.exportArchive(
                sourceLayout,
                this.projectRepository.load(sourceLayout).orElseThrow(),
                archiveFile,
                false
        );

        ProjectArchiveEntry patchEntry = manifest.entries().stream()
                .filter(entry -> entry.path().equals("project/patches/patch-0001.bin.lz4"))
                .findFirst()
                .orElseThrow();
        assertTrue(patchEntry.sha256Hex().matches("[0-9a-f]{64}"));

        Map<String, byte[]> payloads = new LinkedHashMap<>();
        for (ProjectArchiveEntry entry : manifest.entries()) {
            payloads.put(entry.path(), this.readArchiveSource(sourceLayout, entry.path()));
        }
        payloads.put(patchEntry.path(), new byte[] {3, 2, 1});

        Path tamperedArchive = this.tempDir.resolve("tower-hash-tampered.zip");
        this.writeArchive(tamperedArchive, manifest, payloads);

        assertThrows(IOException.class, () -> this.projectArchiveRepository.importArchive(
                this.tempDir.resolve("target-tampered"),
                tamperedArchive
        ));
    }

    @Test
    void importArchiveRejectsUnsafeProjectFolderName() throws Exception {
        Path archiveFile = this.tempDir.resolve("bad-folder.zip");
        this.writeArchive(
                archiveFile,
                manifest("../escape.mbp", List.of()),
                Map.of()
        );

        assertThrows(IOException.class, () -> this.projectArchiveRepository.importArchive(this.tempDir.resolve("target"), archiveFile));
    }

    @Test
    void importArchiveRejectsTooManyManifestEntries() throws Exception {
        Path archiveFile = this.tempDir.resolve("too-many.zip");
        List<ProjectArchiveEntry> entries = IntStream.range(0, 20_001)
                .mapToObj(index -> new ProjectArchiveEntry("project/previews/v" + index + ".png", 0L, true))
                .toList();
        this.writeArchive(archiveFile, manifest("too-many.mbp", entries), Map.of());

        assertThrows(IOException.class, () -> this.projectArchiveRepository.loadManifest(archiveFile));
    }

    @Test
    void importArchiveRejectsEntrySizeMismatch() throws Exception {
        Path archiveFile = this.tempDir.resolve("size-mismatch.zip");
        ProjectArchiveEntry projectEntry = new ProjectArchiveEntry("project/project.json", 128L, false);
        this.writeArchive(
                archiveFile,
                manifest("tower.mbp", List.of(projectEntry)),
                Map.of(projectEntry.path(), "{}".getBytes(StandardCharsets.UTF_8))
        );

        assertThrows(IOException.class, () -> this.projectArchiveRepository.importArchive(this.tempDir.resolve("target"), archiveFile));
    }

    @Test
    void importArchiveRejectsTransientStorageEntries() throws Exception {
        Path archiveFile = this.tempDir.resolve("transient-entry.zip");
        ProjectArchiveEntry transientEntry = new ProjectArchiveEntry(
                "project/versions/v0002.json.83cf2152-3ea4-4342-957b-68a129df6197.tmp",
                2L,
                true
        );
        this.writeArchive(
                archiveFile,
                manifest("tower.mbp", List.of(transientEntry)),
                Map.of(transientEntry.path(), "{}".getBytes(StandardCharsets.UTF_8))
        );

        assertThrows(IOException.class, () -> this.projectArchiveRepository.loadManifest(archiveFile));
    }

    @Test
    void importArchiveRejectsMaliciousVersionIds() throws Exception {
        ProjectLayout sourceLayout = this.seedProject(this.tempDir.resolve("source-bad-version").resolve("tower.mbp"));
        ProjectVersion badVersion = new ProjectVersion(
                "../escape",
                "project",
                "main",
                "",
                "snapshot-0001",
                List.of("patch-0001"),
                VersionKind.INITIAL,
                "tester",
                "Bad",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                Instant.parse("2026-04-21T08:00:00Z")
        );
        Files.writeString(sourceLayout.versionFile("v0001"), GsonProvider.gson().toJson(badVersion), StandardCharsets.UTF_8);
        Path archiveFile = this.tempDir.resolve("bad-version.zip");
        this.projectArchiveRepository.exportArchive(sourceLayout, this.projectRepository.load(sourceLayout).orElseThrow(), archiveFile, false);

        assertThrows(IOException.class, () -> this.projectArchiveRepository.importArchive(this.tempDir.resolve("target-bad-version"), archiveFile));
    }

    @Test
    void importArchiveRejectsMismatchedPatchMetadataId() throws Exception {
        ProjectLayout sourceLayout = this.seedProject(this.tempDir.resolve("source-bad-patch").resolve("tower.mbp"));
        Files.writeString(sourceLayout.patchMetaFile("patch-0001"), "{\"id\":\"other-patch\"}", StandardCharsets.UTF_8);
        Path archiveFile = this.tempDir.resolve("bad-patch.zip");
        this.projectArchiveRepository.exportArchive(sourceLayout, this.projectRepository.load(sourceLayout).orElseThrow(), archiveFile, false);

        assertThrows(IOException.class, () -> this.projectArchiveRepository.importArchive(this.tempDir.resolve("target-bad-patch"), archiveFile));
    }

    @Test
    void exportArchiveRejectsSymlinkedFiles() throws Exception {
        ProjectLayout layout = this.seedProject(this.tempDir.resolve("source-symlink").resolve("tower.mbp"));
        Path outside = this.tempDir.resolve("outside.txt");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);
        Path symlink = layout.cacheDir().resolve("render-cache-link.bin");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symlink creation is not available in this environment");
        }

        assertThrows(IOException.class, () -> this.projectArchiveRepository.exportArchive(
                layout,
                this.projectRepository.load(layout).orElseThrow(),
                this.tempDir.resolve("symlink.zip"),
                false
        ));
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
        Files.writeString(
                layout.workZonesFile(),
                "{\"schemaVersion\":1,\"zones\":[],\"activeZoneByActor\":{}}",
                StandardCharsets.UTF_8
        );
        Files.createDirectories(layout.cacheDir().resolve("baseline-chunks"));
        Files.write(layout.cacheDir().resolve("baseline-chunks").resolve("chunk_0_0.bin.lz4"), new byte[]{10});
        Files.writeString(layout.recoveryJournalFile(), "[]", StandardCharsets.UTF_8);
        Files.write(layout.recoveryDraftFile(), new byte[]{11});
        Files.write(layout.recoveryOperationDraftFile(), new byte[]{12});
        return layout;
    }

    private ProjectArchiveManifest manifest(String projectFolderName, List<ProjectArchiveEntry> entries) {
        return new ProjectArchiveManifest(
                ProjectArchiveManifest.CURRENT_SCHEMA_VERSION,
                ProjectArchiveScope.project(),
                "Tower",
                projectFolderName,
                "project",
                Instant.parse("2026-04-21T08:00:00Z"),
                false,
                entries
        );
    }

    private void writeArchive(
            Path archiveFile,
            ProjectArchiveManifest manifest,
            Map<String, byte[]> payloads
    ) throws Exception {
        Files.createDirectories(archiveFile.getParent());
        Map<String, byte[]> orderedPayloads = new LinkedHashMap<>(payloads);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archiveFile), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(GsonProvider.compactGson().toJson(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> entry : orderedPayloads.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private byte[] readArchiveSource(ProjectLayout layout, String archivePath) throws IOException {
        return Files.readAllBytes(layout.root().resolve(archivePath.substring("project/".length())));
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
