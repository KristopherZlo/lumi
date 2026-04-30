package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSectionApplyCursorTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void cursorKeepsNativeCellProgressBetweenSlices() {
        NativeSectionApplyCursor cursor = new NativeSectionApplyCursor(sectionBatch(70));

        assertEquals(0, cursor.nextCellOrdinal());
        assertEquals(70, cursor.remainingCells());
        assertFalse(cursor.isComplete());

        cursor.advance(16);

        assertEquals(16, cursor.nextCellOrdinal());
        assertEquals(54, cursor.remainingCells());
        assertEquals(16, cursor.nextLocalIndex());

        cursor.advance(54);

        assertTrue(cursor.isComplete());
        assertEquals(0, cursor.remainingCells());
    }

    @Test
    void cursorBuildsCompletedNativeCommitResultFromAccumulatedCounters() {
        NativeSectionApplyCursor cursor = new NativeSectionApplyCursor(sectionBatch(2));

        cursor.recordChanged((short) 1, 2);
        cursor.advance();
        cursor.recordSkipped();
        cursor.advance();

        BlockCommitResult result = cursor.completedNativeResult(1, 1);

        assertEquals(2, result.processedBlocks());
        assertEquals(1, result.changedBlocks());
        assertEquals(1, result.skippedBlocks());
        assertEquals(1, result.nativeSections());
        assertEquals(1, result.nativeCells());
        assertEquals(2, result.blockEntityPackets());
        assertEquals(1, result.lightChecks());
    }

    private static PreparedSectionApplyBatch sectionBatch(int changedCells) {
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(4);
        for (int index = 0; index < changedCells; index++) {
            builder.set(index, Blocks.STONE.defaultBlockState(), null);
        }
        return new PreparedSectionApplyBatch(
                new ChunkPoint(0, 0),
                4,
                builder.build(),
                SectionApplySafetyProfile.nativeSection("test"),
                false
        );
    }
}
