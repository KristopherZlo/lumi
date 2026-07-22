package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.SectionKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class MinecraftWorldStateApplyTest {
    @Test
    void ordersVisibleChunksFirstWithoutSplittingTheirSections() {
        SectionKey far = new SectionKey(20, 0, 20);
        SectionKey nearHigh = new SectionKey(2, 1, 1);
        SectionKey nearLow = new SectionKey(2, 0, 1);

        assertEquals(List.of(nearLow, nearHigh, far),
                MinecraftWorldStateApply.prioritize(
                        List.of(far, nearHigh, nearLow),
                        List.of(new ChunkCoordinate(0, 0))));
    }
}
