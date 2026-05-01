package io.github.luma.domain.model;

public enum OperationStage {
    QUEUED,
    PREPARING,
    PRELOADING,
    WRITING,
    APPLYING,
    FINALIZING,
    COMPLETED,
    FAILED
}
