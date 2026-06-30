package io.github.luma.ui.navigation;

import io.github.luma.LumaMod;
import io.github.luma.ui.LumaUi;
import io.github.luma.ui.ProjectWindowLayout;
import io.github.luma.ui.screen.LumaScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.TextureComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Sizing;
import java.net.URI;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

public final class ProjectSidebarNavigation {

    private static final URI SUPPORT_URI = URI.create("https://buymeacoffee.com/zl0yxp");
    private static final URI PAYPAL_DONATE_URI = URI.create("https://www.paypal.com/donate/?hosted_button_id=CY7A2U64JWY4W");
    private static final Identifier SUPPORT_LOGO = Identifier.fromNamespaceAndPath(
            LumaMod.MOD_ID,
            "textures/gui/buymeacoffee.png"
    );
    private static final Identifier PAYPAL_LOGO = Identifier.fromNamespaceAndPath(
            LumaMod.MOD_ID,
            "textures/gui/paypal.png"
    );

    private final ScreenRouter router = new ScreenRouter();

    public void attach(
            ProjectWindowLayout window,
            Screen currentScreen,
            String projectName,
            ProjectWorkspaceTab activeTab
    ) {
        this.attach(window, currentScreen, projectName, activeTab, null);
    }

    public void attach(
            ProjectWindowLayout window,
            Screen currentScreen,
            String projectName,
            ProjectWorkspaceTab activeTab,
            Integer activeZoneColor
    ) {
        Screen parent = this.navigationParent(currentScreen);
        FlowLayout tabs = LumaUi.sidebarTabs();
        this.addTab(tabs, Component.translatable("luma.tab.history"), ProjectWorkspaceTab.HISTORY, activeTab, activeZoneColor, () ->
                this.router.openProjectTab(parent, projectName));
        this.addTab(tabs, Component.translatable("luma.tab.zones"), ProjectWorkspaceTab.ZONES, activeTab, activeZoneColor, () ->
                this.router.openWorkZones(parent, projectName));
        this.addTab(tabs, Component.translatable("luma.tab.variants"), ProjectWorkspaceTab.VARIANTS, activeTab, activeZoneColor, () ->
                this.router.openVariants(parent, projectName));
        this.addTab(tabs, Component.translatable("luma.tab.compare"), ProjectWorkspaceTab.COMPARE, activeTab, activeZoneColor, () ->
                this.router.openProjectCompare(parent, projectName));
        this.addTab(tabs, Component.translatable("luma.tab.import_export"), ProjectWorkspaceTab.IMPORT_EXPORT, activeTab, activeZoneColor, () ->
                this.router.openShare(parent, projectName));
        this.addTab(tabs, Component.translatable("luma.action.settings"), ProjectWorkspaceTab.SETTINGS, activeTab, activeZoneColor, () ->
                this.router.openSettings(parent, projectName));
        this.addTab(tabs, Component.translatable("luma.action.more"), ProjectWorkspaceTab.MORE, activeTab, activeZoneColor, () ->
                this.router.openMore(parent, projectName));

        window.sidebar().child(tabs);
        window.sidebar().child(LumaUi.sidebarSpacer());
        window.sidebar().child(this.supportFooter());
    }

    private Screen navigationParent(Screen currentScreen) {
        if (currentScreen instanceof LumaScreen lumaScreen) {
            Screen parent = lumaScreen.navigationParent();
            if (parent != null) {
                return parent;
            }
        }
        return currentScreen;
    }

    private void addTab(
            FlowLayout tabs,
            Component label,
            ProjectWorkspaceTab tab,
            ProjectWorkspaceTab activeTab,
            Integer activeZoneColor,
            Runnable action
    ) {
        boolean zoneTab = tab == ProjectWorkspaceTab.ZONES || tab == ProjectWorkspaceTab.VARIANTS;
        tabs.child(activeZoneColor != null && zoneTab
                ? LumaUi.sidebarTab(label, tab == activeTab, activeZoneColor, button -> action.run())
                : LumaUi.sidebarTab(label, tab == activeTab, button -> action.run()));
    }

    private FlowLayout supportFooter() {
        FlowLayout footer = LumaUi.sidebarFooter();
        footer.child(LumaUi.caption(Component.translatable("luma.window.support")));
        footer.child(this.supportLinkRow(
                SUPPORT_LOGO,
                Component.translatable("luma.action.buy_me_a_coffee"),
                SUPPORT_URI
        ));
        footer.child(this.supportLinkRow(
                PAYPAL_LOGO,
                Component.translatable("luma.action.paypal_donate"),
                PAYPAL_DONATE_URI
        ));
        footer.child(LumaUi.caption(Component.translatable("luma.window.credit")));
        footer.child(LumaUi.caption(Component.translatable("luma.window.mod_version", this.currentModVersion())));
        return footer;
    }

    private FlowLayout supportLinkRow(Identifier logoId, Component label, URI uri) {
        FlowLayout row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content());
        row.gap(4);
        if (logoId != null) {
            TextureComponent logo = UIComponents.texture(logoId, 0, 0, 16, 16, 16, 16);
            logo.blend(true);
            logo.sizing(Sizing.fixed(16), Sizing.fixed(16));
            row.child(logo);
        }

        ButtonComponent supportButton = LumaUi.button(
                label,
                button -> Util.getPlatform().openUri(uri)
        );
        supportButton.sizing(Sizing.expand(100), Sizing.fixed(18));
        row.child(supportButton);
        return row;
    }

    private String currentModVersion() {
        return FabricLoader.getInstance().getModContainer(LumaMod.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
