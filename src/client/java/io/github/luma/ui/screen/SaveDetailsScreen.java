package io.github.luma.ui.screen;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.PartialRestorePlanSummary;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.MaterialEntryView;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.preview.ProjectPreviewTextureCache;
import io.github.luma.ui.state.SaveDetailsViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
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
    private boolean showPartialRestore = false;
    private boolean showAdvancedInfo = false;
    private String partialMinX = "";
    private String partialMinY = "";
    private String partialMinZ = "";
    private String partialMaxX = "";
    private String partialMaxY = "";
    private String partialMaxZ = "";
    private PartialRestorePlanSummary partialRestoreSummary;

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

        section.child(actions);
        return section;
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

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent replaceButton = LumaUi.button(Component.translatable("luma.action.amend_version"), button -> this.router.openSave(
                this,
                this.projectName,
                ProjectUiSupport.displayMessage(version),
                true
        ));
        replaceButton.active(this.canReplaceLatest(version) && !operationActive);
        actions.child(replaceButton);

        actions.child(LumaUi.button(Component.translatable("luma.action.refresh_preview"), button -> {
            ProjectPreviewTextureCache.release(this.projectName, version.id());
            this.refresh(this.controller.refreshPreview(this.projectName, version.id()));
        }));

        actions.child(LumaUi.button(Component.translatable("luma.save_details.create_idea"), button -> this.router.openVariants(
                this,
                this.projectName,
                version.id()
        )));
        section.child(actions);

        FlowLayout restoreSelected = LumaUi.actionRow();
        restoreSelected.child(LumaUi.button(Component.translatable("luma.action.restore_selected_area"), button -> {
            this.showPartialRestore = !this.showPartialRestore;
            this.rebuild();
        }));
        section.child(restoreSelected);
        if (this.showPartialRestore) {
            section.child(this.partialRestoreSection(version, operationActive));
        }

        FlowLayout advanced = LumaUi.actionRow();
        advanced.child(LumaUi.button(Component.translatable(
                this.showAdvancedInfo ? "luma.action.hide_advanced_info" : "luma.action.advanced_info"
        ), button -> {
            this.showAdvancedInfo = !this.showAdvancedInfo;
            this.rebuild();
        }));
        section.child(advanced);
        if (this.showAdvancedInfo) {
            section.child(this.advancedInfoSection(version));
        }
        return section;
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

    private FlowLayout partialRestoreSection(ProjectVersion version, boolean operationActive) {
        this.ensurePartialBoundsDefaults();
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.partial_restore.title"),
                Component.translatable("luma.partial_restore.help")
        );
        section.child(this.partialBoundsRow(
                "luma.partial_restore.min",
                this.partialMinX,
                this.partialMinY,
                this.partialMinZ,
                value -> this.partialMinX = value,
                value -> this.partialMinY = value,
                value -> this.partialMinZ = value
        ));
        section.child(this.partialBoundsRow(
                "luma.partial_restore.max",
                this.partialMaxX,
                this.partialMaxY,
                this.partialMaxZ,
                value -> this.partialMaxX = value,
                value -> this.partialMaxY = value,
                value -> this.partialMaxZ = value
        ));

        if (this.partialRestoreSummary != null) {
            section.child(LumaUi.caption(Component.translatable(
                    "luma.partial_restore.summary",
                    this.partialRestoreSummary.changedBlocks(),
                    this.partialRestoreSummary.touchedChunks().size()
            )));
        }

        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.preview_partial_restore"), button -> {
            Optional<PartialRestoreRequest> request = this.partialRestoreRequest(version);
            if (request.isEmpty()) {
                this.refresh("luma.status.partial_restore_invalid_bounds");
                return;
            }
            this.partialRestoreSummary = this.controller.partialRestorePlanSummary(request.get());
            this.refresh(this.partialRestoreSummary == null
                    ? "luma.status.operation_failed"
                    : "luma.status.partial_restore_plan_ready");
        }));
        ButtonComponent applyButton = LumaUi.primaryButton(Component.translatable("luma.action.apply_partial_restore"), button -> {
            Optional<PartialRestoreRequest> request = this.partialRestoreRequest(version);
            if (request.isEmpty()) {
                this.refresh("luma.status.partial_restore_invalid_bounds");
                return;
            }
            String result = this.controller.partialRestore(request.get());
            this.router.openProjectIgnoringRecovery(this.parent, this.projectName, result);
        });
        applyButton.active(!operationActive && this.partialRestoreSummary != null && this.partialRestoreSummary.changedBlocks() > 0);
        actions.child(applyButton);
        section.child(actions);
        return section;
    }

    private FlowLayout partialBoundsRow(
            String labelKey,
            String x,
            String y,
            String z,
            java.util.function.Consumer<String> onX,
            java.util.function.Consumer<String> onY,
            java.util.function.Consumer<String> onZ
    ) {
        FlowLayout row = LumaUi.actionRow();
        row.child(LumaUi.caption(Component.translatable(labelKey)));

        var xBox = UIComponents.textBox(Sizing.fixed(60), x);
        xBox.onChanged().subscribe(value -> {
            onX.accept(value);
            this.partialRestoreSummary = null;
        });
        row.child(xBox);

        var yBox = UIComponents.textBox(Sizing.fixed(60), y);
        yBox.onChanged().subscribe(value -> {
            onY.accept(value);
            this.partialRestoreSummary = null;
        });
        row.child(yBox);

        var zBox = UIComponents.textBox(Sizing.fixed(60), z);
        zBox.onChanged().subscribe(value -> {
            onZ.accept(value);
            this.partialRestoreSummary = null;
        });
        row.child(zBox);
        return row;
    }

    private Optional<PartialRestoreRequest> partialRestoreRequest(ProjectVersion version) {
        try {
            Bounds3i bounds = new Bounds3i(
                    new BlockPoint(
                            Integer.parseInt(this.partialMinX),
                            Integer.parseInt(this.partialMinY),
                            Integer.parseInt(this.partialMinZ)
                    ),
                    new BlockPoint(
                            Integer.parseInt(this.partialMaxX),
                            Integer.parseInt(this.partialMaxY),
                            Integer.parseInt(this.partialMaxZ)
                    )
            );
            Bounds3i normalized = new Bounds3i(
                    new BlockPoint(
                            Math.min(bounds.min().x(), bounds.max().x()),
                            Math.min(bounds.min().y(), bounds.max().y()),
                            Math.min(bounds.min().z(), bounds.max().z())
                    ),
                    new BlockPoint(
                            Math.max(bounds.min().x(), bounds.max().x()),
                            Math.max(bounds.min().y(), bounds.max().y()),
                            Math.max(bounds.min().z(), bounds.max().z())
                    )
            );
            return Optional.of(new PartialRestoreRequest(
                    this.projectName,
                    version.id(),
                    normalized,
                    PartialRestoreRegionSource.MANUAL_BOUNDS,
                    this.client.getUser().getName(),
                    java.util.Map.of()
            ));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private void ensurePartialBoundsDefaults() {
        if (!this.partialMinX.isBlank()
                || !this.partialMinY.isBlank()
                || !this.partialMinZ.isBlank()
                || !this.partialMaxX.isBlank()
                || !this.partialMaxY.isBlank()
                || !this.partialMaxZ.isBlank()) {
            return;
        }
        Bounds3i bounds = this.state.project() == null ? null : this.state.project().bounds();
        if (bounds != null) {
            this.partialMinX = Integer.toString(bounds.min().x());
            this.partialMinY = Integer.toString(bounds.min().y());
            this.partialMinZ = Integer.toString(bounds.min().z());
            this.partialMaxX = Integer.toString(bounds.max().x());
            this.partialMaxY = Integer.toString(bounds.max().y());
            this.partialMaxZ = Integer.toString(bounds.max().z());
            return;
        }

        BlockPos pos = this.client.player == null ? BlockPos.ZERO : this.client.player.blockPosition();
        this.partialMinX = Integer.toString(pos.getX() - 8);
        this.partialMinY = Integer.toString(Math.max(this.client.level == null ? -64 : this.client.level.getMinY(), pos.getY() - 8));
        this.partialMinZ = Integer.toString(pos.getZ() - 8);
        this.partialMaxX = Integer.toString(pos.getX() + 8);
        this.partialMaxY = Integer.toString(Math.min(this.client.level == null ? 320 : this.client.level.getMaxY(), pos.getY() + 8));
        this.partialMaxZ = Integer.toString(pos.getZ() + 8);
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
