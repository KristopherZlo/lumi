package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.domain.model.ZoneShellSnapshot;
import io.github.lumi.domain.service.ZoneService;
import io.github.lumi.domain.service.ZoneShellPlanner;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Builds distance- and allocation-bounded zone shells off the server tick. */
final class DimensionZoneOverlayQueries {
    static final int MAX_VISIBLE_CELLS = 65_536;
    private static final int CELL_RADIUS = 32;
    private static final int MAX_ZONES = 64;
    private final Executor background;
    private final ZoneService zones;
    private final CurrentWorkspace currentWorkspace;
    private final ZoneShellPlanner shells = new ZoneShellPlanner();

    DimensionZoneOverlayQueries(
            Executor background,
            ZoneService zones,
            CurrentWorkspace currentWorkspace) {
        this.background = Objects.requireNonNull(background, "background");
        this.zones = Objects.requireNonNull(zones, "zones");
        this.currentWorkspace = Objects.requireNonNull(
                currentWorkspace, "currentWorkspace");
    }

    CompletableFuture<ZoneShellSnapshot> query(
            UUID actor, SectionKey center, boolean all) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(center, "center");
        return CompletableFuture.supplyAsync(() -> {
            try {
                UUID workspace = currentWorkspace.read();
                List<Zone> available = zones.list(workspace).stream()
                        .limit(MAX_ZONES).toList();
                Zone active = available.stream()
                        .filter(zone -> zone.activeActors().contains(actor))
                        .findFirst().orElse(null);
                Zone entered = active != null && active.cells().contains(center)
                        ? active : available.stream()
                                .filter(zone -> zone.cells().contains(center))
                                .findFirst().orElse(null);
                List<Zone> selected = all
                        ? available
                        : active != null ? List.of(active)
                        : entered != null ? List.of(entered) : List.of();
                int perZone = MAX_VISIBLE_CELLS
                        / Math.max(1, selected.size());
                List<ZoneShellSnapshot.ZoneShell> result = selected.stream()
                        .map(zone -> shell(
                                zone, center, perZone,
                                zone == active, zone == entered))
                        .toList();
                return new ZoneShellSnapshot(workspace, result);
            } catch (IOException failed) {
                throw new CompletionException(failed);
            }
        }, background);
    }

    private ZoneShellSnapshot.ZoneShell shell(
            Zone zone,
            SectionKey center,
            int maximum,
            boolean active,
            boolean entered) {
        Set<SectionKey> occupied = zone.cells().isEmpty() && active
                ? Set.of(center) : zone.cells();
        Set<SectionKey> visible = nearest(
                occupied, center, maximum);
        return new ZoneShellSnapshot.ZoneShell(
                zone.id(), zone.name(), zone.color(), zone.revision(),
                active, entered, shells.plan(occupied, visible));
    }

    private static Set<SectionKey> nearest(
            Set<SectionKey> cells, SectionKey center, int maximum) {
        Comparator<SectionKey> nearestFirst = Comparator
                .comparingLong((SectionKey cell) -> distance(cell, center))
                .thenComparingInt(SectionKey::chunkX)
                .thenComparingInt(SectionKey::sectionY)
                .thenComparingInt(SectionKey::chunkZ);
        PriorityQueue<SectionKey> selected =
                new PriorityQueue<>(nearestFirst.reversed());
        for (SectionKey cell : cells) {
            if (Math.abs((long) cell.chunkX() - center.chunkX()) > CELL_RADIUS
                    || Math.abs((long) cell.sectionY() - center.sectionY())
                    > CELL_RADIUS
                    || Math.abs((long) cell.chunkZ() - center.chunkZ())
                    > CELL_RADIUS) {
                continue;
            }
            selected.add(cell);
            if (selected.size() > maximum) {
                selected.remove();
            }
        }
        return Set.copyOf(selected);
    }

    private static long distance(SectionKey cell, SectionKey center) {
        long x = (long) cell.chunkX() - center.chunkX();
        long y = (long) cell.sectionY() - center.sectionY();
        long z = (long) cell.chunkZ() - center.chunkZ();
        return x * x + y * y + z * z;
    }

    @FunctionalInterface
    interface CurrentWorkspace {
        UUID read() throws IOException;
    }
}
