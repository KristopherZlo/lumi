package io.github.luma.ui.navigation;

import io.github.luma.LumaMod;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectWindowLayout;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextureComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import java.net.URI;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

public final class ProjectSidebarNavigation {

    private static final URI SUPPORT_URI = URI.create("https://buymeacoffee.com/zl0yxp");
    private static final Identifier SUPPORT_LOGO = Identifier.fromNamespaceAndPath(
            LumaMod.MOD_ID,
            "textures/gui/buymeacoffee.png"
    );

    private final ScreenRouter router = new ScreenRouter();

    public void attach(
            ProjectWindowLayout window,
            Screen currentScreen,
            String projectName,
            ProjectWorkspaceTab activeTab,
            Runnable onBack
    ) {
        FlowLayout tabs = LumaUi.sidebarTabs();
        this.addTab(tabs, Component.translatable("luma.tab.history"), ProjectWorkspaceTab.HISTORY, activeTab, () ->
                this.router.openProjectIgnoringRecovery(currentScreen, projectName));
        this.addTab(tabs, Component.translatable("luma.tab.variants"), ProjectWorkspaceTab.VARIANTS, activeTab, () ->
                this.router.openVariants(currentScreen, projectName));
        this.addTab(tabs, Component.translatable("luma.tab.import_export"), ProjectWorkspaceTab.IMPORT_EXPORT, activeTab, () ->
                this.router.openShare(currentScreen, projectName));
        this.addTab(tabs, Component.translatable("luma.action.settings"), ProjectWorkspaceTab.SETTINGS, activeTab, () ->
                this.router.openSettings(currentScreen, projectName));
        this.addTab(tabs, Component.translatable("luma.action.more"), ProjectWorkspaceTab.MORE, activeTab, () ->
                this.router.openMore(currentScreen, projectName));
        tabs.child(LumaUi.sidebarTab(Component.translatable("luma.action.back"), false, button -> onBack.run()));

        window.sidebar().child(tabs);
        window.sidebar().child(LumaUi.sidebarSpacer());
        window.sidebar().child(this.supportFooter());
    }

    private void addTab(
            FlowLayout tabs,
            Component label,
            ProjectWorkspaceTab tab,
            ProjectWorkspaceTab activeTab,
            Runnable action
    ) {
        tabs.child(LumaUi.sidebarTab(label, tab == activeTab, button -> action.run()));
    }

    private FlowLayout supportFooter() {
        FlowLayout footer = LumaUi.sidebarFooter();
        footer.child(LumaUi.caption(Component.translatable("luma.window.support")));

        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        TextureComponent logo = UIComponents.texture(SUPPORT_LOGO, 0, 0, 16, 16, 16, 16);
        logo.blend(true);
        logo.sizing(Sizing.fixed(16), Sizing.fixed(16));
        row.child(logo);

        ButtonComponent supportButton = LumaUi.button(
                Component.translatable("luma.action.buy_me_a_coffee"),
                button -> Util.getPlatform().openUri(SUPPORT_URI)
        );
        supportButton.sizing(Sizing.expand(100), Sizing.fixed(18));
        row.child(supportButton);
        footer.child(row);
        return footer;
    }
}
