package io.github.luma.storage.repository;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.storage.GsonProvider;
import io.github.luma.storage.ProjectLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ProjectRepository {

    public void initializeLayout(ProjectLayout layout) throws IOException {
        Files.createDirectories(layout.root());
        Files.createDirectories(layout.versionsDir());
        Files.createDirectories(layout.patchesDir());
        Files.createDirectories(layout.snapshotsDir());
        Files.createDirectories(layout.entityCheckpointsDir());
        Files.createDirectories(layout.previewsDir());
        Files.createDirectories(layout.previewRequestsDir());
        Files.createDirectories(layout.recoveryDir());
        Files.createDirectories(layout.cacheDir());
        Files.createDirectories(layout.locksDir());
    }

    public void save(ProjectLayout layout, BuildProject project) throws IOException {
        this.initializeLayout(layout);
        StorageIo.writeAtomically(layout.projectFile(), output -> output.write(
                GsonProvider.gson().toJson(project).getBytes(StandardCharsets.UTF_8)
        ));
    }

    public Optional<BuildProject> load(ProjectLayout layout) throws IOException {
        if (!Files.exists(layout.projectFile())) {
            return Optional.empty();
        }

        String json = Files.readString(layout.projectFile());
        return Optional.of(this.normalize(
                GsonProvider.gson().fromJson(json, BuildProject.class),
                GsonProvider.gson().fromJson(json, LegacyProject.class),
                layout
        ));
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
        if (projectName == null || projectName.isBlank() || !Files.exists(projectsRoot)) {
            return Optional.empty();
        }

        String rawFolderName = projectName + ".mbp";
        String safeFolderName = ProjectLayout.of(projectsRoot, projectName).root().getFileName().toString();
        List<Path> projectDirs;
        try (var stream = Files.list(projectsRoot)) {
            projectDirs = stream
                    .filter(Files::isDirectory)
                    .toList();
        }

        Optional<ProjectLayout> directMatch = projectDirs.stream()
                .filter(path -> {
                    String folderName = path.getFileName().toString();
                    return folderName.equalsIgnoreCase(rawFolderName)
                            || folderName.equalsIgnoreCase(safeFolderName);
                })
                .map(ProjectLayout::new)
                .findFirst();
        if (directMatch.isPresent()) {
            return directMatch;
        }

        for (Path path : projectDirs) {
            ProjectLayout layout = new ProjectLayout(path);
            Optional<BuildProject> project = this.load(layout);
            if (project.isPresent() && project.get().name().equalsIgnoreCase(projectName)) {
                return Optional.of(layout);
            }
        }
        return Optional.empty();
    }

    private BuildProject normalize(BuildProject project, LegacyProject legacy, ProjectLayout layout) {
        String mainVariantId = firstNonBlank(project.mainVariantId(), legacy.mainBranchId(), "main");
        Instant createdAt = project.createdAt() == null ? Instant.EPOCH : project.createdAt();
        return new BuildProject(
                project.schemaVersion(),
                project.id() == null ? legacyProjectId(legacy, layout) : project.id(),
                firstNonBlank(project.name(), projectNameFromFolder(layout), "Project"),
                firstNonBlank(project.description(), ""),
                firstNonBlank(project.minecraftVersion(), "1.21.11"),
                firstNonBlank(project.modLoader(), "fabric"),
                project.dimensionId() == null || project.dimensionId().isBlank() ? "minecraft:overworld" : project.dimensionId(),
                project.bounds(),
                project.origin(),
                mainVariantId,
                firstNonBlank(project.activeVariantId(), legacy.activeBranchId(), mainVariantId),
                createdAt,
                project.updatedAt() == null ? createdAt : project.updatedAt(),
                ProjectSettings.sanitize(project.settings()),
                project.favorite(),
                project.archived()
        );
    }

    private static UUID legacyProjectId(LegacyProject legacy, ProjectLayout layout) {
        String projectId = legacy == null ? "" : legacy.projectId();
        if (projectId != null && !projectId.isBlank()) {
            return UUID.fromString(projectId);
        }
        return UUID.nameUUIDFromBytes(layout.root().toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String projectNameFromFolder(ProjectLayout layout) {
        String folder = layout.root().getFileName().toString();
        return folder.endsWith(".mbp") ? folder.substring(0, folder.length() - 4) : folder;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record LegacyProject(
            String projectId,
            String mainBranchId,
            String activeBranchId
    ) {
    }
}
