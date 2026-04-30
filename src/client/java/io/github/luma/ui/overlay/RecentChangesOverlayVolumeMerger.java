package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collapses dense block previews into coarse chunk-section volumes.
 */
final class RecentChangesOverlayVolumeMerger {

    private static final int SECTION_SIZE = 16;
    private static final int MAX_EXACT_SECTION_KEYS = 4096;
    private static final int MAX_MERGED_BOXES = 128;

    List<OverlayBox> merge(List<BlockPoint> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }

        Set<SectionKey> sectionKeys = new HashSet<>();
        for (BlockPoint position : positions) {
            if (position != null) {
                sectionKeys.add(SectionKey.from(position));
            }
        }
        if (sectionKeys.isEmpty()) {
            return List.of();
        }
        if (sectionKeys.size() > MAX_EXACT_SECTION_KEYS) {
            return List.of(globalBox(sectionKeys));
        }

        List<SectionKey> ordered = new ArrayList<>(sectionKeys);
        ordered.sort(Comparator
                .comparingInt(SectionKey::sectionY)
                .thenComparingInt(SectionKey::sectionZ)
                .thenComparingInt(SectionKey::sectionX));

        Set<SectionKey> remaining = new HashSet<>(sectionKeys);
        List<OverlayBox> boxes = new ArrayList<>();
        for (SectionKey start : ordered) {
            if (!remaining.contains(start)) {
                continue;
            }

            int maxX = expandX(start, remaining);
            int maxZ = expandZ(start, maxX, remaining);
            int maxY = expandY(start, maxX, maxZ, remaining);
            removeVolume(start, maxX, maxY, maxZ, remaining);
            boxes.add(toBox(start, maxX, maxY, maxZ));
            if (boxes.size() > MAX_MERGED_BOXES) {
                return List.of(globalBox(sectionKeys));
            }
        }
        return List.copyOf(boxes);
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
}
