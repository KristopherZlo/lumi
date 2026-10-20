package io.github.lumi.client.state;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.network.CompareResultPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Holds only the latest correlated Compare result for the current client session. */
public final class ClientCompareStore {
    private Pending pending;
    private CompareResultPayload result;
    private List<BlockChange> changes = new ArrayList<>();
    private List<BlockChange> publishedChanges = List.of();
    private int nextBatch;
    private boolean highlightVisible = true;

    public synchronized void begin(
            UUID requestId,
            String dimensionId,
            CommitId before,
            CommitId after) {
        pending = new Pending(requestId, dimensionId, before, after);
        result = null;
        changes = new ArrayList<>();
        publishedChanges = List.of();
        nextBatch = 0;
        highlightVisible = true;
    }

    public synchronized boolean accept(CompareResultPayload candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (pending != null && result == null && pending.matches(candidate)
                && candidate.batchIndex() >= nextBatch) {
            if (candidate.batchIndex() != nextBatch) {
                result = new CompareResultPayload(candidate.requestId(), candidate.dimensionId(), candidate.before(), candidate.after(),
                        0, 0, List.of(), List.of(), "luma.status.compare_failed", nextBatch, true, List.of(), 0);
                changes = new ArrayList<>();
                return true;
            }
            nextBatch++;
            changes.addAll(candidate.blockChanges());
            if (candidate.complete()) {
                result = candidate;
                publishedChanges = candidate.error().isEmpty() ? List.copyOf(changes) : List.of();
                changes = new ArrayList<>();
            }
            return true;
        }
        return false;
    }

    public synchronized Optional<CompareResultPayload> result() {
        return Optional.ofNullable(result);
    }

    public synchronized Optional<CompareResultPayload> visibleResult() {
        return highlightVisible ? highlight() : Optional.empty();
    }

    public synchronized List<BlockChange> visibleChanges() {
        return highlightVisible ? publishedChanges : List.of();
    }

    public synchronized List<BlockChange> blockChanges() {
        return publishedChanges;
    }

    public synchronized Optional<Boolean> toggleVisibility() {
        if (highlight().isEmpty()) {
            return Optional.empty();
        }
        highlightVisible = !highlightVisible;
        return Optional.of(highlightVisible);
    }

    public synchronized boolean highlightVisible() {
        return highlightVisible && highlight().isPresent();
    }

    public synchronized Optional<UUID> pendingRequest() {
        return pending != null && result == null ? Optional.of(pending.requestId()) : Optional.empty();
    }

    public synchronized boolean isInDimension(String dimension) {
        return pending != null && pending.dimensionId().equals(dimension);
    }

    public synchronized Optional<UUID> retainDimension(String dimension) {
        if (pending == null || isInDimension(dimension)) return Optional.empty();
        var cancelled = pendingRequest();
        clear();
        return cancelled;
    }

    public synchronized void cancel(UUID request) {
        if (pending != null && pending.requestId().equals(request)) clear();
    }

    public synchronized void clear() {
        pending = null;
        result = null;
        changes = new ArrayList<>();
        publishedChanges = List.of();
        nextBatch = 0;
        highlightVisible = true;
    }

    private Optional<CompareResultPayload> highlight() {
        return Optional.ofNullable(result)
                .filter(candidate -> candidate.error().isEmpty())
                .filter(candidate -> !publishedChanges.isEmpty()
                        || !candidate.sectionPreview().isEmpty());
    }

    private record Pending(
            UUID requestId,
            String dimensionId,
            CommitId before,
            CommitId after) {
        private Pending {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }

        private boolean matches(CompareResultPayload candidate) {
            return requestId.equals(candidate.requestId())
                    && dimensionId.equals(candidate.dimensionId())
                    && before.equals(candidate.before())
                    && after.equals(candidate.after());
        }
    }
}
