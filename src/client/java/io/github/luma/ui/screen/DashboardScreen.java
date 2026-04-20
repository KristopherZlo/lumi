package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.controller.DashboardScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.DashboardProjectItem;
import io.github.luma.ui.state.DashboardViewState;
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
    private final ScreenRouter router = new ScreenRouter();
    private DashboardViewState state = new DashboardViewState(List.of(), "luma.status.dashboard_ready");

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
        List<DashboardProjectItem> visibleProjects = this.visibleProjects();
        DashboardProjectItem workspace = this.primaryWorkspace(visibleProjects);

        root.surface(Surface.BLANK);
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout shell = LumaUi.panel(Sizing.fill(100), Sizing.content());
        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), shell));

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.refresh"), button -> this.refresh()));
        shell.child(header);

        FlowLayout titleRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        titleRow.gap(8);
        titleRow.child(LumaUi.value(Component.translatable("luma.screen.dashboard.title")));
        titleRow.child(LumaUi.chip(Component.translatable(this.state.status())));
        shell.child(titleRow);
        shell.child(LumaUi.caption(Component.translatable("luma.dashboard.workspace_hint")));

        FlowLayout content = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        content.gap(6);

        if (workspace == null) {
            content.child(LumaUi.caption(Component.translatable("luma.dashboard.empty")));
        } else {
            content.child(this.workspaceCard(workspace));
        }

        List<DashboardProjectItem> extras = visibleProjects.stream()
                .filter(item -> workspace == null || !item.name().equals(workspace.name()))
                .toList();
        if (!extras.isEmpty()) {
            FlowLayout legacyPanel = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
            legacyPanel.child(LumaUi.caption(Component.translatable("luma.dashboard.secondary_projects")));
            for (DashboardProjectItem item : extras) {
                legacyPanel.child(this.projectRow(item));
            }
            content.child(legacyPanel);
        }

        shell.child(content);
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private void refresh() {
        this.state = this.controller.loadState("");
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private FlowLayout workspaceCard(DashboardProjectItem item) {
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.gap(6);

        FlowLayout top = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.gap(8);
        top.child(LumaUi.value(Component.translatable("luma.dashboard.workspace_title", item.name())));
        top.child(LumaUi.chip(Component.translatable("luma.dashboard.workspace_dimension", this.dimensionLabel(item.dimensionId()))));
        card.child(top);

        FlowLayout metrics = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        metrics.gap(6);
        metrics.child(LumaUi.metric(Component.translatable("luma.dashboard.metric_branch"), Component.literal(item.activeVariantId())));
        metrics.child(LumaUi.metric(Component.translatable("luma.dashboard.metric_versions"), Component.literal(Integer.toString(item.versionCount()))));
        metrics.child(LumaUi.metric(Component.translatable("luma.dashboard.metric_pending"), Component.literal(Integer.toString(item.draftChangeCount()))));
        metrics.child(LumaUi.metric(Component.translatable("luma.dashboard.metric_branches"), Component.literal(Integer.toString(item.branchCount()))));
        card.child(metrics);

        if (item.versionCount() == 0) {
            card.child(LumaUi.caption(Component.translatable("luma.dashboard.workspace_empty_versions")));
        } else if (item.hasDraft()) {
            card.child(LumaUi.accent(Component.translatable("luma.dashboard.workspace_pending", item.draftChangeCount())));
        } else {
            card.child(LumaUi.caption(Component.translatable("luma.dashboard.workspace_ready")));
        }

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.translatable("luma.action.open_project"), button -> this.router.openProject(this, item.name())));
        if (item.hasDraft()) {
            actions.child(UIComponents.button(Component.translatable("luma.action.recovery"), button -> this.router.openRecovery(this, item.name())));
        }
        actions.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, item.name())));
        card.child(actions);

        return card;
    }

    private FlowLayout projectRow(DashboardProjectItem item) {
        FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
        card.gap(4);

        FlowLayout top = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.gap(8);
        top.child(LumaUi.value(Component.literal(item.name())));
        top.child(LumaUi.chip(Component.translatable("luma.dashboard.workspace_dimension", this.dimensionLabel(item.dimensionId()))));
        top.child(LumaUi.chip(Component.translatable("luma.dashboard.project_updated", item.updatedAt())));
        card.child(top);

        card.child(LumaUi.caption(Component.translatable(
                "luma.dashboard.project_entry",
                item.activeVariantId(),
                item.versionCount(),
                item.draftChangeCount()
        )));

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.translatable("luma.action.open_project"), button -> this.router.openProject(this, item.name())));
        actions.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, item.name())));
        card.child(actions);

        FlowLayout badges = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        badges.gap(6);
        if (item.favorite()) {
            badges.child(LumaUi.chip(Component.translatable("luma.dashboard.favorite_badge")));
        }
        if (item.archived()) {
            badges.child(LumaUi.chip(Component.translatable("luma.dashboard.archived_badge")));
        }
        if (item.hasDraft()) {
            badges.child(LumaUi.chip(Component.translatable("luma.recovery.badge")));
        }
        card.child(badges);
        return card;
    }

    private List<DashboardProjectItem> visibleProjects() {
        return this.state.projects().stream()
                .filter(item -> !item.archived())
                .toList();
    }

    private DashboardProjectItem primaryWorkspace(List<DashboardProjectItem> items) {
        for (DashboardProjectItem item : items) {
            if (item.worldWorkspace()) {
                return item;
            }
        }
        return items.isEmpty() ? null : items.getFirst();
    }

    private String dimensionLabel(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> "Overworld";
        };
    }
}
