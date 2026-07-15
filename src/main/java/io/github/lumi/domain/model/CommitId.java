package io.github.lumi.domain.model;

import java.util.Objects;

public record CommitId(ObjectId value) {
    public CommitId {
        Objects.requireNonNull(value, "value");
    }

    public static CommitId hash(byte[] canonicalCommit) {
        return new CommitId(ObjectId.hash(canonicalCommit));
    }

    public String hex() {
        return value.hex();
    }

    @Override
    public String toString() {
        return hex();
    }
}
