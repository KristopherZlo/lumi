package io.github.lumi.network;

import io.github.lumi.domain.model.BranchName;
import java.util.Objects;

/** Canonical source branch and builder-facing message for one confirmed merge. */
public record MergeArgument(String sourceBranch, String message) {
    public MergeArgument {
        sourceBranch = new BranchName(
                Objects.requireNonNull(sourceBranch, "sourceBranch")).value();
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("Merge message cannot be blank");
        }
    }

    public String encode() {
        return sourceBranch + "\n" + message;
    }

    public static MergeArgument parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf('\n');
        if (separator < 1 || separator == value.length() - 1) {
            throw new IllegalArgumentException("Invalid Merge argument");
        }
        return new MergeArgument(
                value.substring(0, separator), value.substring(separator + 1));
    }
}
