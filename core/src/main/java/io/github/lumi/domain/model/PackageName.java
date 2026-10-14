package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Traversal-safe logical name for one server-owned portable package. */
public record PackageName(String value) implements Comparable<PackageName> {
    private static final Pattern SAFE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");

    public PackageName {
        Objects.requireNonNull(value, "value");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Package name must use 1-96 letters, digits, dots, dashes or underscores");
        }
    }

    @Override
    public int compareTo(PackageName other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
