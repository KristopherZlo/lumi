package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.domain.model.BlockChange;
import java.util.ArrayList;
import java.util.List;
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
