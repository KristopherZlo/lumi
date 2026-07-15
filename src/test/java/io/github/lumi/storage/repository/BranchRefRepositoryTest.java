package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchRefRepositoryTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void createsAndAtomicallyAdvancesBranch() throws IOException {
        BranchRefRepository repository = new BranchRefRepository(repositoryRoot);
        BranchName name = new BranchName("feature/redstone lab");
        CommitId first = id("first");
        CommitId second = id("second");

        var created = repository.create(name, first);
        var advanced = repository.compareAndSet(created, second);

        assertEquals(0, created.revision());
        assertEquals(1, advanced.revision());
        assertEquals(advanced, new BranchRefRepository(repositoryRoot).read(name).orElseThrow());
    }

    @Test
    void staleWriterCannotOverwritePublishedHead() throws IOException {
        BranchRefRepository repository = new BranchRefRepository(repositoryRoot);
        var stale = repository.create(new BranchName("main"), id("first"));
        var current = repository.compareAndSet(stale, id("second"));

        assertThrows(RefConflictException.class, () -> repository.compareAndSet(stale, id("third")));
        assertEquals(current, repository.read(current.name()).orElseThrow());
    }

    @Test
    void refusesDuplicateBranchCreation() throws IOException {
        BranchRefRepository repository = new BranchRefRepository(repositoryRoot);
        BranchName name = new BranchName("main");
        repository.create(name, id("first"));

        assertThrows(RefConflictException.class, () -> repository.create(name, id("second")));
    }

    private static CommitId id(String value) {
        return CommitId.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
