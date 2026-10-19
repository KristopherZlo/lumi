package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.CommitTombstone;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldActionCheckpoint;
import io.github.lumi.domain.model.WorldActionIndex;
import io.github.lumi.domain.model.WorldActionRecord;
import io.github.lumi.domain.model.WorldActionScope;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.storage.object.WorldActionIndexCodec;
import io.github.lumi.storage.object.ObjectStore;
import io.github.lumi.storage.RepositoryMaintenance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GarbageCollectorTest {
    @TempDir
    Path repositoryRoot;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void staleSweepPreservesOldObjectReusedByNewDurableOrigin(boolean packed) throws Exception {
        ObjectStore raw = new ObjectStore(repositoryRoot.resolve("objects"));
        byte[] payload = {42};
        ObjectId reused;
        if (packed) {
            try (var batch = raw.beginBatch()) {
                reused = batch.write(payload);
                batch.publish();
            }
        } else {
            reused = raw.write(payload);
        }
        ObjectId orphan = raw.write(new byte[] {43});
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        makeObjectsOld(cutoff.minus(Duration.ofDays(1)));
        var maintenance = RepositoryMaintenance.forRepository(repositoryRoot);
        var sweep = maintenance.beginSweep();
        var key = new SectionKey(4, 0, 4);
        try (var publication = new WorldObjectRepository(repositoryRoot).beginPublication()) {
            try (var batch = raw.beginBatch()) {
                assertEquals(reused, batch.write(payload));
                batch.publish();
            }
            new OriginStore(repositoryRoot).register(key, reused);
        }

        assertEquals(0, raw.deleteAll(Set.of(reused, orphan), sweep));
        assertArrayEquals(payload, new ObjectStore(repositoryRoot.resolve("objects"))
                .read(new OriginStore(repositoryRoot).read(key).orElseThrow()));
        var result = new GarbageCollector(repositoryRoot).collect(Set.of(), cutoff);
        assertEquals(1, result.deletedObjects());
        assertThrows(NoSuchFileException.class, () -> raw.read(orphan));
        assertArrayEquals(payload, raw.read(reused));
    }

    @Test
    void collectionDoesNotScanOrDeleteDuringAnUnfinishedPublication() throws Exception {
        ObjectStore raw = new ObjectStore(repositoryRoot.resolve("objects"));
        ObjectId id = raw.write(new byte[] {44});
        makeObjectsOld(Instant.EPOCH);
        try (var publication = new WorldObjectRepository(repositoryRoot).beginPublication()) {
            assertEquals(new GarbageCollectionResult(0, 0, 0),
                    new GarbageCollector(repositoryRoot).collect(Set.of(), Instant.now()));
            assertArrayEquals(new byte[] {44}, raw.read(id));
        }
        assertEquals(1, new GarbageCollector(repositoryRoot)
                .collect(Set.of(), Instant.now()).deletedObjects());
    }

    @Test
    void removesOnlyOldUnreachableData() throws IOException {
        ObjectStore rawObjects = new ObjectStore(repositoryRoot.resolve("objects"));
        ObjectId livePayload = rawObjects.write(new byte[] {1});
        ObjectId originPayload = rawObjects.write(new byte[] {2});
        ObjectId oldOrphan = rawObjects.write(new byte[] {3});
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        ObjectId tree = new MerkleTreeEditor(objects).update(
                Optional.empty(), Map.of(new SectionKey(0, 0, 0), livePayload));
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var commitId = commits.write(commit(tree));
        new BranchRefRepository(repositoryRoot).create(new BranchName("main"), commitId);
        new OriginStore(repositoryRoot).register(new SectionKey(9, 0, 9), originPayload);
        Instant now = Instant.parse("2026-07-15T12:00:00Z");
        makeObjectsOld(now.minus(Duration.ofDays(2)));
        ObjectId freshOrphan = rawObjects.write(new byte[] {4});
        ObjectId freshCommitPayload = rawObjects.write(new byte[] {5});
        ObjectId freshTree = new MerkleTreeEditor(objects).update(
                Optional.empty(), Map.of(new SectionKey(2, 0, 2), freshCommitPayload));
        commits.write(commit(freshTree));

        GarbageCollector collector = new GarbageCollector(repositoryRoot);
        GarbageCollectionInspection inspection = collector.inspect(
                Set.of(), now.minus(Duration.ofHours(24)));

        assertEquals(0, inspection.commits());
        assertEquals(1, inspection.objects());
        assertArrayEquals(new byte[] {3}, rawObjects.read(oldOrphan));

        GarbageCollectionResult result = collector.collect(
                Set.of(), now.minus(Duration.ofHours(24)));

        assertEquals(1, result.deletedObjects());
        assertArrayEquals(new byte[] {1}, rawObjects.read(livePayload));
        assertArrayEquals(new byte[] {2}, rawObjects.read(originPayload));
        assertArrayEquals(new byte[] {4}, rawObjects.read(freshOrphan));
        assertArrayEquals(new byte[] {5}, rawObjects.read(freshCommitPayload));
        assertThrows(NoSuchFileException.class, () -> rawObjects.read(oldOrphan));
        assertFalse(Files.exists(loosePath(livePayload)));
        assertFalse(Files.exists(loosePath(originPayload)));
        assertTrue(Files.exists(loosePath(freshOrphan)));
        assertTrue(Files.exists(loosePath(freshCommitPayload)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retainsRecoveryRootsUntilExplicitCleanup(boolean actionHistory) throws IOException {
        ObjectStore rawObjects = new ObjectStore(repositoryRoot.resolve("objects"));
        ObjectId payload = rawObjects.write(new byte[] {9});
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        ObjectId tree = new MerkleTreeEditor(objects).update(
                Optional.empty(), Map.of(new SectionKey(0, 0, 0), payload));
        CommitRepository commits = new CommitRepository(repositoryRoot);
        var commitId = commits.write(commit(tree));
        TombstoneRepository tombstones = new TombstoneRepository(repositoryRoot);
        UUID actor = new UUID(0, 7), action = new UUID(0, 8);
        var history = new WorldActionHistoryRepository(repositoryRoot, new WorldDataVersion(5000));
        var checkpoint = new WorldActionCheckpoint(new BranchName("main"), commitId, commitId,
                new BranchRef(new BranchName("hidden/session-undo/test"), commitId, 0), WorldActionScope.empty());
        var index = new WorldActionIndex(1, 1, 0, List.of(new WorldActionRecord.Metadata(action, actor, 1,
                action, 1, true, true, false, Optional.of(checkpoint))),
                Map.of(actor, new WorldActionIndex.Stacks(List.of(action), List.of())));
        var state = new WorldActionIndexCodec.State(UUID.randomUUID(), new WorldDataVersion(5000),
                UUID.randomUUID(), new BranchName("main"), index, Map.of(), Optional.empty());
        if (actionHistory) history.writeIndex(state);
        else tombstones.create(new CommitTombstone(
                commitId, new CommitAuthor(actor, "Builder"), Instant.EPOCH));
        Instant cutoff = Instant.parse("2026-07-15T12:00:00Z");
        makeOld(repositoryRoot.resolve("objects"), cutoff.minus(Duration.ofDays(2)));
        makeOld(repositoryRoot.resolve("commits"), cutoff.minus(Duration.ofDays(2)));

        new GarbageCollector(repositoryRoot).collect(Set.of(), cutoff);
        assertArrayEquals(new byte[] {9}, rawObjects.read(payload));

        if (actionHistory) history.writeIndex(new WorldActionIndexCodec.State(state.epoch(), state.dataVersion(),
                state.workspace(), state.branch(), new WorldActionIndex(2, 1, 1, List.of(), Map.of()), Map.of(), Optional.empty()));
        else tombstones.delete(commitId);
        new GarbageCollector(repositoryRoot).collect(Set.of(), cutoff);

        assertThrows(NoSuchFileException.class, () -> commits.read(commitId));
        assertThrows(NoSuchFileException.class, () -> rawObjects.read(payload));
    }

    @Test
    void combinesSmallLivePacksWithoutChangingTheirObjects() throws IOException {
        ObjectStore objects = new ObjectStore(repositoryRoot.resolve("objects"));
        OriginStore origins = new OriginStore(repositoryRoot);
        Map<ObjectId, byte[]> expected = new LinkedHashMap<>();
        for (int pack = 0; pack < 6; pack++) {
            try (ObjectStore.WriteBatch batch = objects.beginBatch()) {
                byte[] payload = ("live-pack-" + pack).getBytes(StandardCharsets.UTF_8);
                ObjectId id = batch.write(payload);
                batch.publish();
                expected.put(id, payload);
                origins.register(new SectionKey(pack, 0, 0), id);
            }
        }

        GarbageCollectionResult result = new GarbageCollector(repositoryRoot)
                .collect(Set.of(), Instant.EPOCH);

        assertEquals(6, result.compactedPacks());
        try (var files = Files.list(repositoryRoot.resolve("objects").resolve("packs"))) {
            assertEquals(1,
                    files.filter(path -> path.toString().endsWith(".pack")).count());
        }
        for (var entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), objects.read(entry.getKey()));
        }
    }

    private void makeObjectsOld(Instant timestamp) throws IOException {
        makeOld(repositoryRoot.resolve("objects"), timestamp);
    }

    private Path loosePath(ObjectId id) {
        return repositoryRoot.resolve("objects")
                .resolve(id.hex().substring(0, 2))
                .resolve(id.hex().substring(2) + ".lz4");
    }

    private static void makeOld(Path root, Instant timestamp) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(file, FileTime.from(timestamp));
            }
        }
    }

    private static Commit commit(ObjectId tree) {
        return new Commit(tree, List.of(), new CommitAuthor(UUID.randomUUID(), "Builder"), "Save",
                Instant.EPOCH, UUID.randomUUID(), Optional.empty(), CommitKind.MANUAL,
                new CommitStatistics(1, 0, 1, 0));
    }
}
