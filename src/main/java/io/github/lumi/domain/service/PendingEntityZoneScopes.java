package io.github.lumi.domain.service;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.Zone;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Attributes builder-dirty entity columns without pretending they are block changes. */
public final class PendingEntityZoneScopes {
    public Set<UUID> find(WorkingIndexSnapshot boundary, List<Zone> zones) {
        var entities = boundary.generations().keySet().stream()
                .filter(EntityChunkKey.class::isInstance).map(EntityChunkKey.class::cast).toList();
        if (entities.isEmpty()) return Set.of();
        var result = new HashSet<UUID>();
        for (Zone zone : zones) {
            var scope = new ZoneScope(zone);
            if (entities.stream().anyMatch(scope::includes)) result.add(zone.id());
        }
        return Set.copyOf(result);
    }
}
