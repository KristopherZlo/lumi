package io.github.lumi.storage.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reads repository metadata without trusting an attacker-controlled file size. */
final class RepositoryFileReader {
    private RepositoryFileReader() { }

    static byte[] read(Path file, int maximumBytes) throws IOException {
        Objects.requireNonNull(file, "file");
        if (maximumBytes < 0 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid repository file limit");
        }
        try (var input = Files.newInputStream(file)) {
            byte[] payload = input.readNBytes(maximumBytes + 1);
            if (payload.length > maximumBytes) {
                throw new IOException("Lumi repository file exceeds "
                        + maximumBytes + " bytes: " + file);
            }
            return payload;
        }
    }
}
