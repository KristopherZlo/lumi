package io.github.luma.client.preview;

import io.github.luma.domain.model.Bounds3i;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class PreviewFramingCalculator {

    static final float ISO_PITCH_RADIANS = (float) Math.toRadians(35.2643897D);
    static final float ISO_YAW_RADIANS = (float) Math.toRadians(45.0D);
    private static final float FRAME_MARGIN = 0.14F;
    private static final int MIN_RESOLUTION = 512;
    private static final int MAX_RESOLUTION = 1536;
    private static final int PIXELS_PER_BLOCK = 18;

    PreviewFraming calculate(Bounds3i bounds) {
        float halfX = bounds.sizeX() / 2.0F;
        float halfY = bounds.sizeY() / 2.0F;
        float halfZ = bounds.sizeZ() / 2.0F;

        Matrix4f rotation = rotationMatrix();

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        for (float x : new float[]{-halfX, halfX}) {
            for (float y : new float[]{-halfY, halfY}) {
                for (float z : new float[]{-halfZ, halfZ}) {
                    Vector3f corner = new Vector3f(x, y, z).mulPosition(rotation);
                    minX = Math.min(minX, corner.x());
                    maxX = Math.max(maxX, corner.x());
                    minY = Math.min(minY, corner.y());
                    maxY = Math.max(maxY, corner.y());
                }
            }
        }

        float rangeX = Math.max(0.001F, maxX - minX);
        float rangeY = Math.max(0.001F, maxY - minY);
        float visibleSpan = 2.0F * (1.0F - FRAME_MARGIN);
        float scale = Math.min(visibleSpan / rangeX, visibleSpan / rangeY);
        float offsetX = -((minX + maxX) * 0.5F) * scale;
        float offsetY = -((minY + maxY) * 0.5F) * scale;

        int dominantSpan = Math.max(
                bounds.sizeX() + bounds.sizeZ(),
                bounds.sizeY() + Math.max(bounds.sizeX(), bounds.sizeZ())
        );
        int resolution = clamp(dominantSpan * PIXELS_PER_BLOCK, MIN_RESOLUTION, MAX_RESOLUTION);

        return new PreviewFraming(resolution, scale, offsetX, offsetY, halfX, halfY, halfZ);
    }

    static Matrix4f rotationMatrix() {
        return new Matrix4f()
                .rotateX(ISO_PITCH_RADIANS)
                .rotateY(ISO_YAW_RADIANS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record PreviewFraming(
            int resolution,
            float scale,
            float offsetX,
            float offsetY,
            float halfX,
            float halfY,
            float halfZ
    ) {
    }
}
