package io.github.lumi.network;

import io.github.lumi.domain.model.VersionTags;
import java.util.Objects;
import java.util.UUID;

/** Immutable zone revision and builder-facing message for a scoped Save. */
public record ZoneSaveArgument(
        UUID zoneId, long expectedRevision, String message, VersionTags tags) {
    private static final int MAX_MESSAGE_LENGTH = 256;

    public ZoneSaveArgument {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(tags, "tags");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected zone revision cannot be negative");
        }
        message = message.trim();
        if (message.isEmpty() || message.length() > MAX_MESSAGE_LENGTH
                || message.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid zone Save message");
        }
    }

    public String encode() {
        return zoneId + "\n" + expectedRevision + "\n"
                + new SaveArgument(message, tags).encode();
    }

    public static ZoneSaveArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        int first = encoded.indexOf('\n');
        int second = encoded.indexOf('\n', first + 1);
        if (first < 1 || second < 0) {
            throw new IllegalArgumentException("Invalid zone Save argument");
        }
        try {
            String encodedRevision = encoded.substring(first + 1, second);
            long revision = Long.parseLong(encodedRevision);
            if (!encodedRevision.equals(Long.toString(revision))) {
                throw new IllegalArgumentException("Non-canonical zone Save revision");
            }
            SaveArgument save = SaveArgument.parse(encoded.substring(second + 1));
            return new ZoneSaveArgument(
                    UUID.fromString(encoded.substring(0, first)), revision,
                    save.message(), save.tags());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid zone Save revision", invalid);
        }
    }
}
