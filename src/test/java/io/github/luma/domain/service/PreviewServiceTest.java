package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.service.PreviewService.PreviewRenderData;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewServiceTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sampleRendersIsometricPreviewUsingBlockMapColors() {
        PreviewService service = new PreviewService();
        FakeBlockGetter blocks = new FakeBlockGetter(60, 24);
        blocks.set(0, 64, 0, Blocks.STONE.defaultBlockState());
        blocks.set(0, 65, 0, Blocks.STONE.defaultBlockState());
        blocks.set(1, 64, 0, Blocks.OAK_PLANKS.defaultBlockState());
        blocks.set(1, 64, 1, Blocks.GRASS_BLOCK.defaultBlockState());

        PreviewRenderData render = service.sample(
                new Bounds3i(new BlockPoint(-6, 60, -6), new BlockPoint(6, 70, 6)),
                blocks
        );

        int stoneBase = Blocks.STONE.defaultBlockState().getMapColor(blocks, new BlockPos(0, 65, 0)).col;
        int shadedStone = IsometricPreviewRenderer.shadeColor(stoneBase, 1.08D);

        assertTrue(render.width() > 8);
        assertTrue(render.height() > 8);
        assertTrue(countOpaque(render) > 0);
        assertTrue(containsColor(render, shadedStone));
    }

    @Test
    void sampleDrawsFallbackFootprintWhenBoundsAreEmpty() {
        PreviewService service = new PreviewService();
        FakeBlockGetter blocks = new FakeBlockGetter(60, 16);

        PreviewRenderData render = service.sample(
                new Bounds3i(new BlockPoint(0, 60, 0), new BlockPoint(4, 65, 4)),
                blocks
        );

        assertTrue(render.width() > 1);
        assertTrue(render.height() > 1);
        assertTrue(countOpaque(render) > 0);
    }

    @Test
    void samplerPrefersSolidSurfaceBelowLeafCover() {
        PreviewColumnSampler sampler = new PreviewColumnSampler();
        FakeBlockGetter blocks = new FakeBlockGetter(40, 48);
        blocks.set(0, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.set(0, 65, 0, Blocks.OAK_LEAVES.defaultBlockState());
        blocks.set(0, 66, 0, Blocks.OAK_LEAVES.defaultBlockState());

        PreviewScene scene = sampler.sample(
                new Bounds3i(new BlockPoint(0, 40, 0), new BlockPoint(0, 80, 0)),
                blocks
        );

        PreviewColumn column = scene.columnAt(0, 0);
        assertEquals(Blocks.GRASS_BLOCK, column.topState().getBlock());
        assertEquals(64, column.topY());
        assertEquals(63, scene.frameBounds().min().y());
    }

    @Test
    void sampleDoesNotStretchSingleSurfaceColumnToDeepTerrainBase() {
        PreviewService service = new PreviewService();
        FakeBlockGetter blocks = new FakeBlockGetter(-32, 160);
        for (int y = -16; y <= 63; y++) {
            blocks.set(0, y, 0, Blocks.STONE.defaultBlockState());
        }
        blocks.set(0, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState());

        PreviewRenderData render = service.sample(
                new Bounds3i(new BlockPoint(0, -32, 0), new BlockPoint(0, 96, 0)),
                blocks
        );

        assertTrue(render.height() < 48);
    }

    private static boolean containsColor(PreviewRenderData render, int rgb) {
        int target = rgb & 0x00FFFFFF;
        for (int pixel : render.pixels()) {
            if ((pixel & 0x00FFFFFF) == target && ((pixel >>> 24) & 0xFF) > 0) {
                return true;
            }
        }
        return false;
    }

    private static int countOpaque(PreviewRenderData render) {
        int count = 0;
        for (int pixel : render.pixels()) {
            if (((pixel >>> 24) & 0xFF) > 0) {
                count += 1;
            }
        }
        return count;
    }

    private static final class FakeBlockGetter implements BlockGetter {

        private final int minY;
        private final int height;
        private final Map<String, BlockState> states = new HashMap<>();

        private FakeBlockGetter(int minY, int height) {
            this.minY = minY;
            this.height = height;
        }

        private void set(int x, int y, int z, BlockState state) {
            this.states.put(key(x, y, z), state);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.states.getOrDefault(key(pos.getX(), pos.getY(), pos.getZ()), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return this.height;
        }

        @Override
        public int getMinY() {
            return this.minY;
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
