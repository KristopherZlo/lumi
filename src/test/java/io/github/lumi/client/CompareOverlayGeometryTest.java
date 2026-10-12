package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockChange;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class CompareOverlayGeometryTest {
    private final CompareOverlayGeometry geometry = new CompareOverlayGeometry();

    @Test
    void keepsIndividualBlockEdgesAndRemovesOnlySameColorDuplicates() {
        assertCounts(List.of(block(0, 0, 0), block(1, 0, 0)), 10, 20);
        assertCounts(List.of(block(0, 0, 0),
                new BlockChange(1, 0, 0, BlockChange.Kind.REMOVED)), 10, 24);
        assertCounts(List.of(block(0, 0, 0), block(1, 1, 0)), 12, 23);
        assertCounts(List.of(block(0, 0, 0), block(1, 1, 1)), 12, 24);
    }

    @Test
    void preservesTheGridInsideDenseVolumes() {
        List<BlockChange> cube = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) cube.add(block(x, y, z));
            }
        }
        // Three orientations, three segments, four by four grid lines.
        assertCounts(cube, 54, 3 * 3 * 4 * 4);
    }

    @Test
    void removesSharedGeometryAcrossSectionsAndNegativeCoordinates() {
        var sections = geometry.prepare(List.of(block(-1, -1, -1), block(0, -1, -1)), () -> false);
        assertEquals(2, sections.size());
        assertEquals(-16, sections.getFirst().x());
        assertEquals(0, sections.getLast().x());
        assertCounts(List.of(block(-1, -1, -1), block(0, -1, -1)), 10, 20);
    }

    @Test
    void cancelsObsoletePreparation() {
        assertThrows(CancellationException.class,
                () -> geometry.prepare(List.of(block(0, 0, 0)), () -> true));
    }

    @Test
    void reportsBothPreparationPassesWithoutChangingGeometry() {
        List<Long> progress = new ArrayList<>();
        List<BlockChange> changes = new ArrayList<>();
        for (int x = 0; x < 2050; x++) changes.add(block(x, 0, 0));
        assertEquals(geometry.prepare(changes, () -> false),
                geometry.prepare(changes, () -> false, progress::add));
        assertEquals(4100, progress.getLast());
        assertTrue(progress.contains(2050L));
        assertTrue(progress.size() < 10, "Progress must not publish one update per block");
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    }

    @Test
    void matchesEveryColoredEdgeOfTheOriginalRendererAcrossAllAxes() {
        var random = new Random(710);
        List<BlockChange> changes = new ArrayList<>();
        for (int x = -18; x < 18; x++) {
            for (int y = -2; y < 2; y++) {
                for (int z = -18; z < 18; z++) {
                    if (random.nextBoolean()) changes.add(new BlockChange(x, y, z,
                            BlockChange.Kind.values()[random.nextInt(3)]));
                }
            }
        }
        Set<String> original = new HashSet<>();
        changes.forEach(block -> addEdges(original, block, 0xfff, false));
        Set<String> prepared = new HashSet<>();
        geometry.prepare(changes, () -> false).forEach(section -> section.blocks()
                .forEach(block -> addEdges(prepared, block.change(), block.edges(), true)));
        assertEquals(original, prepared);
    }

    private static void addEdges(Set<String> edges, BlockChange block, int mask, boolean unique) {
        for (int axis = 0; axis < 3; axis++) {
            for (int corner = 0; corner < 4; corner++) {
                if ((mask & (1 << (axis * 4 + corner))) == 0) continue;
                int[] start = {block.x(), block.y(), block.z()};
                start[(axis + 1) % 3] += corner & 1;
                start[(axis + 2) % 3] += corner >> 1;
                String edge = block.kind() + ":" + axis + ":"
                        + start[0] + ":" + start[1] + ":" + start[2];
                boolean added = edges.add(edge);
                if (unique) assertTrue(added, "Duplicate edge: " + edge);
            }
        }
    }

    private void assertCounts(List<BlockChange> changes, int faces, int edges) {
        var sections = geometry.prepare(changes, () -> false);
        assertEquals(changes.size(), sections.stream().mapToInt(s -> s.blocks().size()).sum());
        assertEquals(faces, sections.stream().flatMap(s -> s.blocks().stream())
                .mapToInt(b -> Integer.bitCount(b.faces())).sum());
        assertEquals(edges, sections.stream().flatMap(s -> s.blocks().stream())
                .mapToInt(b -> Integer.bitCount(b.edges())).sum());
    }

    private static BlockChange block(int x, int y, int z) {
        return new BlockChange(x, y, z, BlockChange.Kind.CHANGED);
    }
}
