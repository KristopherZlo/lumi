package io.github.lumi.domain.model;

public enum OperationKind {
    SAVE(0), RESTORE(1), MERGE(2), BRANCH_SWITCH(3), QUICK_ROLLBACK(4),
    CHECKPOINT_UNDO(5);

    private final int code;

    OperationKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static OperationKind fromCode(int code) {
        for (OperationKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown operation kind: " + code);
    }
}
