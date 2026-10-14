package io.github.lumi.domain.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record WorkingIndexSnapshot(Map<HistoryKey, Long> generations) {
    public WorkingIndexSnapshot {
        generations = Map.copyOf(Objects.requireNonNull(generations, "generations"));
        if (generations.values().stream().anyMatch(value -> value == null || value < 1)) {
            throw new IllegalArgumentException("Dirty generations must be positive");
        }
    }

    public static WorkingIndexSnapshot empty() {
        return new WorkingIndexSnapshot(Map.of());
    }

    /** Returns the inclusive block bounds covered by captured block sections. */
    public Optional<BlockBox> sectionBounds() {
        long minX = Long.MAX_VALUE;
        long minY = Long.MAX_VALUE;
        long minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        long maxY = Long.MIN_VALUE;
        long maxZ = Long.MIN_VALUE;
        for (HistoryKey key : generations.keySet()) {
            if (!(key instanceof SectionKey section)) continue;
            long x = (long) section.chunkX() * 16L;
            long y = (long) section.sectionY() * 16L;
            long z = (long) section.chunkZ() * 16L;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 15L);
            maxY = Math.max(maxY, y + 15L);
            maxZ = Math.max(maxZ, z + 15L);
        }
        return minX == Long.MAX_VALUE
                ? Optional.empty()
                : Optional.of(new BlockBox(
                        Math.toIntExact(minX), Math.toIntExact(minY), Math.toIntExact(minZ),
                        Math.toIntExact(maxX), Math.toIntExact(maxY), Math.toIntExact(maxZ)));
    }
}
