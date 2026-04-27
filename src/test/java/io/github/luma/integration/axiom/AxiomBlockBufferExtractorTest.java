package io.github.luma.integration.axiom;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomBlockBufferExtractorTest {

    private final AxiomBlockBufferExtractor extractor = new AxiomBlockBufferExtractor();

    @Test
    void extractsOnlyNonEmptySectionStates() {
        FakeBlockBuffer buffer = new FakeBlockBuffer();
        PalettedContainer<BlockState> section = new PalettedContainer<>(
                FakeBlockBuffer.EMPTY_STATE,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
        );
        section.set(2, 3, 4, Blocks.STONE.defaultBlockState());
        section.set(5, 6, 7, FakeBlockBuffer.EMPTY_STATE);
        buffer.values.put(BlockPos.asLong(1, -2, 3), section);

        List<AxiomBlockMutation> mutations = this.extractor.extract(buffer);

        assertEquals(1, mutations.size());
        assertEquals(new BlockPos(18, -29, 52), mutations.getFirst().pos());
        assertEquals(Blocks.STONE.defaultBlockState(), mutations.getFirst().newState());
    }

    @Test
    void ignoresObjectsThatDoNotLookLikeAxiomBlockBuffers() {
        assertTrue(this.extractor.extract(new Object()).isEmpty());
    }

    public static final class FakeBlockBuffer {

        public static final BlockState EMPTY_STATE = Blocks.VOID_AIR.defaultBlockState();

        private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> values = new Long2ObjectOpenHashMap<>();

        public Iterable<?> entrySet() {
            return this.values.long2ObjectEntrySet();
        }
    }
}
