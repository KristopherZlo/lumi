package io.github.luma.ui.screen;

import io.github.luma.ui.controller.DashboardScreenController;
import io.github.luma.ui.navigation.ScreenRouter;
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

        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(10));
        root.gap(8);

        FlowLayout header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        header.gap(6);
        header.child(UIComponents.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        header.child(UIComponents.button(Component.translatable("luma.action.refresh"), button -> this.refresh()));
        root.child(header);

        root.child(UIComponents.label(Component.translatable("luma.screen.dashboard.title")).shadow(true));
        root.child(UIComponents.label(Component.translatable(this.state.status())));

        FlowLayout projectList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content());
        projectList.gap(4);

        if (this.state.projects().isEmpty()) {
            projectList.child(UIComponents.label(Component.translatable("luma.dashboard.empty")));
        } else {
            for (var item : this.state.projects()) {
                FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
                row.gap(6);
                row.child(UIComponents.button(
                        Component.literal(item.name() + " | " + item.activeVariantId() + " | " + item.versionCount()),
                        button -> this.router.openProject(this, item.name())
                ));
                if (item.hasDraft()) {
                    row.child(UIComponents.label(Component.translatable("luma.recovery.badge")));
                }
                projectList.child(row);
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
}
