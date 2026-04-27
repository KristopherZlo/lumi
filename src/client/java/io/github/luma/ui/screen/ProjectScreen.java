package io.github.luma.ui.screen;

import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RestorePlanSummary;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.github.luma.ui.framework.component.ButtonComponent;
import io.github.luma.ui.framework.container.FlowLayout;
import io.github.luma.ui.framework.container.UIContainers;
import io.github.luma.ui.framework.core.Insets;
import io.github.luma.ui.framework.core.LumaUIAdapter;
import io.github.luma.ui.framework.core.Sizing;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ProjectScreen extends LumaScreen {

    private static final int RECENT_SAVE_LIMIT = 6;

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController stateController = new ProjectHomeScreenController();
    private final ProjectScreenController actionController = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private ProjectHomeViewState state = new ProjectHomeViewState(
            null,
            List.of(),
            List.of(),
            io.github.luma.domain.model.PendingChangeSummary.empty(),
            false,
            null,
            null,
            "luma.status.project_ready"
    );
    private String statusKey;
    private String selectedVariantId = "";
    private boolean showAllSaves = false;
    private String pendingRestoreVariantId = "";
    private String pendingRestoreVersionId = "";
    private int refreshCooldown = 0;

    public ProjectScreen(Screen parent, String projectName) {
        this(parent, projectName, "", "luma.status.project_ready");
    }

    public ProjectScreen(Screen parent, String projectName, String statusKey) {
        this(parent, projectName, "", statusKey);
    }

    public ProjectScreen(Screen parent, String projectName, String selectedVariantId, String statusKey) {
        super(Component.translatable("luma.screen.project.title", projectName));
        this.parent = parent;
        this.projectName = projectName;
        this.selectedVariantId = selectedVariantId == null ? "" : selectedVariantId;
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
    }

    @Override
    protected LumaUIAdapter<FlowLayout> createAdapter() {
        return LumaUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.stateController.loadState(this.projectName, this.statusKey, false);
        this.ensureSelectedVariant();

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(8));
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

        Component place = Component.translatable(
                "luma.simple.current_place",
                ProjectUiSupport.dimensionLabel(this.state.project().dimensionId())
        );
        Component idea = Component.translatable(
                "luma.simple.current_idea",
                ProjectUiSupport.displayVariantName(this.state.variants(), this.state.project().activeVariantId())
        );
        ProjectWindowLayout window = new ProjectWindowLayout(
                this.width,
                Component.translatable("luma.simple.workspace_title", this.projectName),
                place,
                idea
        );
        root.child(window.root());
        window.sidebar().child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        window.sidebar().child(LumaUi.button(Component.translatable("luma.action.more"), button -> this.router.openMore(this, this.projectName)));
        window.content().child(LumaUi.statusBanner(this.bannerText()));

        FlowLayout confirmation = this.initialRestoreConfirmationSection();
        if (confirmation != null) {
            window.content().child(confirmation);
        }

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        window.content().child(this.bodyScroll);

        body.child(this.buildSection());
        body.child(this.historySection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout buildSection() {
        PendingChangeSummary pending = this.state.pendingChanges();
        ProjectVersion activeHead = ProjectUiSupport.activeHead(this.state.project(), this.state.variants(), this.state.versions());
        ProjectVariant activeVariant = ProjectUiSupport.variantFor(this.state.variants(), this.state.project().activeVariantId());
        boolean operationActive = this.operationActive();

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.build.status_title"),
                Component.translatable(pending.isEmpty() ? "luma.build.status_clean" : "luma.build.status_dirty")
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.translatable(
                "luma.build.current_idea",
                ProjectUiSupport.displayVariantName(activeVariant)
        )));
        meta.child(LumaUi.chip(Component.translatable(
                "luma.build.current_place",
                ProjectUiSupport.dimensionLabel(this.state.project().dimensionId())
        )));
        section.child(meta);

        if (!pending.isEmpty()) {
            FlowLayout stats = LumaUi.actionRow();
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_placed"), Component.literal("+" + pending.addedBlocks())));
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_removed"), Component.literal("-" + pending.removedBlocks())));
            stats.child(LumaUi.statChip(Component.translatable("luma.build.blocks_changed"), Component.literal(Integer.toString(pending.changedBlocks()))));
            section.child(stats);
        }

        ButtonComponent saveButton = LumaUi.primaryButton(Component.translatable("luma.action.save_build"), button -> this.router.openSave(
                this,
                this.projectName
        ));
        saveButton.active(!pending.isEmpty() && !operationActive);
        FlowLayout primary = LumaUi.actionRow();
        primary.child(saveButton);
        if (pending.isEmpty()) {
            primary.child(LumaUi.caption(Component.translatable("luma.build.save_disabled_help")));
        }
        section.child(primary);

        FlowLayout secondary = LumaUi.actionRow();
        ButtonComponent changesButton = LumaUi.button(Component.translatable("luma.action.see_changes"), button -> this.router.openCompare(
                this,
                this.projectName,
                activeHead == null ? "" : activeHead.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                activeHead == null ? "" : activeHead.id()
        ));
        changesButton.active(activeHead != null);
        secondary.child(changesButton);
        secondary.child(LumaUi.button(Component.translatable("luma.action.ideas"), button -> this.router.openVariants(
                this,
                this.projectName
        )));
        secondary.child(LumaUi.button(Component.translatable("luma.action.more"), button -> this.router.openMore(
                this,
                this.projectName
        )));
        section.child(secondary);

        if (this.state.hasRecoveryDraft()) {
            FlowLayout recovery = LumaUi.insetSection(
                    Component.translatable("luma.recovery.found_title"),
                    Component.translatable("luma.recovery.found_help")
            );
            FlowLayout actions = LumaUi.actionRow();
            actions.child(LumaUi.primaryButton(Component.translatable("luma.action.review_recovered_work"), button -> this.router.openRecovery(
                    this,
                    this.projectName
            )));
            recovery.child(actions);
            section.child(recovery);
        }
        if (this.state.operationSnapshot() != null) {
            section.child(this.operationSection());
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

    private FlowLayout historySection() {
        ProjectVariant selectedVariant = this.selectedVariant();
        List<ProjectVersion> versions = selectedVariant == null ? List.of() : this.variantVersions(selectedVariant.id());

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.build.recent_saves_title"),
                Component.translatable(
                        "luma.build.recent_saves_help",
                        selectedVariant == null ? Component.translatable("luma.variant.empty") : Component.literal(ProjectUiSupport.displayVariantName(selectedVariant))
                )
        );
        section.child(LumaUi.caption(Component.translatable("luma.build.idea_picker_help")));
        section.child(this.variantPicker());

        if (versions.isEmpty()) {
            FlowLayout empty = LumaUi.emptyState(
                    Component.translatable("luma.build.no_saves_title"),
                    Component.translatable("luma.build.no_saves_help")
            );
            FlowLayout actions = LumaUi.actionRow();
            ButtonComponent saveButton = LumaUi.primaryButton(Component.translatable("luma.action.save_build"), button -> this.router.openSave(
                    this,
                    this.projectName
            ));
            saveButton.active(!this.state.pendingChanges().isEmpty() && !this.operationActive());
            actions.child(saveButton);
            empty.child(actions);
            section.child(empty);
            return section;
        }

        int limit = this.showAllSaves ? versions.size() : Math.min(RECENT_SAVE_LIMIT, versions.size());
        for (int index = 0; index < limit; index++) {
            section.child(this.saveCard(versions.get(index)));
        }

        if (versions.size() > RECENT_SAVE_LIMIT) {
            FlowLayout actions = LumaUi.actionRow();
            actions.child(LumaUi.button(Component.translatable(
                    this.showAllSaves ? "luma.action.show_recent_saves" : "luma.action.show_older_saves"
            ), button -> {
                this.showAllSaves = !this.showAllSaves;
                this.refresh("luma.status.project_ready");
            }));
            section.child(actions);
        }
        return section;
    }

    private FlowLayout variantPicker() {
        FlowLayout picker = LumaUi.actionRow();
        for (ProjectVariant variant : this.sortedVariants()) {
            ButtonComponent button = LumaUi.button(Component.literal(ProjectUiSupport.displayVariantName(variant)), pressed -> {
                this.selectedVariantId = variant.id();
                this.showAllSaves = false;
                this.refresh("luma.status.project_ready");
            });
            button.active(!variant.id().equals(this.selectedVariantId));
            picker.child(button);
        }
        return picker;
    }

    private FlowLayout saveCard(ProjectVersion version) {
        ProjectVariant versionVariant = ProjectUiSupport.variantFor(this.state.variants(), version.variantId());
        boolean current = ProjectUiSupport.isVariantHead(this.state.variants(), version);
        boolean operationActive = this.state.operationSnapshot() != null && !this.state.operationSnapshot().terminal();

        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        FlowLayout hero = this.width < 860
                ? UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
                : UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        hero.gap(6);
        hero.child(ProjectUiSupport.versionPreview(this.actionController, this.projectName, version, 96, 72, 96));

        FlowLayout text = UIContainers.verticalFlow(this.width < 860 ? Sizing.fill(100) : Sizing.expand(100), Sizing.content());
        text.gap(3);
        text.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));
        text.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        text.child(LumaUi.caption(Component.translatable(
                "luma.build.save_card_summary",
                version.stats().changedBlocks()
        )));

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))));
        if (current) {
            meta.child(LumaUi.chip(Component.translatable("luma.history.current_badge")));
        }
        text.child(meta);
        hero.child(text);
        card.child(hero);

        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.open_save"), button -> this.router.openSaveDetails(
                this,
                this.projectName,
                version.id()
        )));
        ButtonComponent restoreButton = LumaUi.button(Component.translatable("luma.action.restore_this_save"), button -> {
            if (versionVariant != null) {
                this.restoreVersion(versionVariant, version);
            }
        });
        restoreButton.active(versionVariant != null && !operationActive);
        actions.child(restoreButton);
        card.child(actions);
        return card;
    }

    private FlowLayout initialRestoreConfirmationSection() {
        if (this.pendingRestoreVersionId.isBlank()) {
            return null;
        }

        ProjectVersion version = ProjectUiSupport.versionFor(this.state.versions(), this.pendingRestoreVersionId);
        ProjectVariant variant = ProjectUiSupport.variantFor(this.state.variants(), this.pendingRestoreVariantId);
        if (version == null || variant == null) {
            this.clearPendingRestore();
            return null;
        }

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
                ProjectUiSupport.displayVariantName(variant),
                ProjectUiSupport.displayMessage(version)
        )));

        RestorePlanSummary summary = rootRestore ? this.actionController.restorePlanSummary(this.projectName, version.id()) : null;
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
        actions.child(LumaUi.button(Component.translatable("luma.action.cancel"), button -> {
            this.clearPendingRestore();
            this.refresh("luma.status.project_ready");
        }));
        ButtonComponent confirmButton = LumaUi.primaryButton(Component.translatable("luma.action.restore"), button -> {
            ProjectVersion confirmedVersion = ProjectUiSupport.versionFor(this.state.versions(), this.pendingRestoreVersionId);
            ProjectVariant confirmedVariant = ProjectUiSupport.variantFor(this.state.variants(), this.pendingRestoreVariantId);
            this.clearPendingRestore();
            if (confirmedVersion != null && confirmedVariant != null) {
                this.executeRestore(confirmedVariant, confirmedVersion);
            } else {
                this.refresh("luma.status.operation_failed");
            }
        });
        confirmButton.active(this.state.operationSnapshot() == null || this.state.operationSnapshot().terminal());
        actions.child(confirmButton);
        section.child(actions);
        return section;
    }

    private void restoreVersion(ProjectVariant variant, ProjectVersion version) {
        if (variant == null || version == null) {
            return;
        }
        this.pendingRestoreVariantId = variant.id();
        this.pendingRestoreVersionId = version.id();
        this.refresh("luma.status.restore_confirmation_required");
    }

    private void executeRestore(ProjectVariant variant, ProjectVersion version) {
        if (variant == null || version == null) {
            this.refresh("luma.status.operation_failed");
            return;
        }

        if (!variant.id().equals(this.state.project().activeVariantId())) {
            String switched = this.actionController.switchVariant(this.projectName, variant.id(), false);
            if (!"luma.status.variant_switched".equals(switched)) {
                this.refresh(switched);
                return;
            }
        }

        this.refresh(this.actionController.restoreVersion(this.projectName, version.id()));
    }

    private void clearPendingRestore() {
        this.pendingRestoreVariantId = "";
        this.pendingRestoreVersionId = "";
    }

    private void ensureSelectedVariant() {
        if (this.state.project() == null) {
            return;
        }

        if (!this.selectedVariantId.isBlank()
                && ProjectUiSupport.variantFor(this.state.variants(), this.selectedVariantId) != null) {
            return;
        }
        this.selectedVariantId = this.state.project().activeVariantId();
    }

    private ProjectVariant selectedVariant() {
        ProjectVariant selected = ProjectUiSupport.variantFor(this.state.variants(), this.selectedVariantId);
        if (selected != null) {
            return selected;
        }
        if (this.state.project() == null) {
            return null;
        }
        return ProjectUiSupport.variantFor(this.state.variants(), this.state.project().activeVariantId());
    }

    private List<ProjectVariant> sortedVariants() {
        return this.state.variants().stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(this.state.project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
    }

    private List<ProjectVersion> variantVersions(String variantId) {
        return this.state.versions().stream()
                .filter(version -> variantId.equals(version.variantId()))
                .sorted(Comparator.comparing(ProjectVersion::createdAt).reversed())
                .toList();
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
        double scrollProgress = this.currentScrollProgress();
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        this.restoreScroll(scrollProgress);
    }

    @Override
    protected void onLumaTick() {
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;
        ProjectHomeViewState refreshed = this.stateController.loadState(this.projectName, this.statusKey, false);
        String normalizedStatusKey = ScreenOperationStateSupport.normalizeStatusKey(
                this.statusKey,
                refreshed.operationSnapshot(),
                "luma.status.project_ready"
        );
        if (!normalizedStatusKey.equals(this.statusKey)) {
            this.statusKey = normalizedStatusKey;
            refreshed = this.stateController.loadState(this.projectName, this.statusKey, false);
        }
        if (!refreshed.equals(this.state)) {
            this.state = refreshed;
            this.refresh(this.statusKey);
        }
    }

    private boolean operationActive() {
        return ScreenOperationStateSupport.blocksMutationActions(this.state.operationSnapshot());
    }

    private Component bannerText() {
        return ScreenOperationStateSupport.bannerText(this.state.status(), this.state.operationSnapshot(), "luma.status.project_ready");
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
