package io.github.lumi.domain.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;

public final class CanonicalNbt {
    private final byte[] bytes;
    private volatile ObjectId identity;
    private final int size;
    private final Content stored;

    public CanonicalNbt(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        size = bytes.length;
        stored = null;
    }

    private CanonicalNbt(CanonicalNbt value, Content stored) {
        bytes = null;
        identity = value.identity();
        size = value.size;
        this.stored = Objects.requireNonNull(stored, "stored");
    }

    /** Storage may replace resident bytes with an immutable, verified source. */
    public CanonicalNbt stored(Content source) { return new CanonicalNbt(this, source); }

    public int residentByteSize() { return bytes == null ? 0 : bytes.length; }

    @FunctionalInterface
    public interface Content {
        /** Returns newly owned bytes; callers may mutate the returned array. */
        byte[] read() throws IOException;
    }

    public byte[] bytes() {
        if (bytes != null) return bytes.clone();
        try {
            byte[] result = stored.read();
            if (result.length != size || !ObjectId.hash(result).equals(identity)) throw new IOException("Stored NBT content changed");
            return result;
        } catch (IOException failed) { throw new UncheckedIOException(failed); }
    }

    public int byteSize() {
        return size;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CanonicalNbt nbt) || size != nbt.size) return false;
        return bytes != null && nbt.bytes != null ? Arrays.equals(bytes, nbt.bytes) : identity().equals(nbt.identity());
    }

    @Override
    public int hashCode() {
        return identity().hashCode();
    }

    private ObjectId identity() {
        ObjectId value = identity;
        if (value == null) identity = value = ObjectId.hash(bytes);
        return value;
    }
}
