package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RestoreEntityTypeCount;
import io.github.luma.domain.model.RestoreEntityTypeSelection;
import io.github.luma.domain.service.ChangeStatsFactory;
import io.github.luma.domain.service.HistoryEditService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.ProjectVersionVisibility;
import io.github.luma.domain.service.QuickRollbackService;
import io.github.luma.domain.service.RecoveryService;
import io.github.luma.domain.service.RestoreEntitySummaryService;
import io.github.luma.domain.service.RestoreService;
import io.github.luma.domain.service.VariantService;
import io.github.luma.domain.service.VariantMergeService;
import io.github.luma.domain.service.VersionService;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.telemetry.TelemetryService;
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
    private final RestoreEntitySummaryService restoreEntitySummaryService = new RestoreEntitySummaryService();
    private final QuickRollbackService quickRollbackService = new QuickRollbackService();
    private final HistoryEditService historyEditService = new HistoryEditService();
    private final VariantService variantService = new VariantService();
    private final VariantMergeService variantMergeService = new VariantMergeService();
    private final RecoveryService recoveryService = new RecoveryService();
    private final SaveDetailsStateFactory saveDetailsStateFactory = new SaveDetailsStateFactory();
    private final BranchCreationWorkflow branchCreationWorkflow;

    public ProjectScreenController() {
        this.branchCreationWorkflow = new BranchCreationWorkflow(this::createBranchVariant, this::switchBranchVariant);
    }

    ProjectScreenController(BranchCreationWorkflow branchCreationWorkflow) {
        this.branchCreationWorkflow = branchCreationWorkflow;
    }

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
            return this.saveDetailsStateFactory.create(
                    project,
                    loadedVersions,
                    loadedVariants,
                    selectedVersionId,
                    this.recoveryService.loadDraft(server, projectName).orElse(null),
                    operationSnapshot,
                    status
            );
        } catch (Exception exception) {
            return new SaveDetailsViewState(
                    null,
                    List.of(),
                    List.of(),
                    null,
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
            return this.recoveryService.hasInterruptedDraft(ClientProjectAccess.requireSingleplayerServer(this.client), projectName);
        } catch (Exception exception) {
            return false;
        }
    }

    public String saveVersion(String projectName, String message) {
        return this.saveVersion(projectName, message, List.of());
    }

    public String saveVersion(String projectName, String message, List<String> tags) {
        try {
            this.versionService.startSaveVersion(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    message,
                    this.client.getUser().getName(),
                    tags
            );
            return "luma.status.save_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Save request rejected for project {}", projectName, exception);
            this.reportRejectedAction("save", "luma.status.world_operation_busy", exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Save request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return "luma.status.operation_failed";
        }
    }

    public String amendVersion(String projectName, String message) {
        return this.amendVersion(projectName, message, List.of());
    }

    public String amendVersion(String projectName, String message, List<String> tags) {
        try {
            this.versionService.startAmendVersion(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName,
                    message,
                    this.client.getUser().getName(),
                    tags
            );
            return "luma.status.amend_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Amend request rejected for project {}", projectName, exception);
            this.reportRejectedAction("amend", "luma.status.world_operation_busy", exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Amend request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return "luma.status.operation_failed";
        }
    }

    public String restoreVersion(String projectName, String versionId) {
        return this.restoreVersion(projectName, versionId, "");
    }

    public String restoreVersion(String projectName, String versionId, String targetVariantId) {
        return this.restoreVersion(projectName, versionId, targetVariantId, RestoreEntityTypeSelection.includeAll());
    }

    public String restoreVersion(
            String projectName,
            String versionId,
            String targetVariantId,
            RestoreEntityTypeSelection entityTypeSelection
    ) {
        try {
            var level = ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName);
            if (targetVariantId == null || targetVariantId.isBlank()) {
                this.restoreService.restore(level, projectName, versionId, entityTypeSelection);
            } else {
                this.restoreService.restoreToVariant(level, projectName, versionId, targetVariantId, entityTypeSelection);
            }
            return "luma.status.restore_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Restore request rejected for project {}", projectName, exception);
            this.reportRejectedAction("restore", "luma.status.world_operation_busy", exception);
            return "luma.status.world_operation_busy";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Restore request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return "luma.status.operation_failed";
        }
    }

    public List<RestoreEntityTypeCount> restoreEntityTypes(String projectName, String versionId) {
        return this.restoreEntityTypes(projectName, versionId, null);
    }

    public List<RestoreEntityTypeCount> restoreEntityTypes(PartialRestoreRequest request) {
        if (request == null) {
            return List.of();
        }
        return this.restoreEntityTypes(request.projectName(), request.targetVersionId(), request);
    }

    private List<RestoreEntityTypeCount> restoreEntityTypes(
            String projectName,
            String versionId,
            PartialRestoreRequest request
    ) {
        if (projectName == null || projectName.isBlank() || versionId == null || versionId.isBlank()) {
            return List.of();
        }
        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            var layout = this.projectService.resolveLayout(server, projectName);
            var project = this.projectService.loadProject(server, projectName);
            List<ProjectVersion> versions = this.projectService.loadVersions(server, projectName);
            List<ProjectVariant> variants = this.projectService.loadVariants(server, projectName);
            ProjectVersion version = versions.stream()
                    .filter(candidate -> versionId.equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
            return this.restoreEntitySummaryService.summarize(
                    layout,
                    version,
                    this.activeHeadVersion(versions, variants, project.activeVariantId()),
                    request
            );
        } catch (Exception exception) {
            LumaMod.LOGGER.warn(
                    "Failed to summarize restore entities for project {} version {}",
                    projectName,
                    versionId,
                    exception
            );
            return List.of();
        }
    }

    private ProjectVersion activeHeadVersion(
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            String activeVariantId
    ) {
        String headVersionId = variants.stream()
                .filter(variant -> variant.id().equals(activeVariantId))
                .map(ProjectVariant::headVersionId)
                .findFirst()
                .orElse("");
        if (headVersionId.isBlank()) {
            return null;
        }
        return versions.stream()
                .filter(version -> version.id().equals(headVersionId))
                .findFirst()
                .orElse(null);
    }

    public String quickRollback(String projectName) {
        try {
            this.quickRollbackService.quickRollback(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName
            );
            return "luma.status.quick_rollback_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Quick rollback request rejected for project {}", projectName, exception);
            this.reportRejectedAction("quick_rollback", "luma.status.world_operation_busy", exception);
            return "luma.status.world_operation_busy";
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Quick rollback unavailable for project {}", projectName, exception);
            this.reportRejectedAction("quick_rollback", "luma.status.quick_rollback_unavailable", exception);
            return "luma.status.quick_rollback_unavailable";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Quick rollback request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return "luma.status.operation_failed";
        }
    }

    public String returnBeforeRestore(String projectName) {
        try {
            this.quickRollbackService.returnBeforeLastRestore(
                    ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                    projectName
            );
            return "luma.status.return_before_restore_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Return-before-restore request rejected for project {}", projectName, exception);
            this.reportRejectedAction("return_before_restore", "luma.status.world_operation_busy", exception);
            return "luma.status.world_operation_busy";
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Return-before-restore unavailable for project {}", projectName, exception);
            this.reportRejectedAction("return_before_restore", "luma.status.return_before_restore_unavailable", exception);
            return "luma.status.return_before_restore_unavailable";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Return-before-restore request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return "luma.status.operation_failed";
        }
    }

    public String partialRestore(io.github.luma.domain.model.PartialRestoreRequest request) {
        if (request == null) {
            return "luma.status.operation_failed";
        }
        try {
            var level = ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, request.projectName());
            var summary = this.restoreService.summarizePartialRestorePlan(level, request);
            String status = partialRestoreStatus(summary);
            if (!"luma.status.partial_restore_started".equals(status)) {
                return status;
            }
            this.restoreService.partialRestore(level, request);
            return zoneRestoreRequest(request) ? "luma.status.zone_restore_started" : "luma.status.partial_restore_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Partial restore request rejected for project {}", request.projectName(), exception);
            this.reportRejectedAction("partial_restore", "luma.status.world_operation_busy", exception);
            return "luma.status.world_operation_busy";
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Partial restore request rejected for project {}", request.projectName(), exception);
            this.reportRejectedAction("partial_restore", partialRestoreFailureStatus(exception, request.restoreMode()), exception);
            return partialRestoreFailureStatus(exception, request.restoreMode());
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Partial restore request failed for project {}", request == null ? "" : request.projectName(), exception);
            this.reportFailedAction(exception);
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

    static String partialRestoreStatus(io.github.luma.domain.model.PartialRestorePlanSummary summary) {
        if (summary == null) {
            return "luma.status.operation_failed";
        }
        if (summary.hasChanges()) {
            return "luma.status.partial_restore_started";
        }
        return partialRestoreNoChangesStatus(summary.partialRestoreMode());
    }

    private static boolean zoneRestoreRequest(PartialRestoreRequest request) {
        return request != null
                && !request.metadata().getOrDefault(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, "").isBlank();
    }

    public static String partialRestorePreviewStatus(io.github.luma.domain.model.PartialRestorePlanSummary summary) {
        if (summary == null) {
            return "luma.status.operation_failed";
        }
        if (summary.hasChanges()) {
            return "luma.status.partial_restore_plan_ready";
        }
        return partialRestoreNoChangesStatus(summary.partialRestoreMode());
    }

    private static String partialRestoreFailureStatus(
            Exception exception,
            io.github.luma.domain.model.PartialRestoreMode mode
    ) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("no changes inside") || message.contains("no changes outside")) {
            return partialRestoreNoChangesStatus(mode);
        }
        return "luma.status.operation_failed";
    }

    private static String partialRestoreNoChangesStatus(io.github.luma.domain.model.PartialRestoreMode mode) {
        return mode == io.github.luma.domain.model.PartialRestoreMode.OUTSIDE_SELECTED_AREA
                ? "luma.status.partial_restore_no_changes_outside_selection"
                : "luma.status.partial_restore_no_changes_selected";
    }

    public String createVariant(String projectName, String variantName, String fromVersionId) {
        try {
            this.createBranchVariant(projectName, variantName, fromVersionId);
            return "luma.status.variant_created";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Create variant request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return variantFailureStatus(exception);
        }
    }

    public BranchCreationResult createAndSwitchVariant(String projectName, String variantName, String fromVersionId) {
        try {
            ProjectVariant created = this.branchCreationWorkflow.createAndSwitch(projectName, variantName, fromVersionId);
            return new BranchCreationResult("luma.status.variant_switched", created.id());
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Create and switch variant request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return BranchCreationResult.status(variantFailureStatus(exception));
        }
    }

    public String switchVariant(String projectName, String variantId) {
        try {
            this.switchBranchVariant(projectName, variantId);
            return "luma.status.variant_switched";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Switch variant request failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return variantFailureStatus(exception);
        }
    }

    private ProjectVariant createBranchVariant(String projectName, String variantName, String fromVersionId) throws Exception {
        return this.variantService.createVariant(
                ClientProjectAccess.requireSingleplayerServer(this.client),
                projectName,
                variantName,
                fromVersionId
        );
    }

    private void switchBranchVariant(String projectName, String variantId) throws Exception {
        this.variantService.switchVariant(
                ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName),
                projectName,
                variantId
        );
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
            this.reportFailedAction(exception);
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
            this.reportRejectedAction("merge_variant", "luma.status.world_operation_busy", exception);
            return "luma.status.world_operation_busy";
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Local merge blocked for project {}", projectName, exception);
            this.reportRejectedAction("merge_variant", mergeFailureStatus(exception), exception);
            return mergeFailureStatus(exception);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Local merge failed for project {}", projectName, exception);
            this.reportFailedAction(exception);
            return "luma.status.operation_failed";
        }
    }

    public String renameVersion(String projectName, String versionId, String message) {
        try {
            this.historyEditService.renameVersion(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    versionId,
                    message
            );
            return "luma.status.version_renamed";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Rename version request failed for project {} version {}", projectName, versionId, exception);
            this.reportFailedAction(exception);
            return historyEditFailureStatus(exception);
        }
    }

    public String updateVersionTags(String projectName, String versionId, List<String> tags) {
        try {
            this.historyEditService.updateVersionTags(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    versionId,
                    tags
            );
            return "luma.status.tags_updated";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Update tags request failed for project {} version {}", projectName, versionId, exception);
            this.reportFailedAction(exception);
            return historyEditFailureStatus(exception);
        }
    }

    public String deleteVersion(String projectName, String versionId) {
        try {
            this.historyEditService.deleteVersion(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    versionId
            );
            return "luma.status.version_deleted";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Delete version request failed for project {} version {}", projectName, versionId, exception);
            this.reportFailedAction(exception);
            return historyEditFailureStatus(exception);
        }
    }

    public String restoreDeletedVersion(String projectName, String versionId) {
        try {
            this.historyEditService.restoreDeletedVersion(
                    ClientProjectAccess.requireSingleplayerServer(this.client),
                    projectName,
                    versionId
            );
            return "luma.status.version_restored";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Restore deleted version request failed for project {} version {}", projectName, versionId, exception);
            this.reportFailedAction(exception);
            return historyEditFailureStatus(exception);
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
        if (message.startsWith("Save name is required")) {
            return "luma.status.save_name_required";
        }
        if (message.startsWith("Root saves cannot be deleted")
                || message.startsWith("Only leaf saves can be deleted")
                || message.startsWith("Save is the head of multiple branches")
                || message.startsWith("Version not found")) {
            return "luma.status.version_delete_blocked";
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

    private void reportRejectedAction(String action, String statusKey, Exception exception) {
        TelemetryService.getInstance().recordOperationRejected(action, statusKey, exception);
    }

    private void reportFailedAction(Exception exception) {
        TelemetryService.getInstance().recordOperationFailed(null, null, exception);
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
            this.reportFailedAction(exception);
            return "luma.status.operation_failed";
        }
    }

    public String resolvePreviewPath(String projectName, String versionId) {
        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            return this.projectService.previewPath(server, projectName, versionId).toString();
        } catch (Exception exception) {
            return "";
        }
    }

    public boolean previewLoading(String projectName, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        try {
            var server = ClientProjectAccess.requireSingleplayerServer(this.client);
            return this.projectService.previewQueued(server, projectName, versionId);
        } catch (Exception exception) {
            return false;
        }
    }

    public io.github.luma.domain.model.PendingChangeSummary summarizePending(io.github.luma.domain.model.RecoveryDraft draft) {
        if (draft == null || draft.isEmpty()) {
            return io.github.luma.domain.model.PendingChangeSummary.empty();
        }
        return ChangeStatsFactory.summarizePending(draft.changes());
    }

}
