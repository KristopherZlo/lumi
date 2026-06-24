package io.github.luma.domain.service;

import io.github.luma.domain.model.WorkZoneCell;
import io.github.luma.domain.model.WorkZoneShellFace;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkZoneShellPlannerTest {

    private final WorkZoneShellPlanner planner = new WorkZoneShellPlanner();

    @Test
    void mergesOuterFacesForAdjacentCells() {
        List<WorkZoneShellFace> faces = this.planner.plan(List.of(
                new WorkZoneCell(0, 0, 0),
                new WorkZoneCell(1, 0, 0)
        ));

        assertEquals(6, faces.size());
        assertFalse(faces.stream().anyMatch(face -> face.side() == WorkZoneShellFace.Side.EAST && face.plane() == 16));
        assertTrue(faces.contains(new WorkZoneShellFace(WorkZoneShellFace.Side.UP, 16, 0, 32, 0, 16)));
        assertTrue(faces.contains(new WorkZoneShellFace(WorkZoneShellFace.Side.NORTH, 0, 0, 32, 0, 16)));
    }

    @Test
    void keepsConcaveOuterFacesWithoutInternalWalls() {
        List<WorkZoneShellFace> faces = this.planner.plan(List.of(
                new WorkZoneCell(0, 0, 0),
                new WorkZoneCell(1, 0, 0),
                new WorkZoneCell(0, 0, 1)
        ));

        assertEquals(10, faces.size());
        assertFalse(faces.stream().anyMatch(face -> face.side() == WorkZoneShellFace.Side.EAST
                && face.plane() == 16
                && face.minA() == 0
                && face.minB() == 0));
        assertTrue(faces.contains(new WorkZoneShellFace(WorkZoneShellFace.Side.EAST, 16, 0, 16, 16, 32)));
    }
}
