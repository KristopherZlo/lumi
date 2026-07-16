package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZoneRepositoryTest {
    @TempDir Path repositoryRoot;

    @Test
    void atomicallyCreatesReadsAndReplacesWorkspaceZone() throws Exception {
        ZoneRepository repository = new ZoneRepository(repositoryRoot);
        UUID workspace = new UUID(0, 1);
        Zone created = new Zone(
                new UUID(0, 2), workspace, "Input", 0xff22aa44,
                Set.of(new SectionKey(1, 2, 3)), Set.of(new UUID(0, 4)));

        repository.create(created);
        Zone updated = new Zone(
                created.id(), workspace, "Output", created.color(),
                Set.of(new SectionKey(4, 5, 6)), Set.of(), 1);
        repository.replace(created, updated);

        assertEquals(updated, repository.read(workspace, created.id()).orElseThrow());
        assertEquals(java.util.List.of(updated), repository.list(workspace));
        assertThrows(RefConflictException.class, () -> repository.replace(created, updated));
    }
}
