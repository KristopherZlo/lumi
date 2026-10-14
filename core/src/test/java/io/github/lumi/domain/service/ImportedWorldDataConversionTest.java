package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.domain.model.*;
import io.github.lumi.storage.repository.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportedWorldDataConversionTest {
    @TempDir Path directory;

    @Test
    void oldPackageEquivalentToTheTargetWorldProducesNoFalseCompareChanges() throws Exception {
        Path source = directory.resolve("old"), target = directory.resolve("new");
        var original = create(source, WorldDataVersion.LEGACY_12111);
        var version = new WorldDataVersion(4786);
        var base = create(target, version);
        Path archive = directory.resolve("old.lumi");
        var inspection = new ImportExportService("minecraft:overworld", source).export(original.commit(), archive, false);
        var imports = new ImportExportService("minecraft:overworld", target,
                new WorldPayloadConversionService(version, HistoryGenerationConversionServiceTest.adapter()));
        var result = imports.importPackage(archive, inspection, base, new BranchName("imported"), author(), Instant.EPOCH);
        var difference = new CompareService(new WorldObjectRepository(target), new CommitRepository(target), new OriginStore(target))
                .compare(base.commit(), result.commit());
        assertTrue(difference.sections().isEmpty());
        assertTrue(difference.entities().isEmpty());
        var leaves = new WorldObjectGraph(new WorldObjectRepository(target))
                .scan(new CommitRepository(target).read(result.commit()).tree()).leaves();
        assertEquals(version, new WorldObjectRepository(target).readSection(leaves.get(new SectionKey(0, 0, 0))).dataVersion());
        assertEquals(version, new WorldObjectRepository(target).readEntities(leaves.get(new EntityChunkKey(0, 0))).dataVersion());
    }

    @Test
    void conversionRefusalAndFuturePackagesNeverPublishABranch() throws Exception {
        Path source = directory.resolve("future"), target = directory.resolve("legacy");
        var original = create(source, new WorldDataVersion(4786));
        var base = create(target, WorldDataVersion.LEGACY_12111);
        Path archive = directory.resolve("future.lumi");
        var inspection = new ImportExportService("minecraft:overworld", source).export(original.commit(), archive, false);
        var legacy = new ImportExportService("minecraft:overworld", target);
        assertThrows(IOException.class, () -> legacy.importPackage(archive, inspection, base,
                new BranchName("future"), author(), Instant.EPOCH));
        var refusing = new WorldPayloadConverter() {
            public SectionBlob convert(SectionBlob value, WorldDataVersion version) throws IOException {
                throw new IOException("Missing target registry content");
            }
            public EntityChunkBlob convert(EntityChunkBlob value, WorldDataVersion version) throws IOException {
                throw new IOException("Missing target registry content");
            }
        };
        var imports = new ImportExportService("minecraft:overworld", target,
                new WorldPayloadConversionService(new WorldDataVersion(4786), refusing));
        assertThrows(IOException.class, () -> imports.importPackage(archive, inspection, base,
                new BranchName("invalid"), author(), Instant.EPOCH));
        assertEquals(List.of(base), new BranchRefRepository(target).list());
    }

    private static BranchRef create(Path root, WorldDataVersion version) throws IOException {
        var objects = new WorldObjectRepository(root);
        var block = objects.write(SectionBlob.fromPalette(List.of("minecraft:stone"), new short[4096], Map.of(), version));
        var entity = objects.write(new EntityChunkBlob(List.of(), version));
        var tree = new MerkleTreeEditor(objects).update(Optional.empty(), Map.of(new SectionKey(0, 0, 0), block,
                new EntityChunkKey(0, 0), entity));
        var commit = new CommitRepository(root).write(new Commit(tree, List.of(), author(), "Test", Instant.EPOCH,
                new UUID(0, 2), Optional.empty(), CommitKind.MANUAL, new CommitStatistics(1, 1, 4096, 0)));
        return new BranchRefRepository(root).create(new BranchName("main"), commit);
    }

    private static CommitAuthor author() { return new CommitAuthor(new UUID(0, 1), "Builder"); }
}
