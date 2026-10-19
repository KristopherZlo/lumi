package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OperationJournal(
        UUID operationId,
        OperationKind kind,
        OperationPhase phase,
        OperationTarget target,
        Optional<WorkingIndexSnapshot> capturedGenerations,
        Optional<ActionReplayTarget> actionReplay) {
    public OperationJournal {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(capturedGenerations, "capturedGenerations");
        Objects.requireNonNull(actionReplay, "actionReplay");
        if (actionReplay.isPresent() && (kind == OperationKind.SAVE
                || target.target().isEmpty() || target.returnPoint().isEmpty())) {
            throw new IllegalArgumentException("Action history publication requires both world endpoints");
        }
    }

    public OperationJournal(
            UUID operationId, OperationKind kind, OperationPhase phase, OperationTarget target,
            Optional<WorkingIndexSnapshot> capturedGenerations) {
        this(operationId, kind, phase, target, capturedGenerations, Optional.empty());
    }

    public OperationJournal(
            UUID operationId, OperationKind kind, OperationPhase phase, OperationTarget target) {
        this(operationId, kind, phase, target, Optional.empty());
    }

    public OperationJournal withPhase(OperationPhase nextPhase) {
        return new OperationJournal(operationId, kind, nextPhase, target, capturedGenerations, actionReplay);
    }

    public OperationJournal withActionReplay(ActionReplayTarget action) {
        return new OperationJournal(operationId, kind, phase, target, capturedGenerations,
                Optional.of(Objects.requireNonNull(action, "action")));
    }
}
