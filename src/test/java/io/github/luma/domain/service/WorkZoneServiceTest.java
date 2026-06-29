package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.WorkZoneRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkZoneServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void createZoneSelectsItAndUsesFirstMinecraftColor() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        WorkZoneService service = new WorkZoneService();

        WorkZone zone = service.createZone(layout, "project-1", "North Bridge", "Max", NOW);
        WorkZoneState state = new WorkZoneRepository().load(layout);

        assertEquals("North Bridge", zone.name());
        assertEquals(0xF9FFFE, zone.color());
        assertEquals(zone.id(), state.activeZoneId("Max"));
        assertTrue(zone.cells().isEmpty());
    }

    @Test
    void touchBlockAddsActiveZoneCellOnlyOnce() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        WorkZoneService service = new WorkZoneService();
        WorkZone zone = service.createZone(layout, "project-1", "Gate", "Kate", NOW);

        service.touchBlock(layout, "Kate", new BlockPoint(17, 64, -1), NOW.plusSeconds(1));
        service.touchBlock(layout, "Kate", new BlockPoint(18, 70, -2), NOW.plusSeconds(2));

        WorkZone saved = new WorkZoneRepository().load(layout).zones().getFirst();
        assertEquals(zone.id(), saved.id());
        assertEquals(java.util.List.of(new WorkZoneCell(1, 4, -1)), saved.cells());
    }

    @Test
    void touchBlockDoesNothingWithoutActiveZone() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        WorkZoneService service = new WorkZoneService();

        assertTrue(service.touchBlock(layout, "Max", new BlockPoint(0, 64, 0), NOW).isEmpty());
        assertTrue(new WorkZoneRepository().load(layout).zones().isEmpty());
    }

    @Test
    void addAndRemoveCellsUpdatesActiveZone() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        WorkZoneService service = new WorkZoneService();
        WorkZone zone = service.createZone(layout, "project-1", "Gate", "Kate", NOW);

        service.addCells(layout, "Kate", List.of(
                new WorkZoneCell(1, 4, -1),
                new WorkZoneCell(2, 4, -1)
        ), NOW.plusSeconds(1));
        service.removeCells(layout, "Kate", List.of(new WorkZoneCell(1, 4, -1)), NOW.plusSeconds(2));

        WorkZone saved = new WorkZoneRepository().load(layout).zones().getFirst();
        assertEquals(zone.id(), saved.id());
        assertEquals(List.of(new WorkZoneCell(2, 4, -1)), saved.cells());
    }

    @Test
    void addCellsToZoneUpdatesRequestedZoneWithoutActorSelection() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        WorkZoneService service = new WorkZoneService();
        WorkZone zone = service.createZone(layout, "project-1", "Gate", "Kate", NOW);
        service.selectZone(layout, "Kate", "");

        service.addCellsToZone(layout, zone.id(), List.of(new WorkZoneCell(2, 4, 3)), NOW.plusSeconds(1));

        WorkZone saved = new WorkZoneRepository().load(layout).zones().getFirst();
        assertEquals(List.of(new WorkZoneCell(2, 4, 3)), saved.cells());
        assertTrue(new WorkZoneRepository().load(layout).activeZoneId("Kate").isBlank());
    }

    @Test
    void deleteZoneRemovesZoneAndActiveSelectionsOnly() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        WorkZoneService service = new WorkZoneService();
        WorkZone zone = service.createZone(layout, "project-1", "Gate", "Kate", NOW);
        service.selectZone(layout, "Max", zone.id());

        service.deleteZone(layout, zone.id());

        WorkZoneState saved = new WorkZoneRepository().load(layout);
        assertTrue(saved.zones().isEmpty());
        assertTrue(saved.activeZoneId("Kate").isBlank());
        assertTrue(saved.activeZoneId("Max").isBlank());
    }

    @Test
    void loadQuarantinesMalformedZoneFile() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Castle");
        Files.createDirectories(layout.root());
        Files.writeString(layout.workZonesFile(), "{bad-json", StandardCharsets.UTF_8);

        WorkZoneState state = new WorkZoneService().load(layout);

        assertTrue(state.zones().isEmpty());
        assertFalse(Files.exists(layout.workZonesFile()));
        try (var files = Files.list(layout.root())) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().startsWith("work-zones.json.corrupt-")));
        }
    }
}
