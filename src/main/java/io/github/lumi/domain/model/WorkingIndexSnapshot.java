package io.github.lumi.domain.model;

import java.util.Map;
import java.util.Objects;

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
}
