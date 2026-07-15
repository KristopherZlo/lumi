package io.github.lumi.domain.model;

public enum OperationPhase {
    PREPARED(0), CAPTURING(1), WRITING_OBJECTS(2), COMMIT_WRITTEN(3), APPLYING(4), VERIFYING(5),
    REF_PUBLISHED(6), ROLLING_BACK(7), COMPLETE(8), DEGRADED(9);

    private final int code;

    OperationPhase(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static OperationPhase fromCode(int code) {
        for (OperationPhase phase : values()) {
            if (phase.code == code) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown operation phase: " + code);
    }
}
