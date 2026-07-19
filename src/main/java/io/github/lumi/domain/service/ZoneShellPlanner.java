package io.github.lumi.domain.service;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.ZoneShellFace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Removes internal cell walls and merges coplanar exposed zone faces. */
public final class ZoneShellPlanner {
    private static final int CELL_SIZE = 16;

    public List<ZoneShellFace> plan(Collection<SectionKey> cells) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        Set<SectionKey> occupied = new HashSet<>(cells);
        return plan(occupied, occupied);
    }

    public List<ZoneShellFace> plan(
            Collection<SectionKey> occupiedCells,
            Collection<SectionKey> visibleCells) {
        if (occupiedCells == null || occupiedCells.isEmpty()
                || visibleCells == null || visibleCells.isEmpty()) {
            return List.of();
        }
        Set<SectionKey> occupied = new HashSet<>(occupiedCells);
        Set<UnitFace> exposed = new TreeSet<>(Comparator
                .comparing(UnitFace::side)
                .thenComparingInt(UnitFace::plane)
                .thenComparingInt(UnitFace::a)
                .thenComparingInt(UnitFace::b));
        for (SectionKey cell : visibleCells) {
            if (occupied.contains(cell)) {
                collect(cell, occupied, exposed);
            }
        }
        return merge(exposed);
    }

    private void collect(
            SectionKey cell,
            Set<SectionKey> occupied,
            Set<UnitFace> exposed) {
        int x = cell.chunkX();
        int y = cell.sectionY();
        int z = cell.chunkZ();
        addIfExposed(exposed, occupied, cell,
                ZoneShellFace.Side.WEST, x, y, z, -1, 0, 0);
        addIfExposed(exposed, occupied, cell,
                ZoneShellFace.Side.EAST, x + 1, y, z, 1, 0, 0);
        addIfExposed(exposed, occupied, cell,
                ZoneShellFace.Side.DOWN, y, x, z, 0, -1, 0);
        addIfExposed(exposed, occupied, cell,
                ZoneShellFace.Side.UP, y + 1, x, z, 0, 1, 0);
        addIfExposed(exposed, occupied, cell,
                ZoneShellFace.Side.NORTH, z, x, y, 0, 0, -1);
        addIfExposed(exposed, occupied, cell,
                ZoneShellFace.Side.SOUTH, z + 1, x, y, 0, 0, 1);
    }

    private void addIfExposed(
            Set<UnitFace> exposed,
            Set<SectionKey> occupied,
            SectionKey cell,
            ZoneShellFace.Side side,
            int plane,
            int a,
            int b,
            int dx,
            int dy,
            int dz) {
        SectionKey neighbor = new SectionKey(
                cell.chunkX() + dx,
                cell.sectionY() + dy,
                cell.chunkZ() + dz);
        if (!occupied.contains(neighbor)) {
            exposed.add(new UnitFace(side, plane, a, b));
        }
    }

    private List<ZoneShellFace> merge(Set<UnitFace> exposed) {
        Set<UnitFace> remaining = new HashSet<>(exposed);
        List<ZoneShellFace> faces = new ArrayList<>();
        for (UnitFace unit : exposed) {
            if (!remaining.contains(unit)) {
                continue;
            }
            FaceRect rectangle = expand(unit, remaining);
            remove(remaining, rectangle);
            faces.add(rectangle.toFace());
        }
        return List.copyOf(faces);
    }

    private FaceRect expand(UnitFace origin, Set<UnitFace> remaining) {
        int maxA = origin.a();
        while (remaining.contains(new UnitFace(
                origin.side(), origin.plane(), maxA + 1, origin.b()))) {
            maxA++;
        }
        int maxB = origin.b();
        while (containsRow(
                remaining, origin.side(), origin.plane(),
                origin.a(), maxA, maxB + 1)) {
            maxB++;
        }
        return new FaceRect(
                origin.side(), origin.plane(),
                origin.a(), maxA, origin.b(), maxB);
    }

    private boolean containsRow(
            Set<UnitFace> faces,
            ZoneShellFace.Side side,
            int plane,
            int minA,
            int maxA,
            int b) {
        for (int a = minA; a <= maxA; a++) {
            if (!faces.contains(new UnitFace(side, plane, a, b))) {
                return false;
            }
        }
        return true;
    }

    private void remove(Set<UnitFace> faces, FaceRect rectangle) {
        for (int a = rectangle.minA(); a <= rectangle.maxA(); a++) {
            for (int b = rectangle.minB(); b <= rectangle.maxB(); b++) {
                faces.remove(new UnitFace(
                        rectangle.side(), rectangle.plane(), a, b));
            }
        }
    }

    private record UnitFace(
            ZoneShellFace.Side side, int plane, int a, int b) { }

    private record FaceRect(
            ZoneShellFace.Side side,
            int plane,
            int minA,
            int maxA,
            int minB,
            int maxB) {
        private ZoneShellFace toFace() {
            return new ZoneShellFace(
                    side,
                    plane * CELL_SIZE,
                    minA * CELL_SIZE,
                    (maxA + 1) * CELL_SIZE,
                    minB * CELL_SIZE,
                    (maxB + 1) * CELL_SIZE);
        }
    }
}
