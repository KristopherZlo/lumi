package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.Workspace;
import io.github.lumi.domain.model.WorkspaceSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRepositoryTest {
    @TempDir Path repositoryRoot;

    @Test
    void createsListsAndAtomicallyUpdatesBoundedWorkspace() throws Exception {
        WorkspaceRepository repository = new WorkspaceRepository(repositoryRoot);
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        Workspace created = new Workspace(
                id, "Redstone lab", Optional.of(new BlockBox(-16, 0, -16, 31, 127, 31)),
                WorkspaceSettings.defaults());

        repository.create(created);
        Workspace updated = new Workspace(
                id, "Fast redstone lab", created.bounds(),
                new WorkspaceSettings(false, false, false, false, true));
        repository.replace(created, updated);

        assertEquals(Optional.of(updated), repository.read(id));
        assertEquals(List.of(updated), new WorkspaceRepository(repositoryRoot).list());
    }

    @Test
    void refusesToReplaceAStaleWorkspace() throws Exception {
        WorkspaceRepository repository = new WorkspaceRepository(repositoryRoot);
        Workspace created = new Workspace(
                UUID.randomUUID(), "Build", Optional.empty(), WorkspaceSettings.defaults());
        repository.create(created);
        Workspace stale = new Workspace(
                created.id(), "Stale", Optional.empty(), WorkspaceSettings.defaults());

        assertThrows(RefConflictException.class,
                () -> repository.replace(stale, created));
        assertThrows(RefConflictException.class, () -> repository.create(created));
    }
}
