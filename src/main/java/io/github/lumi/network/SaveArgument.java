package io.github.lumi.network;

import io.github.lumi.domain.model.VersionTags;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Versioned Save message and tags, with raw-message compatibility for older clients. */
public record SaveArgument(String message, VersionTags tags) {
    private static final String PREFIX = "LST1:";
    private static final int MAX_MESSAGE_LENGTH = 256;

    public SaveArgument {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(tags, "tags");
        if (message.isBlank()
                || message.codePointCount(0, message.length()) > MAX_MESSAGE_LENGTH
                || message.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Save message");
        }
    }

    public String encode() {
        return PREFIX + encodeText(message) + ":" + encodeText(tags.serialize());
    }

    public static SaveArgument parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (!encoded.startsWith(PREFIX)) {
            return new SaveArgument(encoded, VersionTags.empty());
        }
        String[] fields = encoded.split(":", -1);
        if (fields.length != 3 || !fields[0].equals("LST1")) {
            throw new IllegalArgumentException("Invalid versioned Save argument");
        }
        try {
            return new SaveArgument(
                    decodeText(fields[1]), VersionTags.parse(decodeText(fields[2])));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid versioned Save argument", invalid);
        }
    }

    private static String encodeText(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (!encodeText(new String(bytes, StandardCharsets.UTF_8)).equals(value)) {
            throw new IllegalArgumentException("Non-canonical Save argument text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
