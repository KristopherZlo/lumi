package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.MeshData;
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

    void render() {
        for (ChunkSectionLayer layer : DRAW_ORDER) {
            MeshData mesh = layers.remove(layer);
            if (mesh != null) {
                //? if >=26.2 {
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
    /*private void draw(MeshData mesh, RenderType type) {
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
        try (mesh; var vertices = device.createBuffer(() -> "Lumi Preview vertices",
                com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())) {
            var prepared = type.prepare();
            if (mesh.indexBuffer() != null) {
                try (var indices = device.createBuffer(() -> "Lumi Preview sorted indices",
                        com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX, mesh.indexBuffer())) {
                    prepared.drawFromBuffer(vertices, indices, mesh.drawState().indexType(),
                            0, 0, mesh.drawState().indexCount());
                }
            } else {
                var indices = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(type.primitiveTopology());
                var buffer = indices.getBuffer(mesh.drawState().indexCount());
                prepared.drawFromBuffer(vertices, buffer, indices.type(), 0, 0, mesh.drawState().indexCount());
            }
        }
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
