package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** Owns native tessellation state for one worker's preview mesh. */
final class PreviewBlockTessellator implements AutoCloseable {
    private final BlockRenderDispatcher renderer = Minecraft.getInstance().getBlockRenderer();
    private final PreviewRenderableBlockFilter filter = new PreviewRenderableBlockFilter();
    private final RandomSource random = RandomSource.create();
    private final List<BlockModelPart> parts = new ArrayList<>();
    private final PoseStack poses = new PoseStack();
    private final BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    private final Function<ChunkSectionLayer, BufferBuilder> output;
    private final PreviewFluidVertexConsumer fluidVertices = new PreviewFluidVertexConsumer();

    PreviewBlockTessellator(Function<ChunkSectionLayer, BufferBuilder> output) {
        this.output = output;
    }

    void addBlock(PreviewCullingBlockGetter blocks, PreviewTranslatedBlockGetter fluids,
            BlockPos min, BlockPos position) {
        var state = blocks.getBlockState(position);
        if (state.isAir()) return;
        var fluid = state.getFluidState();
        if (filter.shouldRenderFluid(blocks, position, fluid, neighbor)) {
            local.set(position.getX() - min.getX(), position.getY() - min.getY(), position.getZ() - min.getZ());
            renderer.renderLiquid(local, fluids,
                    fluidVertices.bind(output.apply(ItemBlockRenderTypes.getRenderLayer(fluid)), local), state, fluid);
        }
        if (!filter.shouldRenderModel(blocks, position, state, neighbor)) return;
        random.setSeed(state.getSeed(position));
        parts.clear();
        renderer.getBlockModel(state).collectParts(random, parts);
        poses.pushPose();
        poses.translate(position.getX() - min.getX(), position.getY() - min.getY(), position.getZ() - min.getZ());
        renderer.renderBatched(state, position, blocks, poses,
                output.apply(ItemBlockRenderTypes.getChunkRenderType(state)), true, parts);
        poses.popPose();
    }

    @Override public void close() {
        ModelBlockRenderer.clearCache();
    }
}
