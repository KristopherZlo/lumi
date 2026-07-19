package io.github.lumi.domain.model;

import java.util.List;
import java.util.Objects;

/** One bounded immutable window into a builder-visible commit history. */
public record HistoryPage(
        int offset,
        List<HistoryEntry> entries,
        boolean hasMore) {
    public HistoryPage {
        if (offset < 0) {
            throw new IllegalArgumentException("History offset cannot be negative");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
