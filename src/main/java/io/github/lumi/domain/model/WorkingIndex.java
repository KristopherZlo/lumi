package io.github.lumi.domain.model;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
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

    public synchronized WorkingIndexPreview preview(
            Predicate<HistoryKey> scope, int maximumSections) {
        Objects.requireNonNull(scope, "scope");
        if (maximumSections < 0) {
            throw new IllegalArgumentException("Maximum section count cannot be negative");
        }
        int total = 0;
        var sections = new ArrayList<SectionKey>(
                Math.min(maximumSections, generations.size()));
        for (HistoryKey key : generations.keySet()) {
            if (!scope.test(key)) continue;
            total++;
            if (key instanceof SectionKey section && sections.size() < maximumSections) {
                sections.add(section);
            }
        }
        return new WorkingIndexPreview(total, sections);
    }
}
