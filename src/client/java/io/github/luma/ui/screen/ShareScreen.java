package io.github.luma.ui.screen;

import io.github.luma.domain.model.HistoryPackageImportResult;
import io.github.luma.domain.model.HistoryPackageFileSummary;
import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.MergeConflictResolution;
import io.github.luma.domain.model.MergeConflictZone;
import io.github.luma.domain.model.MergeConflictZoneResolution;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.VariantMergeApplyRequest;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.controller.MergePreviewStatus;
import io.github.luma.ui.controller.ShareScreenController;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.screen.section.ShareMergeReviewSection;
import io.github.luma.ui.state.ShareViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ShareScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ShareScreenController controller = new ShareScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private final ShareMergeReviewSection mergeReviewSections = new ShareMergeReviewSection(new MergeReviewActions());
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private ShareViewState state = new ShareViewState(null, List.of(), List.of(), List.of(), null, List.of(), null, "luma.status.share_ready");
    private String status = "luma.status.share_ready";
    private String importArchivePath = "";
    private String selectedExportVariantId = "";
    private String selectedTargetVariantId = "";
    private String selectedImportedProjectName = "";
    private String selectedImportedVariantId = "";
    private String selectedImportedVariantName = "";
    private String lastExportPath = "";
    private String lastImportedProjectName = "";
    private String validationMessage = "";
    private VariantMergePlan mergePlan = null;
    private final Map<String, MergeConflictResolution> conflictResolutions = new LinkedHashMap<>();
    private TextBoxComponent importArchiveInput;
    private boolean includePreviews = false;
    private boolean mergePreviewPending = false;
    private int refreshCooldown = 0;

    public ShareScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.import_export.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, this.status);
        this.ensureSelections();

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        if (this.state.project() == null) {
            FlowLayout frame = LumaUi.screenFrame();
            root.child(frame);
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.import_export.title"),
                this.state.project(),
                this.state.variants()
        );
        root.child(window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.IMPORT_EXPORT);
        window.content().child(LumaUi.statusBanner(this.bannerText()));
        if (!this.validationMessage.isBlank()) {
            window.content().child(LumaUi.danger(Component.literal(this.validationMessage)));
        }
        if (this.state.operationSnapshot() != null) {
            window.content().child(this.operationSection());
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);

        body.child(this.exportSection());
        body.child(this.importSection());
        body.child(this.importedPackagesSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
    }

    @Override
    protected void onLumaTick() {
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;
        ShareViewState refreshed = this.controller.loadState(this.projectName, this.status);
        String normalizedStatus = ScreenOperationStateSupport.normalizeStatusKey(
                this.status,
                refreshed.operationSnapshot(),
                "luma.status.share_ready"
        );
        if (!normalizedStatus.equals(this.status)) {
            this.status = normalizedStatus;
            refreshed = this.controller.loadState(this.projectName, this.status);
        }
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.rebuild();
        }
        if (this.mergePreviewPending) {
            this.pollMergePreview();
        }
    }

    private FlowLayout importSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.import_export.import_title"),
                Component.translatable("luma.share.import_help")
        );
        section.child(this.packageFolderSection());
        this.importArchiveInput = UIComponents.textBox(Sizing.fill(100), this.importArchivePath);
        this.importArchiveInput.setHint(Component.translatable("luma.share.import_path"));
        this.importArchiveInput.onChanged().subscribe(value -> this.importArchivePath = value);
        section.child(this.importArchiveInput);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent importButton = LumaUi.primaryButton(Component.translatable("luma.action.import_package"), button -> this.importPackage());
        importButton.active(!this.importArchivePath.isBlank() && !this.operationActive());
        actions.child(importButton);
        section.child(actions);

        if (!this.lastImportedProjectName.isBlank()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.import_ready", this.lastImportedProjectName)));
        }
        section.child(this.packageFilesSection());
        return section;
    }

    private FlowLayout packageFolderSection() {
        FlowLayout folder = LumaUi.insetSection(
                Component.translatable("luma.share.package_folder_title"),
                Component.translatable("luma.share.package_folder_help")
        );
        Path packageFolder = this.state.packageFolder();
        if (packageFolder != null) {
            folder.child(LumaUi.caption(Component.translatable("luma.share.package_folder_path", packageFolder.toString())));
        }
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.open_packages_folder"), button -> {
            this.validationMessage = "";
            this.refresh(this.controller.openPackageFolder());
        }));
        folder.child(actions);
        return folder;
    }

    private FlowLayout packageFilesSection() {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.share.package_files_title"),
                Component.translatable("luma.share.package_files_help")
        );
        if (this.state.packageFiles().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.package_files_empty")));
            return section;
        }
        for (HistoryPackageFileSummary packageFile : this.state.packageFiles()) {
            section.child(this.packageFileCard(packageFile));
        }
        return section;
    }

    private FlowLayout packageFileCard(HistoryPackageFileSummary packageFile) {
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.literal(packageFile.fileName())));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.package_file_entry",
                this.formatBytes(packageFile.sizeBytes()),
                ProjectUiSupport.formatTimestamp(packageFile.updatedAt())
        )));
        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent importButton = LumaUi.button(Component.translatable("luma.action.import_package"), button -> this.importPackage(
                packageFile.archiveFile().toString()
        ));
        importButton.active(!this.operationActive());
        actions.child(importButton);
        card.child(actions);
        return card;
    }

    private FlowLayout importedPackagesSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.import_export.packages_title"),
                Component.translatable("luma.share.packages_help")
        );

        if (this.state.importedProjects().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.imported_empty")));
            return section;
        }

        for (ImportedHistoryProjectSummary importedProject : this.state.importedProjects()) {
            section.child(this.importedProjectCard(importedProject));
        }
        return section;
    }

    private FlowLayout importedProjectCard(ImportedHistoryProjectSummary importedProject) {
        boolean selected = importedProject.projectName().equals(this.selectedImportedProjectName);

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.literal(importedProject.projectName())));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.imported_entry",
                importedProject.variantName(),
                ProjectUiSupport.formatTimestamp(importedProject.updatedAt())
        )));

        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.open_project"), button -> this.router.openProjectIgnoringRecovery(
                this,
                importedProject.projectName()
        )));
        ButtonComponent reviewButton = LumaUi.button(Component.translatable("luma.action.combine_with_build"), button -> {
            this.selectedImportedProjectName = importedProject.projectName();
            this.selectedImportedVariantId = importedProject.variantId();
            this.selectedImportedVariantName = importedProject.variantName();
            this.conflictResolutions.clear();
            this.refreshMergePreview();
        });
        reviewButton.active(true);
        actions.child(reviewButton);
        ButtonComponent deleteButton = LumaUi.button(Component.translatable("luma.action.delete_package"), button -> this.deleteImportedProject(importedProject));
        deleteButton.active(!this.operationActive());
        actions.child(deleteButton);
        card.child(actions);

        if (selected) {
            ShareMergeReviewSection.Model model = this.mergeReviewModel();
            if (this.mergePreviewPending && this.mergePlan == null) {
                card.child(this.mergeReviewSections.pendingSection(model));
            } else if (this.mergePlan != null) {
                card.child(this.mergeReviewSections.reviewSection(model));
            }
        }
        return card;
    }

    private ShareMergeReviewSection.Model mergeReviewModel() {
        return new ShareMergeReviewSection.Model(
                this.state,
                this.mergePlan,
                this.selectedTargetVariantId,
                this.selectedImportedVariantId,
                this.selectedImportedVariantName,
                this.conflictResolutions,
                this.operationActive()
        );
    }

    private FlowLayout exportSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.import_export.export_title"),
                Component.translatable("luma.share.export_help")
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.share.selected_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.selectedExportVariantId)
        )));
        section.child(this.variantButtons(this.selectedExportVariantId, variantId -> {
            this.selectedExportVariantId = variantId;
            this.refresh("luma.status.share_ready");
        }));

        FlowLayout previewToggle = LumaUi.actionRow();
        var includePreviewCheckbox = UIComponents.checkbox(Component.translatable("luma.share.include_previews"));
        includePreviewCheckbox.checked(this.includePreviews);
        includePreviewCheckbox.onChanged(value -> {
            this.includePreviews = value;
            this.refresh("luma.status.share_ready");
        });
        previewToggle.child(includePreviewCheckbox);
        section.child(previewToggle);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent exportButton = LumaUi.primaryButton(Component.translatable("luma.action.export_package"), button -> {
            var result = this.controller.exportVariantPackage(this.projectName, this.selectedExportVariantId, this.includePreviews);
            this.validationMessage = this.controller.lastValidationMessage();
            this.lastExportPath = result == null ? "" : result.archiveFile().toString();
            this.refresh(result == null ? "luma.status.operation_failed" : "luma.status.history_exported");
        });
        exportButton.active(!this.selectedExportVariantId.isBlank() && !this.operationActive());
        actions.child(exportButton);
        section.child(actions);

        if (!this.lastExportPath.isBlank()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.export_ready", this.lastExportPath)));
        }
        return section;
    }

    private FlowLayout operationSection() {
        var operation = this.state.operationSnapshot();
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.project.operation_title"),
                Component.literal(OperationProgressPresenter.progressSummary(operation))
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.project.operation_stage",
                operation.stage().name().toLowerCase(java.util.Locale.ROOT)
        )));
        section.child(LumaUi.caption(Component.translatable(
                "luma.project.operation_percent_label",
                OperationProgressPresenter.displayPercent(operation)
        )));
        if (operation.detail() != null && !operation.detail().isBlank()) {
            section.child(LumaUi.caption(Component.literal(operation.detail())));
        }
        return section;
    }

    private boolean operationActive() {
        return ScreenOperationStateSupport.blocksMutationActions(this.state.operationSnapshot());
    }

    private Component bannerText() {
        return ScreenOperationStateSupport.bannerText(this.state.status(), this.state.operationSnapshot(), "luma.status.share_ready");
    }

    private FlowLayout variantButtons(String selectedVariantId, java.util.function.Consumer<String> onSelected) {
        FlowLayout row = LumaUi.actionRow();
        for (ProjectVariant variant : this.sortedVariants()) {
            ButtonComponent button = LumaUi.button(Component.literal(ProjectUiSupport.displayVariantName(variant)), pressed -> onSelected.accept(variant.id()));
            button.active(!variant.id().equals(selectedVariantId));
            row.child(button);
        }
        return row;
    }

    private List<ProjectVariant> sortedVariants() {
        return this.state.variants().stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(this.state.project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
    }

    private void importPackage() {
        this.importPackage(this.importArchivePath);
    }

    private void importPackage(String archivePath) {
        HistoryPackageImportResult result = this.controller.importVariantPackage(this.projectName, archivePath);
        this.validationMessage = this.controller.lastValidationMessage();
        if (result == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }

        this.lastImportedProjectName = result.importedProjectName();
        this.selectedImportedProjectName = result.importedProjectName();
        this.selectedImportedVariantId = result.importedVariantId();
        this.selectedImportedVariantName = result.importedVariantName();
        this.conflictResolutions.clear();
        if (this.selectedTargetVariantId.isBlank() && this.state.project() != null) {
            this.selectedTargetVariantId = this.state.project().activeVariantId();
        }
        this.refreshMergePreview();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return (bytes / (1024L * 1024L)) + " MB";
    }

    private void deleteImportedProject(ImportedHistoryProjectSummary importedProject) {
        String statusKey = this.controller.deleteImportedProject(this.projectName, importedProject.projectName());
        this.validationMessage = this.controller.lastValidationMessage();
        if (importedProject.projectName().equals(this.selectedImportedProjectName)) {
            this.selectedImportedProjectName = "";
            this.selectedImportedVariantId = "";
            this.selectedImportedVariantName = "";
            this.mergePlan = null;
            this.mergePreviewPending = false;
            this.conflictResolutions.clear();
            this.controller.clearConflictZoneOverlay();
        }
        this.refresh(statusKey);
    }

    private void refreshMergePreview() {
        if (this.selectedImportedProjectName.isBlank() || this.selectedImportedVariantId.isBlank() || this.selectedTargetVariantId.isBlank()) {
            this.mergePlan = null;
            this.mergePreviewPending = false;
            this.refresh("luma.status.share_ready");
            return;
        }
        this.applyMergePreviewStatus(this.controller.requestMergePreview(
                this.projectName,
                this.selectedImportedProjectName,
                this.selectedImportedVariantId,
                this.selectedTargetVariantId
        ));
    }

    private void pollMergePreview() {
        if (this.selectedImportedProjectName.isBlank() || this.selectedImportedVariantId.isBlank() || this.selectedTargetVariantId.isBlank()) {
            this.mergePreviewPending = false;
            return;
        }
        MergePreviewStatus status = this.controller.requestMergePreview(
                this.projectName,
                this.selectedImportedProjectName,
                this.selectedImportedVariantId,
                this.selectedTargetVariantId
        );
        if (status.state() != MergePreviewStatus.State.PENDING) {
            this.applyMergePreviewStatus(status);
        }
    }

    private void applyMergePreviewStatus(MergePreviewStatus status) {
        if (status.state() == MergePreviewStatus.State.PENDING) {
            this.mergePlan = null;
            this.mergePreviewPending = true;
            this.validationMessage = "";
            this.refresh("luma.status.merge_preview_loading");
            return;
        }
        this.mergePreviewPending = false;
        this.validationMessage = status.state() == MergePreviewStatus.State.FAILED
                ? status.detail()
                : this.controller.lastValidationMessage();
        this.mergePlan = status.state() == MergePreviewStatus.State.READY ? status.plan() : null;
        this.refresh(status.state() == MergePreviewStatus.State.READY
                ? this.mergeStatusKey(this.mergePlan)
                : "luma.status.merge_preview_failed");
    }

    private String mergeStatusKey(VariantMergePlan plan) {
        if (plan == null) {
            return "luma.status.operation_failed";
        }
        if (plan.hasConflicts()) {
            return "luma.status.merge_conflicts_found";
        }
        if (plan.mergeChanges().isEmpty()) {
            return "luma.status.merge_no_changes";
        }
        return "luma.status.merge_preview_ready";
    }

    private void ensureSelections() {
        if (this.state.project() == null || this.state.variants().isEmpty()) {
            return;
        }
        if (ProjectUiSupport.variantFor(this.state.variants(), this.selectedExportVariantId) == null) {
            this.selectedExportVariantId = this.state.project().activeVariantId();
        }
        if (ProjectUiSupport.variantFor(this.state.variants(), this.selectedTargetVariantId) == null) {
            this.selectedTargetVariantId = this.state.project().activeVariantId();
        }
        if (!this.selectedImportedProjectName.isBlank() && this.importedProject(this.selectedImportedProjectName) == null) {
            this.selectedImportedProjectName = "";
            this.selectedImportedVariantId = "";
            this.selectedImportedVariantName = "";
            this.mergePlan = null;
            this.conflictResolutions.clear();
        }
    }

    private ImportedHistoryProjectSummary importedProject(String projectName) {
        return this.state.importedProjects().stream()
                .filter(project -> project.projectName().equals(projectName))
                .findFirst()
                .orElse(null);
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.share_ready" : statusKey;
        this.rebuild();
    }

    private void rebuild() {
        double scrollProgress = this.currentScrollProgress();
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        this.restoreScroll(scrollProgress);
    }

    private double currentScrollProgress() {
        return this.bodyScroll == null ? 0.0D : this.bodyScroll.progress();
    }

    private void restoreScroll(double scrollProgress) {
        if (this.bodyScroll != null) {
            this.bodyScroll.restoreProgress(scrollProgress);
        }
    }

    private final class MergeReviewActions implements ShareMergeReviewSection.Actions {

        @Override
        public void selectTargetVariant(String variantId) {
            selectedTargetVariantId = variantId;
            conflictResolutions.clear();
            refreshMergePreview();
        }

        @Override
        public void showAllConflicts(VariantMergePlan mergePlan) {
            String statusKey = controller.showConflictZonesOverlay(
                    selectedImportedProjectName,
                    selectedImportedVariantId,
                    selectedTargetVariantId,
                    mergePlan
            );
            validationMessage = controller.lastValidationMessage();
            refresh(statusKey);
        }

        @Override
        public void clearOverlay() {
            String statusKey = controller.clearConflictZoneOverlay();
            validationMessage = controller.lastValidationMessage();
            refresh(statusKey);
        }

        @Override
        public void setZoneResolution(String zoneId, MergeConflictResolution resolution) {
            conflictResolutions.put(zoneId, resolution);
            refresh("luma.status.merge_conflicts_found");
        }

        @Override
        public void clearZoneResolution(String zoneId) {
            conflictResolutions.remove(zoneId);
            refresh("luma.status.merge_conflicts_found");
        }

        @Override
        public void showZoneHighlight(MergeConflictZone zone) {
            String statusKey = controller.showConflictZoneOverlay(
                    selectedImportedProjectName,
                    selectedImportedVariantId,
                    selectedTargetVariantId,
                    zone
            );
            validationMessage = controller.lastValidationMessage();
            refresh(statusKey);
        }

        @Override
        public void applyMerge(List<MergeConflictZoneResolution> resolutions) {
            String statusKey = controller.startMerge(new VariantMergeApplyRequest(
                    projectName,
                    selectedImportedProjectName,
                    selectedImportedVariantId,
                    selectedTargetVariantId,
                    resolutions
            ));
            validationMessage = controller.lastValidationMessage();
            refresh(statusKey);
        }
    }
}
