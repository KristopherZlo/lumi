package io.github.luma.ui.screen;

import io.github.luma.ui.LumaUi;
import io.github.luma.ui.navigation.ScreenRouter;
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
        root.surface(LumaUi.screenBackdrop());
        root.padding(Insets.of(10));
        root.gap(0);

        FlowLayout frame = LumaUi.screenFrame();
        root.child(frame);

        FlowLayout header = LumaUi.actionRow();
        header.child(LumaUi.button(Component.translatable("luma.action.back"), button -> this.onClose()));
        frame.child(header);

        frame.child(LumaUi.value(Component.translatable("luma.screen.more.title")));
        frame.child(LumaUi.caption(Component.translatable("luma.more.help")));

        FlowLayout body = LumaUi.screenBody();
        frame.child(LumaUi.screenScroll(body));

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
