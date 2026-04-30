package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class TrackedProjectCatalog {

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final VariantRepository variantRepository;
    private final ProjectCatalogCache<ProjectCatalogSnapshot> cache = new ProjectCatalogCache<>();

    TrackedProjectCatalog(
            ProjectService projectService,
            ProjectRepository projectRepository,
            VariantRepository variantRepository
    ) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.variantRepository = variantRepository;
    }

    void invalidate(MinecraftServer server) {
        this.cache.invalidate(this.cacheKey(server));
    }

    TrackedProject find(MinecraftServer server, String projectId) throws IOException {
        for (TrackedProject trackedProject : this.loadAll(server)) {
            if (trackedProject.project().id().toString().equals(projectId)) {
                return trackedProject;
            }
        }
        return null;
    }

    List<TrackedProject> loadAll(MinecraftServer server) throws IOException {
        return this.loadCache(server).projects();
    }

    List<TrackedProject> matching(ServerLevel level, BlockPos pos) throws IOException {
        return this.loadCache(level.getServer())
                .index()
                .matching(level.dimension().identifier().toString(), pos);
    }

    private ProjectCatalogSnapshot loadCache(MinecraftServer server) throws IOException {
        return this.cache.getOrLoad(this.cacheKey(server), () -> this.loadProjects(server));
    }

    private ProjectCatalogSnapshot loadProjects(MinecraftServer server) throws IOException {
        List<TrackedProject> trackedProjects = new ArrayList<>();
        java.nio.file.Path projectsRoot = this.projectService.projectsRoot(server);
        if (java.nio.file.Files.exists(projectsRoot)) {
            try (var stream = java.nio.file.Files.list(projectsRoot)) {
                for (var path : stream.filter(java.nio.file.Files::isDirectory).toList()) {
                    ProjectLayout layout = new ProjectLayout(path);
                    BuildProject project = this.projectRepository.load(layout).orElse(null);
                    if (project == null || project.archived()) {
                        continue;
                    }
                    trackedProjects.add(new TrackedProject(layout, project, this.variantRepository.loadAll(layout)));
                }
            }
        }

        List<ProjectTrackingIndex.Entry<TrackedProject>> indexEntries = new ArrayList<>();
        for (TrackedProject trackedProject : trackedProjects) {
            indexEntries.add(new ProjectTrackingIndex.Entry<>(
                    trackedProject.project().dimensionId(),
                    trackedProject.project().bounds(),
                    trackedProject
            ));
        }
        return new ProjectCatalogSnapshot(
                List.copyOf(trackedProjects),
                ProjectTrackingIndex.build(indexEntries)
        );
    }

    private String cacheKey(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString();
    }
}
