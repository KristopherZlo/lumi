package io.github.luma.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class LightRefreshActiveOperationTest {

    @Test
    void summarizesEmptyDirtyChunks() {
        assertEquals("none", LightRefreshActiveOperation.describeDirtyChunks(List.of()));
    }

    @Test
    void summarizesDirtyChunkBoundsAndSample() {
        String summary = LightRefreshActiveOperation.describeDirtyChunks(List.of(
                new ChunkPoint(2, -4),
                new ChunkPoint(-1, 8),
                new ChunkPoint(3, 1)
        ));

        assertEquals("count=3, minX=-1, maxX=3, minZ=-4, maxZ=8, sample=2:-4|-1:8|3:1", summary);
    }
}
