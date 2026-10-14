package io.github.lumi.domain.model;

public enum CommitKind {
    MANUAL(0),
    AMEND(1),
    AUTO(2),
    ZONE(3),
    HIDDEN_SAFETY(4),
    HIDDEN_RETURN(5),
    MERGE(6),
    IMPORT(7),
    RESTORE(8);

    private final int code;

    CommitKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static CommitKind fromCode(int code) {
        for (CommitKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown commit kind code: " + code);
    }
}
