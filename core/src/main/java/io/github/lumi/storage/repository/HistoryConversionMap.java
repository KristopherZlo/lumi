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

    public void visit(Visitor visitor) throws IOException {
        for (Kind kind : Kind.values()) {
            Path root = directory.resolve(kind.name().toLowerCase(java.util.Locale.ROOT));
            if (!Files.exists(root)) continue;
            try (var files = Files.walk(root)) {
                var iterator = files.iterator();
                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    if (Files.isSymbolicLink(file)) throw new IOException("Conversion mapping is a symbolic link: " + file);
                    if (!file.getFileName().toString().endsWith(".map")) continue;
                    Path relative = root.relativize(file);
                    if (relative.getNameCount() != 2) throw new IOException("Invalid conversion mapping path: " + file);
                    String name = relative.getName(1).toString();
                    try {
                        ObjectId source = new ObjectId(relative.getName(0) + name.substring(0, name.length() - 4));
                        if (!path(kind, source).equals(file)) throw new IOException("Noncanonical conversion mapping path");
                        visitor.visit(kind, source, read(kind, source).orElseThrow());
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException("Invalid conversion mapping identity", invalid);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface Visitor {
        void visit(Kind kind, ObjectId source, ObjectId target) throws IOException;
    }
}
