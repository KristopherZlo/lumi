package io.github.lumi.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.lumi.domain.model.BlockChange;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
        int alpha = xray ? XRAY_ALPHA : NORMAL_ALPHA;
        Set<Long> occupied = positions(changes);
        for (BlockChange change : changes) {
            double x = change.x() - camera.x;
            double y = change.y() - camera.y;
            double z = change.z() - camera.z;
            if (distanceSquared(x + 0.5, y + 0.5, z + 0.5)
                    > MAX_DISTANCE_SQUARED) {
                continue;
            }
            int color = color(change.kind());
            for (Direction side : Direction.values()) {
                if (!occupied.contains(BlockPos.asLong(
                        change.x() + side.getStepX(),
                        change.y() + side.getStepY(),
                        change.z() + side.getStepZ()))) {
                    renderFace(context.matrices(), fills,
                            (float) x, (float) y, (float) z,
                            (float) x + 1, (float) y + 1, (float) z + 1,
                            side, (color >> 16) & 255,
                            (color >> 8) & 255, color & 255, alpha);
                }
            }
        }
        VertexConsumer lines = context.consumers().getBuffer(
                LumiCompareRenderTypes.outline(xray));
        for (BlockChange change : changes) {
            double x = change.x() - camera.x;
            double y = change.y() - camera.y;
            double z = change.z() - camera.z;
            if (distanceSquared(x + 0.5, y + 0.5, z + 0.5)
                    > MAX_DISTANCE_SQUARED) {
                continue;
            }
            int color = color(change.kind());
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

    static int exposedFaceCount(List<BlockChange> changes) {
        Set<Long> occupied = positions(changes);
        int count = 0;
        for (BlockChange change : changes) {
            for (Direction side : Direction.values()) {
                if (!occupied.contains(BlockPos.asLong(
                        change.x() + side.getStepX(),
                        change.y() + side.getStepY(),
                        change.z() + side.getStepZ()))) count++;
            }
        }
        return count;
    }

    private static Set<Long> positions(List<BlockChange> changes) {
        Set<Long> occupied = new HashSet<>();
        changes.forEach(change -> occupied.add(
                BlockPos.asLong(change.x(), change.y(), change.z())));
        return occupied;
    }

    static void renderSolidBox(
            PoseStack matrices,
            VertexConsumer consumer,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            int red,
            int green,
            int blue,
            int alpha) {
        for (Direction side : Direction.values()) {
            renderFace(matrices, consumer, x1, y1, z1, x2, y2, z2,
                    side, red, green, blue, alpha);
        }
    }

    static void renderFace(
            PoseStack matrices,
            VertexConsumer consumer,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            Direction side,
            int red, int green, int blue, int alpha) {
        PoseStack.Pose pose = matrices.last();
        switch (side) {
            case NORTH -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1);
            case SOUTH -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z2, x1, y2, z2, x2, y2, z2, x2, y1, z2);
            case WEST -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z1, x1, y2, z1, x1, y2, z2, x1, y1, z2);
            case EAST -> quad(pose, consumer, red, green, blue, alpha,
                    x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1);
            case DOWN -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y1, z1, x1, y1, z2, x2, y1, z2, x2, y1, z1);
            case UP -> quad(pose, consumer, red, green, blue, alpha,
                    x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2);
        }
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
