package io.github.lumi.client.preview;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;

/** Restores the section origin that vanilla fluid tessellation removes from vertex positions. */
final class PreviewFluidVertexConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    private int x;
    private int y;
    private int z;

    VertexConsumer bind(VertexConsumer target, BlockPos previewLocal) {
        delegate = target;
        x = previewLocal.getX() & ~15;
        y = previewLocal.getY() & ~15;
        z = previewLocal.getZ() & ~15;
        return this;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(this.x + x, this.y + y, this.z + z);
        return this;
    }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        delegate.setColor(r, g, b, a); return this;
    }
    @Override public VertexConsumer setColor(int color) { delegate.setColor(color); return this; }
    @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z); return this;
    }
    @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }
}
