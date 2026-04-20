package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class VersionRepository {

    public void save(ProjectLayout layout, ProjectVersion version) throws IOException {
        Files.createDirectories(layout.versionsDir());
        StorageIo.writeAtomically(layout.versionFile(version.id()), output -> output.write(
                GsonProvider.gson().toJson(version).getBytes(StandardCharsets.UTF_8)
        ));
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

        List<ProjectVersion> versions = new ArrayList<>();
        try (var stream = Files.list(layout.versionsDir())) {
            for (var file : stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                versions.add(this.normalize(GsonProvider.gson().fromJson(Files.readString(file), ProjectVersion.class)));
            }
        }

        versions.sort(Comparator.comparing(ProjectVersion::createdAt));
        return versions;
    }

    private ProjectVersion normalize(ProjectVersion version) {
        return new ProjectVersion(
                version.id(),
                version.projectId(),
                version.variantId() == null || version.variantId().isBlank() ? "main" : version.variantId(),
                version.parentVersionId() == null ? "" : version.parentVersionId(),
                version.snapshotId() == null ? "" : version.snapshotId(),
                version.patchIds() == null ? List.of() : List.copyOf(version.patchIds()),
                version.versionKind() == null ? VersionKind.LEGACY : version.versionKind(),
                version.author() == null ? "" : version.author(),
                version.message() == null ? "" : version.message(),
                version.stats() == null ? ChangeStats.empty() : version.stats(),
                version.preview() == null ? PreviewInfo.none() : version.preview(),
                version.sourceInfo() == null ? ExternalSourceInfo.manual() : version.sourceInfo(),
                version.createdAt()
        );
    }
}
