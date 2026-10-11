package io.github.lumi.network;

import java.util.Objects;
import java.util.UUID;

/** One revision-checked zone pending-preview scope. */
public record ZonePendingPreviewArgument(UUID zoneId, long expectedRevision) {
    public ZonePendingPreviewArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        if (expectedRevision < 0) throw new IllegalArgumentException("Negative zone revision");
    }

    public String encode() {
        return zoneId + "\n" + expectedRevision;
    }

    public static ZonePendingPreviewArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid zone preview scope");
        }
        return new ZonePendingPreviewArgument(UUID.fromString(encoded.substring(0, separator)),
                Long.parseLong(encoded.substring(separator + 1)));
    }
}
