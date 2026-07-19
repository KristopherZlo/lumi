package io.github.lumi.network;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionDisplayName;
import java.util.Objects;

/** Commit identity and replacement builder-facing name. */
public record VersionRenameArgument(
        CommitId target, VersionDisplayName replacement) {
    public VersionRenameArgument {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(replacement, "replacement");
    }

    public String encode() {
        return target.hex() + "\n" + replacement.value();
    }

    public static VersionRenameArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid version rename argument");
        }
        try {
            return new VersionRenameArgument(
                    new CommitId(new ObjectId(encoded.substring(0, separator))),
                    new VersionDisplayName(encoded.substring(separator + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid version rename argument", invalid);
        }
    }
}
