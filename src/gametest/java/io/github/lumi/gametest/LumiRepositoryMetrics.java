package io.github.lumi.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Measures Lumi repository growth independently from operation timings. */
final class LumiRepositoryMetrics {
    Snapshot capture(Path repository) throws IOException {
        long started = System.nanoTime();
        long bytes = 0;
        long files = 0;
        Map<String, Long> categories = new TreeMap<>();
        if (Files.exists(repository)) {
            try (Stream<Path> paths = Files.walk(repository)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    long size;
                    try {
                        size = Files.size(path);
                    } catch (NoSuchFileException disappeared) {
                        continue;
                    }
                    bytes += size;
                    files++;
                    Path relative = repository.relativize(path);
                    String category = relative.getNameCount() == 1
                            ? "root" : relative.getName(0).toString();
                    categories.merge(category, size, Long::sum);
                }
            }
        }
        return new Snapshot(
                bytes, files, (System.nanoTime() - started) / 1_000_000,
                Map.copyOf(categories));
    }

    record Snapshot(
            long bytes,
            long files,
            long measurementMillis,
            Map<String, Long> categoryBytes) {
        String describe(long previousBytes) {
            return "bytes=" + bytes
                    + ";deltaBytes=" + (bytes - previousBytes)
                    + ";files=" + files
                    + ";measurementMillis=" + measurementMillis
                    + ";categories=" + categoryBytes;
        }
    }
}
