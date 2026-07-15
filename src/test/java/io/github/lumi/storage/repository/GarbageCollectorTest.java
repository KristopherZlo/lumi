package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.object.ObjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
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

        GarbageCollectionResult result = new GarbageCollector(repositoryRoot)
                .collect(Set.of(), now.minus(Duration.ofHours(24)));

        assertEquals(1, result.deletedObjects());
        assertArrayEquals(new byte[] {1}, rawObjects.read(livePayload));
        assertArrayEquals(new byte[] {2}, rawObjects.read(originPayload));
        assertArrayEquals(new byte[] {4}, rawObjects.read(freshOrphan));
        assertThrows(NoSuchFileException.class, () -> rawObjects.read(oldOrphan));
    }

    private void makeObjectsOld(Instant timestamp) throws IOException {
        try (var files = Files.walk(repositoryRoot.resolve("objects"))) {
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
