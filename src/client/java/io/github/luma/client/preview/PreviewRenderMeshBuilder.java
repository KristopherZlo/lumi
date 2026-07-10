package io.github.luma.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.luma.domain.model.Bounds3i;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class PreviewRenderMeshBuilder {

    private final Minecraft client = Minecraft.getInstance();
    private final PreviewRenderableBlockFilter renderableBlockFilter = new PreviewRenderableBlockFilter();

    CompletableFuture<PreviewRenderMesh> scheduleBuild(
            ClientLevel level,
            Bounds3i bounds,
            ExecutorService executor
    ) {
        CompletableFuture<PreviewRenderMesh> result = new CompletableFuture<>();
        Future<?> submitted = executor.submit(() -> {
            try {
                result.complete(this.build(level, bounds));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        result.whenComplete((mesh, failure) -> {
            if (result.isCancelled()) {
                submitted.cancel(true);
            }
        });
        return result;
    }

    private PreviewRenderMesh build(ClientLevel level, Bounds3i bounds) {
        BlockPos min = bounds.min().toBlockPos();
        BlockPos max = bounds.max().toBlockPos();
        BlockRenderDispatcher blockRenderer = this.client.getBlockRenderer();
        RandomSource random = RandomSource.create();
        List<BlockModelPart> parts = new ArrayList<>();
        PoseStack poseStack = new PoseStack();
        SectionBufferBuilderPack bufferPack = new SectionBufferBuilderPack();
        EnumMap<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, MeshData> layers = new EnumMap<>(ChunkSectionLayer.class);
        PreviewCullingBlockGetter cullingView = new PreviewCullingBlockGetter(level, bounds);
        PreviewTranslatedBlockGetter localFluidView = new PreviewTranslatedBlockGetter(cullingView, min);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos localCursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();

        try {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        this.throwIfInterrupted();
                        cursor.set(x, y, z);
                        this.addBlock(
                                cullingView,
                                localFluidView,
                                min,
                                blockRenderer,
                                random,
                                parts,
                                poseStack,
                                bufferPack,
                                builders,
                                cursor,
                                localCursor,
                                neighbor
                        );
                    }
                }
            }

            VertexSorting translucentSorting = this.depthSorting();
            for (var entry : builders.entrySet()) {
                MeshData meshData = entry.getValue().build();
                if (meshData == null) {
                    continue;
                }
                if (entry.getKey().sortOnUpload()) {
                    meshData.sortQuads(bufferPack.buffer(entry.getKey()), translucentSorting);
                }
                layers.put(entry.getKey(), meshData);
            }

            return new PreviewRenderMesh(bufferPack, layers);
        } catch (Throwable throwable) {
            for (MeshData meshData : layers.values()) {
                meshData.close();
            }
            bufferPack.close();
            throw throwable;
        } finally {
            ModelBlockRenderer.clearCache();
        }
    }

    private void addBlock(
            PreviewCullingBlockGetter blocks,
            PreviewTranslatedBlockGetter localFluidBlocks,
            BlockPos min,
            BlockRenderDispatcher blockRenderer,
            RandomSource random,
            List<BlockModelPart> parts,
            PoseStack poseStack,
            SectionBufferBuilderPack bufferPack,
            EnumMap<ChunkSectionLayer, BufferBuilder> builders,
            BlockPos pos,
            BlockPos.MutableBlockPos localPos,
            BlockPos.MutableBlockPos neighbor
    ) {
        BlockState state = blocks.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        FluidState fluidState = state.getFluidState();
        boolean renderFluid = this.renderableBlockFilter.shouldRenderFluid(blocks, pos, fluidState, neighbor);
        boolean renderModel = this.renderableBlockFilter.shouldRenderModel(blocks, pos, state, neighbor);
        if (!renderFluid && !renderModel) {
            return;
        }

        if (renderFluid) {
            localPos.set(pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ());
            blockRenderer.renderLiquid(
                    localPos,
                    localFluidBlocks,
                    this.getOrCreateBuilder(builders, bufferPack, ItemBlockRenderTypes.getRenderLayer(fluidState)),
                    state,
                    fluidState
            );
        }

        if (!renderModel) {
            return;
        }

        random.setSeed(state.getSeed(pos));
        parts.clear();
        blockRenderer.getBlockModel(state).collectParts(random, parts);

        poseStack.pushPose();
        poseStack.translate(
                pos.getX() - min.getX(),
                pos.getY() - min.getY(),
                pos.getZ() - min.getZ()
        );
        blockRenderer.renderBatched(
                state,
                pos,
                blocks,
                poseStack,
                this.getOrCreateBuilder(builders, bufferPack, ItemBlockRenderTypes.getChunkRenderType(state)),
                true,
                parts
        );
        poseStack.popPose();
    }

    private BufferBuilder getOrCreateBuilder(
            EnumMap<ChunkSectionLayer, BufferBuilder> builders,
            SectionBufferBuilderPack bufferPack,
            ChunkSectionLayer layer
    ) {
        return builders.computeIfAbsent(
                layer,
                key -> new BufferBuilder(bufferPack.buffer(key), VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        );
    }

    private VertexSorting depthSorting() {
        Matrix4f rotation = PreviewFramingCalculator.rotationMatrix();
        return VertexSorting.byDistance(vector -> -new Vector3f(vector).mulPosition(rotation).z());
    }

    private void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Preview mesh build was interrupted");
        }
    }
}
