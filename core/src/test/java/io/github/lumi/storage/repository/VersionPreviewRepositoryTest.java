package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionPreviewRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void atomicallyStoresThumbnailByTraversalSafeCommitId() throws Exception {
        var repository = new VersionPreviewRepository(tempDir);
        CommitId commit = new CommitId(new ObjectId("01".repeat(32)));
        byte[] png = {1, 2, 3};

        repository.save(commit, png);

        assertArrayEquals(png, repository.load(commit).orElseThrow());
        assertTrue(repository.load(
                new CommitId(new ObjectId("02".repeat(32)))).isEmpty());
    }

    @Test
    void rejectsEmptyAndUnboundedPayloads() {
        var repository = new VersionPreviewRepository(tempDir);
        CommitId commit = new CommitId(new ObjectId("03".repeat(32)));

        assertThrows(IllegalArgumentException.class,
                () -> repository.save(commit, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> repository.save(commit, new byte[4 * 1024 * 1024 + 1]));
    }
}
