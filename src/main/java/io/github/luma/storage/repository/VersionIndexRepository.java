package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

final class VersionIndexRepository {

    Optional<List<ProjectVersion>> loadIfFresh(ProjectLayout layout, List<Path> manifestFiles) throws IOException {
        Path indexFile = layout.versionIndexFile();
        if (!Files.exists(indexFile)) {
            return Optional.empty();
        }

        VersionIndex index;
        try {
            index = GsonProvider.gson().fromJson(Files.readString(indexFile), VersionIndex.class);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (index == null || index.entries() == null || index.entries().size() != manifestFiles.size()) {
            return Optional.empty();
        }

        Map<String, Path> manifestsByName = manifestFiles.stream()
                .collect(Collectors.toMap(path -> path.getFileName().toString(), Function.identity()));
        for (VersionIndexEntry entry : index.entries()) {
            Path manifest = manifestsByName.remove(entry.fileName());
            if (manifest == null
                    || Files.size(manifest) != entry.sizeBytes()
                    || Files.getLastModifiedTime(manifest).toMillis() != entry.modifiedAtMillis()
                    || entry.version() == null) {
                return Optional.empty();
            }
        }
        if (!manifestsByName.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(index.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.version().createdAt()))
                .map(VersionIndexEntry::version)
                .toList());
    }

    void rebuild(ProjectLayout layout, List<ProjectVersion> versions) throws IOException {
        Files.createDirectories(layout.versionsDir());
        List<VersionIndexEntry> entries = versions.stream()
                .map(version -> this.entry(layout.versionFile(version.id()), version))
                .toList();
        StorageIo.writeAtomically(layout.versionIndexFile(), output -> output.write(
                GsonProvider.gson().toJson(new VersionIndex(entries)).getBytes(StandardCharsets.UTF_8)
        ));
    }

    void delete(ProjectLayout layout) throws IOException {
        Files.deleteIfExists(layout.versionIndexFile());
    }

    private VersionIndexEntry entry(Path manifest, ProjectVersion version) {
        try {
            return new VersionIndexEntry(
                    manifest.getFileName().toString(),
                    Files.size(manifest),
                    Files.getLastModifiedTime(manifest).toMillis(),
                    version
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot index version manifest " + manifest.getFileName(), exception);
        }
    }

    private record VersionIndex(List<VersionIndexEntry> entries) {
    }

    private record VersionIndexEntry(
            String fileName,
            long sizeBytes,
            long modifiedAtMillis,
            ProjectVersion version
    ) {
    }
}
