package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** Secondary client-only tools kept away from the main history workflow. */
public final class LumiMoreScreen extends LumiLegacyPageScreen {
    private static final Identifier COFFEE_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/buymeacoffee.png");
    private static final Identifier PAYPAL_ICON = Identifier.fromNamespaceAndPath(
            LumiMod.MOD_ID, "textures/gui/paypal.png");
    private final Runnable workspaces;
    private final Runnable deletedVersions;
    private final Runnable onboarding;
    private final Runnable hotkeys;
    private final Runnable thanks;
    private final Runnable diagnostics;
    private final Runnable settings;
    private final Runnable updates;
    private final Runnable cleanup;
    private final Runnable manualCompare;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int coffeeX;
    private int paypalX;
    private int supportY;
    private int creditY;

    public LumiMoreScreen(
            Screen parent,
            Runnable workspaces,
            Runnable deletedVersions,
            Runnable onboarding,
            Runnable hotkeys,
            Runnable thanks,
            Runnable diagnostics,
            Runnable settings,
            Runnable updates,
            Runnable cleanup,
            Runnable manualCompare) {
        super(parent, Component.translatable("luma.screen.more.title"),
                LegacyProjectTab.MORE);
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.deletedVersions = Objects.requireNonNull(
                deletedVersions, "deletedVersions");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.hotkeys = Objects.requireNonNull(hotkeys, "hotkeys");
        this.thanks = Objects.requireNonNull(thanks, "thanks");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.manualCompare = Objects.requireNonNull(manualCompare, "manualCompare");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        button("luma.action.workspaces", workspaces, 0, 0);
        button("luma.more.deleted_saves_title", deletedVersions, 1, 0);
        button("luma.more.onboarding_title", onboarding, 0, 1);
        button("luma.hotkeys.title", hotkeys, 1, 1);
        button("luma.more.special_thanks_title", thanks, 0, 2);
        button("luma.action.open_diagnostics", diagnostics, 1, 2);
        button("luma.action.settings", settings, 0, 3);
        button("luma.action.check_updates", updates, 1, 3);
        button("luma.action.open_cleanup", cleanup, 0, 4);
        button("luma.action.manual_compare", manualCompare, 1, 4);
        coffeeX = button("luma.action.buy_me_a_coffee", () -> Util.getPlatform().openUri(
                java.net.URI.create("https://buymeacoffee.com/zl0yxp")), 0, 5);
        paypalX = button("luma.action.paypal_donate", () -> Util.getPlatform().openUri(
                java.net.URI.create("https://www.paypal.com/donate/?hosted_button_id=CY7A2U64JWY4W")), 1, 5);
        supportY = panelY + 58 + 5 * 28;
        creditY = supportY + 31;
    }

    private int button(String key, Runnable action, int column, int row) {
        int gap = 4;
        int width = (panelWidth - 36) / 2;
        int x = panelX + 16 + column * (width + gap);
        addLegacyButton(x, panelY + 58 + row * 28, width,
                Component.translatable(key), action, LumiLegacyButton.Kind.NORMAL);
        return x;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        renderLegacyPanel(graphics, panelX + 12, panelY + 50,
                panelWidth - 24, panelHeight - 62);
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.more.help"),
                panelX + 16, panelY + 40, LegacyLumiTheme.MUTED, false);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        drawSupportIcon(graphics, COFFEE_ICON, coffeeX + 3, supportY + 1);
        drawSupportIcon(graphics, PAYPAL_ICON, paypalX + 3, supportY + 1);
        String version = FabricLoader.getInstance().getModContainer(LumiMod.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        graphics.drawString(font, Component.translatable("luma.window.credit"),
                panelX + 16, creditY, LegacyLumiTheme.MUTED, false);
        graphics.drawString(font, Component.translatable("luma.window.mod_version", version),
                panelX + 16, creditY + 11, LegacyLumiTheme.MUTED, false);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private static void drawSupportIcon(
            GuiGraphics graphics, Identifier icon, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon,
                x, y, 0, 0, 16, 16, 16, 16, 16, 16);
    }

    @Override public boolean isPauseScreen() { return false; }
}
