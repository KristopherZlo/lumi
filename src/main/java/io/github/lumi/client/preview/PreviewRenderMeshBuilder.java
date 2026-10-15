package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.lumi.domain.model.BlockBox;
import java.util.EnumMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Builds the retained layered block/fluid mesh away from the render thread. */
final class PreviewRenderMeshBuilder {

    CompletableFuture<PreviewRenderMesh> scheduleBuild(
            BlockAndTintGetter blocks,
            BlockBox bounds,
            ExecutorService executor) {
        CompletableFuture<PreviewRenderMesh> result = new CompletableFuture<>();
        Future<?> submitted = executor.submit(() -> {
            try {
                PreviewRenderMesh mesh = build(blocks, bounds);
                if (!result.complete(mesh)) mesh.close();
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
        SectionBufferBuilderPack buffers = new SectionBufferBuilderPack();
        var builders = new EnumMap<ChunkSectionLayer, BufferBuilder>(
                ChunkSectionLayer.class);
        var layers = new EnumMap<ChunkSectionLayer, MeshData>(
                ChunkSectionLayer.class);
        var culling = new PreviewCullingBlockGetter(blocks, bounds);
        var fluids = new PreviewTranslatedBlockGetter(culling, min);
        var cursor = new BlockPos.MutableBlockPos();
        try (var renderer = new PreviewBlockTessellator(layer -> builder(builders, buffers, layer))) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        checkInterrupted();
                        cursor.set(x, y, z);
                        renderer.addBlock(culling, fluids, min, cursor);
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
        }
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
