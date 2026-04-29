package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.HistoryEditService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VariantMergeService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.domain.service.DiffService;
import io.github.luma.domain.service.MaterialDeltaService;
import io.github.luma.domain.service.ChangeStatsFactory;
import io.github.luma.domain.service.VersionLineageService;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.ui.state.SaveDetailsViewState;
import io.github.luma.ui.state.SaveViewState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;

public final class ProjectScreenController {

    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final VersionService versionService = new VersionService();
    private final RestoreService restoreService = new RestoreService();
    private final HistoryEditService historyEditService = new HistoryEditService();
    private final VariantService variantService = new VariantService();
    private final VariantMergeService variantMergeService = new VariantMergeService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final DiffService diffService = new DiffService();
    private final MaterialDeltaService materialDeltaService = new MaterialDeltaService();
    private final VersionLineageService versionLineageService = new VersionLineageService();

    public SaveViewState loadSaveState(String projectName, String status) {
        if (!this.client.hasSingleplayerServer()) {
            return new SaveViewState(
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    "luma.status.singleplayer_only"
            );
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var project = this.projectService.loadProject(server, projectName);
            var loadedVariants = new ArrayList<>(this.projectService.loadVariants(server, projectName));
            var loadedVersions = new ArrayList<>(this.projectService.loadVersions(server, projectName));
            loadedVersions.sort(java.util.Comparator.comparing(io.github.luma.domain.model.ProjectVersion::createdAt).reversed());
            var operationSnapshot = this.visibleOperationSnapshot(WorldOperationManager.getInstance()
                    .snapshot(server, project.id().toString())
                    .orElse(null));
            return new SaveViewState(
                    project,
                    loadedVersions,
                    loadedVariants,
                    this.recoveryService.loadDraft(server, projectName).orElse(null),
                    operationSnapshot,
                    status == null || status.isBlank() ? "luma.status.project_ready" : status
            );
        } catch (Exception exception) {
            return new SaveViewState(
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    "luma.status.project_failed"
            );
        }
    }

