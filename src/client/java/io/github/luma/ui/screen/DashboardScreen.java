package io.github.luma.ui.screen;

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
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DashboardScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final Minecraft client = Minecraft.getInstance();
    private final DashboardScreenController controller = new DashboardScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private DashboardViewState state = new DashboardViewState(List.of(), "luma.status.dashboard_ready");
    private String searchQuery = "";
    private boolean onlyFavorites = false;
    private boolean showArchived = false;

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

        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.refresh"), button -> this.refresh()));
        header.child(UIComponents.button(Component.translatable("luma.action.create_project"), button -> this.router.openCreateProject(this)));
        header.child(UIComponents.button(
                Component.translatable(this.onlyFavorites ? "luma.action.favorites_only_on" : "luma.action.favorites_only_off"),
                button -> {
                    this.onlyFavorites = !this.onlyFavorites;
                    this.refresh();
                }
        ));
        header.child(UIComponents.button(
                Component.translatable(this.showArchived ? "luma.action.archived_on" : "luma.action.archived_off"),
                button -> {
                    this.showArchived = !this.showArchived;
                    this.refresh();
                }
        ));
        root.child(header);

        root.child(UIComponents.label(Component.translatable("luma.screen.dashboard.title")).shadow(true));
        root.child(UIComponents.label(Component.translatable(this.state.status())));
        root.child(UIComponents.label(Component.translatable(
                "luma.dashboard.summary",
                this.visibleProjects().size(),
                this.state.projects().size()
        )));

        FlowLayout searchRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        searchRow.gap(6);
        searchRow.child(UIComponents.label(Component.translatable("luma.dashboard.search")));
        var searchBox = UIComponents.textBox(Sizing.fill(100), this.searchQuery);
        searchBox.onChanged().subscribe(value -> {
            this.searchQuery = value;
            this.refresh();
        });
        searchRow.child(searchBox);
        root.child(searchRow);

        FlowLayout projectList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        projectList.gap(4);

        List<DashboardProjectItem> visibleProjects = this.visibleProjects();
        if (visibleProjects.isEmpty()) {
            projectList.child(UIComponents.label(Component.translatable("luma.dashboard.empty")));
        } else {
            for (var item : visibleProjects) {
                projectList.child(this.projectRow(item));
            }
        }

        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), projectList));
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

    private FlowLayout projectRow(DashboardProjectItem item) {
        FlowLayout card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        card.gap(3);
        card.child(UIComponents.label(Component.translatable(
                "luma.dashboard.project_entry",
                item.name(),
                item.activeVariantId(),
                item.versionCount()
        )));
        card.child(UIComponents.label(Component.translatable("luma.dashboard.project_updated", item.updatedAt())));

        FlowLayout actions = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        actions.gap(6);
        actions.child(UIComponents.button(Component.translatable("luma.action.open_project"), button -> this.router.openProject(this, item.name())));
        actions.child(UIComponents.button(Component.translatable("luma.action.settings"), button -> this.router.openSettings(this, item.name())));
        card.child(actions);

        FlowLayout badges = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        badges.gap(6);
        if (item.favorite()) {
            badges.child(UIComponents.label(Component.translatable("luma.dashboard.favorite_badge")));
        }
        if (item.archived()) {
            badges.child(UIComponents.label(Component.translatable("luma.dashboard.archived_badge")));
        }
        if (item.hasDraft()) {
            badges.child(UIComponents.label(Component.translatable("luma.recovery.badge")));
        }
        card.child(badges);
        return card;
    }

    private List<DashboardProjectItem> visibleProjects() {
        return this.state.projects().stream()
                .filter(item -> this.showArchived || !item.archived())
                .filter(item -> !this.onlyFavorites || item.favorite())
                .filter(item -> this.searchQuery == null
                        || this.searchQuery.isBlank()
                        || item.name().toLowerCase(Locale.ROOT).contains(this.searchQuery.toLowerCase(Locale.ROOT)))
                .toList();
    }
}
