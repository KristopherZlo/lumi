package io.github.lumi.client.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** One project sidebar page rendered inside its retained Dashboard shell. */
abstract class LumiPageScreen extends LumiModalScreen {
    private final Screen parent;
    private final LumiPageSession pageSession;

    protected LumiPageScreen(
            Screen parent, Component title, ProjectTab tab) {
        this(parent, title, tab, ((LumiPageScreen) parent).pageSession);
    }

    protected LumiPageScreen(
            Screen parent, Component title, ProjectTab tab,
            LumiPageSession pageSession) {
        super(parent, title);
        this.parent = parent;
        this.pageSession = pageSession;
        LumiDashboardScreen dashboard = dashboardParent();
        if (dashboard != null) {
            dashboard.selectTab(tab);
        }
    }

    protected final LumiPageLayout pageLayout() {
        return LumiPageLayout.fit(width, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            return true;
        }
        LumiPageLayout layout = pageLayout();
        double x = virtualCoordinate(click.x());
        double y = virtualCoordinate(click.y());
        LumiDashboardScreen dashboard = dashboardParent();
        return dashboard != null
                && x >= layout.windowX() && x < layout.contentX()
                && y >= layout.windowY()
                && y < layout.windowY() + layout.windowHeight()
                && dashboard.mouseClicked(click, doubled);
    }

    protected final LumiPageSession pageSession() {
        return pageSession;
    }

    private LumiDashboardScreen dashboardParent() {
        if (parent instanceof LumiDashboardScreen dashboard) return dashboard;
        return parent instanceof LumiPageScreen page
                ? page.dashboardParent() : null;
    }

    @Override
    protected boolean pointerHovered(int mouseX, int mouseY) {
        if (super.pointerHovered(mouseX, mouseY)) return true;
        LumiPageLayout layout = pageLayout();
        LumiDashboardScreen dashboard = dashboardParent();
        return dashboard != null
                && mouseX >= layout.windowX() && mouseX < layout.contentX()
                && mouseY >= layout.windowY()
                && mouseY < layout.windowY() + layout.windowHeight()
                && dashboard.pointerHovered(mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (parent instanceof LumiDashboardScreen dashboard) {
            dashboard.selectTab(ProjectTab.HISTORY);
        }
        minecraft.setScreen(parent);
    }
}
