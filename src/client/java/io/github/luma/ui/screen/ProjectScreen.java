package io.github.luma.ui.screen;

import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VersionRepository;
import io.github.luma.ui.state.ProjectViewState;
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

public final class ProjectScreen extends BaseOwoScreen<FlowLayout> {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectService projectService = new ProjectService();
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private ProjectViewState state = new ProjectViewState(null, List.of(), "Loading...");

    public ProjectScreen(Screen parent, String projectName) {
        super(Component.literal(projectName));
        this.parent = parent;
        this.projectName = projectName;
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
        header.child(UIComponents.button(Component.literal("Save version"), button -> this.sendCommand("luma save " + this.quoted(this.projectName) + " \"Manual save\"")));
        header.child(UIComponents.button(Component.literal("Restore latest"), button -> this.sendCommand("luma restore " + this.quoted(this.projectName))));
        header.child(UIComponents.button(Component.literal("Refresh"), button -> this.refresh()));
        root.child(header);

        root.child(UIComponents.label(Component.literal("Project: " + this.projectName)).shadow(true));
        root.child(UIComponents.label(Component.literal(this.state.status())));

        if (this.state.project() != null) {
            BuildProject project = this.state.project();
            root.child(UIComponents.label(Component.literal(
                    "Bounds: " + project.bounds().min().x() + "," + project.bounds().min().y() + "," + project.bounds().min().z()
                            + " -> " + project.bounds().max().x() + "," + project.bounds().max().y() + "," + project.bounds().max().z()
            )));
        }

        FlowLayout versionList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        versionList.gap(4);

        if (this.state.versions().isEmpty()) {
            versionList.child(UIComponents.label(Component.literal("No versions saved yet.")));
        } else {
            for (var version : this.state.versions()) {
                versionList.child(UIComponents.button(
                        Component.literal(version.id() + " | " + version.message()),
                        button -> this.sendCommand("luma restore " + this.quoted(this.projectName) + " " + version.id())
                ));
            }
        }

        root.child(UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), versionList));
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
            this.state = new ProjectViewState(null, List.of(), "Project view currently requires the integrated server");
            return;
        }

        try {
            var layout = this.projectService.resolveLayout(this.client.getSingleplayerServer(), this.projectName);
            this.state = new ProjectViewState(
                    this.projectRepository.load(layout).orElse(null),
                    this.versionRepository.loadAll(layout),
                    "Use buttons above or /luma commands for manual workflows"
            );
        } catch (IOException exception) {
            this.state = new ProjectViewState(null, List.of(), "Failed to load project: " + exception.getMessage());
        }
    }

    private void sendCommand(String command) {
        if (this.client.player == null || this.client.player.connection == null) {
            return;
        }

        this.client.player.connection.sendCommand(command);
    }

    private String quoted(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
