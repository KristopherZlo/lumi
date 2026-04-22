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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class PreviewRenderMeshBuilder {

    private final Minecraft client = Minecraft.getInstance();

    CompletableFuture<PreviewRenderMesh> scheduleBuild(ClientLevel level, Bounds3i bounds, Executor executor) {
        return CompletableFuture.supplyAsync(() -> this.build(level, bounds), executor);
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

        try {
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }

                FluidState fluidState = state.getFluidState();
                if (!fluidState.isEmpty()) {
                    blockRenderer.renderLiquid(
                            pos,
                            level,
                            this.getOrCreateBuilder(builders, bufferPack, ItemBlockRenderTypes.getRenderLayer(fluidState)),
                            state,
                            fluidState
                    );
                }

                if (state.getRenderShape() != RenderShape.MODEL) {
                    continue;
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
                        level,
                        poseStack,
                        this.getOrCreateBuilder(builders, bufferPack, ItemBlockRenderTypes.getChunkRenderType(state)),
                        true,
                        parts
                );
                poseStack.popPose();
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
}
