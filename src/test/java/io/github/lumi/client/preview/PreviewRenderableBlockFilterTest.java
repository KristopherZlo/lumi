package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreviewRenderableBlockFilterTest {
    private final PreviewRenderableBlockFilter filter =
            new PreviewRenderableBlockFilter();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emitsOnlyModelAndFluidCellsWithAnExposedFace() {
        FakeBlocks blocks = new FakeBlocks();
        BlockPos center = new BlockPos(0, 64, 0);
        for (Direction direction : Direction.values()) {
            blocks.set(center.relative(direction), Blocks.STONE.defaultBlockState());
        }
        var neighbor = new BlockPos.MutableBlockPos();
        assertFalse(filter.shouldRenderModel(
                blocks, center, Blocks.STONE.defaultBlockState(), neighbor));

        blocks.set(center.above(), Blocks.AIR.defaultBlockState());
        assertTrue(filter.shouldRenderModel(
                blocks, center, Blocks.STONE.defaultBlockState(), neighbor));

        BlockState water = Blocks.WATER.defaultBlockState();
        blocks.set(center, water);
        for (Direction direction : Direction.values()) {
            blocks.set(center.relative(direction), water);
        }
        assertFalse(filter.shouldRenderFluid(
                blocks, center, water.getFluidState(), neighbor));

        blocks.set(center.above(), Blocks.AIR.defaultBlockState());
        assertTrue(filter.shouldRenderFluid(
                blocks, center, water.getFluidState(), neighbor));
    }

    private static final class FakeBlocks implements BlockGetter {
        private final Map<BlockPos, BlockState> states = new HashMap<>();

        private void set(BlockPos pos, BlockState state) {
            states.put(pos.immutable(), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override public int getHeight() {
            return 384;
        }

        @Override public int getMinY() {
            return -64;
        }
    }
}
