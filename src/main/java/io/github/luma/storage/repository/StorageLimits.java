package io.github.luma.storage.repository;

import java.io.IOException;

final class StorageLimits {

    static final int MAX_NBT_BYTES = 16 * 1024 * 1024;
    static final int MAX_RECOVERY_ENTRY_BYTES = 64 * 1024 * 1024;
    static final int MAX_PATCH_FRAME_COMPRESSED_BYTES = 64 * 1024 * 1024;
    static final int MAX_PATCH_FRAME_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;
    static final int MAX_PATCH_CHUNKS = 1_000_000;
    static final int MAX_PATCH_CHANGES_PER_CHUNK = 128 * 4096;
    static final int MAX_PATCH_SECTIONS_PER_CHUNK = 128;
    static final int MAX_PALETTE_ENTRIES = 4096;
    static final int MAX_ENTITY_CHANGES_PER_CHUNK = 262_144;
    static final int MAX_SNAPSHOT_CHUNKS = 1_000_000;
    static final int MAX_SNAPSHOT_SECTIONS_PER_CHUNK = 128;
    static final int MAX_SNAPSHOT_BLOCK_ENTITIES_PER_CHUNK = 128 * 4096;
    static final int MAX_SNAPSHOT_ENTITY_SNAPSHOTS_PER_CHUNK = 262_144;
    static final int MAX_SNAPSHOT_PALETTE_INDEXES = 4096;

    private StorageLimits() {
    }

    static int requireLength(String label, int length, int maxBytes) throws IOException {
        if (length < 0 || length > maxBytes) {
            throw new IOException(label + " length out of bounds: " + length);
        }
        return length;
    }
}
