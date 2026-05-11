package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChunkSectionUpdateBroadcasterTest {

    @Test
    void changedCellsUseSectionRelativeBlockPositions() {
        BlockPos first = new BlockPos(32, 64, 48);
        BlockPos second = new BlockPos(47, 79, 63);

        ShortSet cells = ChunkSectionUpdateBroadcaster.changedCells(List.of(first, second, first));

        Assertions.assertEquals(2, cells.size());
        Assertions.assertTrue(cells.contains(SectionPos.sectionRelativePos(first)));
        Assertions.assertTrue(cells.contains(SectionPos.sectionRelativePos(second)));
    }

    @Test
    void renderInvalidationIncludesSameSectionNeighbors() {
        ShortSet cells = ChunkSectionUpdateBroadcaster.changedCells(List.of(new BlockPos(1, 2, 3)));

        ShortSet invalidationCells = ChunkSectionUpdateBroadcaster.renderInvalidationCells(cells);

        Assertions.assertEquals(7, invalidationCells.size());
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(1, 2, 3))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(0, 2, 3))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(2, 2, 3))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(1, 1, 3))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(1, 3, 3))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(1, 2, 2))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(1, 2, 4))));
    }

    @Test
    void renderInvalidationDoesNotWrapSectionBoundaryNeighbors() {
        ShortSet cells = ChunkSectionUpdateBroadcaster.changedCells(List.of(new BlockPos(0, 0, 0)));

        ShortSet invalidationCells = ChunkSectionUpdateBroadcaster.renderInvalidationCells(cells);

        Assertions.assertEquals(4, invalidationCells.size());
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(0, 0, 0))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(1, 0, 0))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(0, 1, 0))));
        Assertions.assertTrue(invalidationCells.contains(SectionPos.sectionRelativePos(new BlockPos(0, 0, 1))));
    }
}
