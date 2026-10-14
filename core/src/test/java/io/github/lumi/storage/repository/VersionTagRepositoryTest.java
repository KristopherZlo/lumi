package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionTagRepositoryTest {
    @TempDir Path repositoryRoot;

    @Test
    void atomicallyReplacesTagsByTraversalSafeCommitId() throws Exception {
        VersionTagRepository repository = new VersionTagRepository(repositoryRoot);
        CommitId commit = id('a');

        assertEquals(VersionTags.empty(), repository.read(commit));

        repository.replace(commit, VersionTags.parse("#roof, castle"));
        assertEquals(
                VersionTags.parse("roof, castle"), repository.read(commit));

        repository.replace(commit, VersionTags.empty());
        assertEquals(VersionTags.empty(), repository.read(commit));
    }

    @Test
    void rejectsCorruptOrMismatchedSidecars() throws Exception {
        VersionTagRepository repository = new VersionTagRepository(repositoryRoot);
        CommitId first = id('b');
        CommitId second = id('c');
        repository.replace(first, VersionTags.parse("clock"));
        byte[] firstPayload = Files.readAllBytes(path(first));
        Files.createDirectories(path(second).getParent());
        Files.write(path(second), firstPayload);

        assertThrows(IOException.class, () -> repository.read(second));

        Files.write(path(first), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> repository.read(first));
    }

    private Path path(CommitId commit) {
        return repositoryRoot.resolve("tags").resolve(commit.hex() + ".tags");
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
