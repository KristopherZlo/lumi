package io.github.lumi.client.ui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Secondary client-only tools kept away from the main history workflow. */
public final class LumiMoreScreen extends LumiLegacyPageScreen {
    private final Runnable onboarding;
    private final Runnable hotkeys;
    private final Runnable thanks;
    private final Runnable diagnostics;
    private final Runnable settings;
    private final Runnable updates;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public LumiMoreScreen(
            Screen parent,
            Runnable onboarding,
            Runnable hotkeys,
            Runnable thanks,
            Runnable diagnostics,
            Runnable settings,
            Runnable updates) {
        super(parent, Component.translatable("luma.screen.more.title"),
                LegacyProjectTab.MORE);
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.hotkeys = Objects.requireNonNull(hotkeys, "hotkeys");
        this.thanks = Objects.requireNonNull(thanks, "thanks");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.updates = Objects.requireNonNull(updates, "updates");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        button("luma.more.onboarding_title", onboarding, 66);
        button("luma.hotkeys.title", hotkeys, 100);
        button("luma.more.special_thanks_title", thanks, 134);
        button("luma.action.open_diagnostics", diagnostics, 168);
        button("luma.action.settings", settings, 202);
        button("luma.action.check_updates", updates, 236);
        button("luma.action.close", this::onClose, 270);
    }

    private void button(String key, Runnable action, int offset) {
        addLegacyButton(panelX + 16, panelY + offset, panelWidth - 32,
                Component.translatable(key), action, LumiLegacyButton.Kind.NORMAL);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        renderLegacyPanel(graphics, panelX + 12, panelY + 58,
                panelWidth - 24, 206);
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.more.help"),
                panelX + 16, panelY + 40, LegacyLumiTheme.MUTED, false);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
