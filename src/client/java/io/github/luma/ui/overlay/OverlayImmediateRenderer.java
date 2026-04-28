package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.rendertype.RenderType;

final class OverlayImmediateRenderer {

    private OverlayImmediateRenderer() {
    }

    static BufferBuilder begin(RenderType renderType) {
        return Tesselator.getInstance().begin(renderType.mode(), renderType.format());
    }

    static boolean draw(RenderType renderType, BufferBuilder buffer) {
        MeshData meshData = buffer.build();
        if (meshData == null) {
            return false;
        }

        renderType.draw(meshData);
        return true;
    }
}
