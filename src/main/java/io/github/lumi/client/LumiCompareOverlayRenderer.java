package io.github.lumi.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.lumi.domain.model.BlockChange;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.world.phys.shapes.Shapes;

/** Renders exact directional Compare blocks without reading client world state. */
final class LumiCompareOverlayRenderer {
    private static final double MAX_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final int NORMAL_ALPHA = 48;
    private static final int XRAY_ALPHA = 96;

    void render(
            WorldRenderContext context,
            List<BlockChange> changes,
            boolean xray) {
        if (changes.isEmpty()) return;
        var camera = context.worldState().cameraRenderState.pos;
        VertexConsumer fills = context.consumers().getBuffer(
                LumiCompareRenderTypes.fill(xray));
        VertexConsumer lines = context.consumers().getBuffer(
                LumiCompareRenderTypes.outline(xray));
        int alpha = xray ? XRAY_ALPHA : NORMAL_ALPHA;
        for (BlockChange change : changes) {
            double x = change.x() - camera.x;
            double y = change.y() - camera.y;
            double z = change.z() - camera.z;
            if (distanceSquared(x + 0.5, y + 0.5, z + 0.5)
                    > MAX_DISTANCE_SQUARED) {
                continue;
            }
            int color = color(change.kind());
            renderSolidBox(
                    context.matrices(), fills,
                    (float) x, (float) y, (float) z,
                    (color >> 16) & 255, (color >> 8) & 255, color & 255, alpha);
            ShapeRenderer.renderShape(
                    context.matrices(), lines, Shapes.block(),
                    x, y, z, color, 2.75F);
        }
    }

    static int color(BlockChange.Kind kind) {
        return switch (kind) {
            case ADDED -> 0xff55ff55;
            case REMOVED -> 0xffff5555;
            case CHANGED -> 0xffffd455;
        };
    }

    private static double distanceSquared(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    private static void renderSolidBox(
            PoseStack matrices,
            VertexConsumer consumer,
            float x,
            float y,
            float z,
            int red,
            int green,
            int blue,
            int alpha) {
        PoseStack.Pose pose = matrices.last();
        quad(pose, consumer, red, green, blue, alpha,
                x, y, z, x + 1, y, z, x + 1, y + 1, z, x, y + 1, z);
        quad(pose, consumer, red, green, blue, alpha,
                x, y, z + 1, x, y + 1, z + 1,
                x + 1, y + 1, z + 1, x + 1, y, z + 1);
        quad(pose, consumer, red, green, blue, alpha,
                x, y, z, x, y + 1, z, x, y + 1, z + 1, x, y, z + 1);
        quad(pose, consumer, red, green, blue, alpha,
                x + 1, y, z, x + 1, y, z + 1,
                x + 1, y + 1, z + 1, x + 1, y + 1, z);
        quad(pose, consumer, red, green, blue, alpha,
                x, y, z, x, y, z + 1, x + 1, y, z + 1, x + 1, y, z);
        quad(pose, consumer, red, green, blue, alpha,
                x, y + 1, z, x + 1, y + 1, z,
                x + 1, y + 1, z + 1, x, y + 1, z + 1);
    }

    private static void quad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int red,
            int green,
            int blue,
            int alpha,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4) {
        consumer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x2, y2, z2).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x3, y3, z3).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x4, y4, z4).setColor(red, green, blue, alpha);
    }
}
