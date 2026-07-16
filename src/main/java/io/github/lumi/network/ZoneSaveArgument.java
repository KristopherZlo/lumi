package io.github.lumi.network;

import java.util.Objects;
import java.util.UUID;

/** Zone identity and builder-facing message for a scoped Save. */
public record ZoneSaveArgument(UUID zoneId, String message) {
    private static final int MAX_MESSAGE_LENGTH = 256;

    public ZoneSaveArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(message, "message");
        message = message.trim();
        if (message.isEmpty() || message.length() > MAX_MESSAGE_LENGTH
                || message.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid zone Save message");
        }
    }

    public String encode() {
        return zoneId + "\n" + message;
    }

    public static ZoneSaveArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int separator = encoded.indexOf('\n');
        if (separator < 1 || separator != encoded.lastIndexOf('\n')) {
            throw new IllegalArgumentException("Invalid zone Save argument");
        }
        return new ZoneSaveArgument(
                UUID.fromString(encoded.substring(0, separator)),
                encoded.substring(separator + 1));
    }
}
