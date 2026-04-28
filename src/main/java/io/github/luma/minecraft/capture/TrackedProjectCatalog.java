package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class TrackedProjectCatalog {

    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final VariantRepository variantRepository;
    private final Map<String, CachedProjects> projectCaches = new HashMap<>();

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
        this.projectCaches.remove(this.cacheKey(server));
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

    private CachedProjects loadCache(MinecraftServer server) throws IOException {
        String cacheKey = this.cacheKey(server);
        CachedProjects cachedProjects = this.projectCaches.get(cacheKey);
        if (cachedProjects != null && Duration.between(cachedProjects.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cachedProjects;
        }

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
        CachedProjects refreshed = new CachedProjects(
                Instant.now(),
                List.copyOf(trackedProjects),
                ProjectTrackingIndex.build(indexEntries)
        );
        this.projectCaches.put(cacheKey, refreshed);
        return refreshed;
    }

    private String cacheKey(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString();
    }

    private record CachedProjects(
            Instant loadedAt,
            List<TrackedProject> projects,
            ProjectTrackingIndex<TrackedProject> index
    ) {
    }
}
