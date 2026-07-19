package io.github.lumi.client.state;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.CompareResultPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Holds only the latest correlated Compare result for the current client session. */
public final class ClientCompareStore {
    private Pending pending;
    private CompareResultPayload result;
    private boolean highlightVisible = true;

    public synchronized void begin(
            UUID requestId,
            String dimensionId,
            CommitId before,
            CommitId after) {
        pending = new Pending(requestId, dimensionId, before, after);
        result = null;
        highlightVisible = true;
    }

    public synchronized void accept(CompareResultPayload candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (pending != null && pending.matches(candidate)) {
            result = candidate;
        }
    }

    public synchronized Optional<CompareResultPayload> result() {
        return Optional.ofNullable(result);
    }

    public synchronized Optional<CompareResultPayload> visibleResult() {
        return highlightVisible ? highlight() : Optional.empty();
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
        highlightVisible = true;
    }

    private Optional<CompareResultPayload> highlight() {
        return Optional.ofNullable(result)
                .filter(candidate -> candidate.error().isEmpty())
                .filter(candidate -> !candidate.sectionPreview().isEmpty());
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
