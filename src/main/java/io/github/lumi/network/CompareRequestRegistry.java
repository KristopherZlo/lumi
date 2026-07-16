package io.github.lumi.network;

import io.github.lumi.domain.model.ComparisonSummary;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Owns cancellable Compare request identity and player lifecycle. */
final class CompareRequestRegistry {
    private final Map<UUID, Job> jobs = new HashMap<>();

    synchronized Job start(UUID requestId, UUID playerId, Starter starter)
            throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(starter, "starter");
        if (jobs.containsKey(requestId)) {
            throw new IllegalStateException("Compare request already exists");
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<ComparisonSummary> future =
                Objects.requireNonNull(starter.start(cancelled::get), "future");
        Job job = new Job(playerId, cancelled, future);
        jobs.put(requestId, job);
        return job;
    }

    synchronized boolean finish(UUID requestId, Job expected) {
        return jobs.remove(
                Objects.requireNonNull(requestId, "requestId"),
                Objects.requireNonNull(expected, "expected"));
    }

    synchronized boolean cancelOwned(UUID requestId, UUID playerId) {
        Job job = jobs.get(Objects.requireNonNull(requestId, "requestId"));
        if (job == null || !job.playerId().equals(
                Objects.requireNonNull(playerId, "playerId"))) {
            return false;
        }
        jobs.remove(requestId);
        job.cancel();
        return true;
    }

    synchronized void cancelPlayer(UUID playerId) {
        var iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next().getValue();
            if (job.playerId().equals(playerId)) {
                iterator.remove();
                job.cancel();
            }
        }
    }

    synchronized void clear() {
        jobs.values().forEach(Job::cancel);
        jobs.clear();
    }

    @FunctionalInterface
    interface Starter {
        CompletableFuture<ComparisonSummary> start(BooleanSupplier cancelled)
                throws IOException;
    }

    record Job(
            UUID playerId,
            AtomicBoolean cancelled,
            CompletableFuture<ComparisonSummary> future) {
        Job {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(cancelled, "cancelled");
            Objects.requireNonNull(future, "future");
        }

        private void cancel() {
            cancelled.set(true);
            future.cancel(false);
        }
    }
}
