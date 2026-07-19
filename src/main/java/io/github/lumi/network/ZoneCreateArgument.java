package io.github.lumi.network;

import java.util.Objects;

/** Bounded name used to create empty project-scoped zone metadata. */
public record ZoneCreateArgument(String name) {
    private static final int MAX_NAME_LENGTH = 256;

    public ZoneCreateArgument {
        Objects.requireNonNull(name, "name");
        name = name.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid zone name");
        }
    }

    public String encode() {
        return name;
    }

    public static ZoneCreateArgument parse(String encoded) {
        return new ZoneCreateArgument(Objects.requireNonNull(encoded, "encoded"));
    }
}
