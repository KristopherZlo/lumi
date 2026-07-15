package io.github.lumi.domain.model;

import java.util.Arrays;
import java.util.Objects;

public final class CanonicalNbt {
    private final byte[] bytes;

    public CanonicalNbt(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanonicalNbt nbt && Arrays.equals(bytes, nbt.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
