package io.github.luma.ui.screen;

import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.ui.OperationProgressPresenter;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.graph.CommitGraphNode;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.controller.CompareScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectTab;
import io.github.luma.ui.state.ProjectViewState;
import io.github.luma.ui.tab.ChangesTabView;
import io.github.luma.ui.tab.MaterialsTabView;
import io.github.luma.ui.tab.PreviewTabView;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ProjectScreen extends LumaScreen {

    private static final String FILTER_ALL = "all";
    private static final String FILTER_COMMITS = "commits";
    private static final String FILTER_RECOVERY = "recovery";
    private static final String FILTER_RESTORE = "restore";
    private static final DateTimeFormatter COMMIT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private final String projectName;
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
            ProjectTab.HISTORY,
            "luma.status.project_ready"
    );
    private String statusKey;
    private String selectedVariantId = "";
    private String selectedVersionId = "";
    private String saveMessage = "";
    private String variantName = "";
    private String historySearch = "";
    private String historyFilter = FILTER_ALL;
    private ProjectTab detailTab = ProjectTab.PREVIEW;
    private boolean branchComposerExpanded = false;
    private boolean diagnosticsExpanded = false;
    private TextBoxComponent commitMessageInput;

    public ProjectScreen(Screen parent, String projectName) {
        this(parent, projectName, "", "luma.status.project_ready");
    }

    public ProjectScreen(Screen parent, String projectName, String statusKey) {
        this(parent, projectName, "", statusKey);
    }

    public ProjectScreen(Screen parent, String projectName, String selectedVariantId, String statusKey) {
        super(Component.literal(projectName));
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
        this.state = this.controller.loadState(this.projectName, ProjectTab.HISTORY, this.selectedVersionId, this.statusKey);
        if (this.state.project() != null && this.selectedVariantId.isBlank()) {
            this.selectedVariantId = this.state.project().activeVariantId();
        }
        if (this.state.selectedVersion() != null) {
            this.selectedVersionId = this.state.selectedVersion().id();
            this.selectedVariantId = this.state.selectedVersion().variantId();
        }
        if (this.detailTab == ProjectTab.CHANGES) {
            this.detailTab = ProjectTab.PREVIEW;
        }

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.workspaces"), button -> this.router.openDashboard(this)));
        header.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, this.projectName)));
        if (this.state.recoveryDraft() != null) {
            header.child(UIComponents.button(Component.translatable("luma.action.recovery"), button -> this.router.openRecovery(this, this.projectName)));
        }
        frame.child(header);

        FlowLayout titleRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        titleRow.gap(8);
        titleRow.child(LumaUi.value(Component.translatable("luma.screen.project.title", this.projectName)));
        if (this.state.project() != null) {
            titleRow.child(LumaUi.chip(Component.translatable(
                    "luma.dashboard.current_dimension",
                    this.dimensionLabel(this.state.project().dimensionId())
            )));
            titleRow.child(LumaUi.chip(Component.translatable(
                    "luma.dashboard.active_branch",
                    this.state.project().activeVariantId()
            )));
            if (!this.selectedVariantId.isBlank() && !this.selectedVariantId.equals(this.state.project().activeVariantId())) {
                titleRow.child(LumaUi.chip(Component.translatable("luma.project.viewing_branch", this.selectedVariantId)));
            }
        }
        frame.child(titleRow);
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

        body.child(this.pendingSection());
        if (this.state.operationSnapshot() != null) {
            body.child(this.operationSection());
        }
        body.child(this.workspaceMainSection());
        body.child(this.diagnosticsSection());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout pendingSection() {
        PendingChangeSummary pending = this.controller.summarizePending(this.state.recoveryDraft());
        boolean operationActive = this.state.operationSnapshot() != null && !this.state.operationSnapshot().terminal();
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.pending_title"),
                Component.translatable("luma.project.pending_help", this.state.project().activeVariantId())
        );

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(Component.translatable("luma.dashboard.pending_added"), Component.literal(Integer.toString(pending.addedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.dashboard.pending_removed"), Component.literal(Integer.toString(pending.removedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.dashboard.pending_changed"), Component.literal(Integer.toString(pending.changedBlocks()))));
        section.child(stats);

        this.commitMessageInput = UIComponents.textBox(Sizing.fill(100), this.saveMessage);
        this.commitMessageInput.setHint(Component.translatable("luma.history.message_required"));
        this.commitMessageInput.onChanged().subscribe(value -> this.saveMessage = value);
        section.child(LumaUi.formField(
                Component.translatable("luma.history.message_input"),
                Component.translatable(
                        pending.isEmpty()
                                ? "luma.dashboard.pending_clean"
                                : "luma.history.quick_save_hint"
                ),
                this.commitMessageInput
        ));

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent saveButton = UIComponents.button(Component.translatable("luma.action.save_version"), button -> {
            String status = this.controller.saveVersion(this.projectName, this.saveMessage);
            if ("luma.status.save_started".equals(status)) {
                this.saveMessage = "";
                this.selectedVariantId = this.state.project().activeVariantId();
                this.selectedVersionId = "";
            }
            this.refresh(status);
        });
        saveButton.active(!pending.isEmpty() && !operationActive);
        actions.child(saveButton);

        ButtonComponent amendButton = UIComponents.button(Component.translatable("luma.action.amend_version"), button -> {
            String status = this.controller.amendVersion(this.projectName, this.saveMessage);
            if ("luma.status.amend_started".equals(status)) {
                this.saveMessage = "";
                this.selectedVariantId = this.state.project().activeVariantId();
                this.selectedVersionId = "";
            }
            this.refresh(status);
        });
        amendButton.active(!pending.isEmpty() && !operationActive && this.activeHeadVersion() != null);
        actions.child(amendButton);
        section.child(actions);
        if (!pending.isEmpty()) {
            this.setInitialFocus(this.commitMessageInput);
            this.commitMessageInput.setFocused(true);
        }
        return section;
    }

    private FlowLayout operationSection() {
        var operation = this.state.operationSnapshot();
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.operation_title"),
                Component.literal(operation.handle().label())
        );
        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(
                Component.translatable("luma.project.operation_percent_label"),
                Component.literal(Integer.toString(OperationProgressPresenter.displayPercent(operation)) + "%")
        ));
        section.child(stats);
        section.child(LumaUi.caption(Component.translatable(
                "luma.project.operation_stage",
                operation.stage().name().toLowerCase(Locale.ROOT)
        )));
        section.child(LumaUi.caption(Component.literal(OperationProgressPresenter.progressSummary(operation))));
        if (operation.detail() != null && !operation.detail().isBlank()) {
            section.child(LumaUi.caption(Component.literal(operation.detail())));
        }
        return section;
    }

    private FlowLayout branchesSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.branches_title"),
                Component.translatable("luma.project.branches_help")
        );

        for (ProjectVariant variant : this.sortedVariants(this.state.variants())) {
            section.child(this.branchRow(variant));
        }

        FlowLayout toggleRow = LumaUi.actionRow();
        toggleRow.child(UIComponents.button(
                Component.translatable(
                        this.branchComposerExpanded
                                ? "luma.action.hide_branch_creator"
                                : "luma.action.show_branch_creator"
                ),
                button -> {
                    this.branchComposerExpanded = !this.branchComposerExpanded;
                    this.refresh(this.statusKey);
                }
        ));
        section.child(toggleRow);

        if (this.branchComposerExpanded) {
            var branchNameInput = UIComponents.textBox(Sizing.fill(100), this.variantName);
            branchNameInput.onChanged().subscribe(value -> this.variantName = value);
            String baseVersion = this.selectedVersionId == null ? "" : this.selectedVersionId;
            Component hint = baseVersion.isBlank()
                    ? Component.translatable("luma.variant.creation_hint", "")
                    : Component.translatable("luma.project.branch_create_from_selected", baseVersion);
            section.child(LumaUi.formField(
                    Component.translatable("luma.variant.name_input"),
                    hint,
                    branchNameInput
            ));

            FlowLayout createRow = LumaUi.actionRow();
            createRow.child(UIComponents.button(Component.translatable("luma.action.branch_create"), button -> {
                String status = this.controller.createVariant(this.projectName, this.variantName, baseVersion);
                if ("luma.status.variant_created".equals(status)) {
                    this.variantName = "";
                    this.branchComposerExpanded = false;
                }
                this.refresh(status);
            }));
            section.child(createRow);
        }

        return section;
    }

    private FlowLayout branchRow(ProjectVariant variant) {
        boolean active = variant.id().equals(this.state.project().activeVariantId());
        boolean viewing = variant.id().equals(this.selectedVariantId);

        FlowLayout row = LumaUi.insetSection(
                Component.literal(variant.name()),
                Component.translatable("luma.dashboard.branch_commits", this.commitCount(variant.id()))
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.literal(variant.id())));
        if (active) {
            meta.child(LumaUi.chip(Component.translatable("luma.dashboard.branch_active")));
        }
        if (viewing) {
            meta.child(LumaUi.chip(Component.translatable("luma.history.selected_badge")));
        }
        row.child(meta);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent openButton = UIComponents.button(Component.translatable("luma.action.select"), button -> {
            this.selectedVariantId = variant.id();
            this.selectedVersionId = variant.headVersionId() == null ? "" : variant.headVersionId();
            this.refresh("luma.status.project_ready");
        });
        openButton.active(variant.headVersionId() != null && !variant.headVersionId().isBlank());
        actions.child(openButton);

        if (!active) {
            actions.child(UIComponents.button(Component.translatable("luma.action.branch_switch"), button -> {
                this.selectedVariantId = variant.id();
                this.selectedVersionId = "";
                this.refresh(this.controller.switchVariant(this.projectName, variant.id()));
            }));
        }
        row.child(actions);
        return row;
    }

    private FlowLayout workspaceMainSection() {
        FlowLayout section = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        section.gap(8);

        FlowLayout graphColumn = UIContainers.verticalFlow(Sizing.fill(62), Sizing.content());
        graphColumn.gap(8);
        graphColumn.child(this.branchesSection());
        graphColumn.child(this.commitGraphSection());

        FlowLayout detailColumn = UIContainers.verticalFlow(Sizing.fill(38), Sizing.content());
        detailColumn.gap(8);
        detailColumn.child(this.detailsSection());

        section.child(graphColumn);
        section.child(detailColumn);
        return section;
    }

    private FlowLayout commitGraphSection() {
        FlowLayout section = LumaUi.sectionCard(
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
            section.child(this.graphNodeRow(node));
        }
        return section;
    }

    private FlowLayout graphNodeRow(CommitGraphNode node) {
        ProjectVersion version = node.version();
        boolean selected = this.state.selectedVersion() != null && version.id().equals(this.state.selectedVersion().id());

        FlowLayout row = LumaUi.insetSection(
                Component.literal(this.graphPrefix(node) + " " + this.displayMessage(version)),
                Component.translatable("luma.history.version_meta", this.safeText(version.author()), this.formatTimestamp(version.createdAt()))
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.literal(version.id())));
        meta.child(LumaUi.chip(Component.translatable(this.versionKindKey(version.versionKind()))));
        meta.child(LumaUi.chip(Component.translatable("luma.dashboard.active_branch", version.variantId())));
        if (selected) {
            meta.child(LumaUi.chip(Component.translatable("luma.history.selected_badge")));
        }
        if (node.activeHead()) {
            meta.child(LumaUi.chip(Component.translatable("luma.project.active_head_badge")));
        }
        for (String headVariant : node.headVariants()) {
            meta.child(LumaUi.chip(Component.translatable("luma.project.head_badge", headVariant)));
        }
        row.child(meta);

        row.child(LumaUi.caption(Component.translatable(
                "luma.history.version_changes",
                version.stats().changedBlocks(),
                version.stats().changedChunks(),
                version.stats().distinctBlockTypes()
        )));

        ButtonComponent selectButton = UIComponents.button(Component.translatable("luma.action.select"), button -> {
            this.selectedVariantId = version.variantId();
            this.selectedVersionId = version.id();
            this.detailTab = ProjectTab.PREVIEW;
            this.refresh("luma.status.project_ready");
        });
        selectButton.active(!selected);
        FlowLayout actions = LumaUi.actionRow();
        actions.child(selectButton);
        row.child(actions);
        return row;
    }

    private String graphPrefix(CommitGraphNode node) {
        StringBuilder builder = new StringBuilder(node.laneCount() * 2);
        for (int lane = 0; lane < node.laneCount(); lane++) {
            if (lane == node.lane()) {
                builder.append(node.activeHead() ? '◆' : '●');
            } else if (node.activeLanes().contains(lane)) {
                builder.append('│');
            } else {
                builder.append(' ');
            }
            if (lane + 1 < node.laneCount()) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    private FlowLayout historySection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.history_title"),
                Component.translatable("luma.project.history_help", this.selectedVariantId)
        );

        var searchBox = UIComponents.textBox(Sizing.fill(100), this.historySearch);
        searchBox.onChanged().subscribe(value -> {
            this.historySearch = value;
            this.refresh("luma.status.project_ready");
        });
        section.child(LumaUi.formField(
                Component.translatable("luma.dashboard.search"),
                Component.translatable("luma.project.history_search_help"),
                searchBox
        ));

        FlowLayout firstFilterRow = LumaUi.actionRow();
        firstFilterRow.child(this.filterButton(Component.translatable("luma.history.filter_all"), FILTER_ALL));
        firstFilterRow.child(this.filterButton(Component.translatable("luma.history.filter_commits"), FILTER_COMMITS));
        section.child(firstFilterRow);

        FlowLayout secondFilterRow = LumaUi.actionRow();
        secondFilterRow.child(this.filterButton(Component.translatable("luma.history.filter_recovery"), FILTER_RECOVERY));
        secondFilterRow.child(this.filterButton(Component.translatable("luma.history.filter_restore"), FILTER_RESTORE));
        section.child(secondFilterRow);

        ProjectVariant selectedVariant = this.selectedVariant();
        if (selectedVariant == null) {
            section.child(LumaUi.caption(Component.translatable("luma.variant.empty")));
            return section;
        }

        List<ProjectVersion> versions = this.filteredVersions(selectedVariant.id());
        if (versions.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.history.empty_branch")));
            return section;
        }

        for (ProjectVersion version : versions) {
            section.child(this.versionCard(selectedVariant, version));
        }

        return section;
    }

    private FlowLayout versionCard(ProjectVariant selectedVariant, ProjectVersion version) {
        FlowLayout card = LumaUi.insetSection(
                Component.literal(this.displayMessage(version)),
                Component.translatable("luma.history.version_meta", this.safeText(version.author()), this.formatTimestamp(version.createdAt()))
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.literal(version.id())));
        meta.child(LumaUi.chip(Component.translatable(this.versionKindKey(version.versionKind()))));
        if (this.state.selectedVersion() != null && version.id().equals(this.state.selectedVersion().id())) {
            meta.child(LumaUi.chip(Component.translatable("luma.history.selected_badge")));
        }
        card.child(meta);

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(Component.translatable("luma.history.commit_blocks"), Component.literal(Integer.toString(version.stats().changedBlocks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.history.commit_chunks"), Component.literal(Integer.toString(version.stats().changedChunks()))));
        stats.child(LumaUi.statChip(Component.translatable("luma.history.commit_types"), Component.literal(Integer.toString(version.stats().distinctBlockTypes()))));
        card.child(stats);

        FlowLayout actions = LumaUi.actionRow();
        ButtonComponent openDetails = UIComponents.button(Component.translatable("luma.action.open_details"), button -> {
            this.selectedVersionId = version.id();
            this.detailTab = ProjectTab.PREVIEW;
            this.refresh("luma.status.project_ready");
        });
        openDetails.active(this.state.selectedVersion() == null || !version.id().equals(this.state.selectedVersion().id()));
        actions.child(openDetails);

        actions.child(UIComponents.button(Component.translatable("luma.action.compare"), button -> this.router.openCompare(
                this,
                this.projectName,
                version.parentVersionId(),
                version.id(),
                version.id()
        )));

        actions.child(UIComponents.button(Component.translatable(
                selectedVariant.id().equals(this.state.project().activeVariantId())
                        ? "luma.action.restore"
                        : "luma.action.switch_then_restore"
        ), button -> this.restoreVersion(selectedVariant, version)));

        card.child(actions);
        return card;
    }

    private FlowLayout detailsSection() {
        if (this.state.selectedVersion() == null) {
            return LumaUi.emptyState(
                    Component.translatable("luma.project.details_title"),
                    Component.translatable("luma.preview.no_version")
            );
        }

        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.details_title"),
                Component.translatable("luma.project.selected_version_short", this.state.selectedVersion().id())
        );

        FlowLayout meta = LumaUi.actionRow();
        meta.child(LumaUi.chip(Component.translatable(this.versionKindKey(this.state.selectedVersion().versionKind()))));
        meta.child(LumaUi.chip(Component.translatable("luma.dashboard.active_branch", this.state.selectedVersion().variantId())));
        if (this.isActiveHead(this.state.selectedVersion())) {
            meta.child(LumaUi.chip(Component.translatable("luma.project.active_head_badge")));
        }
        section.child(meta);

        FlowLayout compareActions = LumaUi.actionRow();
        String parentVersionId = this.parentVersionId(this.state.selectedVersion().id());
        ButtonComponent compareParent = UIComponents.button(Component.translatable("luma.action.compare_with_parent"), button -> this.router.openCompare(
                this,
                this.projectName,
                parentVersionId,
                this.state.selectedVersion().id(),
                this.state.selectedVersion().id()
        ));
        compareParent.active(!parentVersionId.isBlank());
        compareActions.child(compareParent);
        compareActions.child(UIComponents.button(Component.translatable("luma.action.compare_with_current"), button -> this.router.openCompare(
                this,
                this.projectName,
                this.state.selectedVersion().id(),
                CompareScreenController.CURRENT_WORLD_REFERENCE,
                this.state.selectedVersion().id()
        )));
        section.child(compareActions);

        FlowLayout versionActions = LumaUi.actionRow();
        ButtonComponent checkoutButton = UIComponents.button(Component.translatable("luma.action.checkout_branch"), button -> this.refresh(
                this.controller.switchVariant(this.projectName, this.state.selectedVersion().variantId(), false)
        ));
        checkoutButton.active(this.state.project() != null
                && !this.state.selectedVersion().variantId().equals(this.state.project().activeVariantId()));
        versionActions.child(checkoutButton);

        ProjectVariant selectedVariant = this.variantFor(this.state.selectedVersion().variantId());
        if (selectedVariant != null) {
            ButtonComponent restoreButton = UIComponents.button(Component.translatable(
                    selectedVariant.id().equals(this.state.project().activeVariantId())
                            ? "luma.action.restore"
                            : "luma.action.switch_then_restore"
            ), button -> this.restoreVersion(selectedVariant, this.state.selectedVersion()));
            restoreButton.active(this.state.operationSnapshot() == null || this.state.operationSnapshot().terminal());
            versionActions.child(restoreButton);
        }
        section.child(versionActions);

        FlowLayout switchers = LumaUi.actionRow();
        switchers.child(this.detailButton(ProjectTab.PREVIEW));
        switchers.child(this.detailButton(ProjectTab.MATERIALS));
        section.child(switchers);
        section.child(this.detailContent());
        return section;
    }

    private FlowLayout detailContent() {
        return switch (this.detailTab) {
            case PREVIEW -> PreviewTabView.build(
                    this.state,
                    this.projectName,
                    this.controller,
                    this::refresh
            );
            case MATERIALS -> MaterialsTabView.build(this.state);
            case CHANGES, HISTORY, VARIANTS, INTEGRATIONS, LOG -> ChangesTabView.build(this.state);
        };
    }

    private FlowLayout diagnosticsSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.project.diagnostics_title"),
                Component.translatable("luma.project.diagnostics_help")
        );

        FlowLayout toggleRow = LumaUi.actionRow();
        toggleRow.child(UIComponents.button(
                Component.translatable(
                        this.diagnosticsExpanded
                                ? "luma.action.hide_diagnostics"
                                : "luma.action.show_diagnostics"
                ),
                button -> {
                    this.diagnosticsExpanded = !this.diagnosticsExpanded;
                    this.refresh(this.statusKey);
                }
        ));
        section.child(toggleRow);

        if (!this.diagnosticsExpanded) {
            return section;
        }

        section.child(this.integrityCard());
        section.child(this.integrationsCard());
        section.child(this.logCard());
        return section;
    }

    private FlowLayout integrityCard() {
        FlowLayout card = LumaUi.insetSection(
                Component.translatable("luma.project.integrity_title"),
                Component.translatable(this.state.integrityReport().valid() ? "luma.integrity.valid" : "luma.integrity.invalid")
        );

        for (String error : this.state.integrityReport().errors()) {
            card.child(LumaUi.caption(Component.translatable("luma.integrity.error", error)));
        }
        for (String warning : this.state.integrityReport().warnings()) {
            card.child(LumaUi.caption(Component.translatable("luma.integrity.warning", warning)));
        }
        if (this.state.integrityReport().errors().isEmpty() && this.state.integrityReport().warnings().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.project.integrity_clean")));
        }
        return card;
    }

    private FlowLayout integrationsCard() {
        FlowLayout card = LumaUi.insetSection(
                Component.translatable("luma.project.integrations_title"),
                Component.translatable("luma.project.integrations_help")
        );

        if (this.state.integrations().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.integrations.empty")));
            return card;
        }

        for (var integration : this.state.integrations()) {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.integrations.entry",
                    integration.toolId(),
                    integration.available() ? Component.translatable("luma.common.available") : Component.translatable("luma.common.unavailable"),
                    integration.mode(),
                    String.join(", ", integration.capabilities())
            )));
        }
        return card;
    }

    private FlowLayout logCard() {
        FlowLayout card = LumaUi.insetSection(
                Component.translatable("luma.project.log_title"),
                Component.translatable("luma.project.log_help")
        );

        if (this.state.project() != null && this.state.project().isLegacySnapshotProject()) {
            card.child(LumaUi.caption(Component.translatable("luma.log.legacy_notice")));
        }
        if (this.state.recoveryDraft() != null) {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.recovery.draft_present",
                    this.state.recoveryDraft().changes().size(),
                    this.state.recoveryDraft().variantId()
            )));
        } else {
            card.child(LumaUi.caption(Component.translatable("luma.recovery.no_draft")));
        }

        if (this.state.journal().isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.log.empty")));
            return card;
        }

        for (RecoveryJournalEntry entry : this.state.journal()) {
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

    private ButtonComponent filterButton(Component label, String value) {
        ButtonComponent button = UIComponents.button(label, pressed -> {
            this.historyFilter = value;
            this.refresh("luma.status.project_ready");
        });
        button.active(!value.equals(this.historyFilter));
        return button;
    }

    private ButtonComponent detailButton(ProjectTab tab) {
        ButtonComponent button = UIComponents.button(Component.translatable(tab.translationKey()), pressed -> {
            this.detailTab = tab;
            this.refresh("luma.status.project_ready");
        });
        button.active(this.detailTab != tab);
        return button;
    }

    private boolean isActiveHead(ProjectVersion version) {
        if (version == null) {
            return false;
        }
        for (ProjectVariant variant : this.state.variants()) {
            if (variant.headVersionId() != null
                    && variant.headVersionId().equals(version.id())
                    && variant.id().equals(this.state.project().activeVariantId())) {
                return true;
            }
        }
        return false;
    }

    private void restoreVersion(ProjectVariant selectedVariant, ProjectVersion version) {
        String status;
        if (!selectedVariant.id().equals(this.state.project().activeVariantId())) {
            status = this.controller.switchVariant(this.projectName, selectedVariant.id(), false);
            if (!"luma.status.variant_switched".equals(status)) {
                this.refresh(status);
                return;
            }
        }

        this.selectedVariantId = selectedVariant.id();
        this.selectedVersionId = version.id();
        status = this.controller.restoreVersion(this.projectName, version.id());
        this.refresh(status);
    }

    private ProjectVariant variantFor(String variantId) {
        for (ProjectVariant variant : this.state.variants()) {
            if (variant.id().equals(variantId)) {
                return variant;
            }
        }
        return null;
    }

    private ProjectVariant selectedVariant() {
        for (ProjectVariant variant : this.state.variants()) {
            if (variant.id().equals(this.selectedVariantId)) {
                return variant;
            }
        }
        if (this.state.project() == null) {
            return null;
        }
        for (ProjectVariant variant : this.state.variants()) {
            if (variant.id().equals(this.state.project().activeVariantId())) {
                return variant;
            }
        }
        return this.state.variants().isEmpty() ? null : this.state.variants().getFirst();
    }

    private ProjectVersion activeHeadVersion() {
        if (this.state.project() == null) {
            return null;
        }
        for (ProjectVariant variant : this.state.variants()) {
            if (!variant.id().equals(this.state.project().activeVariantId())) {
                continue;
            }
            if (variant.headVersionId() == null || variant.headVersionId().isBlank()) {
                return null;
            }
            for (ProjectVersion version : this.state.versions()) {
                if (version.id().equals(variant.headVersionId())) {
                    return version;
                }
            }
        }
        return null;
    }

    private List<ProjectVersion> filteredVersions(String variantId) {
        return this.state.versions().stream()
                .filter(version -> variantId.equals(version.variantId()))
                .filter(this::matchesSearch)
                .filter(this::matchesFilter)
                .sorted(Comparator.comparing(ProjectVersion::createdAt).reversed())
                .toList();
    }

    private boolean matchesSearch(ProjectVersion version) {
        if (this.historySearch == null || this.historySearch.isBlank()) {
            return true;
        }
        String query = this.historySearch.toLowerCase(Locale.ROOT);
        return this.safeText(version.id()).toLowerCase(Locale.ROOT).contains(query)
                || this.safeText(version.author()).toLowerCase(Locale.ROOT).contains(query)
                || this.safeText(version.message()).toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesFilter(ProjectVersion version) {
        return switch (this.historyFilter) {
            case FILTER_COMMITS -> version.versionKind() == VersionKind.WORLD_ROOT
                    || version.versionKind() == VersionKind.INITIAL
                    || version.versionKind() == VersionKind.MANUAL;
            case FILTER_RECOVERY -> version.versionKind() == VersionKind.RECOVERY;
            case FILTER_RESTORE -> version.versionKind() == VersionKind.RESTORE;
            default -> true;
        };
    }

    private int commitCount(String variantId) {
        int count = 0;
        for (ProjectVersion version : this.state.versions()) {
            if (variantId.equals(version.variantId())) {
                count += 1;
            }
        }
        return count;
    }

    private List<ProjectVariant> sortedVariants(List<ProjectVariant> variants) {
        return variants.stream()
                .sorted(Comparator
                        .comparing((ProjectVariant variant) -> !variant.id().equals(this.state.project().activeVariantId()))
                        .thenComparing(ProjectVariant::createdAt))
                .toList();
    }

    private String parentVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return "";
        }
        for (ProjectVersion version : this.state.versions()) {
            if (version.id().equals(versionId)) {
                return version.parentVersionId() == null ? "" : version.parentVersionId();
            }
        }
        return "";
    }

    private String versionKindKey(VersionKind versionKind) {
        return switch (versionKind) {
            case INITIAL -> "luma.version_kind.initial";
            case MANUAL -> "luma.version_kind.manual";
            case RECOVERY -> "luma.version_kind.recovery";
            case RESTORE -> "luma.version_kind.restore";
            case LEGACY -> "luma.version_kind.legacy";
            case WORLD_ROOT -> "luma.version_kind.world_root";
        };
    }

    private String displayMessage(ProjectVersion version) {
        return this.safeText(version.message()).isBlank() ? version.id() : version.message();
    }

    private String formatTimestamp(Instant timestamp) {
        return timestamp == null ? "" : COMMIT_TIME_FORMATTER.format(timestamp);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private void refresh(String statusKey) {
        double scrollProgress = this.currentScrollProgress();
        this.statusKey = statusKey == null || statusKey.isBlank() ? "luma.status.project_ready" : statusKey;
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

    private String dimensionLabel(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }
}
