package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.ZoneShellFace;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ZoneShellPlannerTest {
    private final ZoneShellPlanner planner = new ZoneShellPlanner();

    @Test
    void claimsPartialOverlapBeforeMergingAndRetainsExclusiveGeometry() {
        var first = Set.of(new SectionKey(0, 0, 0), new SectionKey(1, 0, 0));
        var second = Set.of(new SectionKey(1, 0, 0), new SectionKey(2, 0, 0));
        var ownership = planner.session();
        var units = new HashSet<String>();
        var expected = new HashSet<String>();
        for (var cells : List.of(first, second)) {
            planner.plan(cells).forEach(face -> expand(face, expected, false));
            ownership.plan(cells, cells).forEach(face -> expand(face, units, true));
        }
        assertEquals(expected, units);
        assertEquals(List.of(), ownership.plan(first, first));
        assertEquals(6, planner.session().plan(first, first).size());
    }

    private static void expand(ZoneShellFace face, Set<String> units, boolean unique) {
        for (int a = face.minA(); a < face.maxA(); a += 16) {
            for (int b = face.minB(); b < face.maxB(); b += 16) {
                boolean added = units.add(face.side() + ":" + face.plane() + ":" + a + ":" + b);
                if (unique) assertTrue(added, "Coincident shell unit drawn twice");
            }
        }
    }

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

    @Test
    void visibleSubsetDoesNotExposeWallsAgainstHiddenNeighbors() {
        SectionKey visible = new SectionKey(0, 0, 0);
        List<ZoneShellFace> faces = planner.plan(
                List.of(visible, new SectionKey(1, 0, 0)),
                List.of(visible));

        assertEquals(5, faces.size());
        assertFalse(faces.stream().anyMatch(face ->
                face.side() == ZoneShellFace.Side.EAST
                        && face.plane() == 16));
    }
}
