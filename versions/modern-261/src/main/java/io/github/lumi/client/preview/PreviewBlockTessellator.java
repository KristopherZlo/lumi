package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;

/** Uses vanilla's target-version lighting, tint sources and per-quad material layers. */
final class PreviewBlockTessellator implements AutoCloseable {
    private final PreviewRenderableBlockFilter filter = new PreviewRenderableBlockFilter();
    private final BlockStateModelSet models;
    private final ModelBlockRenderer renderer;
    private final FluidRenderer fluidRenderer;
    private final Function<ChunkSectionLayer, BufferBuilder> output;
    private final BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();

    PreviewBlockTessellator(Function<ChunkSectionLayer, BufferBuilder> output) {
        Minecraft client = Minecraft.getInstance();
        models = client.getModelManager().getBlockStateModelSet();
        renderer = new ModelBlockRenderer(client.options.ambientOcclusion().get(), true, client.getBlockColors());
        fluidRenderer = new FluidRenderer(client.getModelManager().getFluidStateModelSet());
        this.output = output;
    }

    void addBlock(PreviewCullingBlockGetter blocks, PreviewTranslatedBlockGetter fluids,
            BlockPos min, BlockPos position) {
        var state = blocks.getBlockState(position);
        if (state.isAir()) return;
        var fluid = state.getFluidState();
        if (filter.shouldRenderFluid(blocks, position, fluid, neighbor)) {
            local.set(position.getX() - min.getX(), position.getY() - min.getY(), position.getZ() - min.getZ());
            fluidRenderer.tesselate(fluids, local, output::apply, state, fluid);
        }
        if (!filter.shouldRenderModel(blocks, position, state, neighbor)) return;
        renderer.tesselateBlock((x, y, z, quad, instance) -> output.apply(quad.materialInfo().layer())
                        .putBlockBakedQuad(x, y, z, quad, instance),
                position.getX() - min.getX(), position.getY() - min.getY(), position.getZ() - min.getZ(),
                blocks, position, state, models.get(state), state.getSeed(position));
    }

    @Override public void close() { }
}
