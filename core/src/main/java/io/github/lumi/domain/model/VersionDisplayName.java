package io.github.lumi.domain.model;

import java.util.Objects;

/** Bounded builder-facing name layered over an immutable commit message. */
public record VersionDisplayName(String value) {
    public static final int MAX_LENGTH = 256;

    public VersionDisplayName {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()
                || value.codePointCount(0, value.length()) > MAX_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid version name");
        }
    }
}