    public SaveDetailsViewState loadSaveDetailsState(String projectName, String selectedVersionId, String status) {
        if (!this.client.hasSingleplayerServer()) {
            return new SaveDetailsViewState(
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    "luma.status.singleplayer_only"
            );
        }

        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var project = this.projectService.loadProject(server, projectName);
            var loadedVariants = new ArrayList<>(this.projectService.loadVariants(server, projectName));
            var loadedVersions = new ArrayList<>(this.projectService.loadVersions(server, projectName));
            loadedVersions.sort(java.util.Comparator.comparing(io.github.luma.domain.model.ProjectVersion::createdAt).reversed());
            var selectedVersion = this.resolveSelectedVersion(loadedVersions, loadedVariants, project.activeVariantId(), selectedVersionId);
            var diff = selectedVersion != null
                    ? this.diffService.compareVersionToParent(server, projectName, selectedVersion.id())
                    : null;
            var operationSnapshot = this.visibleOperationSnapshot(WorldOperationManager.getInstance()
                    .snapshot(server, project.id().toString())
                    .orElse(null));
            return new SaveDetailsViewState(
                    project,
                    loadedVersions,
                    loadedVariants,
                    selectedVersion,
                    diff,
                    diff == null ? List.of() : this.materialDeltaService.summarize(diff),
                    this.recoveryService.loadDraft(server, projectName).orElse(null),
                    operationSnapshot,
                    status == null || status.isBlank() ? "luma.status.project_ready" : status
            );
        } catch (Exception exception) {
            return new SaveDetailsViewState(
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    null,
                    null,
                    "luma.status.project_failed"
            );
        }
    }

    private io.github.luma.domain.model.OperationSnapshot visibleOperationSnapshot(
            io.github.luma.domain.model.OperationSnapshot snapshot
    ) {
        if (snapshot == null || !snapshot.terminal()) {
            return snapshot;
        }

        return Duration.between(snapshot.updatedAt(), Instant.now()).compareTo(Duration.ofSeconds(5)) <= 0
                ? snapshot
                : null;
    }

    public boolean hasRecoveryDraft(String projectName) {
        if (!this.client.hasSingleplayerServer()) {
            return false;
        }

        try {
            return this.recoveryService.hasDraft(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (Exception exception) {
            return false;
        }
    }

    public String saveVersion(String projectName, String message) {
        try {
            this.versionService.startSaveVersion(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    message,
                    this.client.getUser().getName()
            );
            return "luma.status.save_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Save request rejected for project {}", projectName, exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Save request failed for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    public String amendVersion(String projectName, String message) {
        try {
            this.versionService.startAmendVersion(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    message,
                    this.client.getUser().getName()
            );
            return "luma.status.amend_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Amend request rejected for project {}", projectName, exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Amend request failed for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    public String restoreVersion(String projectName, String versionId) {
        try {
            this.restoreService.restore(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    versionId
            );
            return "luma.status.restore_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Restore request rejected for project {}", projectName, exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Restore request failed for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    public String partialRestore(io.github.luma.domain.model.PartialRestoreRequest request) {
        if (request == null) {
            return "luma.status.operation_failed";
        }
        try {
            this.restoreService.partialRestore(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, request.projectName()),
                    request
            );
            return "luma.status.partial_restore_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Partial restore request rejected for project {}", request.projectName(), exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Partial restore request failed for project {}", request == null ? "" : request.projectName(), exception);
            return "luma.status.operation_failed";
        }
    }

    public io.github.luma.domain.model.PartialRestorePlanSummary partialRestorePlanSummary(
            io.github.luma.domain.model.PartialRestoreRequest request
    ) {
        try {
            return this.restoreService.summarizePartialRestorePlan(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, request.projectName()),
                    request
            );
        } catch (Exception exception) {
            LumaMod.LOGGER.warn(
                    "Partial restore plan summary failed for project {} version {}",
                    request == null ? "" : request.projectName(),
                    request == null ? "" : request.targetVersionId(),
                    exception
            );
            return null;
        }
    }

    public String createVariant(String projectName, String variantName, String fromVersionId) {
        try {
            this.variantService.createVariant(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    variantName,
                    fromVersionId
            );
            return "luma.status.variant_created";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Create variant request failed for project {}", projectName, exception);
            return variantFailureStatus(exception);
        }
    }

    public String switchVariant(String projectName, String variantId) {
        return this.switchVariant(projectName, variantId, true);
    }

    public String switchVariant(String projectName, String variantId, boolean restoreHead) {
        try {
            this.variantService.switchVariant(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    variantId,
                    restoreHead
            );
            return "luma.status.variant_switched";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Switch variant request failed for project {}", projectName, exception);
            return variantFailureStatus(exception);
        }
    }

    public String deleteVariant(String projectName, String variantId) {
        try {
            this.historyEditService.deleteVariant(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    variantId
            );
            return "luma.status.variant_deleted";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Delete variant request failed for project {}", projectName, exception);
            return historyEditFailureStatus(exception);
        }
    }

    public String mergeVariantIntoCurrent(String projectName, String sourceVariantId) {
        try {
            this.variantMergeService.startLocalMerge(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    sourceVariantId,
                    List.of(),
                    this.client.getUser().getName()
            );
            return "luma.status.merge_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Local merge rejected for project {}", projectName, exception);
            return "luma.status.world_operation_busy";
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Local merge blocked for project {}", projectName, exception);
            return mergeFailureStatus(exception);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Local merge failed for project {}", projectName, exception);
            return "luma.status.operation_failed";
        }
    }

    static String variantFailureStatus(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "luma.status.operation_failed";
        }

        if (exception instanceof IllegalStateException) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("admin") || normalized.contains("cheats")) {
                return "luma.status.admin_required";
            }
            if (normalized.contains("another world operation")) {
                return "luma.status.world_operation_busy";
            }
            return "luma.status.operation_failed";
        }

        if (!(exception instanceof IllegalArgumentException)) {
            return "luma.status.operation_failed";
        }

        if (message.startsWith("Variant name is required")) {
            return "luma.status.variant_name_required";
        }
        if (message.startsWith("Variant already exists")) {
            return "luma.status.variant_already_exists";
        }
        if (message.startsWith("Version not found")) {
            return "luma.status.variant_base_missing";
        }
        if (message.startsWith("Discard or save the current recovery draft")) {
            return "luma.status.variant_switch_requires_saved_draft";
        }
        return "luma.status.operation_failed";
    }

    static String historyEditFailureStatus(Exception exception) {
        String message = exception.getMessage();
        if (message == null || !(exception instanceof IllegalArgumentException)) {
            return "luma.status.operation_failed";
        }
        if (message.startsWith("Main branch cannot be deleted")
                || message.startsWith("Active branch cannot be deleted")
                || message.startsWith("Variant not found")) {
            return "luma.status.variant_delete_blocked";
        }
        return "luma.status.operation_failed";
    }

    static String mergeFailureStatus(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "luma.status.operation_failed";
        }
        if (message.contains("does not add any new changes")
                || message.contains("Source branch does not add")) {
            return "luma.status.merge_no_changes";
        }
        if (message.contains("conflicts")) {
            return "luma.status.merge_conflicts_found";
        }
        if (message.contains("current recovery draft")) {
            return "luma.status.merge_requires_saved_draft";
        }
        return "luma.status.operation_failed";
    }

    public String refreshPreview(String projectName, String versionId) {
        try {
            this.versionService.refreshPreview(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    versionId
            );
            return "luma.status.preview_requested";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Preview refresh failed for project {} version {}", projectName, versionId, exception);
            return "luma.status.operation_failed";
        }
    }

    public String resolvePreviewPath(String projectName, String versionId) {
        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            return this.projectService.resolveLayout(server, projectName).previewFile(versionId).toString();
        } catch (Exception exception) {
            return "";
        }
    }

    public io.github.luma.domain.model.PendingChangeSummary summarizePending(io.github.luma.domain.model.RecoveryDraft draft) {
        if (draft == null || draft.isEmpty()) {
            return io.github.luma.domain.model.PendingChangeSummary.empty();
        }
        return ChangeStatsFactory.summarizePending(draft.changes());
    }

    private io.github.luma.domain.model.ProjectVersion resolveSelectedVersion(
            List<io.github.luma.domain.model.ProjectVersion> versions,
            List<io.github.luma.domain.model.ProjectVariant> variants,
            String activeVariantId,
            String selectedVersionId
    ) {
        if (versions.isEmpty()) {
            return null;
        }

        if (selectedVersionId != null && !selectedVersionId.isBlank()) {
            for (var version : versions) {
                if (version.id().equals(selectedVersionId)) {
                    return version;
                }
            }
        }

        io.github.luma.domain.model.ProjectVersion activeHead = this.versionLineageService.resolveVariantHead(versions, variants, activeVariantId);
        return activeHead != null ? activeHead : versions.getFirst();
    }
}
