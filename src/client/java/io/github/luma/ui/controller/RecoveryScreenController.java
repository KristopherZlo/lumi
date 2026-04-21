package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.RecoveryDraftSummary;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.VersionService;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.client.Minecraft;

public final class RecoveryScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final VersionService versionService = new VersionService();

    public Optional<RecoveryDraftSummary> loadSummary(String projectName) {
        if (!this.client.hasSingleplayerServer()) {
            return Optional.empty();
        }

        try {
            return this.recoveryService.summarizeDraft(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    public String restoreDraft(String projectName) {
        try {
            this.recoveryService.restoreDraft(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName
            );
            return "luma.status.draft_restore_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Recovery draft restore rejected for project {}", projectName, exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Recovery draft restore failed for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    public String saveDraftVersion(String projectName, String message) {
        try {
            this.versionService.startSaveVersion(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    message,
                    this.client.getUser().getName(),
                    VersionKind.RECOVERY
            );
            return "luma.status.recovery_save_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Recovery draft save rejected for project {}", projectName, exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Recovery draft save failed for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    public String discardDraft(String projectName) {
        try {
            this.recoveryService.discardDraft(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
            return "luma.status.draft_discarded";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Discard draft failed for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }
}
