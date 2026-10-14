package io.github.lumi.domain.model;

import java.util.Objects;

/** Source version of the vanilla representation in one immutable history leaf. */
public record WorldDataVersion(int value) {
    // LUS2/LUE2 were only produced by the Minecraft 1.21.11 adapter.
    public static final WorldDataVersion LEGACY_12111 = new WorldDataVersion(4671);

    public WorldDataVersion {
        if (value < 1) throw new IllegalArgumentException("World data version must be positive");
    }

    public void requireSame(WorldDataVersion other) {
        if (!equals(Objects.requireNonNull(other, "other"))) {
            throw new IllegalArgumentException("Convert world payloads before combining data versions "
                    + value + " and " + other.value);
        }
    }
}
