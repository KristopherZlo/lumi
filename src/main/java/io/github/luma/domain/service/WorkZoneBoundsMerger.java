package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.WorkZoneCell;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class WorkZoneBoundsMerger {

    public List<Bounds3i> merge(Collection<WorkZoneCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        Set<WorkZoneCell> remaining = new HashSet<>(cells);
        List<Bounds3i> bounds = new ArrayList<>();
        for (WorkZoneCell cell : new TreeSet<>(cells)) {
            if (!remaining.contains(cell)) {
                continue;
            }
            Prism prism = this.expand(cell, remaining);
            this.remove(remaining, prism);
            bounds.add(this.bounds(prism));
        }
        return List.copyOf(bounds);
    }

    private Prism expand(WorkZoneCell origin, Set<WorkZoneCell> remaining) {
        int maxX = origin.x();
        while (remaining.contains(new WorkZoneCell(maxX + 1, origin.y(), origin.z()))) {
            maxX++;
        }
        int maxZ = origin.z();
        while (this.containsLayer(remaining, origin.x(), maxX, origin.y(), maxZ + 1)) {
            maxZ++;
        }
        int maxY = origin.y();
        while (this.containsVolume(remaining, origin.x(), maxX, maxY + 1, origin.z(), maxZ)) {
            maxY++;
        }
        return new Prism(origin.x(), maxX, origin.y(), maxY, origin.z(), maxZ);
    }

    private boolean containsLayer(Set<WorkZoneCell> cells, int minX, int maxX, int y, int z) {
        for (int x = minX; x <= maxX; x++) {
            if (!cells.contains(new WorkZoneCell(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private boolean containsVolume(Set<WorkZoneCell> cells, int minX, int maxX, int y, int minZ, int maxZ) {
        for (int z = minZ; z <= maxZ; z++) {
            if (!this.containsLayer(cells, minX, maxX, y, z)) {
                return false;
            }
        }
        return true;
    }

    private void remove(Set<WorkZoneCell> cells, Prism prism) {
        for (int x = prism.minX; x <= prism.maxX; x++) {
            for (int y = prism.minY; y <= prism.maxY; y++) {
                for (int z = prism.minZ; z <= prism.maxZ; z++) {
                    cells.remove(new WorkZoneCell(x, y, z));
                }
            }
        }
    }

    private Bounds3i bounds(Prism prism) {
        return new Bounds3i(
                new BlockPoint(
                        prism.minX * WorkZoneCell.SIZE,
                        prism.minY * WorkZoneCell.SIZE,
                        prism.minZ * WorkZoneCell.SIZE
                ),
                new BlockPoint(
                        (prism.maxX + 1) * WorkZoneCell.SIZE - 1,
                        (prism.maxY + 1) * WorkZoneCell.SIZE - 1,
                        (prism.maxZ + 1) * WorkZoneCell.SIZE - 1
                )
        );
    }

    private record Prism(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }
}
