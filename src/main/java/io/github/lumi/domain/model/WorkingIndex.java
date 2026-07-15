package io.github.lumi.domain.model;

import java.util.HashMap;
import java.util.Map;

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
}
