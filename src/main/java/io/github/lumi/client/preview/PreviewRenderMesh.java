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
            if (mesh != null) renderType(layer).draw(mesh);
        }
    }

    boolean isEmpty() {
        return layers.isEmpty();
    }

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
            case TRIPWIRE -> RenderTypes.tripwireMovingBlock();
        };
    }
}
