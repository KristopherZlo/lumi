package io.github.luma.storage.repository;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ProjectRepository {

    public void initializeLayout(ProjectLayout layout) throws IOException {
        Files.createDirectories(layout.root());
        Files.createDirectories(layout.versionsDir());
        Files.createDirectories(layout.patchesDir());
        Files.createDirectories(layout.snapshotsDir());
        Files.createDirectories(layout.previewsDir());
        Files.createDirectories(layout.previewRequestsDir());
        Files.createDirectories(layout.recoveryDir());
        Files.createDirectories(layout.cacheDir());
        Files.createDirectories(layout.locksDir());
    }

    public void save(ProjectLayout layout, BuildProject project) throws IOException {
        this.initializeLayout(layout);
        Files.writeString(layout.projectFile(), GsonProvider.gson().toJson(project), StandardCharsets.UTF_8);
    }

    public Optional<BuildProject> load(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.projectFile())) {
            return Optional.empty();
        }

        return Optional.of(this.normalize(GsonProvider.gson().fromJson(Files.readString(layout.projectFile()), BuildProject.class)));
    }

    public List<BuildProject> loadAll(Path projectsRoot) throws IOException {
        if (!Files.exists(projectsRoot)) {
            return List.of();
        }

        List<BuildProject> projects = new ArrayList<>();
        try (var stream = Files.list(projectsRoot)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                this.load(new ProjectLayout(path)).ifPresent(projects::add);
            }
        }

        projects.sort(Comparator.comparing(BuildProject::updatedAt).reversed());
        return projects;
    }

    public Optional<ProjectLayout> findLayoutByProjectName(Path projectsRoot, String projectName) throws IOException {
        try (var stream = Files.list(projectsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(projectName + ".mbp"))
                    .map(ProjectLayout::new)
                    .findFirst();
        }
    }

    private BuildProject normalize(BuildProject project) {
        return new BuildProject(
                project.schemaVersion(),
                project.id(),
                project.name(),
                project.description(),
                project.minecraftVersion(),
                project.modLoader(),
                project.dimensionId() == null || project.dimensionId().isBlank() ? "minecraft:overworld" : project.dimensionId(),
                project.bounds(),
                project.origin(),
                project.mainVariantId() == null || project.mainVariantId().isBlank() ? "main" : project.mainVariantId(),
                project.activeVariantId() == null || project.activeVariantId().isBlank()
                        ? (project.mainVariantId() == null || project.mainVariantId().isBlank() ? "main" : project.mainVariantId())
                        : project.activeVariantId(),
                project.createdAt(),
                project.updatedAt(),
                io.github.luma.domain.model.ProjectSettings.sanitize(project.settings()),
                project.favorite(),
                project.archived()
        );
    }
}
