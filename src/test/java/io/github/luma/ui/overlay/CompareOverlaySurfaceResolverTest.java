package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareOverlaySurfaceResolverTest {

    private final CompareOverlaySurfaceResolver resolver = new CompareOverlaySurfaceResolver();

    @Test
    void isolatedBlockExposesAllFaces() {
        DiffBlockEntry entry = entry(10, 64, 10);

        List<CompareOverlaySurfaceResolver.SurfaceBlock> resolved = this.resolver.resolve(
                List.of(entry),
                this.resolver.indexPositions(List.of(entry))
        );

        assertEquals(1, resolved.size());
        assertEquals(6, exposedFaceCount(resolved.getFirst()));
    }

    @Test
    void adjacentBlocksCullTheirSharedFace() {
        DiffBlockEntry left = entry(10, 64, 10);
        DiffBlockEntry right = entry(11, 64, 10);
        List<DiffBlockEntry> entries = List.of(left, right);

        List<CompareOverlaySurfaceResolver.SurfaceBlock> resolved = this.resolver.resolve(
                entries,
                this.resolver.indexPositions(entries)
        );
        CompareOverlaySurfaceResolver.SurfaceBlock resolvedLeft = findByX(resolved, 10);
        CompareOverlaySurfaceResolver.SurfaceBlock resolvedRight = findByX(resolved, 11);

        assertEquals(2, resolved.size());
        assertEquals(10, resolved.stream().mapToInt(CompareOverlaySurfaceResolverTest::exposedFaceCount).sum());
        assertFalse(resolvedLeft.eastExposed());
        assertTrue(resolvedLeft.westExposed());
        assertFalse(resolvedRight.westExposed());
        assertTrue(resolvedRight.eastExposed());
    }

    @Test
    void enclosedVisibleBlockIsSkippedWhenNeighborsExistOutsideSelection() {
        DiffBlockEntry center = entry(10, 64, 10);
        List<DiffBlockEntry> allEntries = Stream.of(
                center,
                entry(10, 64, 9),
                entry(10, 64, 11),
                entry(9, 64, 10),
                entry(11, 64, 10),
                entry(10, 63, 10),
                entry(10, 65, 10)
        ).toList();
        Set<Long> occupiedPositions = this.resolver.indexPositions(allEntries);

        List<CompareOverlaySurfaceResolver.SurfaceBlock> resolved = this.resolver.resolve(List.of(center), occupiedPositions);

        assertTrue(resolved.isEmpty());
    }

    private static int exposedFaceCount(CompareOverlaySurfaceResolver.SurfaceBlock block) {
        int faces = 0;
        if (block.northExposed()) {
            faces++;
        }
        if (block.southExposed()) {
            faces++;
        }
        if (block.westExposed()) {
            faces++;
        }
        if (block.eastExposed()) {
            faces++;
        }
        if (block.downExposed()) {
            faces++;
        }
        if (block.upExposed()) {
            faces++;
        }
        return faces;
    }

    private static CompareOverlaySurfaceResolver.SurfaceBlock findByX(
            List<CompareOverlaySurfaceResolver.SurfaceBlock> blocks,
            int x
    ) {
        return blocks.stream()
                .filter(block -> block.entry().pos().x() == x)
                .findFirst()
                .orElseThrow();
    }

    private static DiffBlockEntry entry(int x, int y, int z) {
        return new DiffBlockEntry(new BlockPoint(x, y, z), "minecraft:stone", "minecraft:glass", ChangeType.CHANGED);
    }
}
