package io.github.luma.domain.service;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.SnapshotRef;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.SnapshotRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.io.IOException;
import java.util.Comparator;
import net.minecraft.server.level.ServerLevel;

public final class RestoreService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotRepository snapshotRepository = new SnapshotRepository();

    public ProjectVersion restore(ServerLevel level, String projectName, String versionId) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        ProjectVersion version = this.resolveVersion(layout, versionId);

        this.snapshotRepository.restore(layout, new SnapshotRef(
                version.snapshotId(),
                project.id().toString(),
                version.snapshotId() + ".nbt.lz4",
                project.bounds(),
                0L,
                version.createdAt()
        ), level);
        return version;
    }

    private ProjectVersion resolveVersion(ProjectLayout layout, String versionId) throws IOException {
        if (versionId != null && !versionId.isBlank()) {
            return this.versionRepository.load(layout, versionId)
                    .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));
        }

        return this.versionRepository.loadAll(layout).stream()
                .max(Comparator.comparing(ProjectVersion::createdAt))
                .orElseThrow(() -> new IllegalArgumentException("Project has no versions yet"));
    }
}
