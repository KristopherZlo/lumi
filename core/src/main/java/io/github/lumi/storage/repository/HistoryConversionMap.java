package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Durable old-to-new identities for one conversion; no history-wide heap index. */
public final class HistoryConversionMap {
    public enum Kind { SECTION, ENTITIES, CHUNK, REGION, DIMENSION, COMMIT }

    private final Path directory;

    public HistoryConversionMap(Path targetGeneration) {
        directory = Objects.requireNonNull(targetGeneration, "targetGeneration").resolve("conversion-map");
    }

    public Optional<ObjectId> read(Kind kind, ObjectId source) throws IOException {
        Path file = path(kind, source);
        if (!Files.exists(file)) return Optional.empty();
        byte[] bytes = RepositoryFileReader.read(file, 32);
        if (bytes.length != 32) throw new IOException("Truncated history conversion mapping: " + file);
        return Optional.of(new ObjectId(HexFormat.of().formatHex(bytes)));
    }

    /** Publish only after the target object and all of its dependencies are durable. */
    public void record(Kind kind, ObjectId source, ObjectId target) throws IOException {
        byte[] bytes = HexFormat.of().parseHex(Objects.requireNonNull(target, "target").hex());
        Path file = path(kind, source);
        if (!AtomicFileWriter.createOnce(file, bytes)
                && !Arrays.equals(bytes, RepositoryFileReader.read(file, 32))) {
            throw new IOException("Conflicting history conversion mapping: " + source);
        }
    }

    private Path path(Kind kind, ObjectId source) {
        Objects.requireNonNull(kind, "kind");
        String hex = Objects.requireNonNull(source, "source").hex();
        return directory.resolve(kind.name().toLowerCase(java.util.Locale.ROOT))
                .resolve(hex.substring(0, 2)).resolve(hex.substring(2) + ".map");
    }
}
