package io.github.luma.storage.repository;

record FileContentHash(long sizeBytes, String sha256Hex) {

    FileContentHash {
        if (sizeBytes < 0L) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        sha256Hex = sha256Hex == null ? "" : sha256Hex.toLowerCase(java.util.Locale.ROOT);
    }
}
