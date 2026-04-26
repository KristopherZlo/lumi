package io.github.luma.ui.screen;

import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.RestorePlanSummary;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.SimpleActionCard;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.controller.ScreenOperationStateSupport;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.graph.CommitGraphNode;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectAdvancedViewState;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.github.luma.ui.toolkit.UiToolkitRegistry;
import io.github.luma.ui.toolkit.UiToolkitStatus;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ProjectScreen extends LumaScreen {

    private static final int RECENT_SAVE_LIMIT = 6;
    private static final UiToolkitRegistry UI_TOOLKITS = UiToolkitRegistry.defaultRegistry();

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
    private boolean advancedToolsExpanded = false;
    private String pendingRestoreVariantId = "";
    private String pendingRestoreVersionId = "";
    private int refreshCooldown = 0;
    private boolean scrollToSavedMoments = false;

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
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.stateController.loadState(this.projectName, this.statusKey, this.advancedToolsExpanded);
        this.ensureSelectedVariant();

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
        window.sidebar().child(LumaUi.button(Component.translatable("luma.action.workspaces"), button -> this.router.openDashboard(this)));
        if (this.state.hasRecoveryDraft()) {
            window.sidebar().child(LumaUi.primaryButton(Component.translatable("luma.action.recovery"), button -> this.router.openRecovery(this, this.projectName)));
        }
        window.sidebar().child(LumaUi.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, this.projectName)));
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
        body.child(this.advancedSection());
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
                Component.translatable("luma.simple.next_title"),
                Component.translatable(
                        pending.isEmpty() ? "luma.simple.next_clean_help" : "luma.simple.next_dirty_help",
                        this.pendingTotal(pending)
                )
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.translatable(
                "luma.simple.current_idea",
                ProjectUiSupport.displayVariantName(activeVariant)
        )));
        meta.child(LumaUi.chip(Component.translatable(
                "luma.simple.tracking_area"
        )));
        section.child(meta);

        if (!pending.isEmpty()) {
            FlowLayout stats = LumaUi.actionRow();
            stats.child(LumaUi.statChip(Component.translatable("luma.simple.blocks_added"), Component.literal(Integer.toString(pending.addedBlocks()))));
            stats.child(LumaUi.statChip(Component.translatable("luma.simple.blocks_removed"), Component.literal(Integer.toString(pending.removedBlocks()))));
            stats.child(LumaUi.statChip(Component.translatable("luma.simple.blocks_changed"), Component.literal(Integer.toString(pending.changedBlocks()))));
            section.child(stats);
        }

        ButtonComponent saveButton = LumaUi.primaryButton(Component.translatable("luma.simple.save_button"), button -> this.router.openSave(
                this,
                this.projectName
        ));
        saveButton.active(!pending.isEmpty() && !operationActive);
        section.child(this.homeAction(
                "1",
                Component.translatable("luma.simple.save_title"),
                pending.isEmpty()
                        ? Component.translatable("luma.simple.save_clean_help")
                        : Component.translatable("luma.simple.save_help"),
                saveButton
        ));

        ButtonComponent restoreButton = LumaUi.primaryButton(Component.translatable("luma.simple.restore_button"), button -> {
            if (activeHead != null && activeVariant != null) {
                this.restoreVersion(activeVariant, activeHead);
            }
        });
        restoreButton.active(activeHead != null && !operationActive);
        section.child(this.homeAction(
                "2",
                Component.translatable("luma.simple.restore_title"),
                activeHead == null
                        ? Component.translatable("luma.simple.restore_empty_help")
                        : Component.translatable("luma.simple.restore_help", ProjectUiSupport.displayMessage(activeHead)),
                restoreButton
        ));

        ButtonComponent savedMomentsButton = LumaUi.button(Component.translatable("luma.simple.saved_button"), button -> this.openSavedMoments());
        section.child(this.homeAction(
                "3",
                Component.translatable("luma.simple.saved_title"),
                Component.translatable("luma.simple.saved_short_help"),
                savedMomentsButton
        ));

        ButtonComponent ideasButton = LumaUi.button(Component.translatable("luma.simple.ideas_button"), button -> this.router.openVariants(
                this,
                this.projectName
        ));
        section.child(this.homeAction(
                "4",
                Component.translatable("luma.simple.ideas_title"),
                Component.translatable("luma.simple.ideas_help"),
                ideasButton
        ));

        ButtonComponent shareButton = LumaUi.button(Component.translatable("luma.simple.share_button"), button -> this.router.openShare(
                this,
                this.projectName
        ));
        section.child(this.homeAction(
                "5",
                Component.translatable("luma.simple.share_title"),
                Component.translatable("luma.simple.share_help"),
                shareButton
        ));

        if (this.state.hasRecoveryDraft()) {
            section.child(LumaUi.caption(Component.translatable("luma.project.recovery_hint")));
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

    private FlowLayout homeAction(String stepNumber, Component title, Component help, ButtonComponent button) {
        return new SimpleActionCard(
                Component.translatable("luma.simple.step", stepNumber),
                title,
                help,
                button
        ).render(this.width);
    }

    private int pendingTotal(PendingChangeSummary pending) {
        return pending.addedBlocks() + pending.removedBlocks() + pending.changedBlocks();
    }

    private void openSavedMoments() {
        this.scrollToSavedMoments = true;
        this.refresh("luma.status.project_ready");
    }

    private FlowLayout historySection() {
        ProjectVariant selectedVariant = this.selectedVariant();
        List<ProjectVersion> versions = selectedVariant == null ? List.of() : this.variantVersions(selectedVariant.id());

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.simple.saved_title"),
                Component.translatable(
                        "luma.simple.saved_help",
                        selectedVariant == null ? Component.translatable("luma.variant.empty") : Component.literal(ProjectUiSupport.displayVariantName(selectedVariant))
                )
        );
        section.child(LumaUi.caption(Component.translatable("luma.simple.idea_picker_help")));
        section.child(this.variantPicker());

        if (versions.isEmpty()) {
            section.child(LumaUi.emptyState(
                    Component.translatable("luma.simple.no_saved_title"),
                    Component.translatable("luma.simple.no_saved_help")
            ));
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
        hero.gap(8);
        hero.child(ProjectUiSupport.versionPreview(this.actionController, this.projectName, version, 96, 72, 96));

        FlowLayout text = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        text.gap(4);
        text.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));
        text.child(LumaUi.caption(Component.translatable(
                "luma.history.version_meta",
                ProjectUiSupport.safeText(version.author()),
                ProjectUiSupport.formatTimestamp(version.createdAt())
        )));
        text.child(LumaUi.caption(Component.translatable(
                "luma.simple.save_card_summary",
                version.stats().changedBlocks(),
                ProjectUiSupport.displayVariantName(versionVariant)
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
        actions.child(LumaUi.button(Component.translatable("luma.simple.action_open_save"), button -> this.router.openSaveDetails(
                this,
                this.projectName,
                version.id()
        )));
        actions.child(LumaUi.button(Component.translatable("luma.simple.action_compare_save"), button -> this.router.openCompare(
                this,
                this.projectName,
                version.id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                version.id()
        )));
        ButtonComponent restoreButton = LumaUi.button(Component.translatable("luma.simple.action_restore_save"), button -> {
            if (versionVariant != null) {
                this.restoreVersion(versionVariant, version);
            }
        });
        restoreButton.active(versionVariant != null && !operationActive);
        actions.child(restoreButton);
        card.child(actions);
        return card;
    }

    private FlowLayout advancedSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.more_title"),
                Component.translatable("luma.project.more_help")
        );

        FlowLayout toggle = LumaUi.actionRow();
        toggle.child(LumaUi.button(Component.translatable(
                this.advancedToolsExpanded ? "luma.action.hide_tools" : "luma.action.more_tools"
        ), button -> {
            this.advancedToolsExpanded = !this.advancedToolsExpanded;
            this.refresh("luma.status.project_ready");
        }));
        section.child(toggle);

        if (!this.advancedToolsExpanded) {
            return section;
        }

        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(
                this,
                this.projectName
        )));
        actions.child(LumaUi.button(Component.translatable("luma.action.compare"), button -> this.router.openCompare(
                this,
                this.projectName,
                "",
                ""
        )));
        if (this.state.hasRecoveryDraft()) {
            actions.child(LumaUi.button(Component.translatable("luma.action.recovery"), button -> this.router.openRecovery(
                    this,
                    this.projectName
            )));
        }
        section.child(actions);
        section.child(this.graphSection());
        section.child(this.diagnosticsSection());
        return section;
    }

    private FlowLayout graphSection() {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.project.graph_title"),
                Component.translatable("luma.project.graph_help")
        );
        List<CommitGraphNode> nodes = CommitGraphLayout.build(
                this.state.versions(),
                this.state.variants(),
                this.state.project().activeVariantId()
        );
        if (nodes.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.history.empty")));
            return section;
        }

        for (CommitGraphNode node : nodes) {
            section.child(this.graphRow(node));
        }
        return section;
    }

    private FlowLayout graphRow(CommitGraphNode node) {
        ProjectVersion version = node.version();
        FlowLayout row = LumaUi.insetSection(
                Component.literal(this.graphPrefix(node) + " " + ProjectUiSupport.displayMessage(version)),
                Component.translatable(
                        "luma.history.version_meta",
                        ProjectUiSupport.safeText(version.author()),
                        ProjectUiSupport.formatTimestamp(version.createdAt())
                )
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.literal(version.id())));
        meta.child(LumaUi.chip(Component.literal(ProjectUiSupport.displayVariantName(this.state.variants(), version.variantId()))));
        row.child(meta);

        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.open_details"), button -> this.router.openSaveDetails(
                this,
                this.projectName,
                version.id()
        )));
        row.child(actions);
        return row;
    }

    private String graphPrefix(CommitGraphNode node) {
        StringBuilder builder = new StringBuilder(node.laneCount() * 2);
        for (int lane = 0; lane < node.laneCount(); lane++) {
            if (lane == node.lane()) {
                builder.append(node.activeHead() ? '*' : 'o');
            } else if (node.activeLanes().contains(lane)) {
                builder.append('|');
            } else {
                builder.append(' ');
            }
            if (lane + 1 < node.laneCount()) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    private FlowLayout diagnosticsSection() {
        FlowLayout section = LumaUi.insetSection(
                Component.translatable("luma.project.diagnostics_title"),
                Component.translatable("luma.project.diagnostics_help")
        );
        section.child(this.integrityCard());
        section.child(this.uiToolkitCard());
        section.child(this.integrationsCard());
        section.child(this.logCard());
        return section;
    }

    private FlowLayout integrityCard() {
        ProjectAdvancedViewState advanced = this.state.advanced();
        FlowLayout card = LumaUi.insetSection(
                Component.translatable("luma.project.integrity_title"),
                Component.translatable(advanced.integrityReport().valid() ? "luma.integrity.valid" : "luma.integrity.invalid")
        );

        for (String error : advanced.integrityReport().errors()) {
            card.child(LumaUi.danger(Component.translatable("luma.integrity.error", error)));
        }
        for (String warning : advanced.integrityReport().warnings()) {
            card.child(LumaUi.caption(Component.translatable("luma.integrity.warning", warning)));
        }
        if (advanced.integrityReport().errors().isEmpty() && advanced.integrityReport().warnings().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.project.integrity_clean")));
        }
        return card;
    }

    private FlowLayout uiToolkitCard() {
        UiToolkitStatus status = UI_TOOLKITS.status();
        FlowLayout card = LumaUi.insetSection(
                Component.translatable("luma.ui_toolkit.title"),
                Component.translatable(
                        status.targetActive() ? "luma.ui_toolkit.ldlib2_active" : "luma.ui_toolkit.fallback_active",
                        status.activeBackend().displayName()
                )
        );
        for (var backend : status.backends()) {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.ui_toolkit.backend",
                    backend.displayName(),
                    backend.available() ? Component.translatable("luma.common.available") : Component.translatable("luma.common.unavailable"),
                    String.join(" ", backend.notes())
            )));
        }
        return card;
    }

    private FlowLayout integrationsCard() {
        ProjectAdvancedViewState advanced = this.state.advanced();
        FlowLayout card = LumaUi.insetSection(
                Component.translatable("luma.project.integrations_title"),
                Component.translatable("luma.project.integrations_help")
        );
        if (advanced.integrations().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.integrations.empty")));
            return card;
        }

        for (var integration : advanced.integrations()) {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.integrations.entry",
                    integration.toolId(),
                    integration.available() ? Component.translatable("luma.common.available") : Component.translatable("luma.common.unavailable"),
                    integration.modeLabel(),
                    String.join(", ", integration.capabilityLabels())
            )));
        }
        return card;
    }

    private FlowLayout logCard() {
        ProjectAdvancedViewState advanced = this.state.advanced();
        FlowLayout card = LumaUi.insetSection(
                Component.translatable("luma.project.log_title"),
                Component.translatable("luma.project.log_help")
        );

        if (advanced.journal().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.log.empty")));
            return card;
        }

        for (RecoveryJournalEntry entry : advanced.journal()) {
            FlowLayout row = LumaUi.insetSection(
                    Component.translatable("luma.log.entry_header", entry.type(), entry.timestamp().toString()),
                    Component.translatable("luma.log.entry_message", entry.message())
            );
            if (entry.versionId() != null && !entry.versionId().isBlank()) {
                row.child(LumaUi.caption(Component.translatable("luma.log.entry_version", entry.versionId())));
            }
            if (entry.variantId() != null && !entry.variantId().isBlank()) {
                row.child(LumaUi.caption(Component.translatable("luma.log.entry_variant", entry.variantId())));
            }
            card.child(row);
        }
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

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.restore.initial_confirm_title", version.id()),
                Component.translatable("luma.restore.initial_confirm_help")
        );
        section.child(LumaUi.danger(Component.translatable("luma.restore.initial_confirm_warning")));
        section.child(LumaUi.caption(Component.translatable(
                "luma.restore.initial_confirm_target",
                ProjectUiSupport.displayVariantName(variant),
                version.id()
        )));

        RestorePlanSummary summary = this.actionController.restorePlanSummary(this.projectName, version.id());
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
        ButtonComponent confirmButton = LumaUi.button(Component.translatable("luma.action.confirm_initial_restore"), button -> {
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

        if (version.versionKind() == VersionKind.INITIAL || version.versionKind() == VersionKind.WORLD_ROOT) {
            this.pendingRestoreVariantId = variant.id();
            this.pendingRestoreVersionId = version.id();
            this.refresh("luma.status.initial_restore_confirmation_required");
            return;
        }

        this.executeRestore(variant, version);
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
        double scrollProgress = this.scrollToSavedMoments ? 0.45D : this.currentScrollProgress();
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
        this.restoreScroll(scrollProgress);
        this.scrollToSavedMoments = false;
    }

    @Override
    protected void onLumaTick() {
        if (++this.refreshCooldown < 10) {
            return;
        }
        this.refreshCooldown = 0;
        ProjectHomeViewState refreshed = this.stateController.loadState(this.projectName, this.statusKey, this.advancedToolsExpanded);
        String normalizedStatusKey = ScreenOperationStateSupport.normalizeStatusKey(
                this.statusKey,
                refreshed.operationSnapshot(),
                "luma.status.project_ready"
        );
        if (!normalizedStatusKey.equals(this.statusKey)) {
            this.statusKey = normalizedStatusKey;
            refreshed = this.stateController.loadState(this.projectName, this.statusKey, this.advancedToolsExpanded);
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
