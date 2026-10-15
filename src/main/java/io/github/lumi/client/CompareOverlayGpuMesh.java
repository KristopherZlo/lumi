package io.github.lumi.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.function.Consumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.Direction;

/** Owns retained GPU vertices for one spatial Compare batch. */
final class CompareOverlayGpuMesh implements AutoCloseable {
    private static final Direction[] SIDES = Direction.values();
    private final Layer fills;
    private final Layer outlines;

    CompareOverlayGpuMesh(CompareOverlayGeometry.Section section) {
        fills = upload(LumiCompareRenderTypes.fill(false), builder -> {
            PoseStack pose = new PoseStack();
            for (var block : section.blocks()) {
                var change = block.change();
                float x = change.x() - section.x();
                float y = change.y() - section.y();
                float z = change.z() - section.z();
                int color = LumiCompareOverlayRenderer.color(change.kind());
                for (Direction side : SIDES) {
                    if ((block.faces() & (1 << side.ordinal())) != 0) {
                        LumiCompareOverlayRenderer.renderFace(pose.last(), builder,
                                x, y, z, x + 1, y + 1, z + 1, side,
                                color >> 16 & 255, color >> 8 & 255, color & 255, 255);
                    }
                }
            }
        });
        try {
            outlines = upload(LumiCompareRenderTypes.outline(false), builder -> {
                for (var block : section.blocks()) {
                    var change = block.change();
                    int color = LumiCompareOverlayRenderer.color(change.kind());
                    for (int edge = 0; edge < 12; edge++) {
                        if ((block.edges() & (1 << edge)) == 0) continue;
                        int axis = edge / 4, corner = edge % 4;
                        int first = (axis + 1) % 3, second = (axis + 2) % 3;
                        float x = change.x() - section.x() + cornerOffset(0, first, second, corner);
                        float y = change.y() - section.y() + cornerOffset(1, first, second, corner);
                        float z = change.z() - section.z() + cornerOffset(2, first, second, corner);
                        vertex(builder, x, y, z, axis, color);
                        vertex(builder, x + (axis == 0 ? 1 : 0),
                                y + (axis == 1 ? 1 : 0), z + (axis == 2 ? 1 : 0), axis, color);
                    }
                }
            });
        } catch (RuntimeException | Error failed) {
            if (fills != null) fills.buffer().close();
            throw failed;
        }
    }

    Layer layer(boolean outline) {
        return outline ? outlines : fills;
    }

    long bytes() {
        return (fills == null ? 0 : fills.buffer().size())
                + (outlines == null ? 0 : outlines.buffer().size());
    }

    static long estimatedBytes(CompareOverlayGeometry.Section section) {
        return (long) section.faceCount() * 4 * LumiCompareRenderTypes.fill(false).format().getVertexSize()
                + (long) section.edgeCount() * 4 * LumiCompareRenderTypes.outline(false).format().getVertexSize();
    }

    @Override
    public void close() {
        if (fills != null) fills.buffer().close();
        if (outlines != null) outlines.buffer().close();
    }

    private static int cornerOffset(int axis, int first, int second, int corner) {
        return axis == first ? corner & 1 : axis == second ? (corner >> 1) & 1 : 0;
    }

    private static void vertex(VertexConsumer builder, float x, float y, float z, int axis, int color) {
        builder.addVertex(x, y, z).setColor(color)
                .setNormal(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0)
                .setLineWidth(2.75F);
    }

    private static Layer upload(RenderType type, Consumer<BufferBuilder> vertices) {
        try (ByteBufferBuilder scratch = new ByteBufferBuilder(65536)) {
            var builder = new BufferBuilder(scratch, type.mode(), type.format());
            vertices.accept(builder);
            try (var mesh = builder.build()) {
                if (mesh == null) return null;
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                        () -> "Lumi Compare vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                return new Layer(buffer, type.mode(), mesh.drawState().indexCount());
            }
        }
    }

    record Layer(GpuBuffer buffer, VertexFormat.Mode mode, int indexCount) { }
}
