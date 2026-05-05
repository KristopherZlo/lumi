package io.github.luma.domain.service;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RestoreReturnPoint;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.io.IOException;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * Starts one-step restore workflows for fast redstone iteration.
 */
public final class QuickRollbackService {

    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final RestoreService restoreService = new RestoreService();
    private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

    public OperationHandle quickRollback(ServerLevel level, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        var project = this.projectRepository.load(layout)
                .orElseThrow(() -> new IllegalArgumentException("Project metadata is missing for " + projectName));
        this.requireIdle(level);
        ProjectVariant activeVariant = this.activeVariant(layout, project.activeVariantId(), projectName);
        if (activeVariant.headVersionId() == null || activeVariant.headVersionId().isBlank()) {
            throw new IllegalArgumentException("Current branch has no committed head yet");
        }
        return this.restoreService.restoreVariantHead(level, projectName, activeVariant.id());
    }

    public OperationHandle returnBeforeLastRestore(ServerLevel level, String projectName) throws IOException {
        ProjectLayout layout = this.projectService.resolveLayout(level.getServer(), projectName);
        this.requireIdle(level);
        RestoreReturnPoint point = this.recoveryRepository.loadRestoreReturnPoint(layout)
                .orElseThrow(() -> new IllegalArgumentException("No restore return point is available"));
        this.activeVariant(layout, point.variantId(), projectName);
        return this.restoreService.restoreToVariant(level, projectName, point.versionId(), point.variantId());
    }

    private void requireIdle(ServerLevel level) {
        if (this.worldOperationManager.hasActiveOperation(level.getServer())) {
            throw new IllegalStateException("Another world operation is already running");
        }
    }

    private ProjectVariant activeVariant(ProjectLayout layout, String variantId, String projectName) throws IOException {
        List<ProjectVariant> variants = this.variantRepository.loadAll(layout);
        return variants.stream()
                .filter(variant -> variant.id().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active branch is missing for " + projectName));
    }
}
