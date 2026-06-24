package io.github.luma.domain.service;

import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneShellFace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class WorkZoneShellPlanner {

    public List<WorkZoneShellFace> plan(Collection<WorkZoneCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        Set<WorkZoneCell> occupied = new HashSet<>(cells);
        Set<UnitFace> exposed = new TreeSet<>();
        for (WorkZoneCell cell : occupied) {
            this.collect(cell, occupied, exposed);
        }
        return this.merge(exposed);
    }

    private void collect(WorkZoneCell cell, Set<WorkZoneCell> occupied, Set<UnitFace> exposed) {
        this.addIfExposed(exposed, occupied, cell, WorkZoneShellFace.Side.WEST, cell.x(), cell.y(), cell.z(), -1, 0, 0);
        this.addIfExposed(exposed, occupied, cell, WorkZoneShellFace.Side.EAST, cell.x() + 1, cell.y(), cell.z(), 1, 0, 0);
        this.addIfExposed(exposed, occupied, cell, WorkZoneShellFace.Side.DOWN, cell.y(), cell.x(), cell.z(), 0, -1, 0);
        this.addIfExposed(exposed, occupied, cell, WorkZoneShellFace.Side.UP, cell.y() + 1, cell.x(), cell.z(), 0, 1, 0);
        this.addIfExposed(exposed, occupied, cell, WorkZoneShellFace.Side.NORTH, cell.z(), cell.x(), cell.y(), 0, 0, -1);
        this.addIfExposed(exposed, occupied, cell, WorkZoneShellFace.Side.SOUTH, cell.z() + 1, cell.x(), cell.y(), 0, 0, 1);
    }

    private void addIfExposed(
            Set<UnitFace> exposed,
            Set<WorkZoneCell> occupied,
            WorkZoneCell cell,
            WorkZoneShellFace.Side side,
            int plane,
            int a,
            int b,
            int dx,
            int dy,
            int dz
    ) {
        WorkZoneCell neighbor = new WorkZoneCell(cell.x() + dx, cell.y() + dy, cell.z() + dz);
        if (!occupied.contains(neighbor)) {
            exposed.add(new UnitFace(side, plane, a, b));
        }
    }

    private List<WorkZoneShellFace> merge(Set<UnitFace> exposed) {
        Set<UnitFace> remaining = new HashSet<>(exposed);
        List<WorkZoneShellFace> faces = new ArrayList<>();
        for (UnitFace unit : exposed) {
            if (!remaining.contains(unit)) {
                continue;
            }
            FaceRect rect = this.expand(unit, remaining);
            this.remove(remaining, rect);
            faces.add(rect.toFace());
        }
        return List.copyOf(faces);
    }

    private FaceRect expand(UnitFace origin, Set<UnitFace> remaining) {
        int maxA = origin.a();
        while (remaining.contains(new UnitFace(origin.side(), origin.plane(), maxA + 1, origin.b()))) {
            maxA++;
        }
        int maxB = origin.b();
        while (this.containsRow(remaining, origin.side(), origin.plane(), origin.a(), maxA, maxB + 1)) {
            maxB++;
        }
        return new FaceRect(origin.side(), origin.plane(), origin.a(), maxA, origin.b(), maxB);
    }

    private boolean containsRow(
            Set<UnitFace> faces,
            WorkZoneShellFace.Side side,
            int plane,
            int minA,
            int maxA,
            int b
    ) {
        for (int a = minA; a <= maxA; a++) {
            if (!faces.contains(new UnitFace(side, plane, a, b))) {
                return false;
            }
        }
        return true;
    }

    private void remove(Set<UnitFace> faces, FaceRect rect) {
        for (int a = rect.minA(); a <= rect.maxA(); a++) {
            for (int b = rect.minB(); b <= rect.maxB(); b++) {
                faces.remove(new UnitFace(rect.side(), rect.plane(), a, b));
            }
        }
    }

    private record UnitFace(WorkZoneShellFace.Side side, int plane, int a, int b) implements Comparable<UnitFace> {

        @Override
        public int compareTo(UnitFace other) {
            int bySide = Integer.compare(this.side.ordinal(), other.side.ordinal());
            if (bySide != 0) {
                return bySide;
            }
            int byPlane = Integer.compare(this.plane, other.plane);
            if (byPlane != 0) {
                return byPlane;
            }
            int byA = Integer.compare(this.a, other.a);
            return byA != 0 ? byA : Integer.compare(this.b, other.b);
        }
    }

    private record FaceRect(
            WorkZoneShellFace.Side side,
            int plane,
            int minA,
            int maxA,
            int minB,
            int maxB
    ) {

        private WorkZoneShellFace toFace() {
            return new WorkZoneShellFace(
                    this.side,
                    this.plane * WorkZoneCell.SIZE,
                    this.minA * WorkZoneCell.SIZE,
                    (this.maxA + 1) * WorkZoneCell.SIZE,
                    this.minB * WorkZoneCell.SIZE,
                    (this.maxB + 1) * WorkZoneCell.SIZE
            );
        }
    }
}
