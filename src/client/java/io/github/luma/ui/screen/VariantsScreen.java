package io.github.luma.ui.screen;

import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ShareFlowController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectTab;
import io.github.luma.ui.state.ProjectViewState;
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
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VariantsScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final String baseVersionId;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController controller = new ProjectScreenController();
    private final ShareFlowController shareController = new ShareFlowController();
    private final ScreenRouter router = new ScreenRouter();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private ProjectViewState state = new ProjectViewState(
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            new io.github.luma.domain.model.ProjectIntegrityReport(true, List.of(), List.of()),
            ProjectTab.VARIANTS,
            "luma.status.project_ready"
    );
    private String status = "luma.status.project_ready";
    private String variantName = "";
    private String shareVariantId = "";
    private String mergeTargetVariantId = "";
    private String importArchivePath = "";
    private String lastExportPath = "";
    private String lastImportedProjectName = "";
    private String mergeSourceProjectName = "";
    private String mergeSourceVariantId = "";
    private String mergeSourceVariantName = "";
    private VariantMergePlan mergePlan = null;
    private TextBoxComponent variantNameInput;
    private TextBoxComponent importArchiveInput;

    public VariantsScreen(Screen parent, String projectName) {
        this(parent, projectName, "");
    }

    public VariantsScreen(Screen parent, String projectName, String baseVersionId) {
        super(Component.translatable("luma.screen.variants.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.baseVersionId = baseVersionId == null ? "" : baseVersionId;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, ProjectTab.VARIANTS, this.baseVersionId, this.status);
        ProjectVersion baseVersion = this.resolvedBaseVersion();
        this.ensureSelections();
        List<ImportedHistoryProjectSummary> importedProjects = this.state.project() == null
                ? List.of()
                : this.shareController.listImportedProjects(this.projectName);

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProjectIgnoringRecovery(
                this.parent,
                this.projectName
        )));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.variants.title", this.projectName)));
        frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        if (this.state.project() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        body.child(this.overviewSection());
        body.child(this.createSection(baseVersion));
        body.child(this.shareSection(importedProjects));
        body.child(this.listSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout overviewSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.variants.overview_title"),
                Component.translatable("luma.variants.overview_help")
        );
        section.child(LumaUi.chip(Component.translatable(
                "luma.project.active_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.state.project().activeVariantId())
        )));
        if (this.state.operationSnapshot() != null) {
            section.child(this.operationSection());
        }
        return section;
    }

    private FlowLayout createSection(ProjectVersion baseVersion) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.variants.create_title"),
                Component.translatable(
                        "luma.variants.create_help",
                        baseVersion == null ? Component.translatable("luma.variants.current_variant_base") : Component.literal(ProjectUiSupport.displayMessage(baseVersion))
                )
        );

        this.variantNameInput = UIComponents.textBox(Sizing.fill(100), this.variantName);
        this.variantNameInput.setHint(Component.translatable("luma.variant.name_input"));
        this.variantNameInput.onChanged().subscribe(value -> this.variantName = value);
        section.child(LumaUi.formField(
                Component.translatable("luma.variant.name_input"),
                Component.translatable("luma.variants.name_help"),
                this.variantNameInput
        ));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent createButton = UIComponents.button(Component.translatable("luma.action.variant_create"), button -> {
            String result = this.controller.createVariant(this.projectName, this.variantName, this.baseVersionId);
            if ("luma.status.variant_created".equals(result)) {
                this.variantName = "";
            }
            this.refresh(result);
        });
        createButton.active(!this.variantName.isBlank() && !this.operationActive());
        actions.child(createButton);
        section.child(actions);
        return section;
    }

    private FlowLayout shareSection(List<ImportedHistoryProjectSummary> importedProjects) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.share.title"),
                Component.translatable("luma.share.help")
        );
        section.child(this.exportSection());
        section.child(this.importSection());
        section.child(this.mergeSection(importedProjects));
        return section;
    }

    private FlowLayout exportSection() {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.share.export_title"),
                Component.translatable("luma.share.export_help")
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.share.selected_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.shareVariantId)
        )));
        section.child(this.variantButtons(this.shareVariantId, variantId -> {
            this.shareVariantId = variantId;
            this.refresh("luma.status.share_ready");
        }));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent exportButton = UIComponents.button(Component.translatable("luma.action.export_history"), button -> {
            var result = this.shareController.exportVariantPackage(this.projectName, this.shareVariantId);
            this.lastExportPath = result == null ? "" : result.archiveFile().toString();
            this.refresh(result == null ? "luma.status.operation_failed" : "luma.status.history_exported");
        });
        exportButton.active(!this.shareVariantId.isBlank() && !this.operationActive());
        actions.child(exportButton);
        section.child(actions);
        if (!this.lastExportPath.isBlank()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.export_ready", this.lastExportPath)));
        }
        return section;
    }

    private FlowLayout importSection() {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.share.import_title"),
                Component.translatable("luma.share.import_help")
        );
        this.importArchiveInput = UIComponents.textBox(Sizing.fill(100), this.importArchivePath);
        this.importArchiveInput.setHint(Component.translatable("luma.share.import_path"));
        this.importArchiveInput.onChanged().subscribe(value -> this.importArchivePath = value);
        section.child(this.importArchiveInput);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent importButton = UIComponents.button(Component.translatable("luma.action.import_history"), button -> {
            var result = this.shareController.importVariantPackage(this.projectName, this.importArchivePath);
            if (result != null) {
                this.lastImportedProjectName = result.importedProjectName();
                this.mergeSourceProjectName = result.importedProjectName();
                this.mergeSourceVariantId = result.importedVariantId();
                this.mergeSourceVariantName = result.importedVariantName();
                this.mergePlan = null;
            }
            this.refresh(result == null ? "luma.status.operation_failed" : "luma.status.history_imported");
        });
        importButton.active(!this.importArchivePath.isBlank() && !this.operationActive());
        actions.child(importButton);
        section.child(actions);
        if (!this.lastImportedProjectName.isBlank()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.import_ready", this.lastImportedProjectName)));
        }
        return section;
    }

    private FlowLayout mergeSection(List<ImportedHistoryProjectSummary> importedProjects) {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.share.merge_title"),
                Component.translatable("luma.share.merge_help")
        );
        section.child(LumaUi.caption(Component.translatable(
                "luma.share.target_variant",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.mergeTargetVariantId)
        )));
        section.child(this.variantButtons(this.mergeTargetVariantId, variantId -> {
            this.mergeTargetVariantId = variantId;
            this.mergePlan = null;
            this.refresh("luma.status.share_ready");
        }));

        if (importedProjects.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.share.imported_empty")));
            return section;
        }

        for (ImportedHistoryProjectSummary importedProject : importedProjects) {
            section.child(this.importedProjectCard(importedProject));
        }
        if (this.mergePlan != null) {
            section.child(this.mergeReviewCard());
        }
        return section;
    }

    private FlowLayout importedProjectCard(ImportedHistoryProjectSummary importedProject) {
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.literal(importedProject.projectName())));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.imported_entry",
                importedProject.variantName(),
                ProjectUiSupport.formatTimestamp(importedProject.updatedAt())
        )));

        FlowLayout actions = LumaUi.actionRow();
        actions.child(UIComponents.button(Component.translatable("luma.action.open_project"), button -> this.router.openProjectIgnoringRecovery(
                this,
                importedProject.projectName()
        )));
        ButtonComponent reviewButton = UIComponents.button(Component.translatable("luma.action.review_merge"), button -> {
            this.mergeSourceProjectName = importedProject.projectName();
            this.mergeSourceVariantId = importedProject.variantId();
            this.mergeSourceVariantName = importedProject.variantName();
            this.mergePlan = this.shareController.previewMerge(
                    this.projectName,
                    importedProject.projectName(),
                    importedProject.variantId(),
                    this.mergeTargetVariantId
            );
            this.refresh(this.mergeStatusKey(this.mergePlan));
        });
        reviewButton.active(!this.operationActive());
        actions.child(reviewButton);
        card.child(actions);
        return card;
    }

    private FlowLayout mergeReviewCard() {
        ProjectVersion commonAncestor = ProjectUiSupport.versionFor(this.state.versions(), this.mergePlan.commonAncestorVersionId());
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.translatable(
                "luma.share.merge_review_title",
                this.mergeSourceVariantLabel(),
                ProjectUiSupport.displayVariantName(this.state.variants(), this.mergeTargetVariantId)
        )));
        card.child(LumaUi.caption(Component.translatable(
                "luma.share.merge_ancestor",
                commonAncestor == null
                        ? ProjectUiSupport.safeText(this.mergePlan.commonAncestorVersionId())
                        : ProjectUiSupport.displayMessage(commonAncestor)
        )));

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(Component.translatable("luma.share.source_changes"), Component.literal(Integer.toString(this.mergePlan.sourceChangedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.target_changes"), Component.literal(Integer.toString(this.mergePlan.targetChangedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.merge_changes"), Component.literal(Integer.toString(this.mergePlan.mergeBlockCount()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.share.conflicts"), Component.literal(Integer.toString(this.mergePlan.conflictPositions().size()))));
        card.child(stats);

        if (this.mergePlan.hasConflicts()) {
            card.child(LumaUi.danger(Component.translatable(
                    "luma.share.merge_conflicts",
                    this.mergePlan.conflictPositions().size(),
                    this.mergePlan.conflictChunkCount()
            )));
            for (var pos : this.mergePlan.sampleConflictPositions(5)) {
                card.child(LumaUi.caption(Component.literal("(" + pos.x() + ", " + pos.y() + ", " + pos.z() + ")")));
            }
            return card;
        }

        if (this.mergePlan.mergeChanges().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.share.merge_no_changes")));
            return card;
        }

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent mergeButton = UIComponents.button(Component.translatable("luma.action.merge_variant"), button -> this.refresh(
                this.shareController.startMerge(
                        this.projectName,
                        this.mergeSourceProjectName,
                        this.mergeSourceVariantId,
                        this.mergeTargetVariantId
                )
        ));
        mergeButton.active(!this.operationActive());
        actions.child(mergeButton);
        card.child(actions);
        return card;
    }

    private FlowLayout listSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.variants.list_title"),
                Component.translatable("luma.variants.list_help")
        );

        if (this.state.variants().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.variant.empty")));
            return section;
        }

        for (ProjectVariant variant : this.sortedVariants()) {
            section.child(this.variantCard(variant));
        }
        return section;
    }

    private FlowLayout variantCard(ProjectVariant variant) {
        ProjectVersion headVersion = ProjectUiSupport.versionFor(this.state.versions(), variant.headVersionId());
        boolean active = this.state.project() != null && variant.id().equals(this.state.project().activeVariantId());

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.child(LumaUi.value(Component.literal(ProjectUiSupport.displayVariantName(variant))));
        if (headVersion == null) {
            card.child(LumaUi.caption(Component.translatable("luma.variants.no_head")));
        } else {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.variants.head_summary",
                    ProjectUiSupport.displayMessage(headVersion),
                    ProjectUiSupport.formatTimestamp(headVersion.createdAt())
            )));
        }

        FlowLayout meta = LumaUi.actionRow();
        if (active) {
            meta.child(LumaUi.chip(Component.translatable("luma.variant.active_badge")));
        }
        if (variant.baseVersionId() != null && !variant.baseVersionId().isBlank()) {
            meta.child(LumaUi.chip(Component.translatable("luma.variants.base_badge", variant.baseVersionId())));
        }
        card.child(meta);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent switchButton = UIComponents.button(Component.translatable("luma.action.variant_switch"), button -> {
            String result = this.controller.switchVariant(this.projectName, variant.id());
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, variant.id(), result);
        });
        switchButton.active(!active);
        actions.child(switchButton);

        actions.child(UIComponents.button(Component.translatable("luma.action.open_history"), button -> this.router.openProjectIgnoringRecovery(
                this.parent,
                this.projectName,
                variant.id(),
                "luma.status.project_ready"
        )));

        ButtonComponent compareButton = UIComponents.button(Component.translatable("luma.variants.compare_current"), button -> this.router.openCompare(
                this,
                this.projectName,
                variant.headVersionId(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                variant.headVersionId()
        ));
        compareButton.active(headVersion != null);
        actions.child(compareButton);
        card.child(actions);
        return card;
    }

    private ProjectVersion resolvedBaseVersion() {
        if (!this.baseVersionId.isBlank()) {
            return ProjectUiSupport.versionFor(this.state.versions(), this.baseVersionId);
        }
        return ProjectUiSupport.activeHead(this.state.project(), this.state.variants(), this.state.versions());
    }

    private List<ProjectVariant> sortedVariants() {
        return this.state.variants().stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(this.state.project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
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
        return this.state.operationSnapshot() != null && !this.state.operationSnapshot().terminal();
    }

    private FlowLayout variantButtons(String selectedVariantId, java.util.function.Consumer<String> onSelected) {
        FlowLayout row = LumaUi.actionRow();
        for (ProjectVariant variant : this.sortedVariants()) {
            ButtonComponent button = UIComponents.button(Component.literal(ProjectUiSupport.displayVariantName(variant)), pressed -> onSelected.accept(variant.id()));
            button.active(!variant.id().equals(selectedVariantId));
            row.child(button);
        }
        return row;
    }

    private void ensureSelections() {
        if (this.state.project() == null || this.state.variants().isEmpty()) {
            return;
        }
        if (ProjectUiSupport.variantFor(this.state.variants(), this.shareVariantId) == null) {
            this.shareVariantId = this.state.project().activeVariantId();
        }
        if (ProjectUiSupport.variantFor(this.state.variants(), this.mergeTargetVariantId) == null) {
            this.mergeTargetVariantId = this.state.project().activeVariantId();
        }
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

    private String mergeSourceVariantLabel() {
        if (this.mergeSourceVariantName != null && !this.mergeSourceVariantName.isBlank()) {
            return this.mergeSourceVariantName;
        }
        if (this.mergeSourceProjectName != null && !this.mergeSourceProjectName.isBlank()) {
            return this.mergeSourceProjectName;
        }
        return ProjectUiSupport.safeText(this.mergeSourceVariantId);
    }

    private void refresh(String statusKey) {
        this.status = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
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
