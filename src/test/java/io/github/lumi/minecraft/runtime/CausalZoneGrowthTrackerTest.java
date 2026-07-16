package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.storage.repository.ZoneRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CausalZoneGrowthTrackerTest {
    @TempDir Path repository;

    @Test
    void performsNoZoneIoUntilBufferedCellsAreFlushed() throws Exception {
        ZoneService zones = new ZoneService(new ZoneRepository(repository));
        UUID workspace = new UUID(0, 1);
        UUID zone = new UUID(0, 2);
        UUID actor = new UUID(0, 3);
        zones.create(zone, workspace, "Clock", 0, Set.of());
        zones.setActorActive(workspace, zone, actor, true);
        var failures = new ArrayList<Throwable>();
        CausalZoneGrowthTracker tracker = new CausalZoneGrowthTracker(
                zones, Runnable::run, failures::add);
        SectionKey first = new SectionKey(1, 2, 3);
        SectionKey second = new SectionKey(4, 5, 6);

        tracker.record(workspace, actor, first);
        tracker.record(workspace, actor, second);
        assertEquals(Set.of(), zones.require(workspace, zone).cells());

        tracker.flush();

        assertEquals(Set.of(first, second), zones.require(workspace, zone).cells());
        assertEquals(java.util.List.of(), failures);
    }
}
