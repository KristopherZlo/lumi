package io.github.lumi.client.preview;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;

/** Selects one coherent, top-biased render window with bounded section volume. */
final class PreviewBoundsLimiter {
    static final int MAX_SECTIONS = 256;

    BlockBox limit(BlockBox source) {
        Objects.requireNonNull(source, "source");
        int minChunkX = Math.floorDiv(source.minX(), 16);
        int minSectionY = Math.floorDiv(source.minY(), 16);
        int minChunkZ = Math.floorDiv(source.minZ(), 16);
        int maxChunkX = Math.floorDiv(source.maxX(), 16);
        int maxSectionY = Math.floorDiv(source.maxY(), 16);
        int maxChunkZ = Math.floorDiv(source.maxZ(), 16);
        long spanX = span(minChunkX, maxChunkX);
        long spanY = span(minSectionY, maxSectionY);
        long spanZ = span(minChunkZ, maxChunkZ);
        if (spanX * spanY * spanZ <= MAX_SECTIONS) return source;
        long horizontal = spanX * spanZ;
        long keptX = spanX;
        long keptZ = spanZ;
        if (horizontal > MAX_SECTIONS) {
            double scale = Math.sqrt(MAX_SECTIONS / (double) horizontal);
            keptX = Math.max(1L, (long) Math.floor(spanX * scale));
            keptZ = Math.max(1L, (long) Math.floor(spanZ * scale));
            if (keptX * keptZ > MAX_SECTIONS) {
                if (keptX >= keptZ) {
                    keptX = Math.max(1L, MAX_SECTIONS / keptZ);
                } else {
                    keptZ = Math.max(1L, MAX_SECTIONS / keptX);
                }
            }
        }
        long keptY = Math.min(
                spanY, Math.max(1L, MAX_SECTIONS / (keptX * keptZ)));
        int selectedMinX = centeredMinimum(minChunkX, spanX, keptX);
        int selectedMinZ = centeredMinimum(minChunkZ, spanZ, keptZ);
        int selectedMinY = Math.toIntExact((long) maxSectionY - keptY + 1L);
        return new BlockBox(
                blockMinimum(selectedMinX), blockMinimum(selectedMinY),
                blockMinimum(selectedMinZ),
                blockMaximum(selectedMinX, keptX),
                blockMaximum(selectedMinY, keptY),
                blockMaximum(selectedMinZ, keptZ));
    }

    private static long span(int minimum, int maximum) {
        return (long) maximum - minimum + 1L;
    }

    private static int centeredMinimum(
            int minimum, long available, long selected) {
        return Math.toIntExact((long) minimum + (available - selected) / 2L);
    }

    private static int blockMinimum(int section) {
        return Math.toIntExact((long) section * 16L);
    }

    private static int blockMaximum(int firstSection, long count) {
        return Math.toIntExact(((long) firstSection + count) * 16L - 1L);
    }
}
