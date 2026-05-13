package io.github.luma.minecraft.debug;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.WorldMutationSource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * Keeps high-volume capture rejection diagnostics useful without writing one
 * log line for every ambient world tick mutation.
 */
public final class CaptureSkipLogThrottler {

    static final int SAMPLE_LIMIT = 3;
    static final int SUMMARY_INTERVAL = 2048;

    private final Map<Key, Counter> counters = new HashMap<>();

    public synchronized Decision record(
            BuildProject project,
            WorldMutationSource source,
            String reason,
            BlockPos latestPos
    ) {
        if (project == null) {
            return Decision.suppressed(0, 0, latestPos);
        }
        Key key = new Key(project.id(), source, reason == null || reason.isBlank() ? "unknown" : reason);
        Counter counter = this.counters.computeIfAbsent(key, ignored -> new Counter());
        long count = counter.record();
        if (count <= SAMPLE_LIMIT) {
            counter.markLogged(count);
            return Decision.sample(count, 0, latestPos);
        }
        if ((count - SAMPLE_LIMIT) % SUMMARY_INTERVAL == 0) {
            long suppressedSinceLastLog = count - counter.lastLoggedCount();
            counter.markLogged(count);
            return Decision.summary(count, suppressedSinceLastLog, latestPos);
        }
        return Decision.suppressed(count, count - counter.lastLoggedCount(), latestPos);
    }

    public record Decision(
            boolean logSample,
            boolean logSummary,
            long totalCount,
            long suppressedSinceLastLog,
            BlockPos latestPos
    ) {
        public boolean shouldLog() {
            return this.logSample || this.logSummary;
        }

        private static Decision sample(long totalCount, long suppressedSinceLastLog, BlockPos latestPos) {
            return new Decision(true, false, totalCount, suppressedSinceLastLog, latestPos);
        }

        private static Decision summary(long totalCount, long suppressedSinceLastLog, BlockPos latestPos) {
            return new Decision(false, true, totalCount, suppressedSinceLastLog, latestPos);
        }

        private static Decision suppressed(long totalCount, long suppressedSinceLastLog, BlockPos latestPos) {
            return new Decision(false, false, totalCount, suppressedSinceLastLog, latestPos);
        }
    }

    private record Key(UUID projectId, WorldMutationSource source, String reason) {
    }

    private static final class Counter {
        private long count;
        private long lastLoggedCount;

        long record() {
            this.count += 1;
            return this.count;
        }

        long lastLoggedCount() {
            return this.lastLoggedCount;
        }

        void markLogged(long count) {
            this.lastLoggedCount = count;
        }
    }
}
