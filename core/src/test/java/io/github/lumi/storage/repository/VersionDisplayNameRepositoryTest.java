package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionDisplayName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionDisplayNameRepositoryTest {
    @TempDir Path repositoryRoot;

    @Test
    void atomicallyReplacesDisplayNameByCommitId() throws Exception {
        VersionDisplayNameRepository repository =
                new VersionDisplayNameRepository(repositoryRoot);
        CommitId commit = id('a');

        assertEquals(Optional.empty(), repository.read(commit));
        repository.replace(commit, new VersionDisplayName("Clock tower"));

        assertEquals(Optional.of(new VersionDisplayName("Clock tower")),
                repository.read(commit));
    }

    @Test
    void rejectsCorruptOrMismatchedSidecars() throws Exception {
        VersionDisplayNameRepository repository =
                new VersionDisplayNameRepository(repositoryRoot);
        CommitId first = id('b');
        CommitId second = id('c');
        repository.replace(first, new VersionDisplayName("Keep"));
        Files.createDirectories(path(second).getParent());
        Files.copy(path(first), path(second));

        assertThrows(IOException.class, () -> repository.read(second));
        Files.write(path(first), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> repository.read(first));
    }

    private Path path(CommitId commit) {
        return repositoryRoot.resolve("names").resolve(commit.hex() + ".name");
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
