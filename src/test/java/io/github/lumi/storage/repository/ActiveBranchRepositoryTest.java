package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.ActiveBranch;
import io.github.lumi.domain.model.BranchName;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveBranchRepositoryTest {
    @TempDir Path repositoryRoot;

    @Test
    void createsAndCompareAndSetsDurableActiveBranch() throws Exception {
        var repository = new ActiveBranchRepository(repositoryRoot);
        var main = repository.create(new BranchName("main"));

        var switched = repository.compareAndSet(
                main, new BranchName("redstone-test"));

        assertEquals(new ActiveBranch(new BranchName("redstone-test"), 1), switched);
        assertEquals(switched, new ActiveBranchRepository(repositoryRoot).read().orElseThrow());
        assertThrows(RefConflictException.class, () -> repository.compareAndSet(
                main, new BranchName("other")));
    }

    @Test
    void refusesToOverwriteAnExistingPointer() throws Exception {
        var repository = new ActiveBranchRepository(repositoryRoot);
        repository.create(new BranchName("main"));

        assertThrows(RefConflictException.class,
                () -> repository.create(new BranchName("other")));
    }
}
