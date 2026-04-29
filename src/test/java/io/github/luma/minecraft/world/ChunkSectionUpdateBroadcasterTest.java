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
}
