package io.github.luma.domain.model;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PartialRestorePlanSummaryTest {

    @Test
    void reportsTotalChangeCountFromBlocksAndEntities() {
        PartialRestorePlanSummary blockSummary = summary(1, 0);
        PartialRestorePlanSummary entitySummary = summary(0, 1);
        PartialRestorePlanSummary emptySummary = summary(0, 0);

        assertTrue(blockSummary.hasChanges());
        assertTrue(entitySummary.hasChanges());
        assertFalse(emptySummary.hasChanges());
        assertEquals(1, blockSummary.totalChanges());
        assertEquals(1, entitySummary.totalChanges());
        assertEquals(0, emptySummary.totalChanges());
    }

    private static PartialRestorePlanSummary summary(int changedBlocks, int changedEntities) {
        return new PartialRestorePlanSummary(
                RestorePlanMode.NO_OP,
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(1, 65, 1)),
                PartialRestoreMode.SELECTED_AREA,
                PartialRestoreRegionSource.LUMI_REGION,
                List.of(),
                "main",
                "v0001",
                "v0002",
                changedBlocks,
                changedEntities
        );
    }
}
