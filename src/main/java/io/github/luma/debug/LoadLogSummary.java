package io.github.luma.debug;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates measured Lumi runtime costs by stable area/name pairs.
 */
final class LoadLogSummary {

    private final Map<LoadLogKey, LoadLogEntry> entries = new HashMap<>();

    void record(String area, String name, long elapsedNanos) {
        if (elapsedNanos <= 0L) {
            return;
        }

        LoadLogKey key = new LoadLogKey(normalize(area), normalize(name));
        this.entries.computeIfAbsent(key, ignored -> new LoadLogEntry(key)).record(elapsedNanos);
    }

    List<LoadLogEntrySnapshot> topByTotal(int limit) {
        int normalizedLimit = Math.max(1, limit);
        return this.entries.values().stream()
                .map(LoadLogEntry::snapshot)
                .sorted(Comparator.comparingLong(LoadLogEntrySnapshot::totalNanos).reversed()
                        .thenComparing(LoadLogEntrySnapshot::area)
                        .thenComparing(LoadLogEntrySnapshot::name))
                .limit(normalizedLimit)
                .toList();
    }

    boolean empty() {
        return this.entries.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record LoadLogKey(String area, String name) {
    }

    private static final class LoadLogEntry {

        private final LoadLogKey key;
        private long count;
        private long totalNanos;
        private long maxNanos;

        private LoadLogEntry(LoadLogKey key) {
            this.key = key;
        }

        private void record(long elapsedNanos) {
            this.count += 1L;
            this.totalNanos += elapsedNanos;
            this.maxNanos = Math.max(this.maxNanos, elapsedNanos);
        }

        private LoadLogEntrySnapshot snapshot() {
            return new LoadLogEntrySnapshot(
                    this.key.area(),
                    this.key.name(),
                    this.count,
                    this.totalNanos,
                    this.maxNanos
            );
        }
    }

    record LoadLogEntrySnapshot(
            String area,
            String name,
            long count,
            long totalNanos,
            long maxNanos
    ) {

        long averageNanos() {
            return this.count <= 0L ? 0L : this.totalNanos / this.count;
        }
    }
}
