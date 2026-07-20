package io.github.lumi.client.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** One project sidebar page rendered inside its retained Dashboard shell. */
abstract class LumiLegacyPageScreen extends LumiLegacyModalScreen {
    private final Screen parent;

    protected LumiLegacyPageScreen(
            Screen parent, Component title, LegacyProjectTab tab) {
        super(parent, title);
        this.parent = parent;
        LumiDashboardScreen dashboard = dashboardParent();
        if (dashboard != null) {
            dashboard.selectTab(tab);
        }
    }

    protected final LegacyWorkspaceLayout pageLayout() {
        return LegacyWorkspaceLayout.fit(width, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            return true;
        }
        LegacyWorkspaceLayout layout = pageLayout();
        double x = virtualCoordinate(click.x());
        double y = virtualCoordinate(click.y());
        LumiDashboardScreen dashboard = dashboardParent();
        return dashboard != null
                && x >= layout.windowX() && x < layout.contentX()
                && y >= layout.windowY()
                && y < layout.windowY() + layout.windowHeight()
                && dashboard.mouseClicked(click, doubled);
    }

    private LumiDashboardScreen dashboardParent() {
        if (parent instanceof LumiDashboardScreen dashboard) return dashboard;
        return parent instanceof LumiLegacyPageScreen page
                ? page.dashboardParent() : null;
    }

    @Override
    public void onClose() {
        if (parent instanceof LumiDashboardScreen dashboard) {
            dashboard.selectTab(LegacyProjectTab.HISTORY);
        }
        minecraft.setScreen(parent);
    }
}
