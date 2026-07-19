package io.github.lumi.network;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;

/** Builder-facing branch name and the existing save it starts from. */
public record BranchCreateArgument(BranchName name, CommitId startingPoint) {
    public BranchCreateArgument {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(startingPoint, "startingPoint");
    }

    public String encode() {
        return startingPoint.hex() + "\n" + name.value();
    }

    public static BranchCreateArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid branch creation argument");
        }
        try {
            return new BranchCreateArgument(
                    new BranchName(encoded.substring(separator + 1)),
                    new CommitId(new ObjectId(encoded.substring(0, separator))));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid branch creation argument", invalid);
        }
    }
}
