package io.github.lumi.client.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** One project sidebar page rendered inside its retained Dashboard shell. */
abstract class LumiLegacyPageScreen extends LumiLegacyModalScreen {
    private final Screen parent;

    protected LumiLegacyPageScreen(
            Screen parent, Component title, LegacyProjectTab tab) {
        super(parent, title);
        this.parent = parent;
        if (parent instanceof LumiDashboardScreen dashboard) {
            dashboard.selectTab(tab);
        }
    }

    protected final LegacyWorkspaceLayout pageLayout() {
        return LegacyWorkspaceLayout.fit(width, height);
    }

    @Override
    protected final boolean forwardsParentInput() {
        return true;
    }

    @Override
    public void onClose() {
        if (parent instanceof LumiDashboardScreen dashboard) {
            dashboard.selectTab(LegacyProjectTab.HISTORY);
        }
        minecraft.setScreen(parent);
    }
}
