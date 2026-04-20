package io.github.luma.domain.service;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

public final class VersionService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final SnapshotRepository snapshotRepository = new SnapshotRepository();

    public ProjectVersion saveVersion(ServerLevel level, String projectName, String message, String author) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        BuildProject project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        List<ProjectVersion> versions = this.versionRepository.loadAll(layout);
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        ProjectVariant mainVariant = variants.stream().filter(ProjectVariant::main).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Main variant is missing for " + projectName));

        int nextIndex = versions.size() + 1;
        Instant now = Instant.now();
        String versionId = ProjectService.versionId(nextIndex);
        String snapshotId = ProjectService.snapshotId(nextIndex);

        this.snapshotRepository.capture(layout, project.id().toString(), snapshotId, project.bounds(), level, now);
        ProjectVersion version = new ProjectVersion(
                versionId,
                project.id().toString(),
                mainVariant.id(),
                mainVariant.headVersionId(),
                snapshotId,
                List.of(),
                author,
                message == null || message.isBlank() ? "Сохранённая версия" : message,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                now
        );

        this.versionRepository.save(layout, version);
        this.variantRepository.save(layout, this.replaceMainVariant(variants, new ProjectVariant(
                mainVariant.id(),
                mainVariant.name(),
                mainVariant.baseVersionId(),
                version.id(),
                true,
                mainVariant.createdAt()
        )));
        this.projectRepository.save(layout, new BuildProject(
                project.id(),
                project.name(),
                project.description(),
                project.minecraftVersion(),
                project.modLoader(),
                project.bounds(),
                project.origin(),
                project.mainVariantId(),
                project.createdAt(),
                now,
                project.settings(),
                project.favorite(),
                project.archived()
        ));

        return version;
    }

    private List<ProjectVariant> replaceMainVariant(List<ProjectVariant> variants, ProjectVariant updatedVariant) {
        List<ProjectVariant> result = new ArrayList<>();
        for (ProjectVariant variant : variants) {
            result.add(variant.main() ? updatedVariant : variant);
        }
        return result;
    }
}
