package io.github.lumi.domain.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Canonical bounded builder tags attached to an immutable version identity. */
public record VersionTags(List<String> values) {
    public static final int MAX_TAGS = 16;
    public static final int MAX_TAG_LENGTH = 32;
    public static final int MAX_SERIALIZED_LENGTH = 128;

    public VersionTags {
        Objects.requireNonNull(values, "values");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String tag = normalize(value);
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.indexOf(',') >= 0
                    || tag.codePoints().anyMatch(Character::isISOControl)
                    || tag.codePointCount(0, tag.length()) > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("Invalid version tag");
            }
            normalized.add(tag);
            if (normalized.size() > MAX_TAGS) {
                throw new IllegalArgumentException("Too many version tags");
            }
        }
        values = List.copyOf(normalized);
        String serialized = String.join(", ", values);
        if (serialized.codePointCount(0, serialized.length()) > MAX_SERIALIZED_LENGTH) {
            throw new IllegalArgumentException("Version tags are too long");
        }
    }

    public static VersionTags empty() {
        return new VersionTags(List.of());
    }

    public static VersionTags parse(String input) {
        Objects.requireNonNull(input, "input");
        if (input.codePointCount(0, input.length()) > MAX_SERIALIZED_LENGTH) {
            throw new IllegalArgumentException("Version tags are too long");
        }
        return input.isBlank()
                ? empty() : new VersionTags(List.of(input.split(",", -1)));
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public String serialize() {
        return String.join(", ", values);
    }

    public String display() {
        return values.isEmpty() ? "" : "#" + String.join(" #", values);
    }

    private static String normalize(String value) {
        String tag = Objects.requireNonNull(value, "tag").trim();
        int prefix = 0;
        while (prefix < tag.length() && tag.charAt(prefix) == '#') {
            prefix++;
        }
        return tag.substring(prefix).trim().toLowerCase(Locale.ROOT);
    }
}
