package io.github.lumi.storage.packageformat;

import io.github.lumi.domain.model.PackageName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Resolves portable packages inside one server-owned world directory. */
public final class LumiPackageDirectory {
    private static final int MAX_LISTED = 256;
    private final Path directory;

    public LumiPackageDirectory(Path worldRoot) {
        directory = Objects.requireNonNull(worldRoot, "worldRoot")
                .toAbsolutePath().normalize().resolve("lumi").resolve("packages");
    }

    public Path resolve(PackageName name) {
        return directory.resolve(
                Objects.requireNonNull(name, "name").value() + ".lumi");
    }

    public Path ensureDirectory() throws IOException {
        Files.createDirectories(directory);
        return directory;
    }

    public List<Entry> list() throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        var entries = new ArrayList<Entry>();
        // ponytail: first 256 files; paginate only if real package libraries exceed this.
        try (var files = Files.list(directory)) {
            var iterator = files.iterator();
            while (iterator.hasNext() && entries.size() < MAX_LISTED) {
                Path file = iterator.next();
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String filename = file.getFileName().toString();
                if (!filename.endsWith(".lumi")) {
                    continue;
                }
                try {
                    entries.add(new Entry(
                            new PackageName(filename.substring(0, filename.length() - 5)),
                            Files.size(file), Files.getLastModifiedTime(file).toInstant()));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::modified).reversed()
                .thenComparing(Entry::name));
        return List.copyOf(entries);
    }

    public record Entry(PackageName name, long bytes, Instant modified) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(modified, "modified");
            if (bytes < 0) {
                throw new IllegalArgumentException("Package size cannot be negative");
            }
        }
    }
}
