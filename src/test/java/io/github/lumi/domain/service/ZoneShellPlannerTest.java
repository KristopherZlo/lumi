package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.ZoneShellFace;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneShellPlannerTest {
    private final ZoneShellPlanner planner = new ZoneShellPlanner();

    @Test
    void mergesOuterFacesForAdjacentCells() {
        List<ZoneShellFace> faces = planner.plan(List.of(
                new SectionKey(0, 0, 0),
                new SectionKey(1, 0, 0)));

        assertEquals(6, faces.size());
        assertFalse(faces.stream().anyMatch(face ->
                face.side() == ZoneShellFace.Side.EAST
                        && face.plane() == 16));
        assertTrue(faces.contains(new ZoneShellFace(
                ZoneShellFace.Side.UP, 16, 0, 32, 0, 16)));
        assertTrue(faces.contains(new ZoneShellFace(
                ZoneShellFace.Side.NORTH, 0, 0, 32, 0, 16)));
    }

    @Test
    void handlesNegativeCellsAndKeepsConcaveOuterFaces() {
        List<ZoneShellFace> faces = planner.plan(List.of(
                new SectionKey(-1, 0, -1),
                new SectionKey(0, 0, -1),
                new SectionKey(-1, 0, 0)));

        assertEquals(10, faces.size());
        assertTrue(faces.contains(new ZoneShellFace(
                ZoneShellFace.Side.UP, 16, -16, 16, -16, 0)));
    }
}
