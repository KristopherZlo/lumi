package io.github.luma.ui.screen;

import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectUiSupport;
import io.github.luma.ui.controller.ProjectHomeScreenController;
import io.github.luma.ui.graph.CommitGraphLayout;
import io.github.luma.ui.graph.CommitGraphNode;
import io.github.luma.ui.navigation.ScreenRouter;
import io.github.luma.ui.state.ProjectHomeViewState;
import io.github.luma.ui.framework.container.FlowLayout;
import io.github.luma.ui.framework.container.UIContainers;
import io.github.luma.ui.framework.core.Insets;
import io.github.luma.ui.framework.core.LumaUIAdapter;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AdvancedScreen extends LumaScreen {

    private final Screen parent;
    private final String projectName;
    private final Minecraft client = Minecraft.getInstance();
    private final ProjectHomeScreenController controller = new ProjectHomeScreenController();
    private final ScreenRouter router = new ScreenRouter();
    private ProjectHomeViewState state;

    public AdvancedScreen(Screen parent, String projectName) {
        super(Component.translatable("luma.screen.advanced.title"));
        this.parent = parent;
        this.projectName = projectName;
    }

    @Override
    protected LumaUIAdapter<FlowLayout> createAdapter() {
        return LumaUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        this.state = this.controller.loadState(this.projectName, "luma.status.project_ready", false);

        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.advanced.title")));
        frame.child(LumaUi.caption(Component.translatable("luma.advanced.help")));

        FlowLayout body = LumaUi.screenBody();
        frame.child(LumaUi.screenScroll(body));

        if (this.state.project() == null) {
            body.child(LumaUi.emptyState(
                    Component.translatable("luma.project.unavailable"),
                    Component.translatable("luma.status.project_failed")
            ));
            return;
        }

        body.child(this.actionsSection());
        body.child(this.graphSection());
        body.child(this.rawReferencesSection());
        body.child(LumaUi.bottomSpacer());
    }

    @Override
    public void onClose() {
        this.client.setScreen(this.parent);
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
        actions.child(LumaUi.button(Component.translatable("luma.action.legacy_limited_project"), button -> this.router.openCreateProject(this)));
        section.child(actions);
        section.child(LumaUi.caption(Component.translatable("luma.advanced.legacy_project_help")));
        return section;
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
        for (CommitGraphNode node : nodes) {
            ProjectVersion version = node.version();
            FlowLayout row = LumaUi.insetSection(
                    Component.literal(this.graphPrefix(node) + " " + ProjectUiSupport.displayMessage(version)),
                    Component.translatable(
                            "luma.history.version_meta",
                            ProjectUiSupport.safeText(version.author()),
                            ProjectUiSupport.formatTimestamp(version.createdAt())
                    )
            );
            row.child(LumaUi.caption(Component.translatable("luma.advanced.raw_save_id", version.id())));
            section.child(row);
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

    private String graphPrefix(CommitGraphNode node) {
        StringBuilder builder = new StringBuilder(node.laneCount() * 2);
        for (int lane = 0; lane < node.laneCount(); lane++) {
            if (node.lane() == lane) {
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
}
