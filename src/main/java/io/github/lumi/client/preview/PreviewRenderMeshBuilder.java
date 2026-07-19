package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.lumi.domain.model.BlockBox;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Builds the retained layered block/fluid mesh away from the render thread. */
final class PreviewRenderMeshBuilder {
    private final Minecraft client = Minecraft.getInstance();
    private final PreviewRenderableBlockFilter filter =
            new PreviewRenderableBlockFilter();

    CompletableFuture<PreviewRenderMesh> scheduleBuild(
            BlockAndTintGetter blocks,
            BlockBox bounds,
            ExecutorService executor) {
        CompletableFuture<PreviewRenderMesh> result = new CompletableFuture<>();
        Future<?> submitted = executor.submit(() -> {
            try {
                result.complete(build(blocks, bounds));
            } catch (Throwable failed) {
                result.completeExceptionally(failed);
            }
        });
        result.whenComplete((mesh, failed) -> {
            if (result.isCancelled()) submitted.cancel(true);
        });
        return result;
    }

    private PreviewRenderMesh build(
            BlockAndTintGetter blocks, BlockBox bounds) {
        BlockPos min = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        BlockRenderDispatcher renderer = client.getBlockRenderer();
        RandomSource random = RandomSource.create();
        List<BlockModelPart> parts = new ArrayList<>();
        PoseStack poses = new PoseStack();
        SectionBufferBuilderPack buffers = new SectionBufferBuilderPack();
        var builders = new EnumMap<ChunkSectionLayer, BufferBuilder>(
                ChunkSectionLayer.class);
        var layers = new EnumMap<ChunkSectionLayer, MeshData>(
                ChunkSectionLayer.class);
        var culling = new PreviewCullingBlockGetter(blocks, bounds);
        var fluids = new PreviewTranslatedBlockGetter(culling, min);
        var cursor = new BlockPos.MutableBlockPos();
        var local = new BlockPos.MutableBlockPos();
        var neighbor = new BlockPos.MutableBlockPos();
        try {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        checkInterrupted();
                        cursor.set(x, y, z);
                        addBlock(culling, fluids, min, renderer, random, parts,
                                poses, buffers, builders, cursor, local, neighbor);
                    }
                }
            }
            VertexSorting sorting = depthSorting();
            for (var entry : builders.entrySet()) {
                MeshData mesh = entry.getValue().build();
                if (mesh == null) continue;
                if (entry.getKey().sortOnUpload()) {
                    mesh.sortQuads(buffers.buffer(entry.getKey()), sorting);
                }
                layers.put(entry.getKey(), mesh);
            }
            return new PreviewRenderMesh(buffers, layers);
        } catch (Throwable failed) {
            layers.values().forEach(MeshData::close);
            buffers.close();
            throw failed;
        } finally {
            ModelBlockRenderer.clearCache();
        }
    }

    private void addBlock(
            PreviewCullingBlockGetter blocks,
            PreviewTranslatedBlockGetter fluids,
            BlockPos min,
            BlockRenderDispatcher renderer,
            RandomSource random,
            List<BlockModelPart> parts,
            PoseStack poses,
            SectionBufferBuilderPack buffers,
            EnumMap<ChunkSectionLayer, BufferBuilder> builders,
            BlockPos position,
            BlockPos.MutableBlockPos local,
            BlockPos.MutableBlockPos neighbor) {
        BlockState state = blocks.getBlockState(position);
        if (state.isAir()) return;
        FluidState fluid = state.getFluidState();
        boolean renderFluid = filter.shouldRenderFluid(
                blocks, position, fluid, neighbor);
        boolean renderModel = filter.shouldRenderModel(
                blocks, position, state, neighbor);
        if (renderFluid) {
            local.set(
                    position.getX() - min.getX(),
                    position.getY() - min.getY(),
                    position.getZ() - min.getZ());
            renderer.renderLiquid(
                    local, fluids,
                    builder(builders, buffers,
                            ItemBlockRenderTypes.getRenderLayer(fluid)),
                    state, fluid);
        }
        if (!renderModel) return;
        random.setSeed(state.getSeed(position));
        parts.clear();
        renderer.getBlockModel(state).collectParts(random, parts);
        poses.pushPose();
        poses.translate(
                position.getX() - min.getX(),
                position.getY() - min.getY(),
                position.getZ() - min.getZ());
        renderer.renderBatched(
                state, position, blocks, poses,
                builder(builders, buffers,
                        ItemBlockRenderTypes.getChunkRenderType(state)),
                true, parts);
        poses.popPose();
    }

    private static BufferBuilder builder(
            EnumMap<ChunkSectionLayer, BufferBuilder> builders,
            SectionBufferBuilderPack buffers,
            ChunkSectionLayer layer) {
        return builders.computeIfAbsent(layer, key -> new BufferBuilder(
                buffers.buffer(key), VertexFormat.Mode.QUADS,
                DefaultVertexFormat.BLOCK));
    }

    private static VertexSorting depthSorting() {
        Matrix4f rotation = PreviewFramingCalculator.rotationMatrix();
        return VertexSorting.byDistance(
                vector -> -new Vector3f(vector).mulPosition(rotation).z());
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "Preview mesh build was interrupted");
        }
    }
}
