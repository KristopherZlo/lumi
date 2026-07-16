package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.WorldObjectRepository;
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

    private static SectionBlob airSection() {
        return new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }
}
