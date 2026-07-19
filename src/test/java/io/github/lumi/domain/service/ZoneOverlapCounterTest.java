package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneOverlapCounterTest {
    @Test
    void countsEachSharedCellOncePerZone() {
        UUID workspace = new UUID(0, 1);
        SectionKey shared = new SectionKey(-1, 2, 3);
        Zone first = zone(2, workspace, Set.of(
                shared, new SectionKey(0, 0, 0)));
        Zone second = zone(3, workspace, Set.of(shared));
        Zone third = zone(4, workspace, Set.of(shared));

        var counts = new ZoneOverlapCounter().count(
                List.of(first, second, third));

        assertEquals(1, counts.get(first.id()));
        assertEquals(1, counts.get(second.id()));
        assertEquals(1, counts.get(third.id()));
    }

    private static Zone zone(
            long id, UUID workspace, Set<SectionKey> cells) {
        return new Zone(
                new UUID(0, id), workspace, "Zone " + id,
                0xff336699, cells, Set.of());
    }
}
