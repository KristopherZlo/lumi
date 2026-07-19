package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.storage.repository.ZoneRepository;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionZoneOverlayQueriesTest {
    @TempDir Path repository;

    @Test
    void previewsAnEmptyActiveZoneAtThePlayersCell() throws Exception {
        UUID workspace = new UUID(0, 1);
        UUID actor = new UUID(0, 2);
        ZoneService zones = new ZoneService(new ZoneRepository(repository));
        zones.createActive(new UUID(0, 3), workspace, "Draft", actor);
        var queries = new DimensionZoneOverlayQueries(
                Runnable::run, zones, () -> workspace);

        var result = queries.query(
                actor, new SectionKey(-2, 4, 5), false).join();

        assertEquals(1, result.zones().size());
        assertEquals(6, result.zones().getFirst().faces().size());
        assertTrue(result.zones().getFirst().active());
        assertFalse(result.zones().getFirst().entered());
    }

    @Test
    void allModeMarksTheZoneContainingThePlayer() throws Exception {
        UUID workspace = new UUID(0, 1);
        UUID actor = new UUID(0, 2);
        SectionKey center = new SectionKey(1, 2, 3);
        ZoneService zones = new ZoneService(new ZoneRepository(repository));
        zones.create(
                new UUID(0, 3), workspace, "Entered", 0xff112233,
                Set.of(center));
        var queries = new DimensionZoneOverlayQueries(
                Runnable::run, zones, () -> workspace);

        var result = queries.query(actor, center, true).join();

        assertEquals(1, result.zones().size());
        assertTrue(result.zones().getFirst().entered());
        assertFalse(result.zones().getFirst().active());
    }
}
