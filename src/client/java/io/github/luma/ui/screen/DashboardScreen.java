package io.github.luma.ui.screen;

import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.LumaScrollContainer;
import io.github.luma.ui.controller.DashboardScreenController;
import io.github.luma.ui.controller.ProjectScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.DashboardProjectItem;
import io.github.luma.ui.state.DashboardViewState;
import io.github.luma.ui.state.ProjectTab;
import io.github.luma.ui.state.ProjectViewState;
import io.wispforest.owo.ui.base.BaseOwoScreen;
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

public final class DashboardScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final Minecraft client = Minecraft.getInstance();
    private final DashboardScreenController controller = new DashboardScreenController();
    private final ProjectScreenController projectController = new ProjectScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private LumaScrollContainer<FlowLayout> bodyScroll;
    private DashboardViewState state = new DashboardViewState(List.of(), "luma.status.dashboard_ready");
    private ProjectViewState workspaceState = new ProjectViewState(
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            List.of(),
            List.of(),
            new io.github.luma.domain.model.ProjectIntegrityReport(true, List.of(), List.of()),
            ProjectTab.HISTORY,
            "luma.status.project_ready"
    );

    public DashboardScreen(Screen parent) {
        super(Component.translatable("luma.screen.dashboard.title"));
        this.parent = parent;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.state.status());
        DashboardProjectItem workspaceItem = this.primaryWorkspace(this.workspaceProjects());
        if (workspaceItem != null) {
            this.workspaceState = this.projectController.loadState(workspaceItem.name(), ProjectTab.HISTORY, "", "luma.status.project_ready");
        }

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.refresh"), button -> this.refresh("luma.status.dashboard_ready")));
        frame.child(header);

        FlowLayout titleRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        titleRow.gap(8);
        titleRow.child(LumaUi.value(Component.translatable("luma.screen.dashboard.title")));
        titleRow.child(LumaUi.chip(Component.translatable("luma.dashboard.current_dimension", this.currentDimensionLabel())));
        frame.child(titleRow);
        frame.child(LumaUi.statusBanner(Component.translatable(this.state.status())));

        FlowLayout body = LumaUi.screenBody();
        this.bodyScroll = LumaUi.screenScroll(body);
        frame.child(this.bodyScroll);

        if (workspaceItem == null || this.workspaceState.project() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.dashboard.empty_title"),
                    Component.translatable("luma.dashboard.empty")
            ));
            return;
        }

        PendingChangeSummary pending = this.projectController.summarizePending(this.workspaceState.recoveryDraft());
        body.child(this.workspaceLauncherCard(workspaceItem, pending));

        List<DashboardProjectItem> extras = this.workspaceProjects().stream()
                .filter(item -> !item.name().equals(workspaceItem.name()))
                .toList();
        if (!extras.isEmpty()) {
            body.child(this.otherWorkspacesCard(extras));
        }
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout workspaceLauncherCard(DashboardProjectItem item, PendingChangeSummary pending) {
        FlowLayout card = LumaUi.sectionCard(
                Component.translatable("luma.dashboard.workspace_section"),
                Component.translatable("luma.dashboard.workspace_help")
        );

        FlowLayout top = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.gap(8);
        top.child(LumaUi.value(Component.literal(item.name())));
        top.child(LumaUi.chip(Component.translatable("luma.dashboard.workspace_dimension", this.dimensionLabel(item.dimensionId()))));
        top.child(LumaUi.chip(Component.translatable("luma.dashboard.active_branch", item.activeVariantId())));
        card.child(top);

        FlowLayout stats = LumaUi.actionRow();
        stats.child(LumaUi.statChip(
                Component.translatable("luma.dashboard.metric_versions"),
                Component.literal(Integer.toString(item.versionCount()))
        ));
        stats.child(LumaUi.statChip(
                Component.translatable("luma.dashboard.metric_pending"),
                Component.literal(Integer.toString(item.draftChangeCount()))
        ));
        card.child(stats);

        if (item.hasDraft()) {
            card.child(LumaUi.accent(Component.translatable("luma.dashboard.workspace_recovery", item.draftChangeCount())));
        } else if (pending.isEmpty()) {
            card.child(LumaUi.caption(Component.translatable("luma.dashboard.pending_clean")));
        } else {
            card.child(LumaUi.caption(Component.translatable(
                    "luma.dashboard.workspace_pending",
                    pending.addedBlocks() + pending.removedBlocks() + pending.changedBlocks()
            )));
        }

        FlowLayout actions = LumaUi.actionRow();
        actions.child(UIComponents.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProject(this, item.name())));
        if (item.hasDraft()) {
            actions.child(UIComponents.button(Component.translatable("luma.action.recovery"), button -> this.router.openRecovery(this, item.name())));
        }
        actions.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, item.name())));
        card.child(actions);
        return card;
    }

    private FlowLayout otherWorkspacesCard(List<DashboardProjectItem> extras) {
        FlowLayout card = LumaUi.sectionCard(
                Component.translatable("luma.dashboard.other_workspaces"),
                Component.translatable("luma.dashboard.other_workspaces_help")
        );

        for (DashboardProjectItem item : extras) {
            FlowLayout row = LumaUi.insetSection(
                    Component.literal(item.name()),
                    Component.translatable("luma.dashboard.project_entry", item.activeVariantId(), item.versionCount(), item.draftChangeCount())
            );

            FlowLayout meta = LumaUi.actionRow();
            meta.child(LumaUi.chip(Component.translatable("luma.dashboard.workspace_dimension", this.dimensionLabel(item.dimensionId()))));
            if (item.hasDraft()) {
                meta.child(LumaUi.chip(Component.translatable("luma.recovery.badge")));
            }
            row.child(meta);

            FlowLayout actions = LumaUi.actionRow();
            actions.child(UIComponents.button(Component.translatable("luma.action.open_workspace"), button -> this.router.openProject(this, item.name())));
            if (item.hasDraft()) {
                actions.child(UIComponents.button(Component.translatable("luma.action.recovery"), button -> this.router.openRecovery(this, item.name())));
            }
            actions.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, item.name())));
            row.child(actions);
            card.child(row);
        }

        return card;
    }

    private void refresh(String statusKey) {
        double scrollProgress = this.currentScrollProgress();
        this.state = new DashboardViewState(this.state.projects(), statusKey);
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

    private List<DashboardProjectItem> workspaceProjects() {
        return this.state.projects().stream()
                .filter(item -> !item.archived())
                .filter(DashboardProjectItem::worldWorkspace)
                .toList();
    }

    private DashboardProjectItem primaryWorkspace(List<DashboardProjectItem> items) {
        String currentDimensionId = this.client.level == null
                ? "minecraft:overworld"
                : this.client.level.dimension().identifier().toString();

        for (DashboardProjectItem item : items) {
            if (item.dimensionId().equals(currentDimensionId)) {
                return item;
            }
        }
        return items.isEmpty() ? null : items.getFirst();
    }

    private String currentDimensionLabel() {
        if (this.client.level == null) {
            return "Overworld";
        }
        return this.dimensionLabel(this.client.level.dimension().identifier().toString());
    }

    private String dimensionLabel(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }
}
