package io.github.lumi.network;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.VersionTags;
import java.util.Objects;

/** Commit identity and complete replacement set for mutable version tags. */
public record VersionTagsArgument(CommitId target, VersionTags tags) {
    public VersionTagsArgument {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(tags, "tags");
    }

    public String encode() {
        return target.hex() + "\n" + tags.serialize();
    }

    public static VersionTagsArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid version tags argument");
        }
        try {
            return new VersionTagsArgument(
                    new CommitId(new ObjectId(encoded.substring(0, separator))),
                    VersionTags.parse(encoded.substring(separator + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid version tags argument", invalid);
        }
    }
}
