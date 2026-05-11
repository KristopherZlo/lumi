package io.github.luma.client.preview;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewRenderableBlockFilterTest {

    private final PreviewRenderableBlockFilter filter = new PreviewRenderableBlockFilter();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void skipsModelBlockFullyHiddenByNeighboringBlocks() {
        FakeBlockGetter blocks = new FakeBlockGetter();
        BlockPos center = new BlockPos(0, 64, 0);
        blocks.set(center, Blocks.STONE.defaultBlockState());
        blocks.set(center.above(), Blocks.STONE.defaultBlockState());
        blocks.set(center.below(), Blocks.STONE.defaultBlockState());
        blocks.set(center.north(), Blocks.STONE.defaultBlockState());
        blocks.set(center.south(), Blocks.STONE.defaultBlockState());
        blocks.set(center.east(), Blocks.STONE.defaultBlockState());
        blocks.set(center.west(), Blocks.STONE.defaultBlockState());

        assertFalse(this.filter.shouldRenderModel(
                blocks,
                center,
                Blocks.STONE.defaultBlockState(),
                new BlockPos.MutableBlockPos()
        ));
    }

    @Test
    void rendersModelBlockWithAtLeastOneOpenFace() {
        FakeBlockGetter blocks = new FakeBlockGetter();
        BlockPos center = new BlockPos(0, 64, 0);
        blocks.set(center, Blocks.STONE.defaultBlockState());
        blocks.set(center.below(), Blocks.STONE.defaultBlockState());

        assertTrue(this.filter.shouldRenderModel(
                blocks,
                center,
                Blocks.STONE.defaultBlockState(),
                new BlockPos.MutableBlockPos()
        ));
    }

    @Test
    void skipsFluidCellFullySurroundedBySameFluid() {
        FakeBlockGetter blocks = new FakeBlockGetter();
        BlockPos center = new BlockPos(0, 64, 0);
        BlockState water = Blocks.WATER.defaultBlockState();
        blocks.set(center, water);
        blocks.set(center.above(), water);
        blocks.set(center.below(), water);
        blocks.set(center.north(), water);
        blocks.set(center.south(), water);
        blocks.set(center.east(), water);
        blocks.set(center.west(), water);

        assertFalse(this.filter.shouldRenderFluid(
                blocks,
                center,
                water.getFluidState(),
                new BlockPos.MutableBlockPos()
        ));
    }

    private static final class FakeBlockGetter implements BlockGetter {

        private final Map<String, BlockState> states = new HashMap<>();

        private void set(BlockPos pos, BlockState state) {
            this.states.put(key(pos), state);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.states.getOrDefault(key(pos), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        private static String key(BlockPos pos) {
            return pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        }
    }
}
