package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectCleanupCandidate;
import io.github.luma.domain.model.ProjectCleanupPolicy;
import io.github.luma.domain.model.ProjectCleanupReport;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectCleanupRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.MinecraftServer;

public final class ProjectCleanupService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final ProjectCleanupRepository projectCleanupRepository = new ProjectCleanupRepository();

    public ProjectCleanupReport inspect(MinecraftServer server, String projectName) throws IOException {
        CleanupContext context = this.context(server, projectName);
        List<String> warnings = this.warnings(context);
        var candidates = this.projectCleanupRepository.inspect(context.layout(), context.policy());
        return new ProjectCleanupReport(true, candidates, warnings, this.totalBytes(candidates));
    }

    public ProjectCleanupReport apply(MinecraftServer server, String projectName) throws IOException {
        CleanupContext context = this.context(server, projectName);
        List<String> warnings = this.warnings(context);
        var candidates = this.projectCleanupRepository.apply(context.layout(), context.policy());
        return new ProjectCleanupReport(false, candidates, warnings, this.totalBytes(candidates));
    }

    private CleanupContext context(MinecraftServer server, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(server, projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        boolean activeOperation = WorldOperationManager.getInstance()
                .snapshot(server, project.id().toString())
                .filter(snapshot -> !snapshot.terminal())
                .isPresent();
        return new CleanupContext(
                layout,
                new ProjectCleanupPolicy(
                        referencedSnapshotFiles(versions),
                        referencedPreviewFiles(versions),
                        !activeOperation
                ),
                activeOperation
        );
    }

    private List<String> warnings(CleanupContext context) {
        List<String> warnings = new ArrayList<>();
        if (context.activeOperation()) {
            warnings.add("Skipped operation-draft cleanup because the project has an active world operation");
        }
        return List.copyOf(warnings);
    }

    private static Set<String> referencedSnapshotFiles(List<ProjectVersion> versions) {
        Set<String> referenced = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            if (version.snapshotId() != null && !version.snapshotId().isBlank()) {
                referenced.add(version.snapshotId() + ".bin.lz4");
            }
        }
        return Set.copyOf(referenced);
    }

    private static Set<String> referencedPreviewFiles(List<ProjectVersion> versions) {
        Set<String> referenced = new LinkedHashSet<>();
        for (ProjectVersion version : versions) {
            if (version.preview() != null && version.preview().fileName() != null && !version.preview().fileName().isBlank()) {
                referenced.add(version.preview().fileName());
            }
        }
        return Set.copyOf(referenced);
    }

    private long totalBytes(List<ProjectCleanupCandidate> candidates) {
        long totalBytes = 0L;
        for (ProjectCleanupCandidate candidate : candidates) {
            totalBytes += Math.max(0L, candidate.sizeBytes());
        }
        return totalBytes;
    }

    private record CleanupContext(
            ProjectLayout layout,
            ProjectCleanupPolicy policy,
            boolean activeOperation
    ) {
    }
}
