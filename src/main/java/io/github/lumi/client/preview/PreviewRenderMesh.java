package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.EnumMap;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** Owns the prepared render layers for one offscreen preview draw. */
final class PreviewRenderMesh implements AutoCloseable {
    private static final ChunkSectionLayer[] DRAW_ORDER = {
        ChunkSectionLayer.SOLID,
        ChunkSectionLayer.CUTOUT,
        //? if <26.1
        ChunkSectionLayer.TRIPWIRE,
        ChunkSectionLayer.TRANSLUCENT
    };

    private final SectionBufferBuilderPack bufferPack;
    private final EnumMap<ChunkSectionLayer, MeshData> layers;

    PreviewRenderMesh(
            SectionBufferBuilderPack bufferPack,
            EnumMap<ChunkSectionLayer, MeshData> layers) {
        this.bufferPack = bufferPack;
        this.layers = layers;
    }

    //? if >=26.3 {
    /*void render(GpuTextureView color, GpuTextureView depth) {
    *///?} else {
    void render() {
    //?}
        for (ChunkSectionLayer layer : DRAW_ORDER) {
            MeshData mesh = layers.remove(layer);
            if (mesh != null) {
                //? if >=26.3 {
                /*draw(mesh, renderType(layer), color, depth);
                *///?} else if >=26.2 {
                /*draw(mesh, renderType(layer));
                *///?} else {
                renderType(layer).draw(mesh);
                //?}
            }
        }
    }

    boolean isEmpty() {
        return layers.isEmpty();
    }

    //? if >=26.2 {
    /*//? if >=26.3 {
    private void draw(MeshData mesh, RenderType type, GpuTextureView color, GpuTextureView depth) {
    //?} else {
    private void draw(MeshData mesh, RenderType type) {
    //?}
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        try (mesh; var vertices = device.createBuffer(() -> "Lumi Preview vertices",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())) {
            var prepared = type.prepare();
            if (mesh.indexBuffer() != null) {
                try (var indices = device.createBuffer(() -> "Lumi Preview sorted indices",
                        com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX, mesh.indexBuffer())) {
                    //? if >=26.3 {
                    drawPrepared(prepared, vertices, indices, mesh.drawState().indexType(), mesh, color, depth);
                    //?} else {
                    prepared.drawFromBuffer(vertices, indices, mesh.drawState().indexType(),
                            0, 0, mesh.drawState().indexCount());
                    //?}
                }
            } else {
                var indices = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(type.primitiveTopology());
                var buffer = indices.getBuffer(mesh.drawState().indexCount());
                //? if >=26.3 {
                drawPrepared(prepared, vertices, buffer, indices.type(), mesh, color, depth);
                //?} else {
                prepared.drawFromBuffer(vertices, buffer, indices.type(), 0, 0, mesh.drawState().indexCount());
                //?}
            }
        }
    }
    *///?}

    //? if >=26.3 {
    /*private void drawPrepared(
            net.minecraft.client.renderer.rendertype.PreparedRenderType prepared,
            com.mojang.blaze3d.buffers.GpuBuffer vertices,
            com.mojang.blaze3d.buffers.GpuBuffer indices,
            com.mojang.renderpearl.api.pipeline.IndexType indexType,
            MeshData mesh, GpuTextureView color, GpuTextureView depth) {
        var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
        try (var pass = encoder.createRenderPass(() -> "Lumi Preview", color,
                java.util.Optional.empty(), depth, java.util.OptionalDouble.empty())) {
            prepared.drawFromBuffer(new net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo(
                    vertices, indices, indexType, 0, 0,
                    mesh.drawState().indexCount(), prepared.pipeline().getPrimitiveTopology()), pass);
        }
        encoder.submit();
    }
    *///?}

    @Override
    public void close() {
        layers.values().forEach(MeshData::close);
        layers.clear();
        bufferPack.close();
    }

    private static RenderType renderType(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> RenderTypes.solidMovingBlock();
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            //? if <26.1
            case TRIPWIRE -> RenderTypes.tripwireMovingBlock();
        };
    }
}
