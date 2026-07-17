package io.github.lumi.client.preview;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Computes the retained legacy isometric projection for one inclusive block box. */
final class PreviewFramingCalculator {
    static final float ISO_PITCH_RADIANS = (float) Math.toRadians(35.2643897D);
    static final float ISO_YAW_RADIANS = (float) Math.toRadians(-45.0D);
    private static final float FRAME_MARGIN = 0.14F;
    private static final int MIN_RESOLUTION = 512;
    private static final int MAX_RESOLUTION = 1536;
    private static final int PIXELS_PER_BLOCK = 18;

    PreviewFraming calculate(BlockBox bounds) {
        Objects.requireNonNull(bounds, "bounds");
        long sizeX = span(bounds.minX(), bounds.maxX());
        long sizeY = span(bounds.minY(), bounds.maxY());
        long sizeZ = span(bounds.minZ(), bounds.maxZ());
        float halfX = sizeX / 2.0F;
        float halfY = sizeY / 2.0F;
        float halfZ = sizeZ / 2.0F;
        Matrix4f rotation = rotationMatrix();
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        for (float x : new float[] {-halfX, halfX}) {
            for (float y : new float[] {-halfY, halfY}) {
                for (float z : new float[] {-halfZ, halfZ}) {
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
        long dominantSpan = Math.max(
                saturatedAdd(sizeX, sizeZ),
                saturatedAdd(sizeY, Math.max(sizeX, sizeZ)));
        long requestedResolution = dominantSpan > Long.MAX_VALUE / PIXELS_PER_BLOCK
                ? Long.MAX_VALUE : dominantSpan * PIXELS_PER_BLOCK;
        int resolution = (int) Math.max(
                MIN_RESOLUTION, Math.min(MAX_RESOLUTION, requestedResolution));
        return new PreviewFraming(
                resolution, scale, offsetX, offsetY, halfX, halfY, halfZ);
    }

    static Matrix4f rotationMatrix() {
        return new Matrix4f()
                .rotateX(ISO_PITCH_RADIANS)
                .rotateY(ISO_YAW_RADIANS);
    }

    private static long span(int min, int max) {
        return (long) max - min + 1;
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    record PreviewFraming(
            int resolution,
            float scale,
            float offsetX,
            float offsetY,
            float halfX,
            float halfY,
            float halfZ) { }
}
