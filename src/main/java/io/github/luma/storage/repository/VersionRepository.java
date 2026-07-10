package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.StoragePathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class VersionRepository {

    private final VersionIndexRepository indexRepository = new VersionIndexRepository();

    public void save(ProjectLayout layout, ProjectVersion version) throws IOException {
        Files.createDirectories(layout.versionsDir());
        Optional<List<ProjectVersion>> indexed = this.loadFreshIndex(layout);
        StorageIo.writeAtomically(layout.versionFile(version.id()), output -> output.write(
                GsonProvider.gson().toJson(version).getBytes(StandardCharsets.UTF_8)
        ));
        try {
            List<ProjectVersion> versions;
            if (indexed.isPresent()) {
                versions = new ArrayList<>(indexed.get());
                versions.removeIf(candidate -> candidate.id().equals(version.id()));
                versions.add(this.normalize(version));
                versions.sort(Comparator.comparing(ProjectVersion::createdAt));
            } else {
                versions = this.scanManifests(layout);
            }
            this.indexRepository.rebuild(layout, versions);
        } catch (IOException | RuntimeException exception) {
            this.deleteIndexQuietly(layout);
        }
    }

    private Optional<List<ProjectVersion>> loadFreshIndex(ProjectLayout layout) {
        try {
            return this.indexRepository.loadIfFresh(layout, this.versionManifestFiles(layout));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Optional<ProjectVersion> load(ProjectLayout layout, String versionId) throws IOException {
        if (!Files.exists(layout.versionFile(versionId))) {
            return Optional.empty();
        }

        return Optional.of(this.normalize(GsonProvider.gson().fromJson(Files.readString(layout.versionFile(versionId)), ProjectVersion.class)));
    }

    public List<ProjectVersion> loadAll(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.versionsDir())) {
            return List.of();
        }

        List<Path> manifestFiles = this.versionManifestFiles(layout);
        Optional<List<ProjectVersion>> indexed = this.indexRepository.loadIfFresh(layout, manifestFiles);
        if (indexed.isPresent()) {
            return indexed.get().stream()
                    .map(this::normalize)
                    .toList();
        }

        List<ProjectVersion> versions = this.scanManifests(layout, manifestFiles);
        try {
            this.indexRepository.rebuild(layout, versions);
        } catch (IOException | RuntimeException exception) {
            this.deleteIndexQuietly(layout);
        }
        return versions;
    }

    public boolean isVersionIndexFresh(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.versionIndexFile())) {
            return true;
        }
        if (!Files.exists(layout.versionsDir())) {
            return false;
        }
        return this.indexRepository.loadIfFresh(layout, this.versionManifestFiles(layout)).isPresent();
    }

    private void deleteIndexQuietly(ProjectLayout layout) {
        try {
            this.indexRepository.delete(layout);
        } catch (IOException ignored) {
        }
    }

    private List<ProjectVersion> scanManifests(ProjectLayout layout) throws IOException {
        return this.scanManifests(layout, this.versionManifestFiles(layout));
    }

    private List<ProjectVersion> scanManifests(ProjectLayout layout, List<Path> manifestFiles) throws IOException {
        List<ProjectVersion> versions = new ArrayList<>();
        for (Path file : manifestFiles) {
            versions.add(this.normalize(GsonProvider.gson().fromJson(Files.readString(file), ProjectVersion.class)));
        }

        versions.sort(Comparator.comparing(ProjectVersion::createdAt));
        return versions;
    }

    private List<Path> versionManifestFiles(ProjectLayout layout) throws IOException {
        try (var stream = Files.list(layout.versionsDir())) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("index.json"))
                    .toList();
        }
    }

    private ProjectVersion normalize(ProjectVersion version) {
        String versionId = StoragePathPolicy.requireStorageId(version.id(), "version id");
        return new ProjectVersion(
                versionId,
                version.projectId(),
                StoragePathPolicy.requireOptionalStorageId(
                        version.variantId() == null || version.variantId().isBlank() ? "main" : version.variantId(),
                        "variant id"
                ),
                StoragePathPolicy.requireOptionalStorageId(version.parentVersionId(), "parent version id"),
                StoragePathPolicy.requireOptionalStorageId(version.snapshotId(), "snapshot id"),
                StoragePathPolicy.requireOptionalStorageId(version.entityCheckpointId(), "entity checkpoint id"),
                version.patchIds() == null
                        ? List.of()
                        : version.patchIds().stream()
                                .map(patchId -> StoragePathPolicy.requireStorageId(patchId, "patch id"))
                                .toList(),
                version.versionKind() == null ? VersionKind.MANUAL : version.versionKind(),
                version.author() == null ? "" : version.author(),
                version.message() == null ? "" : version.message(),
                version.stats() == null ? ChangeStats.empty() : version.stats(),
                version.preview() == null ? PreviewInfo.none() : version.preview(),
                version.sourceInfo() == null ? ExternalSourceInfo.manual() : version.sourceInfo(),
                version.createdAt()
        );
    }
}
