package io.github.luma.minecraft.world;

import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DirectSectionCommitEligibilityTest {

    private final DirectSectionCommitEligibility eligibility = new DirectSectionCommitEligibility();

    @Test
    void acceptsSingleChunkSingleSectionSlices() {
        SectionBatch batch = new SectionBatch(
                4,
                new BitSet(4096),
                List.of(
                        placement(32, 64, 48),
                        placement(33, 65, 49)
                )
        );

        Assertions.assertEquals(BlockCommitFallbackReason.NONE, this.eligibility.validateBatch(batch, 0, 2));
    }

    @Test
    void rejectsMixedChunkSlices() {
        SectionBatch batch = new SectionBatch(
                4,
                new BitSet(4096),
                List.of(
                        placement(32, 64, 48),
                        placement(48, 64, 48)
                )
        );

        Assertions.assertEquals(BlockCommitFallbackReason.MIXED_CHUNK, this.eligibility.validateBatch(batch, 0, 2));
    }

    @Test
    void rejectsMixedSectionSlices() {
        SectionBatch batch = new SectionBatch(
                4,
                new BitSet(4096),
                List.of(
                        placement(32, 64, 48),
                        placement(33, 80, 49)
                )
        );

        Assertions.assertEquals(BlockCommitFallbackReason.MIXED_SECTION, this.eligibility.validateBatch(batch, 0, 2));
    }

    private static PreparedBlockPlacement placement(int x, int y, int z) {
        return new PreparedBlockPlacement(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), null);
    }
}
