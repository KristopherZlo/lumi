package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectTrackingIndexTest {

    @Test
    void matchesWholeDimensionAndBoundedProjectsByDimensionAndChunk() {
        ProjectTrackingIndex<String> index = ProjectTrackingIndex.build(List.of(
                new ProjectTrackingIndex.Entry<>("minecraft:overworld", null, "world"),
                new ProjectTrackingIndex.Entry<>("minecraft:overworld", bounds(0, 64, 0, 15, 80, 15), "spawn"),
                new ProjectTrackingIndex.Entry<>("minecraft:the_nether", bounds(0, 64, 0, 15, 80, 15), "nether")
        ));

        assertEquals(List.of("world", "spawn"), index.matching("minecraft:overworld", new BlockPos(2, 70, 2)));
        assertEquals(List.of("world"), index.matching("minecraft:overworld", new BlockPos(32, 70, 2)));
        assertEquals(List.of("nether"), index.matching("minecraft:the_nether", new BlockPos(2, 70, 2)));
    }

    @Test
    void preservesPreciseBoundsInsideIndexedChunks() {
        ProjectTrackingIndex<String> index = ProjectTrackingIndex.build(List.of(
                new ProjectTrackingIndex.Entry<>("minecraft:overworld", bounds(5, 64, 5, 8, 70, 8), "small")
        ));

        assertEquals(List.of("small"), index.matching("minecraft:overworld", new BlockPos(6, 66, 6)));
        assertEquals(List.of(), index.matching("minecraft:overworld", new BlockPos(4, 66, 6)));
        assertEquals(List.of(), index.matching("minecraft:overworld", new BlockPos(6, 80, 6)));
    }

    private static Bounds3i bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new Bounds3i(
                new BlockPoint(minX, minY, minZ),
                new BlockPoint(maxX, maxY, maxZ)
        );
    }
}
