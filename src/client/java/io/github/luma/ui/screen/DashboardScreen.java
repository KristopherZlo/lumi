package io.github.luma.ui.screen;

import io.github.luma.domain.service.ProjectService;
import io.github.luma.ui.state.DashboardViewState;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DashboardScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private DashboardViewState state = new DashboardViewState(List.of(), "Use /luma create <name> <from> <to> to add a project");

    public DashboardScreen(Screen parent) {
        super(Component.literal("Luma Projects"));
        this.parent = parent;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.reload();

        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.literal("Back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.literal("Refresh"), button -> this.refresh()));
        root.child(header);

        root.child(UIComponents.label(Component.literal("Projects")).shadow(true));
        root.child(UIComponents.label(Component.literal(this.state.status())));

        FlowLayout projectList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        projectList.gap(4);

        if (this.state.projects().isEmpty()) {
            projectList.child(UIComponents.label(Component.literal("No projects found for this world.")));
        } else {
            for (var project : this.state.projects()) {
                projectList.child(UIComponents.button(
                        Component.literal(project.name() + " | volume " + project.bounds().volume()),
                        button -> this.client.setScreen(new ProjectScreen(this, project.name()))
                ));
            }
        }

        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), projectList));
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private void refresh() {
        this.reload();
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private void reload() {
        if (!this.client.hasSingleplayerServer()) {
            this.state = new DashboardViewState(List.of(), "Dashboard currently reads local singleplayer project storage only");
            return;
        }

        try {
            this.state = new DashboardViewState(
                    this.projectService.listProjects(this.client.getSingleplayerServer()),
                    "Open a project or use the commands to create, save and restore"
            );
        } catch (IOException exception) {
            this.state = new DashboardViewState(List.of(), "Failed to load projects: " + exception.getMessage());
        }
    }
}
