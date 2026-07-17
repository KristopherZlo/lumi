package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CommitId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Atomic, non-authoritative PNG thumbnails keyed by immutable commit ID. */
public final class VersionPreviewRepository {
    private static final int MAX_PREVIEW_BYTES = 4 * 1024 * 1024;
    private final Path previewsDirectory;

    public VersionPreviewRepository(Path dimensionRepository) {
        previewsDirectory = Objects.requireNonNull(
                dimensionRepository, "dimensionRepository").resolve("previews");
    }

    public void save(CommitId commit, byte[] png) throws IOException {
        validate(png);
        AtomicFileWriter.replace(path(commit), png);
    }

    public Optional<byte[]> load(CommitId commit) throws IOException {
        Path path = path(commit);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        long size = Files.size(path);
        if (size < 1 || size > MAX_PREVIEW_BYTES) {
            throw new IOException("Invalid Lumi preview size: " + size);
        }
        byte[] png = Files.readAllBytes(path);
        validate(png);
        return Optional.of(png);
    }

    private Path path(CommitId commit) {
        return previewsDirectory.resolve(
                Objects.requireNonNull(commit, "commit").hex() + ".png");
    }

    private static void validate(byte[] png) {
        Objects.requireNonNull(png, "png");
        if (png.length < 1 || png.length > MAX_PREVIEW_BYTES) {
            throw new IllegalArgumentException("Invalid Lumi preview payload size");
        }
    }
}
