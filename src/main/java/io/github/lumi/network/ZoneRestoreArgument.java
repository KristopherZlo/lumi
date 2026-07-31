package io.github.lumi.network;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;
import java.util.UUID;

/** Immutable zone revision and commit identity for a scoped verified Restore. */
public record ZoneRestoreArgument(UUID zoneId, long expectedRevision, CommitId target) {
    public ZoneRestoreArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(target, "target");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected zone revision cannot be negative");
        }
    }

    public String encode() {
        return zoneId + "\n" + expectedRevision + "\n" + target.hex();
    }

    public static ZoneRestoreArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        String[] parts = encoded.split("\n", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid zone Restore argument");
        }
        try {
            long revision = Long.parseLong(parts[1]);
            if (!parts[1].equals(Long.toString(revision))) {
                throw new IllegalArgumentException("Non-canonical zone Restore revision");
            }
            return new ZoneRestoreArgument(
                    UUID.fromString(parts[0]), revision,
                    new CommitId(new ObjectId(parts[2])));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid zone Restore revision", invalid);
        }
    }
}
