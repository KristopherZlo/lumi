package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
//? if >=26.1 {
/*import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
*///?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.util.RandomSource;
//?}

/** Owns native tessellation state for one worker's preview mesh. */
final class PreviewBlockTessellator implements AutoCloseable {
    private final PreviewRenderableBlockFilter filter = new PreviewRenderableBlockFilter();
    private final Function<ChunkSectionLayer, BufferBuilder> output;
    private final PreviewFluidVertexConsumer fluidVertices = new PreviewFluidVertexConsumer();
    private final BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
    //? if >=26.1 {
    /*private final BlockStateModelSet models;
    private final ModelBlockRenderer renderer;
    private final FluidRenderer fluidRenderer;
    private final FluidRenderer.Output fluidOutput;
    *///?} else {
    private final BlockRenderDispatcher renderer = Minecraft.getInstance().getBlockRenderer();
    private final RandomSource random = RandomSource.create();
    private final List<BlockModelPart> parts = new ArrayList<>();
    private final PoseStack poses = new PoseStack();
    //?}

    PreviewBlockTessellator(Function<ChunkSectionLayer, BufferBuilder> output) {
        this.output = output;
        //? if >=26.1 {
        /*Minecraft client = Minecraft.getInstance();
        models = client.getModelManager().getBlockStateModelSet();
        renderer = new ModelBlockRenderer(client.options.ambientOcclusion().get(), true, client.getBlockColors());
        fluidRenderer = new FluidRenderer(client.getModelManager().getFluidStateModelSet());
        fluidOutput = layer -> fluidVertices.bind(output.apply(layer), local);
        *///?}
    }

    void addBlock(PreviewCullingBlockGetter blocks, PreviewTranslatedBlockGetter fluids,
            BlockPos min, BlockPos position) {
        var state = blocks.getBlockState(position);
        if (state.isAir()) return;
        var fluid = state.getFluidState();
        if (filter.shouldRenderFluid(blocks, position, fluid, neighbor)) {
            local.set(position.getX() - min.getX(), position.getY() - min.getY(), position.getZ() - min.getZ());
            //? if >=26.1 {
            /*fluidRenderer.tesselate(fluids, local, fluidOutput, state, fluid);
            *///?} else {
            renderer.renderLiquid(local, fluids, fluidVertices.bind(output.apply(ItemBlockRenderTypes.getRenderLayer(fluid)), local), state, fluid);
            //?}
        }
        if (!filter.shouldRenderModel(blocks, position, state, neighbor)) return;
        //? if >=26.1 {
        /*renderer.tesselateBlock((x, y, z, quad, instance) -> output.apply(quad.materialInfo().layer())
                        .putBlockBakedQuad(x, y, z, quad, instance),
                position.getX() - min.getX(), position.getY() - min.getY(), position.getZ() - min.getZ(),
                blocks, position, state, models.get(state), state.getSeed(position));
        *///?} else {
        random.setSeed(state.getSeed(position));
        parts.clear();
        renderer.getBlockModel(state).collectParts(random, parts);
        poses.pushPose();
        poses.translate(position.getX() - min.getX(), position.getY() - min.getY(), position.getZ() - min.getZ());
        renderer.renderBatched(state, position, blocks, poses,
                output.apply(ItemBlockRenderTypes.getChunkRenderType(state)), true, parts);
        poses.popPose();
        //?}
    }

    @Override public void close() {
        //? if <26.1
        ModelBlockRenderer.clearCache();
    }
}
