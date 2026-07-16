package io.github.lumi.network;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;

/** Two distinct immutable commit IDs requested by a read-only Compare. */
public record CompareArgument(CommitId before, CommitId after) {
    public CompareArgument {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (before.equals(after)) {
            throw new IllegalArgumentException("Compare commits must differ");
        }
    }

    public String encode() {
        return before.hex() + "|" + after.hex();
    }

    public static CompareArgument parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] fields = value.split("\\|", -1);
        if (fields.length != 2) {
            throw new IllegalArgumentException("Invalid Compare argument");
        }
        return new CompareArgument(
                new CommitId(new ObjectId(fields[0])),
                new CommitId(new ObjectId(fields[1])));
    }
}
