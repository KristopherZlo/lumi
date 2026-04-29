package io.github.luma.ui.screen;

import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.client.selection.LumiRegionSelectionController;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.MaterialEntryView;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.screen.section.SaveDetailsPartialRestoreSection;
import io.github.luma.ui.state.PartialRestoreFormState;
import io.github.luma.ui.state.SaveDetailsViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class SaveDetailsScreen extends LumaScreen {

    private static final int MATERIAL_LIMIT = 6;
    private static final int[] PREVIEW_WIDTH_STEPS = {168, 212, 284, 356};

    private final Screen parent;
    private final String projectName;
    private final String versionId;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController controller = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private final PartialRestoreFormState partialRestoreForm = new PartialRestoreFormState();
    private final SaveDetailsPartialRestoreSection partialRestoreSections = new SaveDetailsPartialRestoreSection(new PartialRestoreActions());
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private SaveDetailsViewState state = new SaveDetailsViewState(
            null,
            List.of(),
            List.of(),
            null,
            null,
            List.of(),
            null,
            null,
            "luma.status.project_ready"
    );
    private String status = "luma.status.project_ready";
    private boolean showMoreOptions = false;
    private boolean pendingRestoreConfirmation = false;
    private boolean pendingDeleteConfirmation = false;
    private boolean showPartialRestore = false;
    private boolean showAdvancedInfo = false;
    private String renameVersionId = "";
    private String renameMessage = "";
    private int previewZoomStep = 1;
    private int refreshCooldown = 0;

    public SaveDetailsScreen(Screen parent, String projectName, String versionId) {
        super(Component.translatable("luma.screen.save_details.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.versionId = versionId;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadSaveDetailsState(this.projectName, this.versionId, this.status);
        ProjectVersion version = this.state.selectedVersion();
        ProjectVariant versionVariant = version == null ? null : ProjectUiSupport.variantFor(this.state.variants(), version.variantId());
        boolean operationActive = this.state.operationSnapshot() != null && !this.state.operationSnapshot().terminal();

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        if (version == null) {
            frame.child(LumaUi.emptyState(
                    Component.translatable("luma.save_details.empty_title"),
                    Component.translatable("luma.preview.no_version")
            ));
            return;
        }

        frame.child(LumaUi.value(Component.translatable(
                "luma.screen.save_details.title",
                ProjectUiSupport.displayMessage(version)
        )));
        if (this.shouldShowStatusBanner()) {
            frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));
        }

        if (this.pendingRestoreConfirmation) {
            frame.child(this.restoreConfirmationSection(version, versionVariant, operationActive));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        body.child(this.summarySection(version, versionVariant));
        body.child(this.changesSection(version));
        body.child(this.primaryActions(version, versionVariant, operationActive));
        if (this.showPartialRestore) {
            body.child(this.partialRestoreSection(version, operationActive));
        }
        body.child(this.moreSection(version, operationActive));
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

        SaveDetailsViewState refreshed = this.controller.loadSaveDetailsState(this.projectName, this.versionId, this.status);
        String normalizedStatusKey = ScreenOperationStateSupport.normalizeStatusKey(
                this.status,
                refreshed.operationSnapshot(),
                "luma.status.project_ready"
        );
        if (!normalizedStatusKey.equals(this.status)) {
            this.status = normalizedStatusKey;
            refreshed = this.controller.loadSaveDetailsState(this.projectName, this.versionId, this.status);
        }
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.rebuild();
        }
    }

    private FlowLayout summarySection(ProjectVersion version, ProjectVariant versionVariant) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.summary_title"),
                Component.translatable(
                        "luma.save_details.summary_help",
                        ProjectUiSupport.formatTimestamp(version.createdAt())
                )
        );

        FlowLayout hero = this.width < 860
                ? UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        hero.gap(10);
        hero.child(this.previewPanel(version));

        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(6);
        text.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));

        if (ProjectUiSupport.isVariantHead(this.state.variants(), version)) {
            FlowLayout meta = LumaUi.actionRow();
            meta.child(LumaUi.chip(Component.translatable("luma.history.current_badge")));
            text.child(meta);
        }
        text.child(LumaUi.caption(Component.translatable("luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        hero.child(text);
        section.child(hero);
        return section;
    }

    private FlowLayout previewPanel(ProjectVersion version) {
        int previewWidth = this.previewWidth();
        FlowLayout panel = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
        panel.gap(4);
        panel.child(ProjectUiSupport.versionPreview(
                this.controller,
                this.projectName,
                version,
                previewWidth,
                Math.max(88, previewWidth / 2),
                Math.max(132, (previewWidth * 3) / 4)
        ));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent zoomOut = LumaUi.button(Component.translatable("luma.action.zoom_out"), button -> {
            this.previewZoomStep = Math.max(0, this.previewZoomStep - 1);
            this.rebuild();
        });
        zoomOut.active(this.previewZoomStep > 0);
        actions.child(zoomOut);

        ButtonComponent zoomIn = LumaUi.button(Component.translatable("luma.action.zoom_in"), button -> {
            this.previewZoomStep = Math.min(this.maxPreviewZoomStep(), this.previewZoomStep + 1);
            this.rebuild();
        });
        zoomIn.active(this.previewZoomStep < this.maxPreviewZoomStep());
        actions.child(zoomIn);
        panel.child(actions);
        return panel;
    }

    private int previewWidth() {
        return PREVIEW_WIDTH_STEPS[Math.max(0, Math.min(this.previewZoomStep, this.maxPreviewZoomStep()))];
    }

    private int maxPreviewZoomStep() {
        if (this.width < 560) {
            return 1;
        }
        if (this.width < 760) {
            return 2;
        }
        return PREVIEW_WIDTH_STEPS.length - 1;
    }

    private FlowLayout changesSection(ProjectVersion version) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.changes_title"),
                Component.translatable("luma.save_details.changes_help")
        );

        FlowLayout stats = LumaUi.actionRow();
        if (this.state.selectedVersionDiff() != null) {
            stats.child(LumaUi.statChip(
                    Component.translatable("luma.change_type.added"),
                    Component.literal(Integer.toString(ProjectUiSupport.changeCount(this.state.selectedVersionDiff(), ChangeType.ADDED)))
            ));
            stats.child(LumaUi.statChip(
                    Component.translatable("luma.change_type.removed"),
                    Component.literal(Integer.toString(ProjectUiSupport.changeCount(this.state.selectedVersionDiff(), ChangeType.REMOVED)))
            ));
            stats.child(LumaUi.statChip(
                    Component.translatable("luma.change_type.changed"),
                    Component.literal(Integer.toString(ProjectUiSupport.changeCount(this.state.selectedVersionDiff(), ChangeType.CHANGED)))
            ));
        } else {
            stats.child(LumaUi.statChip(
                    Component.translatable("luma.history.commit_blocks"),
                    Component.literal(Integer.toString(version.stats().changedBlocks()))
            ));
        }
        section.child(stats);

        return section;
    }

    private FlowLayout primaryActions(ProjectVersion version, ProjectVariant versionVariant, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.actions_title"),
                null
        );

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent restoreButton = LumaUi.primaryButton(Component.translatable("luma.action.restore_this_save"), button -> this.restoreVersion(version, versionVariant));
        restoreButton.active(!operationActive);
        actions.child(restoreButton);

        actions.child(LumaUi.button(Component.translatable("luma.action.see_changes"), button -> this.router.openCompare(
                this,
                this.projectName,
                version.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                version.id()
        )));

        ButtonComponent comparePrevious = LumaUi.button(Component.translatable("luma.action.see_previous_changes"), button -> this.router.openCompare(
                this,
                this.projectName,
                this.parentVersionId(version.id()),
                version.id(),
                version.id()
        ));
        comparePrevious.active(!this.parentVersionId(version.id()).isBlank());
        actions.child(comparePrevious);

        actions.child(LumaUi.button(Component.translatable("luma.action.restore_selected_area"), button -> {
            this.showPartialRestore = !this.showPartialRestore;
            this.rebuild();
        }));

        section.child(actions);
        return section;
    }

    private FlowLayout partialRestoreSection(ProjectVersion version, boolean operationActive) {
        return this.partialRestoreSections.section(new SaveDetailsPartialRestoreSection.Model(
                this.projectName,
                version,
                this.client.getUser().getName(),
                operationActive,
                this.partialRestoreForm,
                this.state.project() == null ? null : this.state.project().bounds(),
                this.fallbackPartialRestoreBounds(),
                this.selectedLumiBounds()
        ));
    }

    private FlowLayout moreSection(ProjectVersion version, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.more_title"),
                Component.translatable("luma.save_details.more_help")
        );

        FlowLayout toggle = LumaUi.actionRow();
        toggle.child(LumaUi.button(Component.translatable(
                this.showMoreOptions ? "luma.action.hide_tools" : "luma.action.more_tools"
        ), button -> {
            this.showMoreOptions = !this.showMoreOptions;
            this.rebuild();
        }));
        section.child(toggle);

        if (!this.showMoreOptions) {
            return section;
        }

        FlowLayout expanded = LumaUi.revealGroup();
        this.ensureRenameMessage(version);
        expanded.child(this.renameSection(version, operationActive));
        if (this.pendingDeleteConfirmation) {
            expanded.child(this.deleteConfirmationSection(version, operationActive));
        }

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent replaceButton = LumaUi.button(Component.translatable("luma.action.amend_version"), button -> this.router.openSave(
                this,
                this.projectName,
                ProjectUiSupport.displayMessage(version),
                true
        ));
        replaceButton.active(this.canReplaceLatest(version) && !operationActive);
        actions.child(replaceButton);

        ButtonComponent deleteButton = LumaUi.button(Component.translatable("luma.action.delete_save"), button -> {
            this.pendingDeleteConfirmation = true;
            this.refresh("luma.status.version_delete_confirm");
        });
        deleteButton.active(this.canDeleteVersion(version) && !operationActive);
        actions.child(deleteButton);

        actions.child(LumaUi.button(Component.translatable("luma.save_details.create_idea"), button -> this.router.openVariants(
                this,
                this.projectName,
                version.id()
        )));
        expanded.child(actions);

        FlowLayout advanced = LumaUi.actionRow();
        advanced.child(LumaUi.button(Component.translatable(
                this.showAdvancedInfo ? "luma.action.hide_advanced_info" : "luma.action.advanced_info"
        ), button -> {
            this.showAdvancedInfo = !this.showAdvancedInfo;
            this.rebuild();
        }));
        expanded.child(advanced);
        if (this.showAdvancedInfo) {
            FlowLayout advancedExpanded = LumaUi.revealGroup();
            advancedExpanded.child(this.advancedInfoSection(version));
            expanded.child(advancedExpanded);
        }
        section.child(expanded);
        return section;
    }

    private FlowLayout renameSection(ProjectVersion version, boolean operationActive) {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.save_details.rename_title"),
                Component.translatable("luma.save_details.rename_help")
        );
        ButtonComponent renameButton = LumaUi.primaryButton(Component.translatable("luma.action.rename_save"), button -> {
            String result = this.controller.renameVersion(this.projectName, version.id(), this.renameMessage);
            if ("luma.status.version_renamed".equals(result)) {
                this.renameVersionId = "";
            }
            this.refresh(result);
        });
        TextBoxComponent input = UIComponents.textBox(Sizing.fill(100), this.renameMessage);
        input.setHint(Component.translatable("luma.save.name_input"));
        input.onChanged().subscribe(value -> {
            this.renameMessage = value == null ? "" : value;
            renameButton.active(this.canRenameVersion(version, operationActive));
        });
        section.child(input);

        FlowLayout actions = LumaUi.actionRow();
        renameButton.active(this.canRenameVersion(version, operationActive));
        actions.child(renameButton);
        section.child(actions);
        return section;
    }

    private FlowLayout deleteConfirmationSection(ProjectVersion version, boolean operationActive) {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.save_details.delete_title"),
                Component.translatable("luma.save_details.delete_help")
        );
        section.child(LumaUi.danger(Component.translatable("luma.save_details.delete_warning")));
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> {
            this.pendingDeleteConfirmation = false;
            this.refresh("luma.status.project_ready");
        }));
        ButtonComponent confirmButton = LumaUi.primaryButton(Component.translatable("luma.action.delete_save"), button -> {
            this.pendingDeleteConfirmation = false;
            String result = this.controller.deleteVersion(this.projectName, version.id());
            if ("luma.status.version_deleted".equals(result)) {
                this.router.openProjectIgnoringRecovery(this.parent, this.projectName, result);
                return;
            }
            this.refresh(result);
        });
        confirmButton.active(this.canDeleteVersion(version) && !operationActive);
        actions.child(confirmButton);
        section.child(actions);
        return section;
    }

    private void ensureRenameMessage(ProjectVersion version) {
        if (version == null) {
            return;
        }
        if (!version.id().equals(this.renameVersionId)) {
            this.renameVersionId = version.id();
            this.renameMessage = ProjectUiSupport.displayMessage(version);
        }
    }

    private FlowLayout advancedInfoSection(ProjectVersion version) {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.save_details.advanced_info_title"),
                Component.translatable("luma.save_details.advanced_info_help")
        );
        if (this.state.materialDelta().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.materials.empty")));
        } else {
            int limit = Math.min(MATERIAL_LIMIT, this.state.materialDelta().size());
            for (int index = 0; index < limit; index++) {
                var entry = this.state.materialDelta().get(index);
                section.child(MaterialEntryView.row(
                        entry.blockId(),
                        Component.translatable(
                                "luma.compare.material_entry",
                                entry.blockId(),
                                entry.delta()
                        )
                ));
            }
        }
        section.child(LumaUi.caption(Component.translatable("luma.save_details.raw_info_id", version.id())));
        section.child(LumaUi.caption(Component.translatable("luma.save_details.raw_info_author", ProjectUiSupport.safeText(version.author()))));
        section.child(LumaUi.caption(Component.translatable(
                "luma.save_details.raw_info_type",
                Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))
        )));
        return section;
    }

    private FlowLayout restoreConfirmationSection(ProjectVersion version, ProjectVariant versionVariant, boolean operationActive) {
        boolean rootRestore = version.versionKind() == VersionKind.INITIAL || version.versionKind() == VersionKind.WORLD_ROOT;
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.restore.confirm_title", ProjectUiSupport.displayMessage(version)),
                Component.translatable("luma.restore.confirm_help")
        );
        if (this.state.project().settings().safetySnapshotBeforeRestore()) {
            section.child(LumaUi.caption(Component.translatable("luma.restore.confirm_safety")));
        }
        if (rootRestore) {
            section.child(LumaUi.danger(Component.translatable("luma.restore.initial_confirm_warning")));
        }
        section.child(LumaUi.caption(Component.translatable(
                "luma.restore.confirm_target",
                ProjectUiSupport.displayVariantName(versionVariant),
                ProjectUiSupport.displayMessage(version)
        )));

        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> {
            this.pendingRestoreConfirmation = false;
            this.rebuild();
        }));
        ButtonComponent confirmButton = LumaUi.primaryButton(Component.translatable("luma.action.restore"), button -> {
            this.pendingRestoreConfirmation = false;
            this.executeRestore(version, versionVariant);
        });
        confirmButton.active(!operationActive);
        actions.child(confirmButton);
        section.child(actions);
        return section;
    }

    private void restoreVersion(ProjectVersion version, ProjectVariant versionVariant) {
        if (version == null || versionVariant == null) {
            return;
        }

        this.pendingRestoreConfirmation = true;
        this.refresh("luma.status.restore_confirmation_required");
    }

    private void executeRestore(ProjectVersion version, ProjectVariant versionVariant) {
        if (version == null || versionVariant == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }

        String result = this.controller.restoreVersion(this.projectName, version.id());
        this.router.openProjectIgnoringRecovery(this.parent, this.projectName, result);
    }

    private boolean canReplaceLatest(ProjectVersion version) {
        return version != null
                && this.state.project() != null
                && version.variantId().equals(this.state.project().activeVariantId())
                && ProjectUiSupport.isVariantHead(this.state.variants(), version)
                && this.state.recoveryDraft() != null
                && !this.state.recoveryDraft().isEmpty();
    }

    private boolean canDeleteVersion(ProjectVersion version) {
        if (version == null
                || version.versionKind() == VersionKind.INITIAL
                || version.versionKind() == VersionKind.WORLD_ROOT
                || version.parentVersionId() == null
                || version.parentVersionId().isBlank()) {
            return false;
        }
        return this.state.versions().stream()
                .noneMatch(candidate -> version.id().equals(candidate.parentVersionId()));
    }

    private boolean canRenameVersion(ProjectVersion version, boolean operationActive) {
        return version != null
                && !operationActive
                && !this.renameMessage.trim().isBlank()
                && !this.renameMessage.trim().equals(ProjectUiSupport.displayMessage(version));
    }

    private String parentVersionId(String versionId) {
        for (ProjectVersion version : this.state.versions()) {
            if (version.id().equals(versionId)) {
                return version.parentVersionId() == null ? "" : version.parentVersionId();
            }
        }
        return "";
    }

    private Bounds3i fallbackPartialRestoreBounds() {
        BlockPos pos = this.client.player == null ? BlockPos.ZERO : this.client.player.blockPosition();
        int minY = this.client.level == null ? -64 : this.client.level.getMinY();
        int maxY = this.client.level == null ? 320 : this.client.level.getMaxY();
        return PartialRestoreFormState.fallbackAround(pos, minY, maxY);
    }

    private java.util.Optional<Bounds3i> selectedLumiBounds() {
        if (this.state.project() == null) {
            return java.util.Optional.empty();
        }
        return LumiRegionSelectionController.getInstance().selectedBounds(
                this.projectName,
                this.state.project().dimensionId()
        );
    }

    private boolean shouldShowStatusBanner() {
        return ScreenOperationStateSupport.shouldShowStatusBanner(
                this.state.status(),
                this.state.operationSnapshot(),
                "luma.status.project_ready"
        );
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

    private final class PartialRestoreActions implements SaveDetailsPartialRestoreSection.Actions {

        @Override
        public void preview(PartialRestoreRequest request) {
            partialRestoreForm.setSummary(controller.partialRestorePlanSummary(request));
            refresh(partialRestoreForm.summary() == null
                    ? "luma.status.operation_failed"
                    : "luma.status.partial_restore_plan_ready");
        }

        @Override
        public void apply(PartialRestoreRequest request) {
            String result = controller.partialRestore(request);
            router.openProjectIgnoringRecovery(parent, projectName, result);
        }

        @Override
        public void selectionApplied() {
            refresh("luma.status.partial_restore_selection_applied");
        }

        @Override
        public void invalidBounds() {
            refresh("luma.status.partial_restore_invalid_bounds");
        }
    }
}
