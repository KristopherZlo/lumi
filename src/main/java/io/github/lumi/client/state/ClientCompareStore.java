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
    private final List<BlockChange> changes = new ArrayList<>();
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
        changes.clear();
        publishedChanges = List.of();
        nextBatch = 0;
        highlightVisible = true;
    }

    public synchronized void accept(CompareResultPayload candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (pending != null && pending.matches(candidate)
                && candidate.batchIndex() == nextBatch) {
            nextBatch++;
            changes.addAll(candidate.blockChanges());
            publishedChanges = List.copyOf(changes);
            if (candidate.complete()) {
                result = candidate;
                if (!candidate.error().isEmpty()) {
                    changes.clear();
                    publishedChanges = List.of();
                }
            }
        }
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

    public synchronized boolean hasHighlight() {
        return highlight().isPresent();
    }

    public synchronized void clear() {
        pending = null;
        result = null;
        changes.clear();
        publishedChanges = List.of();
        nextBatch = 0;
        highlightVisible = true;
    }

    private Optional<CompareResultPayload> highlight() {
        return Optional.ofNullable(result)
                .filter(candidate -> candidate.error().isEmpty())
                .filter(candidate -> !changes.isEmpty()
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
