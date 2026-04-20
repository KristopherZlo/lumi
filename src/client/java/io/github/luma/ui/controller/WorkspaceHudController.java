package io.github.luma.ui.controller;

import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.WorkspaceHudSnapshot;
import io.github.luma.domain.service.ChangeStatsFactory;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.minecraft.world.WorldOperationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

/**
 * Loads the minimal per-dimension workspace state required by the client HUD.
 */
public final class WorkspaceHudController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final RecoveryService recoveryService = new RecoveryService();

    public WorkspaceHudSnapshot loadCurrentWorkspaceSnapshot() {
        if (!this.client.hasSingleplayerServer() || this.client.player == null || this.client.level == null) {
            return null;
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            ServerLevel level = server.getLevel(this.client.level.dimension());
            if (level == null) {
                level = server.overworld();
            }

            var project = this.projectService.ensureWorldProject(level, this.client.getUser().getName());
            PendingChangeSummary pending = this.recoveryService.loadDraft(server, project.name())
                    .map(draft -> ChangeStatsFactory.summarizePending(draft.changes()))
                    .orElse(PendingChangeSummary.empty());
            var operationSnapshot = WorldOperationManager.getInstance()
                    .snapshot(server, project.id().toString())
                    .orElse(null);

            return new WorkspaceHudSnapshot(
                    project.name(),
                    project.name() + " | " + project.activeVariantId(),
                    project.activeVariantId(),
                    pending,
                    operationSnapshot
            );
        } catch (Exception exception) {
            return null;
        }
    }
}
