package io.github.lumi.network;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Objects;
import java.util.UUID;

/** Immutable zone and commit identities for a partial verified Restore. */
public record ZoneRestoreArgument(UUID zoneId, CommitId target) {
    public ZoneRestoreArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(target, "target");
    }

    public String encode() {
        return zoneId + "\n" + target.hex();
    }

    public static ZoneRestoreArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid zone Restore argument");
        }
        return new ZoneRestoreArgument(
                UUID.fromString(encoded.substring(0, separator)),
                new CommitId(new ObjectId(encoded.substring(separator + 1))));
    }
}
