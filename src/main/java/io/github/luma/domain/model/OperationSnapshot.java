package io.github.luma.domain.model;

import java.time.Instant;

public record OperationSnapshot(
        OperationHandle handle,
        OperationStage stage,
        OperationProgress progress,
        String detail,
        Instant updatedAt
) {

    public boolean terminal() {
        return this.stage == OperationStage.COMPLETED || this.stage == OperationStage.FAILED;
    }

    public boolean failed() {
        return this.stage == OperationStage.FAILED;
    }
}
