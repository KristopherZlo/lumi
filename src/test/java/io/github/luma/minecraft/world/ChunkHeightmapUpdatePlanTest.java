package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChunkHeightmapUpdatePlanTest {

    @Test
    void keepsOnlyHighestChangedCellPerColumn() {
        ChunkHeightmapUpdatePlan plan = new ChunkHeightmapUpdatePlan();
        int lowLocalIndex = SectionChangeMask.localIndex(3, 12, 9);
        int highLocalIndex = SectionChangeMask.localIndex(3, 2, 9);

        plan.record(16, lowLocalIndex);
        plan.record(18, highLocalIndex);
        plan.record(17, SectionChangeMask.localIndex(3, 15, 9));

        Assertions.assertEquals(1, plan.columnCount());
        Assertions.assertEquals((18 << 4) + 2, plan.worldYForColumn(3, 9));
    }

    @Test
    void tracksIndependentColumnsSeparately() {
        ChunkHeightmapUpdatePlan plan = new ChunkHeightmapUpdatePlan();

        plan.record(18, SectionChangeMask.localIndex(3, 2, 9));
        plan.record(18, SectionChangeMask.localIndex(4, 2, 9));
        plan.record(18, SectionChangeMask.localIndex(3, 1, 10));

        Assertions.assertEquals(3, plan.columnCount());
        Assertions.assertEquals((18 << 4) + 2, plan.worldYForColumn(3, 9));
        Assertions.assertEquals((18 << 4) + 2, plan.worldYForColumn(4, 9));
        Assertions.assertEquals((18 << 4) + 1, plan.worldYForColumn(3, 10));
    }

    @Test
    void ignoresInvalidLocalIndexes() {
        ChunkHeightmapUpdatePlan plan = new ChunkHeightmapUpdatePlan();

        plan.record(18, -1);
        plan.record(18, SectionChangeMask.ENTRY_COUNT);

        Assertions.assertEquals(0, plan.columnCount());
    }
}
