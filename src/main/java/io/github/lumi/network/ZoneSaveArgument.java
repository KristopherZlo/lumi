package io.github.lumi.network;

import io.github.lumi.domain.model.VersionTags;
import java.util.Objects;
import java.util.UUID;

/** Zone identity and builder-facing message for a scoped Save. */
public record ZoneSaveArgument(UUID zoneId, String message, VersionTags tags) {
    private static final int MAX_MESSAGE_LENGTH = 256;

    public ZoneSaveArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(tags, "tags");
        message = message.trim();
        if (message.isEmpty() || message.length() > MAX_MESSAGE_LENGTH
                || message.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid zone Save message");
        }
    }

    public ZoneSaveArgument(UUID zoneId, String message) {
        this(zoneId, message, VersionTags.empty());
    }

    public String encode() {
        return zoneId + "\n" + new SaveArgument(message, tags).encode();
    }

    public static ZoneSaveArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1) {
            throw new IllegalArgumentException("Invalid zone Save argument");
        }
        SaveArgument save = SaveArgument.parse(encoded.substring(separator + 1));
        return new ZoneSaveArgument(UUID.fromString(encoded.substring(0, separator)),
                save.message(), save.tags());
    }
}
