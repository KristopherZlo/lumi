package io.github.luma.ui.screen;

import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RestorePlanSummary;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.MaterialEntryView;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.preview.ProjectPreviewTextureCache;
import io.github.luma.ui.state.ProjectTab;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SaveDetailsScreen extends LumaScreen {

    private static final int MATERIAL_LIMIT = 6;

    private final Screen parent;
    private final String projectName;
    private final String versionId;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectScreenController controller = new ProjectScreenController();
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
            ProjectTab.PREVIEW,
            "luma.status.project_ready"
    );
    private String status = "luma.status.project_ready";
    private boolean showMoreOptions = false;
    private boolean pendingRestoreConfirmation = false;

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
        this.state = this.controller.loadState(this.projectName, ProjectTab.PREVIEW, this.versionId, this.status);
        ProjectVersion version = this.state.selectedVersion();
        ProjectVariant versionVariant = version == null ? null : ProjectUiSupport.variantFor(this.state.variants(), version.variantId());
        boolean operationActive = this.state.operationSnapshot() != null && !this.state.operationSnapshot().terminal();

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
        frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));

        if (this.pendingRestoreConfirmation) {
            frame.child(this.restoreConfirmationSection(version, versionVariant, operationActive));
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        body.child(this.summarySection(version, versionVariant));
        body.child(this.changesSection(version));
        body.child(this.primaryActions(version, versionVariant, operationActive));
        body.child(this.moreSection(version, operationActive));
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
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
        hero.child(ProjectUiSupport.versionPreview(this.controller, this.projectName, version, 212, 112, 168));

        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(6);
        text.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))));
        meta.child(LumaUi.chip(Component.literal(ProjectUiSupport.displayVariantName(versionVariant))));
        if (ProjectUiSupport.isVariantHead(this.state.variants(), version)) {
            meta.child(LumaUi.chip(Component.translatable("luma.history.current_badge")));
        }
        text.child(meta);
        text.child(LumaUi.caption(Component.translatable("luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        hero.child(text);
        section.child(hero);
        return section;
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

        if (this.state.materialDelta().isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.materials.empty")));
            return section;
        }

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
        return section;
    }

    private FlowLayout primaryActions(ProjectVersion version, ProjectVariant versionVariant, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.actions_title"),
                null
        );

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent restoreButton = UIComponents.button(Component.translatable("luma.action.restore"), button -> this.restoreVersion(version, versionVariant));
        restoreButton.active(!operationActive);
        actions.child(restoreButton);

        actions.child(UIComponents.button(Component.translatable("luma.action.compare_with_current"), button -> this.router.openCompare(
                this,
                this.projectName,
                version.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                version.id()
        )));

        ButtonComponent comparePrevious = UIComponents.button(Component.translatable("luma.action.compare_with_parent"), button -> this.router.openCompare(
                this,
                this.projectName,
                this.parentVersionId(version.id()),
                version.id(),
                version.id()
        ));
        comparePrevious.active(!this.parentVersionId(version.id()).isBlank());
        actions.child(comparePrevious);

        section.child(actions);
        return section;
    }

    private FlowLayout moreSection(ProjectVersion version, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.save_details.more_title"),
                Component.translatable("luma.save_details.more_help")
        );

        FlowLayout toggle = LumaUi.actionRow();
        toggle.child(UIComponents.button(Component.translatable(
                this.showMoreOptions ? "luma.action.hide_tools" : "luma.action.more_tools"
        ), button -> {
            this.showMoreOptions = !this.showMoreOptions;
            this.rebuild();
        }));
        section.child(toggle);

        if (!this.showMoreOptions) {
            return section;
        }

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent replaceButton = UIComponents.button(Component.translatable("luma.action.amend_version"), button -> this.router.openSave(
                this,
                this.projectName,
                ProjectUiSupport.displayMessage(version),
                true
        ));
        replaceButton.active(this.canReplaceLatest(version) && !operationActive);
        actions.child(replaceButton);

        actions.child(UIComponents.button(Component.translatable("luma.action.refresh_preview"), button -> {
            ProjectPreviewTextureCache.release(this.projectName, version.id());
            this.refresh(this.controller.refreshPreview(this.projectName, version.id()));
        }));

        actions.child(UIComponents.button(Component.translatable("luma.save_details.create_variant"), button -> this.router.openVariants(
                this,
                this.projectName,
                version.id()
        )));
        section.child(actions);

        FlowLayout secondary = LumaUi.actionRow();
        secondary.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(
                this,
                this.projectName
        )));
        section.child(secondary);

        section.child(LumaUi.caption(Component.translatable("luma.save_details.raw_info_title")));
        section.child(LumaUi.caption(Component.translatable("luma.save_details.raw_info_id", version.id())));
        section.child(LumaUi.caption(Component.translatable("luma.save_details.raw_info_author", ProjectUiSupport.safeText(version.author()))));
        section.child(LumaUi.caption(Component.translatable(
                "luma.save_details.raw_info_type",
                Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))
        )));
        return section;
    }

    private FlowLayout restoreConfirmationSection(ProjectVersion version, ProjectVariant versionVariant, boolean operationActive) {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.restore.initial_confirm_title", version.id()),
                Component.translatable("luma.restore.initial_confirm_help")
        );
        section.child(LumaUi.danger(Component.translatable("luma.restore.initial_confirm_warning")));
        section.child(LumaUi.caption(Component.translatable(
                "luma.restore.initial_confirm_target",
                ProjectUiSupport.displayVariantName(versionVariant),
                version.id()
        )));

        RestorePlanSummary summary = this.controller.restorePlanSummary(this.projectName, version.id());
        if (summary != null) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.restore.plan_mode",
                    Component.translatable(this.restorePlanModeKey(summary.mode()))
            )));
            section.child(LumaUi.caption(Component.translatable(
                    "luma.restore.plan_base",
                    ProjectUiSupport.safeText(summary.branchId()),
                    ProjectUiSupport.safeText(summary.baseVersionId()),
                    ProjectUiSupport.safeText(summary.targetVersionId())
            )));
            section.child(LumaUi.caption(Component.translatable(
                    "luma.restore.plan_chunks",
                    summary.touchedChunks().size()
            )));
        }

        FlowLayout actions = LumaUi.actionRow();
        actions.child(UIComponents.button(Component.translatable("luma.action.cancel"), button -> {
            this.pendingRestoreConfirmation = false;
            this.rebuild();
        }));
        ButtonComponent confirmButton = UIComponents.button(Component.translatable("luma.action.confirm_initial_restore"), button -> {
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

        if (version.versionKind() == VersionKind.INITIAL || version.versionKind() == VersionKind.WORLD_ROOT) {
            this.pendingRestoreConfirmation = true;
            this.refresh("luma.status.initial_restore_confirmation_required");
            return;
        }

        this.executeRestore(version, versionVariant);
    }

    private void executeRestore(ProjectVersion version, ProjectVariant versionVariant) {
        if (version == null || versionVariant == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }

        if (this.state.project() != null && !versionVariant.id().equals(this.state.project().activeVariantId())) {
            String switched = this.controller.switchVariant(this.projectName, versionVariant.id(), false);
            if (!"luma.status.variant_switched".equals(switched)) {
                this.refresh(switched);
                return;
            }
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

    private String parentVersionId(String versionId) {
        for (ProjectVersion version : this.state.versions()) {
            if (version.id().equals(versionId)) {
                return version.parentVersionId() == null ? "" : version.parentVersionId();
            }
        }
        return "";
    }

    private String restorePlanModeKey(io.github.luma.domain.model.RestorePlanMode mode) {
        return switch (mode) {
            case PATCH_REPLAY -> "luma.restore.plan_mode.patch_replay";
            case BASELINE_CHUNKS -> "luma.restore.plan_mode.baseline_chunks";
            case REGEN_TOUCHED_CHUNKS -> "luma.restore.plan_mode.regen_touched_chunks";
            case BLOCKED_FINGERPRINT -> "luma.restore.plan_mode.blocked_fingerprint";
            case NO_OP -> "luma.restore.plan_mode.no_op";
        };
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
