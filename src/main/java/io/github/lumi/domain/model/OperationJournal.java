package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OperationJournal(
        UUID operationId,
        OperationKind kind,
        OperationPhase phase,
        OperationTarget target,
        Optional<WorkingIndexSnapshot> capturedGenerations) {
    public OperationJournal {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(capturedGenerations, "capturedGenerations");
    }

    public OperationJournal(
            UUID operationId, OperationKind kind, OperationPhase phase, OperationTarget target) {
        this(operationId, kind, phase, target, Optional.empty());
    }

    public OperationJournal withPhase(OperationPhase nextPhase) {
        return new OperationJournal(operationId, kind, nextPhase, target, capturedGenerations);
    }
}
