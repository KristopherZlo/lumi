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

    public synchronized void begin(
            UUID requestId,
            String dimensionId,
            CommitId before,
            CommitId after) {
        pending = new Pending(requestId, dimensionId, before, after);
        result = null;
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

    public synchronized void clear() {
        pending = null;
        result = null;
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
