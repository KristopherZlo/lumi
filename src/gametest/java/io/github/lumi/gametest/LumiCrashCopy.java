package io.github.lumi.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

/** Captures and reinstalls one generated client GameTest world without a clean save. */
final class LumiCrashCopy implements AutoCloseable {
    private static final Path SESSION_LOCK = Path.of("session.lock");

    private final Path scratch;
    private final Path worldSnapshot;
    private final Path refsSnapshot;

    private LumiCrashCopy(Path scratch) {
        this.scratch = scratch;
        worldSnapshot = scratch.resolve("world");
        refsSnapshot = scratch.resolve("refs");
    }

    static LumiCrashCopy create(Path liveWorld) throws IOException {
        Objects.requireNonNull(liveWorld, "liveWorld");
        Path parent = liveWorld.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Test world has no parent directory: " + liveWorld);
        }
        return new LumiCrashCopy(Files.createTempDirectory(
                parent, ".lumi-crash-copy-"));
    }

    void captureRefs(Path repository) throws IOException {
        copyTree(repository.resolve("refs").resolve("heads"), refsSnapshot, false);
    }

    void captureWorld(Path liveWorld) throws IOException {
        copyTree(liveWorld, worldSnapshot, true);
    }

    void install(Path liveWorld, Path repository) throws IOException {
        requireSibling(liveWorld);
        Path normalizedWorld = liveWorld.toAbsolutePath().normalize();
        Path normalizedRepository = repository.toAbsolutePath().normalize();
        if (!normalizedRepository.startsWith(normalizedWorld)) {
            throw new IOException("Lumi repository is outside the test world");
        }
        if (!Files.isDirectory(worldSnapshot) || !Files.isDirectory(refsSnapshot)) {
            throw new IOException("Crash copy is incomplete");
        }
        deleteTree(liveWorld);
        copyTree(worldSnapshot, liveWorld, false);
        Path heads = repository.resolve("refs").resolve("heads");
        deleteTree(heads);
        copyTree(refsSnapshot, heads, false);
    }

    private void requireSibling(Path liveWorld) throws IOException {
        Path parent = liveWorld.toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.equals(scratch.getParent())) {
            throw new IOException("Crash copy does not belong to " + liveWorld);
        }
    }

    private static void copyTree(
            Path source, Path target, boolean skipSessionLock) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Snapshot source is not a directory: " + source);
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (skipSessionLock && relative.equals(SESSION_LOCK)) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Override
    public void close() throws IOException {
        deleteTree(scratch);
    }
}
