package io.github.luma.ui.controller;

import io.github.luma.LumaMod;
import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.HistoryPackageImportResult;
import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.MergeConflictZone;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.VariantMergeApplyRequest;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.domain.service.HistoryShareService;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.domain.service.VersionLineageService;
import io.github.luma.domain.service.VariantMergeService;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.ShareViewState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class ShareScreenController {

    private final Query query;
    private final Actions actions;
    private final MergePreviewCache mergePreviewCache;
    private String lastValidationMessage = "";

    public ShareScreenController() {
        this(new ServiceQuery(), new ServiceActions(), new MergePreviewCache());
    }

    ShareScreenController(Query query, Actions actions) {
        this(query, actions, new MergePreviewCache());
    }

    ShareScreenController(Query query, Actions actions, MergePreviewCache mergePreviewCache) {
        this.query = query;
        this.actions = actions;
        this.mergePreviewCache = mergePreviewCache;
    }

    public ShareViewState loadState(String projectName, String status) {
        if (!this.query.hasSingleplayerServer()) {
            return new ShareViewState(null, List.of(), List.of(), List.of(), null, "luma.status.singleplayer_only");
        }

        try {
            BuildProject project = this.query.loadProject(projectName);
            var loadedVariants = new ArrayList<>(this.query.loadVariants(projectName));
            var loadedVersions = new ArrayList<>(this.query.loadVersions(projectName, loadedVariants));
            loadedVersions.sort(Comparator.comparing(ProjectVersion::createdAt).reversed());
            return new ShareViewState(
                    project,
                    loadedVersions,
                    loadedVariants,
                    this.query.loadImportedProjects(projectName),
                    this.query.loadOperationSnapshot(project),
                    status == null || status.isBlank() ? "luma.status.share_ready" : status
            );
        } catch (Exception exception) {
            return new ShareViewState(null, List.of(), List.of(), List.of(), null, "luma.status.project_failed");
        }
    }

    public ProjectArchiveExportResult exportVariantPackage(String projectName, String variantId, boolean includePreviews) {
        try {
            ProjectArchiveExportResult result = this.actions.exportVariantPackage(projectName, variantId, includePreviews);
            this.clearValidationMessage();
            return result;
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Variant package export failed for project {} variant {}", projectName, variantId, exception);
            this.captureValidationMessage(exception);
            return null;
        }
    }

    public HistoryPackageImportResult importVariantPackage(String projectName, String archivePath) {
        try {
            HistoryPackageImportResult result = this.actions.importVariantPackage(projectName, archivePath);
            this.clearValidationMessage();
            this.mergePreviewCache.clear();
            return result;
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Variant package import failed for project {}", projectName, exception);
            this.captureValidationMessage(exception);
            return null;
        }
    }

    public String deleteImportedProject(String targetProjectName, String importedProjectName) {
        try {
            this.actions.deleteImportedProject(targetProjectName, importedProjectName);
            this.clearValidationMessage();
            this.mergePreviewCache.clear();
            return "luma.status.imported_package_deleted";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Imported package delete failed for project {} package {}", targetProjectName, importedProjectName, exception);
            this.captureValidationMessage(exception);
            return "luma.status.operation_failed";
        }
    }

    public VariantMergePlan previewMerge(
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId
    ) {
        try {
            return this.actions.previewMerge(targetProjectName, sourceProjectName, sourceVariantId, targetVariantId);
        } catch (Exception exception) {
            LumaMod.LOGGER.warn(
                    "Merge preview failed for target project {} from {}:{}",
                    targetProjectName,
                    sourceProjectName,
                    sourceVariantId,
                    exception
            );
            return null;
        }
    }

    public MergePreviewStatus requestMergePreview(
            String targetProjectName,
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId
    ) {
        MergePreviewKey key = new MergePreviewKey(targetProjectName, sourceProjectName, sourceVariantId, targetVariantId);
        MergePreviewStatus status = this.mergePreviewCache.request(key, request -> this.actions.previewMerge(
                request.targetProjectName(),
                request.sourceProjectName(),
                request.sourceVariantId(),
                request.targetVariantId()
        ));
        if (status.state() == MergePreviewStatus.State.FAILED) {
            this.lastValidationMessage = status.detail();
            return status;
        }
        if (status.state() == MergePreviewStatus.State.READY) {
            if (status.plan() == null) {
                this.lastValidationMessage = "Merge preview did not return a plan.";
                return MergePreviewStatus.failed(this.lastValidationMessage);
            }
            this.clearValidationMessage();
        }
        return status;
    }

    public String startMerge(VariantMergeApplyRequest request) {
        try {
            this.actions.startMerge(request);
            this.clearValidationMessage();
            this.mergePreviewCache.clear();
            return "luma.status.merge_started";
        } catch (IllegalStateException exception) {
            LumaMod.LOGGER.warn("Merge start rejected for project {}", request.targetProjectName(), exception);
            this.captureValidationMessage(exception);
            return "luma.status.world_operation_busy";
        } catch (IllegalArgumentException exception) {
            LumaMod.LOGGER.warn("Merge start blocked for project {}", request.targetProjectName(), exception);
            this.captureValidationMessage(exception);
            return exception.getMessage() != null && exception.getMessage().contains("does not add any new changes")
                    ? "luma.status.merge_no_changes"
                    : "luma.status.merge_conflicts_found";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Merge start failed for project {}", request.targetProjectName(), exception);
            this.captureValidationMessage(exception);
            return "luma.status.operation_failed";
        }
    }

    public String showConflictZoneOverlay(
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId,
            MergeConflictZone zone
    ) {
        try {
            this.actions.showConflictZoneOverlay(sourceProjectName, sourceVariantId, targetVariantId, zone);
            this.clearValidationMessage();
            return "luma.status.compare_overlay_enabled";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Conflict overlay failed for {}:{}", sourceProjectName, sourceVariantId, exception);
            this.captureValidationMessage(exception);
            return "luma.status.compare_failed";
        }
    }

    public String showConflictZonesOverlay(
            String sourceProjectName,
            String sourceVariantId,
            String targetVariantId,
            VariantMergePlan plan
    ) {
        try {
            this.actions.showConflictZonesOverlay(sourceProjectName, sourceVariantId, targetVariantId, plan.conflictZones());
            this.clearValidationMessage();
            return "luma.status.compare_overlay_enabled";
        } catch (Exception exception) {
            LumaMod.LOGGER.warn("Conflict overlay failed for {}:{}", sourceProjectName, sourceVariantId, exception);
            this.captureValidationMessage(exception);
            return "luma.status.compare_failed";
        }
    }

    public String clearConflictZoneOverlay() {
        CompareOverlayRenderer.clear();
        this.clearValidationMessage();
        return "luma.status.compare_overlay_cleared";
    }

    public String lastValidationMessage() {
        return this.lastValidationMessage;
    }

    static String describeFailure(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor instanceof java.util.concurrent.CompletionException) {
            Throwable cause = cursor.getCause();
            if (cause == null) {
                break;
            }
            cursor = cause;
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return "The action failed. See the log for details.";
        }
        return message.length() <= 180 ? message : message.substring(0, 177) + "...";
    }

    private void captureValidationMessage(Exception exception) {
        this.lastValidationMessage = describeFailure(exception);
    }

    private void clearValidationMessage() {
        this.lastValidationMessage = "";
    }

    interface Query {

        boolean hasSingleplayerServer();

        BuildProject loadProject(String projectName) throws Exception;

        List<ProjectVariant> loadVariants(String projectName) throws Exception;

        List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) throws Exception;

        List<ImportedHistoryProjectSummary> loadImportedProjects(String projectName) throws Exception;

        OperationSnapshot loadOperationSnapshot(BuildProject project) throws Exception;
    }

    interface Actions {

        ProjectArchiveExportResult exportVariantPackage(String projectName, String variantId, boolean includePreviews) throws Exception;

        HistoryPackageImportResult importVariantPackage(String projectName, String archivePath) throws Exception;

        void deleteImportedProject(String targetProjectName, String importedProjectName) throws Exception;

        VariantMergePlan previewMerge(
                String targetProjectName,
                String sourceProjectName,
                String sourceVariantId,
                String targetVariantId
        ) throws Exception;

        void startMerge(VariantMergeApplyRequest request) throws Exception;

        void showConflictZoneOverlay(
                String sourceProjectName,
                String sourceVariantId,
                String targetVariantId,
                MergeConflictZone zone
        );

        void showConflictZonesOverlay(
                String sourceProjectName,
                String sourceVariantId,
                String targetVariantId,
                List<MergeConflictZone> zones
        );
    }

    private static final class ServiceQuery implements Query {

        private final Minecraft client = Minecraft.getInstance();
        private final ProjectService projectService = new ProjectService();
        private final VersionLineageService versionLineageService = new VersionLineageService();
        private final HistoryShareService historyShareService = new HistoryShareService();
        private final OperationSnapshotViewService operationSnapshotViewService = new OperationSnapshotViewService();

        @Override
        public boolean hasSingleplayerServer() {
            return this.client.hasSingleplayerServer();
        }

        @Override
        public BuildProject loadProject(String projectName) throws Exception {
            return this.projectService.loadProject(this.server(), projectName);
        }

        @Override
        public List<ProjectVariant> loadVariants(String projectName) throws Exception {
            return this.projectService.loadVariants(this.server(), projectName);
        }

        @Override
        public List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) throws Exception {
            return this.versionLineageService.reachableVersions(
                    this.projectService.loadVersions(this.server(), projectName),
                    variants
            );
        }

        @Override
        public List<ImportedHistoryProjectSummary> loadImportedProjects(String projectName) throws Exception {
            return this.historyShareService.listImportedProjects(this.server(), projectName);
        }

        @Override
        public OperationSnapshot loadOperationSnapshot(BuildProject project) throws Exception {
            return this.operationSnapshotViewService.loadVisibleSnapshot(this.server(), project.id().toString());
        }

        private MinecraftServer server() {
            return ClientProjectAccess.requireSingleplayerServer(this.client);
        }
    }

    private static final class ServiceActions implements Actions {

        private final Minecraft client = Minecraft.getInstance();
        private final ProjectService projectService = new ProjectService();
        private final HistoryShareService historyShareService = new HistoryShareService();
        private final VariantMergeService variantMergeService = new VariantMergeService();

        @Override
        public ProjectArchiveExportResult exportVariantPackage(String projectName, String variantId, boolean includePreviews) throws Exception {
            return this.historyShareService.exportVariantPackage(this.server(), projectName, variantId, includePreviews);
        }

        @Override
        public HistoryPackageImportResult importVariantPackage(String projectName, String archivePath) throws Exception {
            return this.historyShareService.importVariantPackage(this.server(), projectName, archivePath);
        }

        @Override
        public void deleteImportedProject(String targetProjectName, String importedProjectName) throws Exception {
            this.historyShareService.deleteImportedProject(this.server(), targetProjectName, importedProjectName);
        }

        @Override
        public VariantMergePlan previewMerge(
                String targetProjectName,
                String sourceProjectName,
                String sourceVariantId,
                String targetVariantId
        ) throws Exception {
            return this.variantMergeService.previewMerge(
                    this.server(),
                    targetProjectName,
                    sourceProjectName,
                    sourceVariantId,
                    targetVariantId
            );
        }

        @Override
        public void startMerge(VariantMergeApplyRequest request) throws Exception {
            this.variantMergeService.startMerge(this.level(request.targetProjectName()), request, this.client.getUser().getName());
        }

        @Override
        public void showConflictZoneOverlay(
                String sourceProjectName,
                String sourceVariantId,
                String targetVariantId,
                MergeConflictZone zone
        ) {
            this.showConflictZonesOverlay(
                    sourceProjectName + ":" + zone.id(),
                    sourceVariantId,
                    targetVariantId,
                    List.of(zone)
            );
        }

        @Override
        public void showConflictZonesOverlay(
                String sourceProjectName,
                String sourceVariantId,
                String targetVariantId,
                List<MergeConflictZone> zones
        ) {
            CompareOverlayRenderer.show(
                    sourceProjectName + ":" + sourceVariantId,
                    "local:" + targetVariantId,
                    zones.stream()
                            .flatMap(zone -> zone.importedChanges().stream())
                            .map(this::diffEntry)
                            .toList(),
                    false
            );
        }

        private DiffBlockEntry diffEntry(io.github.luma.domain.model.StoredBlockChange change) {
            StatePayload leftState = change.oldValue();
            StatePayload rightState = change.newValue();
            ChangeType type;
            if (leftState == null && rightState != null) {
                type = ChangeType.ADDED;
            } else if (leftState != null && rightState == null) {
                type = ChangeType.REMOVED;
            } else {
                type = ChangeType.CHANGED;
            }
            return new DiffBlockEntry(
                    new BlockPoint(change.pos().x(), change.pos().y(), change.pos().z()),
                    this.stateString(leftState),
                    this.stateString(rightState),
                    type
            );
        }

        private String stateString(StatePayload state) {
            return state == null ? "" : state.toStateSnbt();
        }

        private MinecraftServer server() {
            return ClientProjectAccess.requireSingleplayerServer(this.client);
        }

        private ServerLevel level(String projectName) throws Exception {
            return ClientProjectAccess.resolveProjectLevel(this.client, this.projectService, projectName);
        }
    }
}
