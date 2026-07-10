package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Collapses dense block previews into coarse chunk-section volumes.
 */
final class OverlayVolumeMerger {

    private static final int SECTION_SIZE = 16;
    private static final int MAX_EXACT_SECTION_KEYS = 4096;
    static final int MAX_MERGED_BOXES = 2048;
    private static final int MAX_BOX_SPAN_SECTIONS = 2;

    List<OverlayBox> merge(List<BlockPoint> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }

        Set<SectionKey> sectionKeys = new HashSet<>();
        for (BlockPoint position : positions) {
            throwIfInterrupted();
            if (position != null) {
                sectionKeys.add(SectionKey.from(position));
            }
        }
        if (sectionKeys.isEmpty()) {
            return List.of();
        }
        if (sectionKeys.size() > MAX_EXACT_SECTION_KEYS) {
            return splitAdaptive(globalBox(sectionKeys), MAX_MERGED_BOXES);
        }

        List<SectionKey> ordered = new ArrayList<>(sectionKeys);
        ordered.sort(Comparator
                .comparingInt(SectionKey::sectionY)
                .thenComparingInt(SectionKey::sectionZ)
                .thenComparingInt(SectionKey::sectionX));

        Set<SectionKey> remaining = new HashSet<>(sectionKeys);
        List<OverlayBox> boxes = new ArrayList<>();
        for (SectionKey start : ordered) {
            throwIfInterrupted();
            if (!remaining.contains(start)) {
                continue;
            }

            int maxX = expandX(start, remaining);
            int maxZ = expandZ(start, maxX, remaining);
            int maxY = expandY(start, maxX, maxZ, remaining);
            removeVolume(start, maxX, maxY, maxZ, remaining);
            boxes.add(toBox(start, maxX, maxY, maxZ));
            if (boxes.size() > MAX_MERGED_BOXES) {
                return splitAdaptive(globalBox(sectionKeys), MAX_MERGED_BOXES);
            }
        }
        return limitBoxSpan(boxes);
    }

    private static int expandX(SectionKey start, Set<SectionKey> remaining) {
        int maxX = start.sectionX();
        while (remaining.contains(new SectionKey(maxX + 1, start.sectionY(), start.sectionZ()))) {
            maxX += 1;
        }
        return maxX;
    }

    private static int expandZ(SectionKey start, int maxX, Set<SectionKey> remaining) {
        int maxZ = start.sectionZ();
        while (rectangleExists(start.sectionX(), maxX, start.sectionY(), maxZ + 1, remaining)) {
            maxZ += 1;
        }
        return maxZ;
    }

    private static int expandY(SectionKey start, int maxX, int maxZ, Set<SectionKey> remaining) {
        int maxY = start.sectionY();
        while (layerExists(start.sectionX(), maxX, maxY + 1, start.sectionZ(), maxZ, remaining)) {
            maxY += 1;
        }
        return maxY;
    }

    private static boolean rectangleExists(int minX, int maxX, int sectionY, int sectionZ, Set<SectionKey> remaining) {
        for (int sectionX = minX; sectionX <= maxX; sectionX++) {
            if (!remaining.contains(new SectionKey(sectionX, sectionY, sectionZ))) {
                return false;
            }
        }
        return true;
    }

    private static boolean layerExists(
            int minX,
            int maxX,
            int sectionY,
            int minZ,
            int maxZ,
            Set<SectionKey> remaining
    ) {
        for (int sectionZ = minZ; sectionZ <= maxZ; sectionZ++) {
            if (!rectangleExists(minX, maxX, sectionY, sectionZ, remaining)) {
                return false;
            }
        }
        return true;
    }

    private static void removeVolume(
            SectionKey start,
            int maxX,
            int maxY,
            int maxZ,
            Set<SectionKey> remaining
    ) {
        for (int sectionY = start.sectionY(); sectionY <= maxY; sectionY++) {
            throwIfInterrupted();
            for (int sectionZ = start.sectionZ(); sectionZ <= maxZ; sectionZ++) {
                for (int sectionX = start.sectionX(); sectionX <= maxX; sectionX++) {
                    remaining.remove(new SectionKey(sectionX, sectionY, sectionZ));
                }
            }
        }
    }

    private static OverlayBox toBox(SectionKey start, int maxX, int maxY, int maxZ) {
        return new OverlayBox(
                start.sectionX() * SECTION_SIZE,
                start.sectionY() * SECTION_SIZE,
                start.sectionZ() * SECTION_SIZE,
                (maxX + 1) * SECTION_SIZE,
                (maxY + 1) * SECTION_SIZE,
                (maxZ + 1) * SECTION_SIZE
        );
    }

    private static OverlayBox globalBox(Set<SectionKey> sectionKeys) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (SectionKey key : sectionKeys) {
            minX = Math.min(minX, key.sectionX());
            minY = Math.min(minY, key.sectionY());
            minZ = Math.min(minZ, key.sectionZ());
            maxX = Math.max(maxX, key.sectionX());
            maxY = Math.max(maxY, key.sectionY());
            maxZ = Math.max(maxZ, key.sectionZ());
        }
        return toBox(new SectionKey(minX, minY, minZ), maxX, maxY, maxZ);
    }

    private static List<OverlayBox> limitBoxSpan(List<OverlayBox> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }

        List<OverlayBox> splitBoxes = new ArrayList<>();
        for (OverlayBox box : boxes) {
            throwIfInterrupted();
            splitFixed(box, splitBoxes);
            if (splitBoxes.size() > MAX_MERGED_BOXES) {
                return splitAdaptive(globalBox(boxes), MAX_MERGED_BOXES);
            }
        }
        return List.copyOf(splitBoxes);
    }

    private static void splitFixed(OverlayBox box, List<OverlayBox> target) {
        SectionBounds bounds = SectionBounds.from(box);
        for (int sectionY = bounds.minY(); sectionY <= bounds.maxY(); sectionY += MAX_BOX_SPAN_SECTIONS) {
            int maxY = Math.min(bounds.maxY(), sectionY + MAX_BOX_SPAN_SECTIONS - 1);
            for (int sectionZ = bounds.minZ(); sectionZ <= bounds.maxZ(); sectionZ += MAX_BOX_SPAN_SECTIONS) {
                int maxZ = Math.min(bounds.maxZ(), sectionZ + MAX_BOX_SPAN_SECTIONS - 1);
                for (int sectionX = bounds.minX(); sectionX <= bounds.maxX(); sectionX += MAX_BOX_SPAN_SECTIONS) {
                    int maxX = Math.min(bounds.maxX(), sectionX + MAX_BOX_SPAN_SECTIONS - 1);
                    target.add(toBox(new SectionKey(sectionX, sectionY, sectionZ), maxX, maxY, maxZ));
                }
            }
        }
    }

    private static List<OverlayBox> splitAdaptive(OverlayBox box, int maxBoxes) {
        SectionBounds bounds = SectionBounds.from(box);
        int[] spans = {bounds.spanX(), bounds.spanY(), bounds.spanZ()};
        int[] splits = {1, 1, 1};

        while (true) {
            int axis = largestPartAxis(spans, splits);
            if (ceilDiv(spans[axis], splits[axis]) <= MAX_BOX_SPAN_SECTIONS) {
                break;
            }
            int product = splits[0] * splits[1] * splits[2];
            int nextProduct = product / splits[axis] * (splits[axis] + 1);
            if (nextProduct > maxBoxes) {
                break;
            }
            splits[axis] += 1;
        }

        List<OverlayBox> result = new ArrayList<>(splits[0] * splits[1] * splits[2]);
        for (int splitY = 0; splitY < splits[1]; splitY++) {
            SectionRange yRange = splitRange(bounds.minY(), bounds.spanY(), splits[1], splitY);
            for (int splitZ = 0; splitZ < splits[2]; splitZ++) {
                SectionRange zRange = splitRange(bounds.minZ(), bounds.spanZ(), splits[2], splitZ);
                for (int splitX = 0; splitX < splits[0]; splitX++) {
                    SectionRange xRange = splitRange(bounds.minX(), bounds.spanX(), splits[0], splitX);
                    result.add(toBox(
                            new SectionKey(xRange.min(), yRange.min(), zRange.min()),
                            xRange.max(),
                            yRange.max(),
                            zRange.max()
                    ));
                }
            }
        }
        return List.copyOf(result);
    }

    private static OverlayBox globalBox(List<OverlayBox> boxes) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (OverlayBox box : boxes) {
            SectionBounds bounds = SectionBounds.from(box);
            minX = Math.min(minX, bounds.minX());
            minY = Math.min(minY, bounds.minY());
            minZ = Math.min(minZ, bounds.minZ());
            maxX = Math.max(maxX, bounds.maxX());
            maxY = Math.max(maxY, bounds.maxY());
            maxZ = Math.max(maxZ, bounds.maxZ());
        }
        return toBox(new SectionKey(minX, minY, minZ), maxX, maxY, maxZ);
    }

    private static int largestPartAxis(int[] spans, int[] splits) {
        int axis = 0;
        int largest = ceilDiv(spans[0], splits[0]);
        for (int index = 1; index < spans.length; index++) {
            int partSpan = ceilDiv(spans[index], splits[index]);
            if (partSpan > largest) {
                largest = partSpan;
                axis = index;
            }
        }
        return axis;
    }

    private static SectionRange splitRange(int min, int span, int splitCount, int splitIndex) {
        int offset = (int) (((long) span * splitIndex) / splitCount);
        int nextOffset = (int) (((long) span * (splitIndex + 1)) / splitCount);
        return new SectionRange(min + offset, min + nextOffset - 1);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Overlay volume preparation was interrupted");
        }
    }

    record OverlayBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        double distanceSquared(double cameraX, double cameraY, double cameraZ) {
            double nearestX = clamp(cameraX, this.minX, this.maxX);
            double nearestY = clamp(cameraY, this.minY, this.maxY);
            double nearestZ = clamp(cameraZ, this.minZ, this.maxZ);
            double dx = nearestX - cameraX;
            double dy = nearestY - cameraY;
            double dz = nearestZ - cameraZ;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private record SectionKey(int sectionX, int sectionY, int sectionZ) {

        private static SectionKey from(BlockPoint position) {
            return new SectionKey(
                    Math.floorDiv(position.x(), SECTION_SIZE),
                    Math.floorDiv(position.y(), SECTION_SIZE),
                    Math.floorDiv(position.z(), SECTION_SIZE)
            );
        }
    }

    private record SectionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        private static SectionBounds from(OverlayBox box) {
            return new SectionBounds(
                    Math.floorDiv(box.minX(), SECTION_SIZE),
                    Math.floorDiv(box.minY(), SECTION_SIZE),
                    Math.floorDiv(box.minZ(), SECTION_SIZE),
                    Math.floorDiv(box.maxX() - 1, SECTION_SIZE),
                    Math.floorDiv(box.maxY() - 1, SECTION_SIZE),
                    Math.floorDiv(box.maxZ() - 1, SECTION_SIZE)
            );
        }

        private int spanX() {
            return (this.maxX - this.minX) + 1;
        }

        private int spanY() {
            return (this.maxY - this.minY) + 1;
        }

        private int spanZ() {
            return (this.maxZ - this.minZ) + 1;
        }
    }

    private record SectionRange(int min, int max) {
    }
}
