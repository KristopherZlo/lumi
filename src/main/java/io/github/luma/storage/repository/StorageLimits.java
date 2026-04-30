package io.github.luma.storage.repository;

import java.io.IOException;

final class StorageLimits {

    static final int MAX_NBT_BYTES = 16 * 1024 * 1024;
    static final int MAX_RECOVERY_ENTRY_BYTES = 64 * 1024 * 1024;

    private StorageLimits() {
    }

    static int requireLength(String label, int length, int maxBytes) throws IOException {
        if (length < 0 || length > maxBytes) {
            throw new IOException(label + " length out of bounds: " + length);
        }
        return length;
    }
}
