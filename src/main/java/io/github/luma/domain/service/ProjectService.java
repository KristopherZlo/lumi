package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.SnapshotRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

public final class ProjectService {

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotRepository snapshotRepository = new SnapshotRepository();

    public List<BuildProject> listProjects(MinecraftServer server) throws IOException {
        return this.projectRepository.loadAll(this.projectsRoot(server));
    }

    public ProjectLayout resolveLayout(MinecraftServer server, String projectName) throws IOException {
        return this.projectRepository.findLayoutByProjectName(this.projectsRoot(server), projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectName));
    }

    public BuildProject createProject(ServerLevel level, String name, BlockPos from, BlockPos to, String author) throws IOException {
        Path root = this.projectsRoot(level.getServer());
        Files.createDirectories(root);

        ProjectLayout layout = ProjectLayout.of(root, name);
        if (Files.exists(layout.root())) {
            throw new IllegalArgumentException("Project already exists: " + name);
        }

        Instant now = Instant.now();
        Bounds3i bounds = Bounds3i.of(from, to);
        BuildProject project = BuildProject.create(name, bounds, BlockPoint.from(from), now);

        String versionId = versionId(1);
        String snapshotId = snapshotId(1);
        this.snapshotRepository.capture(layout, project.id().toString(), snapshotId, bounds, level, now);
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(ProjectVariant.main(versionId, now)));
        this.versionRepository.save(layout, new ProjectVersion(
                versionId,
                project.id().toString(),
                "main",
                "",
                snapshotId,
                List.of(),
                author,
                "Начальная версия",
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                now
        ));

        return project;
    }

    public Path projectsRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("luma").resolve("projects");
    }

    public static String versionId(int number) {
        return String.format(Locale.ROOT, "v%04d", number);
    }

    public static String snapshotId(int number) {
        return String.format(Locale.ROOT, "snapshot-%04d", number);
    }
}
