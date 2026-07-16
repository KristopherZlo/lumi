package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.ActiveWorkspace;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveWorkspaceRepositoryTest {
    @TempDir Path repositoryRoot;

    @Test
    void compareAndSetsRevisionedWorkspaceSelection() throws Exception {
        ActiveWorkspaceRepository repository = new ActiveWorkspaceRepository(repositoryRoot);
        UUID first = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("20000000-0000-0000-0000-000000000002");
        ActiveWorkspace created = repository.create(first);

        ActiveWorkspace selected = repository.compareAndSet(created, second);

        assertEquals(new ActiveWorkspace(second, 1), selected);
        assertEquals(selected, new ActiveWorkspaceRepository(repositoryRoot)
                .read().orElseThrow());
        assertThrows(RefConflictException.class,
                () -> repository.compareAndSet(created, UUID.randomUUID()));
    }
}
