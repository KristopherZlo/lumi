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

class HistoryGenerationConversionServiceTest {
    @TempDir Path root;

    @Test
    void convertsEveryRootAndPendingOriginBeforeOnePointerPublication() throws Exception {
        Fixture fixture = fixture(root);
        var repository = new HistoryGenerationRepository(root);
        var phases = new java.util.HashSet<HistoryGenerationConversionService.Phase>();
        var converted = new HistoryGenerationConversionService(root).convert(new WorldDataVersion(4786), adapter(),
                progress -> phases.add(progress.phase()));
        assertEquals(converted, repository.active());
        assertTrue(repository.pending().isEmpty());
        assertEquals(java.util.Set.of(HistoryGenerationConversionService.Phase.values()), phases);
        Path target = repository.directory(converted);
        assertEquals(fixture.main().revision(), new BranchRefRepository(target).read(fixture.main().name()).orElseThrow().revision());
        assertEquals(2, new BranchRefRepository(target).list().size());
        assertEquals(1, new TombstoneRepository(target).list().size());
        var origin = new OriginStore(target).read(fixture.pendingKey()).orElseThrow();
        assertEquals(new WorldDataVersion(4786), new WorldObjectRepository(target).readSection(origin).dataVersion());
        assertEquals(new WorkingIndexRepository(root).readState(), new WorkingIndexRepository(target).readState());
        assertEquals(fixture.main(), new BranchRefRepository(root).read(fixture.main().name()).orElseThrow());
        assertEquals(converted, new HistoryGenerationConversionService(root).convert(new WorldDataVersion(4786), adapter(), ignored -> { }));
        assertThrows(IOException.class, () -> new HistoryGenerationConversionService(root)
                .convert(WorldDataVersion.LEGACY_12111, adapter(), ignored -> { }));
    }

    @Test
    void changedSourceMetadataDuringVerificationRefusesPublication() throws Exception {
        Fixture fixture = fixture(root);
        assertThrows(IOException.class, () -> new HistoryGenerationConversionService(root)
                .convert(new WorldDataVersion(4786), adapter(), progress -> {
                    if (progress.phase() == HistoryGenerationConversionService.Phase.METADATA) {
                        try {
                            new WorkingIndexRepository(root).write(WorkingIndexSnapshot.empty());
                        } catch (IOException failed) { throw new java.io.UncheckedIOException(failed); }
                    }
                }));
        assertEquals(HistoryGeneration.LEGACY, new HistoryGenerationRepository(root).active());
        assertEquals(fixture.main(), new BranchRefRepository(root).read(fixture.main().name()).orElseThrow());
    }

    static Fixture fixture(Path root) throws IOException {
        var objects = new WorldObjectRepository(root);
        var section = objects.write(SectionBlob.fromPalette(List.of("minecraft:stone"), new short[4096], Map.of()));
        var tree = new MerkleTreeEditor(objects).update(Optional.empty(), Map.of(new SectionKey(0, 0, 0), section));
        var commits = new CommitRepository(root);
        var author = new CommitAuthor(new UUID(0, 1), "Builder");
        CommitId initial = commits.write(new Commit(tree, List.of(), author, "Initial", Instant.EPOCH,
                new UUID(0, 2), Optional.empty(), CommitKind.MANUAL, new CommitStatistics(1, 0, 4096, 0)));
        CommitId deleted = commits.write(new Commit(tree, List.of(initial), author, "Deleted", Instant.EPOCH,
                new UUID(0, 2), Optional.empty(), CommitKind.MANUAL, new CommitStatistics(1, 0, 4096, 0)));
        var refs = new BranchRefRepository(root);
        var main = refs.create(new BranchName("main"), initial);
        main = refs.compareAndSet(main, initial);
        refs.create(new BranchName("hidden/checkpoint"), initial);
        new ActiveBranchRepository(root).create(main.name());
        new TombstoneRepository(root).create(new CommitTombstone(deleted, author, Instant.EPOCH));
        var pendingKey = new SectionKey(90, 4, 80);
        var origin = objects.write(SectionBlob.fromPalette(List.of("minecraft:air"), new short[4096], Map.of()));
        new OriginStore(root).registerAll(Map.of(pendingKey, origin, new SectionKey(0, 0, 0), section));
        new WorkingIndexRepository(root).write(new WorkingIndexRepository.State(
                new WorkingIndexSnapshot(Map.of(pendingKey, 2L)), new WorkingIndexSnapshot(Map.of(pendingKey, 1L))));
        return new Fixture(main, pendingKey);
    }

    static WorldPayloadConverter adapter() {
        return new WorldPayloadConverter() {
            public SectionBlob convert(SectionBlob value, WorldDataVersion target) {
                return new SectionBlob(value.blockStates(), value.blockEntities(), target);
            }
            public EntityChunkBlob convert(EntityChunkBlob value, WorldDataVersion target) {
                return new EntityChunkBlob(value.entities(), target);
            }
        };
    }

    record Fixture(BranchRef main, SectionKey pendingKey) { }
}
