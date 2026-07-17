package io.github.lumi.domain.model;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class WorkingIndex {
    private final Map<HistoryKey, Long> generations;

    public WorkingIndex() {
        generations = new HashMap<>();
    }

    public WorkingIndex(WorkingIndexSnapshot snapshot) {
        generations = new HashMap<>(snapshot.generations());
    }

    public synchronized long markDirty(HistoryKey key) {
        long next = Math.incrementExact(generations.getOrDefault(key, 0L));
        generations.put(key, next);
        return next;
    }

    public synchronized void clearCaptured(WorkingIndexSnapshot captured) {
        captured.generations().forEach(generations::remove);
    }

    public synchronized WorkingIndexSnapshot snapshot() {
        return new WorkingIndexSnapshot(generations);
    }

    public synchronized Long generation(HistoryKey key) {
        return generations.get(Objects.requireNonNull(key, "key"));
    }

    public synchronized boolean isEmpty() {
        return generations.isEmpty();
    }

    public synchronized WorkingIndexPreview preview(
            Predicate<HistoryKey> scope, int maximumSections) {
        Objects.requireNonNull(scope, "scope");
        if (maximumSections < 0) {
            throw new IllegalArgumentException("Maximum section count cannot be negative");
        }
        int total = 0;
        var sections = new ArrayList<SectionKey>(
                Math.min(maximumSections, generations.size()));
        long minX = Long.MAX_VALUE;
        long minY = Long.MAX_VALUE;
        long minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        long maxY = Long.MIN_VALUE;
        long maxZ = Long.MIN_VALUE;
        for (HistoryKey key : generations.keySet()) {
            if (!scope.test(key)) continue;
            total++;
            if (key instanceof SectionKey section) {
                if (sections.size() < maximumSections) {
                    sections.add(section);
                }
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
        }
        Optional<BlockBox> bounds = minX == Long.MAX_VALUE
                ? Optional.empty()
                : Optional.of(new BlockBox(
                        Math.toIntExact(minX), Math.toIntExact(minY), Math.toIntExact(minZ),
                        Math.toIntExact(maxX), Math.toIntExact(maxY), Math.toIntExact(maxZ)));
        return new WorkingIndexPreview(total, sections, java.util.List.of(), bounds);
    }
}
