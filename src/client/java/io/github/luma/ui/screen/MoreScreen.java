package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MoreScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ScreenRouter router = new ScreenRouter();
    private final ProjectHomeScreenController controller = new ProjectHomeScreenController();
    private final ProjectSidebarNavigation sidebarNavigation = new ProjectSidebarNavigation();
    private ProjectHomeViewState state;

    public MoreScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.more.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, "luma.status.project_ready", false);

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

        ProjectWindowLayout window = ProjectWindowLayout.forProject(
                this.width,
                Component.translatable("luma.screen.more.title"),
                this.state.project(),
                this.state.variants()
        );
        root.child(window.root());
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.MORE, this::onClose);
        window.content().child(LumaUi.caption(Component.translatable("luma.more.help")));
        FlowLayout body = LumaUi.screenBody();
        window.content().child(LumaUi.screenScroll(body));

        body.child(this.navigationCard(
                "luma.more.projects_title",
                "luma.more.projects_help",
                "luma.action.open_projects",
                button -> this.router.openDashboard(this)
        ));
        body.child(this.navigationCard(
                "luma.more.import_export_title",
                "luma.more.import_export_help",
                "luma.action.open_import_export",
                button -> this.router.openShare(this, this.projectName)
        ));
        body.child(this.navigationCard(
                "luma.more.settings_title",
                "luma.more.settings_help",
                "luma.action.settings",
                button -> this.router.openSettings(this, this.projectName)
        ));
        body.child(this.navigationCard(
                "luma.more.cleanup_title",
                "luma.more.cleanup_help",
                "luma.action.open_cleanup",
                button -> this.router.openCleanup(this, this.projectName)
        ));
        body.child(this.navigationCard(
                "luma.more.diagnostics_title",
                "luma.more.diagnostics_help",
                "luma.action.open_diagnostics",
                button -> this.router.openDiagnostics(this, this.projectName)
        ));
        body.child(this.navigationCard(
                "luma.more.advanced_title",
                "luma.more.advanced_help",
                "luma.action.open_advanced",
                button -> this.router.openAdvanced(this, this.projectName)
        ));
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    private FlowLayout navigationCard(
            String titleKey,
            String helpKey,
            String buttonKey,
            java.util.function.Consumer<io.wispforest.owo.ui.component.ButtonComponent> action
    ) {
        FlowLayout card = LumaUi.sectionCard(
                Component.translatable(titleKey),
                Component.translatable(helpKey)
        );
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable(buttonKey), action));
        card.child(actions);
        return card;
    }
}
