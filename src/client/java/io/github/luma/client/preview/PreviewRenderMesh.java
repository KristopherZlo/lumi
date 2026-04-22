package io.github.luma.client.preview;

import com.mojang.blaze3d.vertex.MeshData;
import java.util.EnumMap;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

final class PreviewRenderMesh implements AutoCloseable {

    private static final ChunkSectionLayer[] DRAW_ORDER = new ChunkSectionLayer[]{
            ChunkSectionLayer.SOLID,
            ChunkSectionLayer.CUTOUT,
            ChunkSectionLayer.TRIPWIRE,
            ChunkSectionLayer.TRANSLUCENT
    };

    private final SectionBufferBuilderPack bufferPack;
    private final EnumMap<ChunkSectionLayer, MeshData> layers;

    PreviewRenderMesh(SectionBufferBuilderPack bufferPack, EnumMap<ChunkSectionLayer, MeshData> layers) {
        this.bufferPack = bufferPack;
        this.layers = layers;
    }

    void render() {
        for (ChunkSectionLayer layer : DRAW_ORDER) {
            MeshData meshData = this.layers.remove(layer);
            if (meshData == null) {
                continue;
            }
            renderType(layer).draw(meshData);
        }
    }

    boolean isEmpty() {
        return this.layers.isEmpty();
    }

    @Override
    public void close() {
        for (MeshData meshData : this.layers.values()) {
            meshData.close();
        }
        this.layers.clear();
        this.bufferPack.close();
    }

    private static RenderType renderType(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> RenderTypes.solidMovingBlock();
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            case TRIPWIRE -> RenderTypes.tripwireMovingBlock();
        };
    }
}
