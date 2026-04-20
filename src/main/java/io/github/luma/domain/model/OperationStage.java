package io.github.luma.domain.model;

public enum OperationStage {
    QUEUED,
    PREPARING,
    WRITING,
    APPLYING,
    FINALIZING,
    COMPLETED,
    FAILED
}
