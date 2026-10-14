package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitTombstone;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TombstoneRepositoryTest {
    @TempDir Path repositoryRoot;

    @Test
    void createsListsAndExplicitlyCleansOneTombstone() throws Exception {
        TombstoneRepository repository = new TombstoneRepository(repositoryRoot);
        CommitId commit = id('a');
        CommitTombstone tombstone = new CommitTombstone(
                commit, new CommitAuthor(new UUID(0, 7), "Builder"),
                Instant.parse("2026-07-16T10:15:30.123456789Z"));

        assertEquals(tombstone, repository.create(tombstone));
        assertEquals(tombstone, repository.create(tombstone));
        assertEquals(java.util.List.of(tombstone), repository.list());
        assertTrue(repository.contains(commit));

        repository.delete(commit);

        assertEquals(false, repository.contains(commit));
    }

    @Test
    void rejectsCorruptTombstone() throws Exception {
        TombstoneRepository repository = new TombstoneRepository(repositoryRoot);
        CommitId commit = id('b');
        repository.create(new CommitTombstone(
                commit, new CommitAuthor(new UUID(0, 7), "Builder"), Instant.EPOCH));
        Path file;
        try (var files = Files.walk(repositoryRoot.resolve("tombstones"))) {
            file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.write(file, new byte[] {1, 2, 3});

        assertThrows(IOException.class, repository::list);
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
