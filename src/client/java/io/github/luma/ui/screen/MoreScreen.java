package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.graph.CommitGraphComponent;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.graph.CommitGraphNode;
import io.github.luma.ui.navigation.ProjectSidebarNavigation;
import io.github.luma.ui.navigation.ProjectWorkspaceTab;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import java.util.List;
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
    private List<ProjectVersion> deletedVersions = List.of();
    private MoreTab activeTab = MoreTab.PROJECT_TOOLS;

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
        this.deletedVersions = this.controller.loadDeletedVersions(this.projectName);

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
        this.sidebarNavigation.attach(window, this, this.projectName, ProjectWorkspaceTab.MORE);
        window.content().child(LumaUi.caption(Component.translatable("luma.more.help")));
        FlowLayout body = LumaUi.screenBody();
        window.content().child(LumaUi.screenScroll(body));

        body.child(this.tabRow());
        if (this.activeTab == MoreTab.PROJECT_TOOLS) {
            body.child(this.navigationCard(
                    "luma.more.cleanup_title",
                    "luma.more.cleanup_help",
                    "luma.action.open_cleanup",
                    button -> this.router.openCleanup(this, this.projectName)
            ));
            body.child(this.actionsSection());
            body.child(this.graphSection());
            body.child(this.rawReferencesSection());
        } else {
            body.child(this.deletedSavesSection());
        }
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
    }

    @Override
    public Screen navigationParent() {
        return this.parent;
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

    private FlowLayout actionsSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.advanced.actions_title"),
                Component.translatable("luma.advanced.actions_help")
        );
        FlowLayout actions = LumaUi.actionRow();
        actions.child(LumaUi.button(Component.translatable("luma.action.manual_compare"), button -> this.router.openCompare(
                this,
                this.projectName,
                "",
                ""
        )));
        section.child(actions);
        return section;
    }

    private FlowLayout tabRow() {
        FlowLayout row = LumaUi.actionRow();
        ButtonComponent tools = LumaUi.button(Component.translatable("luma.more.tab_project_tools"), button -> {
            this.activeTab = MoreTab.PROJECT_TOOLS;
            this.rebuild();
        });
        tools.active(this.activeTab != MoreTab.PROJECT_TOOLS);
        row.child(tools);

        ButtonComponent deleted = LumaUi.button(Component.translatable("luma.more.deleted_saves_title"), button -> {
            this.activeTab = MoreTab.DELETED_SAVES;
            this.rebuild();
        });
        deleted.active(this.activeTab != MoreTab.DELETED_SAVES);
        row.child(deleted);
        return row;
    }

    private FlowLayout graphSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.advanced.history_graph_title"),
                Component.translatable("luma.advanced.history_graph_help")
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
        section.child(new CommitGraphComponent(
                nodes,
                this.state.variants(),
                versionId -> this.router.openSaveDetails(this, this.projectName, versionId)
        ));
        return section;
    }

    private FlowLayout deletedSavesSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.more.deleted_saves_title"),
                Component.translatable("luma.more.deleted_saves_help")
        );
        if (this.deletedVersions.isEmpty()) {
            section.child(LumaUi.caption(Component.translatable("luma.more.deleted_saves_empty")));
            return section;
        }

        for (ProjectVersion version : this.deletedVersions) {
            FlowLayout card = LumaUi.insetPanel(Sizing.fill(100), Sizing.content());
            card.child(LumaUi.value(Component.literal(ProjectUiSupport.displayMessage(version))));
            card.child(LumaUi.caption(Component.translatable(
                    "luma.history.version_meta",
                    ProjectUiSupport.safeText(version.author()),
                    ProjectUiSupport.formatTimestamp(version.createdAt())
            )));
            card.child(LumaUi.caption(Component.translatable(
                    "luma.save_details.raw_info_type",
                    Component.translatable(ProjectUiSupport.versionKindKey(version.versionKind()))
            )));
            card.child(LumaUi.caption(Component.translatable("luma.advanced.raw_save_id", version.id())));
            section.child(card);
        }
        return section;
    }

    private FlowLayout rawReferencesSection() {
        FlowLayout section = LumaUi.sectionCard(
                Component.translatable("luma.advanced.raw_refs_title"),
                Component.translatable("luma.advanced.raw_refs_help")
        );
        section.child(LumaUi.caption(Component.translatable("luma.advanced.raw_project_name", this.state.project().name())));
        section.child(LumaUi.caption(Component.translatable("luma.advanced.raw_active_idea", this.state.project().activeVariantId())));
        for (ProjectVersion version : this.state.versions().stream().limit(8).toList()) {
            section.child(LumaUi.caption(Component.translatable("luma.advanced.raw_save_id", version.id())));
        }
        return section;
    }

    private void rebuild() {
        this.uiAdapter.rootComponent.clearChildren();
        this.build(this.uiAdapter.rootComponent);
        this.uiAdapter.inflateAndMount();
    }

    private enum MoreTab {
        PROJECT_TOOLS,
        DELETED_SAVES
    }
}
