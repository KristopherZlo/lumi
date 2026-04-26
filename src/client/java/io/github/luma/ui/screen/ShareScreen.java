package io.github.luma.ui.screen;

import io.github.luma.domain.model.HistoryPackageImportResult;
import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.MergeConflictResolution;
import io.github.luma.domain.model.MergeConflictZone;
import io.github.luma.domain.model.MergeConflictZoneResolution;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VariantMergeApplyRequest;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.controller.MergePreviewStatus;
import io.github.luma.ui.controller.ShareScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.overlay.CompareOverlayRenderer;
import io.github.luma.ui.state.ShareViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
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
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private ShareViewState state = new ShareViewState(null, List.of(), List.of(), List.of(), null, "luma.status.share_ready");
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
        super(Component.translatable("luma.screen.share.title", projectName));
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

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(LumaUi.button(Component.translatable("luma.action.workspaces"), button -> this.router.openDashboard(this)));
        frame.child(header);

        if (this.state.project() == null) {
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        FlowLayout titleRow = LumaUi.actionRow();
        titleRow.child(LumaUi.value(Component.translatable("luma.screen.share.title", this.projectName)));
        titleRow.child(LumaUi.chip(Component.translatable(
                "luma.dashboard.current_dimension",
                ProjectUiSupport.dimensionLabel(this.state.project().dimensionId())
        )));
        titleRow.child(LumaUi.chip(Component.translatable(
                "luma.project.active_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.state.project().activeVariantId())
        )));
        frame.child(titleRow);
        frame.child(LumaUi.statusBanner(this.bannerText()));
        if (!this.validationMessage.isBlank()) {
            frame.child(LumaUi.danger(Component.literal(this.validationMessage)));
        }
        frame.child(this.navigationRow());
        if (this.state.operationSnapshot() != null) {
            frame.child(this.operationSection());
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        body.child(this.importSection());
        body.child(this.importedPackagesSection());
        body.child(this.exportSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
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

    private FlowLayout navigationRow() {
        FlowLayout navigation = LumaUi.actionRow();
        navigation.child(LumaUi.button(Component.translatable("luma.tab.history"), button -> this.router.openProjectIgnoringRecovery(
                this,
                this.projectName,
                "luma.status.project_ready"
        )));
        navigation.child(LumaUi.button(Component.translatable("luma.tab.variants"), button -> this.router.openVariants(
                this,
                this.projectName
        )));
        ButtonComponent shareButton = LumaUi.button(Component.translatable("luma.tab.share"), button -> this.refresh("luma.status.share_ready"));
        shareButton.active(false);
        navigation.child(shareButton);
        return navigation;
    }

    private FlowLayout importSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.share.import_title"),
                Component.translatable("luma.share.import_help")
        );
        this.importArchiveInput = UIComponents.textBox(Sizing.fill(100), this.importArchivePath);
        this.importArchiveInput.setHint(Component.translatable("luma.share.import_path"));
        this.importArchiveInput.onChanged().subscribe(value -> this.importArchivePath = value);
        section.child(this.importArchiveInput);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent importButton = LumaUi.primaryButton(Component.translatable("luma.action.import_history"), button -> this.importPackage());
        importButton.active(!this.importArchivePath.isBlank() && !this.operationActive());
        actions.child(importButton);
        section.child(actions);

        if (!this.lastImportedProjectName.isBlank()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.import_ready", this.lastImportedProjectName)));
        }
        return section;
    }

    private FlowLayout importedPackagesSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.share.packages_title"),
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
        ButtonComponent reviewButton = LumaUi.button(Component.translatable("luma.action.review_merge"), button -> {
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
            if (this.mergePreviewPending && this.mergePlan == null) {
                card.child(this.mergePreviewPendingSection());
            } else if (this.mergePlan != null) {
                card.child(this.mergeReviewSection());
            }
        }
        return card;
    }

    private FlowLayout mergePreviewPendingSection() {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.share.merge_preview_title"),
                Component.translatable("luma.share.merge_preview_loading")
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.share.target_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.selectedTargetVariantId)
        )));
        return section;
    }

    private FlowLayout mergeReviewSection() {
        ProjectVersion commonAncestor = ProjectUiSupport.versionFor(this.state.versions(), this.mergePlan.commonAncestorVersionId());
        FlowLayout section = LumaUi.insetSection(
                Component.translatable(
                        "luma.share.merge_review_title",
                        this.importedVariantLabel(),
                        ProjectUiSupport.displayVariantName(this.state.variants(), this.selectedTargetVariantId)
                ),
                Component.translatable(
                        "luma.share.merge_ancestor",
                        commonAncestor == null
                                ? ProjectUiSupport.safeText(this.mergePlan.commonAncestorVersionId())
                                : ProjectUiSupport.displayMessage(commonAncestor)
                )
        );

        section.child(LumaUi.caption(Component.translatable(
                "luma.share.target_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.selectedTargetVariantId)
        )));
        section.child(this.variantButtons(this.selectedTargetVariantId, variantId -> {
            this.selectedTargetVariantId = variantId;
            this.conflictResolutions.clear();
            this.refreshMergePreview();
        }));

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(Component.translatable("luma.share.source_changes"), Component.literal(Integer.toString(this.mergePlan.sourceChangedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.target_changes"), Component.literal(Integer.toString(this.mergePlan.targetChangedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.merge_changes"), Component.literal(Integer.toString(this.mergePlan.mergeBlockCount()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.conflicts"), Component.literal(Integer.toString(this.mergePlan.conflictPositions().size()))));
        section.child(stats);

        if (this.mergePlan.hasConflicts()) {
            section.child(LumaUi.danger(Component.translatable(
                    "luma.share.merge_conflicts",
                    this.mergePlan.conflictPositions().size(),
                    this.mergePlan.conflictChunkCount()
            )));
            FlowLayout conflictOverlayActions = LumaUi.actionRow();
            conflictOverlayActions.child(LumaUi.button(Component.translatable("luma.action.show_all_conflicts"), button -> {
                String statusKey = this.controller.showConflictZonesOverlay(
                            this.selectedImportedProjectName,
                            this.selectedImportedVariantId,
                            this.selectedTargetVariantId,
                            this.mergePlan
                    );
                this.validationMessage = this.controller.lastValidationMessage();
                this.refresh(statusKey);
            }));
            section.child(conflictOverlayActions);
            for (MergeConflictZone zone : this.mergePlan.conflictZones()) {
                section.child(this.conflictZoneCard(zone));
            }
        } else if (this.mergePlan.mergeChanges().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.merge_no_changes")));
        }

        if (CompareOverlayRenderer.hasData()) {
            FlowLayout overlayActions = LumaUi.actionRow();
            overlayActions.child(LumaUi.button(Component.translatable("luma.action.hide_highlight"), button -> {
                String statusKey = this.controller.clearConflictZoneOverlay();
                this.validationMessage = this.controller.lastValidationMessage();
                this.refresh(statusKey);
            }));
            section.child(overlayActions);
        }

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent mergeButton = LumaUi.primaryButton(Component.translatable("luma.action.merge_variant"), button -> {
            String statusKey = this.controller.startMerge(new VariantMergeApplyRequest(
                        this.projectName,
                        this.selectedImportedProjectName,
                        this.selectedImportedVariantId,
                        this.selectedTargetVariantId,
                        this.conflictZoneResolutions()
                ));
            this.validationMessage = this.controller.lastValidationMessage();
            this.refresh(statusKey);
        });
        mergeButton.active(this.mergePlan.canApply(this.conflictZoneResolutions()) && !this.operationActive());
        actions.child(mergeButton);
        section.child(actions);

        boolean allZonesResolved = this.mergePlan.conflictZones().stream()
                .allMatch(zone -> this.conflictResolutions.containsKey(zone.id()));
        if (this.mergePlan.canApply(this.conflictZoneResolutions())) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.share.merge_ready",
                    this.mergePlan.effectiveMergeBlockCount(this.conflictZoneResolutions())
            )));
        } else if (allZonesResolved && this.mergePlan.effectiveMergeBlockCount(this.conflictZoneResolutions()) == 0) {
            section.child(LumaUi.caption(Component.translatable("luma.share.merge_no_changes")));
        } else if (this.mergePlan.hasConflicts()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.merge_unresolved_help")));
        }
        return section;
    }

    private FlowLayout conflictZoneCard(MergeConflictZone zone) {
        MergeConflictResolution resolution = this.conflictResolutions.get(zone.id());

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.translatable("luma.share.zone_title", this.zoneLabel(zone))));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.zone_summary",
                zone.chunkCount(),
                zone.blockCount()
        )));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.zone_bounds",
                zone.bounds().min().x(),
                zone.bounds().min().y(),
                zone.bounds().min().z(),
                zone.bounds().max().x(),
                zone.bounds().max().y(),
                zone.bounds().max().z()
        )));
        for (var pos : zone.samplePositions(3)) {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.share.zone_position",
                    pos.x(),
                    pos.y(),
                    pos.z()
            )));
        }
        card.child(LumaUi.caption(Component.translatable(this.zoneStatusKey(resolution))));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent keepLocal = LumaUi.button(Component.translatable("luma.action.keep_local"), button -> {
            this.conflictResolutions.put(zone.id(), MergeConflictResolution.KEEP_LOCAL);
            this.refresh("luma.status.merge_conflicts_found");
        });
        keepLocal.active(resolution != MergeConflictResolution.KEEP_LOCAL);
        actions.child(keepLocal);

        ButtonComponent useImported = LumaUi.button(Component.translatable("luma.action.use_imported"), button -> {
            this.conflictResolutions.put(zone.id(), MergeConflictResolution.USE_IMPORTED);
            this.refresh("luma.status.merge_conflicts_found");
        });
        useImported.active(resolution != MergeConflictResolution.USE_IMPORTED);
        actions.child(useImported);

        ButtonComponent skip = LumaUi.button(Component.translatable("luma.action.skip_for_now"), button -> {
            this.conflictResolutions.remove(zone.id());
            this.refresh("luma.status.merge_conflicts_found");
        });
        skip.active(resolution != null);
        actions.child(skip);

        actions.child(LumaUi.button(Component.translatable("luma.action.show_highlight"), button -> {
            String statusKey = this.controller.showConflictZoneOverlay(
                        this.selectedImportedProjectName,
                        this.selectedImportedVariantId,
                        this.selectedTargetVariantId,
                        zone
                );
            this.validationMessage = this.controller.lastValidationMessage();
            this.refresh(statusKey);
        }));
        card.child(actions);
        return card;
    }

    private FlowLayout exportSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.share.export_title"),
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
        ButtonComponent exportButton = LumaUi.primaryButton(Component.translatable("luma.action.export_history"), button -> {
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
        HistoryPackageImportResult result = this.controller.importVariantPackage(this.projectName, this.importArchivePath);
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

    private List<MergeConflictZoneResolution> conflictZoneResolutions() {
        return this.conflictResolutions.entrySet().stream()
                .map(entry -> new MergeConflictZoneResolution(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String zoneStatusKey(MergeConflictResolution resolution) {
        if (resolution == null) {
            return "luma.share.zone_status_unresolved";
        }
        return switch (resolution) {
            case KEEP_LOCAL -> "luma.share.zone_status_keep_local";
            case USE_IMPORTED -> "luma.share.zone_status_use_imported";
        };
    }

    private String zoneLabel(MergeConflictZone zone) {
        return zone.id().startsWith("zone-") ? zone.id().substring("zone-".length()) : zone.id();
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

    private String importedVariantLabel() {
        if (this.selectedImportedVariantName != null && !this.selectedImportedVariantName.isBlank()) {
            return this.selectedImportedVariantName;
        }
        return ProjectUiSupport.safeText(this.selectedImportedVariantId);
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
}
