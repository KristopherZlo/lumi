package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

public record OperationJournal(
        UUID operationId, OperationKind kind, OperationPhase phase, OperationTarget target) {
    public OperationJournal {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(target, "target");
    }

    public OperationJournal withPhase(OperationPhase nextPhase) {
        return new OperationJournal(operationId, kind, nextPhase, target);
    }
}
