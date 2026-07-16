package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.repository.ZoneRepository;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZoneServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void growsOnlyForAnActorActiveInThatZone() throws Exception {
        ZoneService service = new ZoneService(new ZoneRepository(repositoryRoot));
        UUID workspace = new UUID(0, 1);
        UUID zoneId = new UUID(0, 2);
        UUID actor = new UUID(0, 3);
        SectionKey initial = new SectionKey(1, 2, 3);
        SectionKey grown = new SectionKey(4, 5, 6);
        service.create(zoneId, workspace, "Clock", 0xff336699, Set.of(initial));

        assertThrows(IllegalStateException.class,
                () -> service.growForActor(workspace, zoneId, actor, grown));
        service.setActorActive(workspace, zoneId, actor, true);
        service.growForActor(workspace, zoneId, actor, grown);

        assertEquals(Set.of(initial, grown), service.require(workspace, zoneId).cells());
    }

    @Test
    void scopeIncludesExactCellsAndTheirEntityColumns() throws Exception {
        ZoneService service = new ZoneService(new ZoneRepository(repositoryRoot));
        UUID workspace = new UUID(0, 1);
        var zone = service.create(new UUID(0, 2), workspace, "Clock", 0,
                Set.of(new SectionKey(1, 2, 3)));
        ZoneScope scope = new ZoneScope(zone);

        assertTrue(scope.includes(new SectionKey(1, 2, 3)));
        assertTrue(scope.includes(new EntityChunkKey(1, 3)));
        assertEquals(false, scope.includes(new SectionKey(1, 3, 3)));
        assertEquals(false, scope.includes(new EntityChunkKey(1, 4)));
    }
}
