package io.github.luma.ui.overlay;

import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class CompareOverlaySpatialIndex {

    private static final int MAX_RENDERED_BLOCKS = 2048;
    private static final CompareOverlaySpatialIndex EMPTY = new CompareOverlaySpatialIndex(List.of());

    private final List<ChunkBucket> buckets;

    private CompareOverlaySpatialIndex(List<ChunkBucket> buckets) {
        this.buckets = buckets;
    }

    static CompareOverlaySpatialIndex build(List<DiffBlockEntry> changedBlocks) {
        if (changedBlocks == null || changedBlocks.isEmpty()) {
            return EMPTY;
        }

        Map<Long, ChunkBucketBuilder> builders = new LinkedHashMap<>();
        for (DiffBlockEntry entry : changedBlocks) {
            int chunkX = Math.floorDiv(entry.pos().x(), 16);
            int chunkZ = Math.floorDiv(entry.pos().z(), 16);
            builders.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ChunkBucketBuilder(chunkX, chunkZ))
                    .add(entry);
        }

        List<ChunkBucket> buckets = new ArrayList<>(builders.size());
        for (ChunkBucketBuilder builder : builders.values()) {
            buckets.add(builder.freeze());
        }
        return new CompareOverlaySpatialIndex(List.copyOf(buckets));
    }

    List<DiffBlockEntry> selectNearestEntries(double cameraX, double cameraY, double cameraZ) {
        if (this.buckets.isEmpty()) {
            return List.of();
        }

        List<RankedBucket> rankedBuckets = new ArrayList<>(this.buckets.size());
        for (ChunkBucket bucket : this.buckets) {
            rankedBuckets.add(new RankedBucket(bucket, bucket.minDistanceSquared(cameraX, cameraY, cameraZ)));
        }
        rankedBuckets.sort(Comparator.comparingDouble(RankedBucket::distanceSquared));

        PriorityQueue<RankedEntry> selected = new PriorityQueue<>(
                MAX_RENDERED_BLOCKS,
                Comparator.comparingDouble(RankedEntry::distanceSquared).reversed()
        );
        for (RankedBucket rankedBucket : rankedBuckets) {
            RankedEntry farthest = selected.peek();
            if (selected.size() >= MAX_RENDERED_BLOCKS
                    && farthest != null
                    && rankedBucket.distanceSquared() > farthest.distanceSquared()) {
                break;
            }

            for (DiffBlockEntry entry : rankedBucket.bucket().entries()) {
                addNearest(selected, entry, cameraX, cameraY, cameraZ);
            }
        }

        List<RankedEntry> ranked = new ArrayList<>(selected);
        ranked.sort(Comparator.comparingDouble(RankedEntry::distanceSquared));
        List<DiffBlockEntry> result = new ArrayList<>(ranked.size());
        for (RankedEntry entry : ranked) {
            result.add(entry.entry());
        }
        return List.copyOf(result);
    }

    private static void addNearest(
            PriorityQueue<RankedEntry> selected,
            DiffBlockEntry entry,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        double distanceSquared = distanceSquared(entry, cameraX, cameraY, cameraZ);
        if (selected.size() < MAX_RENDERED_BLOCKS) {
            selected.add(new RankedEntry(entry, distanceSquared));
            return;
        }

        RankedEntry farthest = selected.peek();
        if (farthest != null && distanceSquared < farthest.distanceSquared()) {
            selected.poll();
            selected.add(new RankedEntry(entry, distanceSquared));
        }
    }

    private static double distanceSquared(DiffBlockEntry entry, double cameraX, double cameraY, double cameraZ) {
        double dx = (entry.pos().x() + 0.5D) - cameraX;
        double dy = (entry.pos().y() + 0.5D) - cameraY;
        double dz = (entry.pos().z() + 0.5D) - cameraZ;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private record RankedEntry(DiffBlockEntry entry, double distanceSquared) {
    }

    private static final class ChunkBucketBuilder {

        private final int chunkX;
        private final int chunkZ;
        private final List<DiffBlockEntry> entries = new ArrayList<>();
        private int minY = Integer.MAX_VALUE;
        private int maxY = Integer.MIN_VALUE;

        private ChunkBucketBuilder(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        private void add(DiffBlockEntry entry) {
            this.entries.add(entry);
            this.minY = Math.min(this.minY, entry.pos().y());
            this.maxY = Math.max(this.maxY, entry.pos().y());
        }

        private ChunkBucket freeze() {
            return new ChunkBucket(this.chunkX, this.chunkZ, this.minY, this.maxY, List.copyOf(this.entries));
        }
    }

    private record ChunkBucket(int chunkX, int chunkZ, int minY, int maxY, List<DiffBlockEntry> entries) {

        private double minDistanceSquared(double cameraX, double cameraY, double cameraZ) {
            double nearestX = clamp(cameraX, this.chunkX << 4, (this.chunkX << 4) + 16.0D);
            double nearestY = clamp(cameraY, this.minY, this.maxY + 1.0D);
            double nearestZ = clamp(cameraZ, this.chunkZ << 4, (this.chunkZ << 4) + 16.0D);
            double dx = nearestX - cameraX;
            double dy = nearestY - cameraY;
            double dz = nearestZ - cameraZ;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private record RankedBucket(ChunkBucket bucket, double distanceSquared) {
    }
}
