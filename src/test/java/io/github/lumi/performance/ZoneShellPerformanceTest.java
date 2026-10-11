package io.github.lumi.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.ZoneShellPlanner;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("performance")
class ZoneShellPerformanceTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 64})
    void measuresBoundedOverlappingZonePreparation(int zones) throws Exception {
        var selected = new HashSet<SectionKey>();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) selected.add(new SectionKey(x, 0, z));
        Set<SectionKey> cells = Set.copyOf(selected);
        var planner = new ZoneShellPlanner();
        var before = new PerformanceSamples("zone-shell-independent-" + zones, zones * cells.size());
        var after = new PerformanceSamples("zone-shell-owned-" + zones, zones * cells.size());
        PerformanceSamples.Work original = () -> {
            long faces = 0;
            for (int zone = 0; zone < zones; zone++) faces += planner.plan(cells, cells).size();
            assertEquals(6L * zones, faces);
            return faces;
        };
        PerformanceSamples.Work owned = () -> {
            long faces = 0;
            var session = planner.session();
            for (int zone = 0; zone < zones; zone++) faces += session.plan(cells, cells).size();
            assertEquals(6L, faces);
            return faces;
        };
        for (int sample = -10; sample < 9; sample++) {
            if (sample < 0) { original.run(); owned.run(); }
            else if (sample % 2 == 0) { before.measure(original); after.measure(owned); }
            else { after.measure(owned); before.measure(original); }
        }
        before.write(); after.write();
        if (before.medianAllocatedBytes() >= 0) {
            assertTrue(after.medianAllocatedBytes() < before.medianAllocatedBytes(),
                    "Shell preparation must allocate less for both one zone and overlapping zones");
        }
    }
}
