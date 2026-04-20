package io.github.luma.storage.repository;

import io.github.luma.domain.model.ProjectVersion;
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
        Files.writeString(layout.versionFile(version.id()), GsonProvider.gson().toJson(version), StandardCharsets.UTF_8);
    }

    public Optional<ProjectVersion> load(ProjectLayout layout, String versionId) throws IOException {
        if (!Files.exists(layout.versionFile(versionId))) {
            return Optional.empty();
        }

        return Optional.of(GsonProvider.gson().fromJson(Files.readString(layout.versionFile(versionId)), ProjectVersion.class));
    }

    public List<ProjectVersion> loadAll(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.versionsDir())) {
            return List.of();
        }

        List<ProjectVersion> versions = new ArrayList<>();
        try (var stream = Files.list(layout.versionsDir())) {
            for (var file : stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                versions.add(GsonProvider.gson().fromJson(Files.readString(file), ProjectVersion.class));
            }
        }

        versions.sort(Comparator.comparing(ProjectVersion::createdAt));
        return versions;
    }
}
