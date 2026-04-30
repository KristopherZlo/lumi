package io.github.luma.ui.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

final class OverlayFaceRenderer {

    private OverlayFaceRenderer() {
    }

    static int renderFilledBox(
            PoseStack matrices,
            VertexConsumer consumer,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            CompareOverlaySurfaceResolver.SurfaceBlock surfaceBlock,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        PoseStack.Pose pose = matrices.last();
        int renderedFaces = 0;

        if (surfaceBlock.northExposed()) {
            addQuad(pose, consumer, red, green, blue, alpha, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
            renderedFaces += 1;
        }
        if (surfaceBlock.southExposed()) {
            addQuad(pose, consumer, red, green, blue, alpha, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ);
            renderedFaces += 1;
        }
        if (surfaceBlock.westExposed()) {
            addQuad(pose, consumer, red, green, blue, alpha, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ);
            renderedFaces += 1;
        }
        if (surfaceBlock.eastExposed()) {
            addQuad(pose, consumer, red, green, blue, alpha, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
            renderedFaces += 1;
        }
        if (surfaceBlock.downExposed()) {
            addQuad(pose, consumer, red, green, blue, alpha, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ);
            renderedFaces += 1;
        }
        if (surfaceBlock.upExposed()) {
            addQuad(pose, consumer, red, green, blue, alpha, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
            renderedFaces += 1;
        }
        return renderedFaces;
    }

    static int renderSolidBox(
            PoseStack matrices,
            VertexConsumer consumer,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        PoseStack.Pose pose = matrices.last();
        addQuad(pose, consumer, red, green, blue, alpha, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
        addQuad(pose, consumer, red, green, blue, alpha, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        addQuad(pose, consumer, red, green, blue, alpha, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ);
        addQuad(pose, consumer, red, green, blue, alpha, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        addQuad(pose, consumer, red, green, blue, alpha, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ);
        addQuad(pose, consumer, red, green, blue, alpha, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ);
        return 6;
    }

    private static void addQuad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int red,
            int green,
            int blue,
            int alpha,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4
    ) {
        consumer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x2, y2, z2).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x3, y3, z3).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x4, y4, z4).setColor(red, green, blue, alpha);
    }
}
