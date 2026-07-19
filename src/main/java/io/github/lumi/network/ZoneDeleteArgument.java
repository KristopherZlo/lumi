package io.github.lumi.network;

import java.util.Objects;
import java.util.UUID;

/** Revision-checked request to delete zone metadata without deleting history. */
public record ZoneDeleteArgument(UUID zoneId, long expectedRevision) {
    public ZoneDeleteArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "Expected zone revision cannot be negative");
        }
    }

    public String encode() {
        return zoneId + "\n" + expectedRevision;
    }

    public static ZoneDeleteArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid zone delete argument");
        }
        try {
            return new ZoneDeleteArgument(
                    UUID.fromString(encoded.substring(0, separator)),
                    Long.parseLong(encoded.substring(separator + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid zone delete argument", invalid);
        }
    }
}
