package io.github.lumi.domain.service;

import io.github.lumi.domain.model.Zone;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Counts each zone cell once when at least one other zone also contains it. */
public final class ZoneOverlapCounter {
    public Map<UUID, Integer> count(List<Zone> zones) {
        List<Zone> snapshot = List.copyOf(
                Objects.requireNonNull(zones, "zones"));
        Map<UUID, Integer> counts = new HashMap<>();
        for (Zone zone : snapshot) {
            int shared = 0;
            for (var cell : zone.cells()) {
                if (snapshot.stream().anyMatch(other ->
                        !other.id().equals(zone.id())
                                && other.cells().contains(cell))) {
                    shared++;
                }
            }
            counts.put(zone.id(), shared);
        }
        return Map.copyOf(counts);
    }
}
