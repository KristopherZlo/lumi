package io.github.lumi.domain.model;

/** Exact directional block totals between the current world and saved HEAD. */
public record PendingChangeStatistics(long added, long removed, long changed) {
    public static final PendingChangeStatistics NONE =
            new PendingChangeStatistics(0, 0, 0);

    public PendingChangeStatistics {
        if (added < 0 || removed < 0 || changed < 0) {
            throw new IllegalArgumentException(
                    "Pending change statistics cannot be negative");
        }
    }

    public long total() {
        return Math.addExact(Math.addExact(added, removed), changed);
    }

    public PendingChangeStatistics plus(PendingChangeStatistics other) {
        return new PendingChangeStatistics(
                Math.addExact(added, other.added),
                Math.addExact(removed, other.removed),
                Math.addExact(changed, other.changed));
    }
}
