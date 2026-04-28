package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.Bounds3i;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Dimension and chunk index for project membership checks on the mutation path.
 */
final class ProjectTrackingIndex<T> {

    private static final int MAX_INDEXED_CHUNKS_PER_PROJECT = 4_096;

    private final Map<String, DimensionIndex<T>> dimensions;

    private ProjectTrackingIndex(Map<String, DimensionIndex<T>> dimensions) {
        this.dimensions = Map.copyOf(dimensions);
    }

    static <T> ProjectTrackingIndex<T> build(Collection<Entry<T>> entries) {
        Map<String, MutableDimensionIndex<T>> mutableDimensions = new LinkedHashMap<>();
        for (Entry<T> entry : entries == null ? List.<Entry<T>>of() : entries) {
            if (entry == null || entry.value() == null || entry.dimensionId().isBlank()) {
                continue;
            }

            mutableDimensions.computeIfAbsent(entry.dimensionId(), ignored -> new MutableDimensionIndex<>())
                    .add(entry);
        }

        Map<String, DimensionIndex<T>> dimensions = new LinkedHashMap<>();
        for (Map.Entry<String, MutableDimensionIndex<T>> entry : mutableDimensions.entrySet()) {
            dimensions.put(entry.getKey(), entry.getValue().freeze());
        }
        return new ProjectTrackingIndex<>(dimensions);
    }

    List<T> matching(String dimensionId, BlockPos pos) {
        if (dimensionId == null || pos == null) {
            return List.of();
        }

        DimensionIndex<T> dimension = this.dimensions.get(dimensionId);
        return dimension == null ? List.of() : dimension.matching(pos);
    }

    record Entry<T>(String dimensionId, Bounds3i bounds, T value) {

        Entry {
            dimensionId = dimensionId == null ? "" : dimensionId;
        }

        private boolean tracksWholeDimension() {
            return this.bounds == null;
        }
    }

    private record DimensionIndex<T>(
            List<T> wholeDimension,
            Map<Long, List<BoundedEntry<T>>> chunkIndex,
            List<BoundedEntry<T>> wideBounds
    ) {

        private List<T> matching(BlockPos pos) {
            List<T> matches = new ArrayList<>(this.wholeDimension);
            List<BoundedEntry<T>> indexed = this.chunkIndex.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
            if (indexed != null) {
                addMatching(matches, indexed, pos);
            }
            addMatching(matches, this.wideBounds, pos);
            return List.copyOf(matches);
        }

        private static <T> void addMatching(List<T> matches, List<BoundedEntry<T>> candidates, BlockPos pos) {
            for (BoundedEntry<T> candidate : candidates) {
                if (candidate.contains(pos)) {
                    matches.add(candidate.value());
                }
            }
        }
    }

    private static final class MutableDimensionIndex<T> {

        private final List<T> wholeDimension = new ArrayList<>();
        private final Map<Long, List<BoundedEntry<T>>> chunkIndex = new LinkedHashMap<>();
        private final List<BoundedEntry<T>> wideBounds = new ArrayList<>();

        private void add(Entry<T> entry) {
            if (entry.tracksWholeDimension()) {
                this.wholeDimension.add(entry.value());
                return;
            }

            BoundedEntry<T> boundedEntry = new BoundedEntry<>(entry.bounds(), entry.value());
            int minChunkX = Math.floorDiv(entry.bounds().min().x(), 16);
            int maxChunkX = Math.floorDiv(entry.bounds().max().x(), 16);
            int minChunkZ = Math.floorDiv(entry.bounds().min().z(), 16);
            int maxChunkZ = Math.floorDiv(entry.bounds().max().z(), 16);
            long chunkCount = ((long) maxChunkX - minChunkX + 1L) * ((long) maxChunkZ - minChunkZ + 1L);
            if (chunkCount > MAX_INDEXED_CHUNKS_PER_PROJECT) {
                this.wideBounds.add(boundedEntry);
                return;
            }

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    this.chunkIndex.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>())
                            .add(boundedEntry);
                }
            }
        }

        private DimensionIndex<T> freeze() {
            Map<Long, List<BoundedEntry<T>>> frozenChunkIndex = new LinkedHashMap<>();
            for (Map.Entry<Long, List<BoundedEntry<T>>> entry : this.chunkIndex.entrySet()) {
                frozenChunkIndex.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new DimensionIndex<>(
                    List.copyOf(this.wholeDimension),
                    Map.copyOf(frozenChunkIndex),
                    List.copyOf(this.wideBounds)
            );
        }
    }

    private record BoundedEntry<T>(Bounds3i bounds, T value) {

        private boolean contains(BlockPos pos) {
            return pos.getX() >= this.bounds.min().x()
                    && pos.getX() <= this.bounds.max().x()
                    && pos.getY() >= this.bounds.min().y()
                    && pos.getY() <= this.bounds.max().y()
                    && pos.getZ() >= this.bounds.min().z()
                    && pos.getZ() <= this.bounds.max().z();
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }
}
