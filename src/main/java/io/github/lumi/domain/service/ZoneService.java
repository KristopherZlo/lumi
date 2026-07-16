package io.github.lumi.domain.service;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.storage.repository.ZoneRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owns explicit zone membership and actor-authorized causal growth. */
public final class ZoneService {
    private final ZoneRepository zones;

    public ZoneService(ZoneRepository zones) {
        this.zones = Objects.requireNonNull(zones, "zones");
    }

    public Zone create(
            UUID id,
            UUID workspaceId,
            String name,
            int color,
            Set<SectionKey> cells) throws IOException {
        return zones.create(new Zone(
                id, workspaceId, name, color, cells, Set.of()));
    }

    public Zone require(UUID workspaceId, UUID zoneId) throws IOException {
        return zones.read(workspaceId, zoneId).orElseThrow(
                () -> new IOException("Zone does not exist: " + zoneId));
    }

    public List<Zone> list(UUID workspaceId) throws IOException {
        return zones.list(workspaceId);
    }

    public synchronized Zone setActorActive(
            UUID workspaceId, UUID zoneId, UUID actor, boolean enabled) throws IOException {
        Objects.requireNonNull(actor, "actor");
        Zone current = require(workspaceId, zoneId);
        var actors = new HashSet<>(current.activeActors());
        if (!(enabled ? actors.add(actor) : actors.remove(actor))) {
            return current;
        }
        return zones.replace(current, copy(current, current.cells(), actors));
    }

    public synchronized Zone growForActor(
            UUID workspaceId, UUID zoneId, UUID actor, SectionKey cell) throws IOException {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(cell, "cell");
        Zone current = require(workspaceId, zoneId);
        if (!current.activeActors().contains(actor)) {
            throw new IllegalStateException("Actor is not active in zone: " + zoneId);
        }
        var cells = new HashSet<>(current.cells());
        if (!cells.add(cell)) {
            return current;
        }
        return zones.replace(current, copy(current, cells, current.activeActors()));
    }

    public synchronized int growActiveForActor(
            UUID workspaceId, UUID actor, Set<SectionKey> additions) throws IOException {
        Objects.requireNonNull(actor, "actor");
        Set<SectionKey> cellsToAdd = Set.copyOf(
                Objects.requireNonNull(additions, "additions"));
        if (cellsToAdd.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Zone current : list(workspaceId)) {
            if (!current.activeActors().contains(actor)) {
                continue;
            }
            var cells = new HashSet<>(current.cells());
            if (cells.addAll(cellsToAdd)) {
                zones.replace(current, copy(current, cells, current.activeActors()));
                changed++;
            }
        }
        return changed;
    }

    private static Zone copy(Zone source, Set<SectionKey> cells, Set<UUID> actors) {
        return new Zone(source.id(), source.workspaceId(), source.name(), source.color(),
                cells, actors, Math.addExact(source.revision(), 1));
    }
}
