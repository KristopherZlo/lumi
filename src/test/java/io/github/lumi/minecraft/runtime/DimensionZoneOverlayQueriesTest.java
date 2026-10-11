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
    void activeZoneOwnsCoincidentGeometryAndFocusedModeKeepsItsFullShell() throws Exception {
        UUID workspace = new UUID(0, 1);
        UUID actor = new UUID(0, 2);
        var center = new SectionKey(0, 0, 0);
        var zones = new ZoneService(new ZoneRepository(repository));
        UUID other = new UUID(0, 3);
        UUID active = new UUID(0, 9);
        zones.create(other, workspace, "Other", 1, Set.of(center));
        zones.create(active, workspace, "Active", 2, Set.of(center));
        zones.setActorActive(workspace, active, actor, true);
        var queries = new DimensionZoneOverlayQueries(Runnable::run, zones, () -> workspace);
        var all = queries.query(actor, center, true).join();
        assertEquals(2, all.zones().size());
        assertEquals(active, all.zones().getFirst().id());
        assertEquals(6, all.zones().getFirst().faces().size());
        assertEquals(0, all.zones().getLast().faces().size());
        assertEquals(6, queries.query(actor, center, false).join().zones().getFirst().faces().size());
        zones.setActorActive(workspace, active, actor, false);
        var unselected = queries.query(actor, center, true).join();
        assertEquals(other, unselected.zones().getFirst().id());
        assertTrue(unselected.zones().getFirst().entered());
        assertEquals(6, unselected.zones().getFirst().faces().size());
        assertEquals(0, unselected.zones().getLast().faces().size());
    }

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
