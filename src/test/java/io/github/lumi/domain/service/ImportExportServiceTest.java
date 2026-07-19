package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.WorldObjectGraph;
import io.github.lumi.storage.repository.WorldObjectRepository;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportExportServiceTest {
    @TempDir Path directory;

    @Test
    void exportsAndInspectsOneCompleteCommitTree() throws Exception {
        Path repository = directory.resolve("source");
        WorldObjectRepository objects = new WorldObjectRepository(repository);
        var section = objects.write(airSection());
        var entities = objects.write(new EntityChunkBlob(List.of()));
        var tree = new MerkleTreeEditor(objects).update(Optional.empty(), Map.of(
                new SectionKey(-33, 5, 64), section,
                new EntityChunkKey(-33, 64), entities));
        CommitRepository commits = new CommitRepository(repository);
        var source = commits.write(new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 7), "Builder"),
                "Clock works", Instant.parse("2026-07-16T10:00:00Z"),
                new UUID(0, 8), Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(1, 1, SectionBlob.BLOCK_COUNT, 0)));
        Path archive = directory.resolve("clock.lumi");
        ImportExportService service =
                new ImportExportService("minecraft:overworld", repository);

        ImportExportService.PackageInspection exported =
                service.export(source, archive);
        ImportExportService.PackageInspection inspected = service.inspect(archive);

        assertTrue(java.nio.file.Files.isRegularFile(archive));
        assertEquals(exported, inspected);
        assertEquals(source, inspected.manifest().commit());
        assertEquals("minecraft:overworld", inspected.manifest().dimensionId());
        assertEquals(5, inspected.manifest().objects().size());
        assertEquals("Clock works", inspected.source().message());
    }

    @Test
    void importsOntoLocalHeadAndPublishesOnlyANewBranch() throws Exception {
        Path sourceRepository = directory.resolve("source-import");
        WorldObjectRepository sourceObjects =
                new WorldObjectRepository(sourceRepository);
        var importedSection = sourceObjects.write(section("minecraft:stone"));
        var sourceTree = new MerkleTreeEditor(sourceObjects).update(
                Optional.empty(),
                Map.of(new SectionKey(0, 0, 0), importedSection));
        CommitRepository sourceCommits = new CommitRepository(sourceRepository);
        var sourceCommit = sourceCommits.write(new Commit(
                sourceTree, List.of(), new CommitAuthor(new UUID(0, 1), "Source"),
                "Working clock", Instant.EPOCH, new UUID(0, 2),
                Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(1, 0, SectionBlob.BLOCK_COUNT, 0)));
        Path archive = directory.resolve("import.lumi");
        new ImportExportService("minecraft:overworld", sourceRepository)
                .export(sourceCommit, archive);

        Path targetRepository = directory.resolve("target");
        WorldObjectRepository targetObjects =
                new WorldObjectRepository(targetRepository);
        var oldSection = targetObjects.write(section("minecraft:dirt"));
        var unrelatedSection = targetObjects.write(section("minecraft:glass"));
        var baseTree = new MerkleTreeEditor(targetObjects).update(
                Optional.empty(), Map.of(
                        new SectionKey(0, 0, 0), oldSection,
                        new SectionKey(40, 0, 0), unrelatedSection));
        UUID targetWorkspace = new UUID(0, 3);
        CommitRepository targetCommits = new CommitRepository(targetRepository);
        var baseCommit = targetCommits.write(new Commit(
                baseTree, List.of(), new CommitAuthor(new UUID(0, 4), "Target"),
                "Local base", Instant.EPOCH, targetWorkspace, Optional.empty(),
                CommitKind.MANUAL,
                new CommitStatistics(2, 0, 2L * SectionBlob.BLOCK_COUNT, 0)));
        BranchRefRepository refs = new BranchRefRepository(targetRepository);
        var main = refs.create(new BranchName("main"), baseCommit);
        ImportExportService target =
                new ImportExportService("minecraft:overworld", targetRepository);
        var inspection = target.inspect(archive);

        var imported = target.importPackage(
                archive, inspection, main, new BranchName("import/clock"),
                new CommitAuthor(new UUID(0, 5), "Importer"),
                Instant.parse("2026-07-16T11:00:00Z"));

        assertEquals(main, refs.read(main.name()).orElseThrow());
        assertEquals(imported.branch(),
                refs.read(imported.branch().name()).orElseThrow());
        Commit result = targetCommits.read(imported.commit());
        assertEquals(CommitKind.IMPORT, result.kind());
        assertEquals(List.of(baseCommit), result.parents());
        assertEquals(targetWorkspace, result.workspaceId());
        assertEquals("Working clock", result.message());
        assertEquals(Map.of(
                new SectionKey(0, 0, 0), importedSection,
                new SectionKey(40, 0, 0), unrelatedSection),
                new WorldObjectGraph(targetObjects).scan(result.tree()).leaves());
    }

    @Test
    void rejectsAReplacedPackageBeforePersistingItsObjects() throws Exception {
        Path firstRepository = directory.resolve("confirmed-source");
        var confirmed = packageWithSection(
                firstRepository, "minecraft:stone", "Confirmed");
        Path archive = directory.resolve("replaced.lumi");
        new ImportExportService("minecraft:overworld", firstRepository)
                .export(confirmed, archive);

        Path targetRepository = directory.resolve("replacement-target");
        WorldObjectRepository targetObjects =
                new WorldObjectRepository(targetRepository);
        var baseTree = new MerkleTreeEditor(targetObjects).update(
                Optional.empty(), Map.of());
        CommitRepository targetCommits = new CommitRepository(targetRepository);
        var baseCommit = targetCommits.write(new Commit(
                baseTree, List.of(), new CommitAuthor(new UUID(0, 21), "Target"),
                "Base", Instant.EPOCH, new UUID(0, 22), Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0)));
        BranchRefRepository refs = new BranchRefRepository(targetRepository);
        var main = refs.create(new BranchName("main"), baseCommit);
        ImportExportService target =
                new ImportExportService("minecraft:overworld", targetRepository);
        var inspection = target.inspect(archive);
        long objectsBefore = objectFileCount(targetRepository);

        Path replacementRepository = directory.resolve("replacement-source");
        var replacement = packageWithSection(
                replacementRepository, "minecraft:gold_block", "Replacement");
        new ImportExportService("minecraft:overworld", replacementRepository)
                .export(replacement, archive);

        assertThrows(java.io.IOException.class, () -> target.importPackage(
                archive, inspection, main, new BranchName("import/replaced"),
                new CommitAuthor(new UUID(0, 23), "Importer"), Instant.EPOCH));
        assertEquals(objectsBefore, objectFileCount(targetRepository));
        assertTrue(refs.read(new BranchName("import/replaced")).isEmpty());
    }

    @Test
    void optionallyTransfersTheSourcePreviewToTheImportedCommit() throws Exception {
        Path sourceRepository = directory.resolve("preview-source");
        CommitId source = packageWithSection(
                sourceRepository, "minecraft:stone", "Previewed");
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        new VersionPreviewRepository(sourceRepository).save(source, png);
        Path archive = directory.resolve("preview.lumi");
        var sourceService = new ImportExportService(
                "minecraft:overworld", sourceRepository);

        var exported = sourceService.export(source, archive, true);

        assertTrue(exported.manifest().preview().isPresent());
        Path targetRepository = directory.resolve("preview-target");
        WorldObjectRepository targetObjects =
                new WorldObjectRepository(targetRepository);
        var emptyTree = new MerkleTreeEditor(targetObjects).update(
                Optional.empty(), Map.of());
        UUID workspace = new UUID(0, 42);
        CommitRepository targetCommits = new CommitRepository(targetRepository);
        CommitId base = targetCommits.write(new Commit(
                emptyTree, List.of(), new CommitAuthor(new UUID(0, 43), "Target"),
                "Base", Instant.EPOCH, workspace, Optional.empty(),
                CommitKind.MANUAL, new CommitStatistics(0, 0, 0, 0)));
        BranchRefRepository refs = new BranchRefRepository(targetRepository);
        var main = refs.create(new BranchName("main"), base);
        var target = new ImportExportService(
                "minecraft:overworld", targetRepository);

        var result = target.importPackage(
                archive, target.inspect(archive), main,
                new BranchName("import/preview"),
                new CommitAuthor(new UUID(0, 44), "Importer"), Instant.EPOCH);

        assertArrayEquals(
                png,
                new VersionPreviewRepository(targetRepository)
                        .load(result.commit()).orElseThrow());
    }

    private CommitId packageWithSection(
            Path repository, String state, String message) throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repository);
        var section = objects.write(section(state));
        var tree = new MerkleTreeEditor(objects).update(
                Optional.empty(), Map.of(new SectionKey(0, 0, 0), section));
        return new CommitRepository(repository).write(new Commit(
                tree, List.of(), new CommitAuthor(new UUID(0, 20), "Source"),
                message, Instant.EPOCH, new UUID(0, 19), Optional.empty(),
                CommitKind.MANUAL,
                new CommitStatistics(1, 0, SectionBlob.BLOCK_COUNT, 0)));
    }

    private static long objectFileCount(Path repository) throws Exception {
        try (var files = java.nio.file.Files.walk(repository.resolve("objects"))) {
            return files.filter(java.nio.file.Files::isRegularFile).count();
        }
    }

    private static SectionBlob airSection() {
        return section("minecraft:air");
    }

    private static SectionBlob section(String state) {
        return new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, state)), Map.of());
    }
}
