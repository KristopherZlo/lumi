package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Secondary client-only tools kept away from the main history workflow. */
public final class LumiMoreScreen extends LumiLegacyPageScreen {
    private final Runnable dimensions;
    private final Runnable deletedVersions;
    private final Runnable onboarding;
    private final Runnable hotkeys;
    private final Runnable thanks;
    private final Runnable diagnostics;
    private final Runnable updates;
    private final Runnable cleanup;
    private final Runnable manualCompare;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int creditY;
    private int actionX;
    private int actionY;
    private int actionRight;

    public LumiMoreScreen(
            Screen parent,
            Runnable dimensions,
            Runnable deletedVersions,
            Runnable onboarding,
            Runnable hotkeys,
            Runnable thanks,
            Runnable diagnostics,
            Runnable updates,
            Runnable cleanup,
            Runnable manualCompare) {
        super(parent, Component.translatable("luma.screen.more.title"),
                LegacyProjectTab.MORE);
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.deletedVersions = Objects.requireNonNull(
                deletedVersions, "deletedVersions");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.hotkeys = Objects.requireNonNull(hotkeys, "hotkeys");
        this.thanks = Objects.requireNonNull(thanks, "thanks");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
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
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.MORE,
                panelX + 12, panelY + 50, panelWidth - 24);
        actionX = panelX + 16;
        actionY = panelY + 58
                + (hintVisible ? contextualHintOffset(8) : 0);
        actionRight = panelX + panelWidth - 16;
        button("luma.action.dimensions", dimensions);
        button("luma.more.deleted_saves_title", deletedVersions);
        button("luma.more.onboarding_title", onboarding);
        button("luma.hotkeys.title", hotkeys);
        button("luma.more.special_thanks_title", thanks);
        button("luma.action.open_diagnostics", diagnostics);
        button("luma.action.check_updates", updates);
        button("luma.action.open_cleanup", cleanup);
        button("luma.action.manual_compare", manualCompare);
        button("luma.action.reset_contextual_hints", this::resetContextualHints);
        creditY = panelY + panelHeight - 32;
    }

    private void button(String key, Runnable action) {
        Component label = Component.translatable(key);
        int width = Math.min(actionRight - panelX - 16,
                Math.max(18, font.width(label) + 12));
        if (actionX > panelX + 16 && actionX + width > actionRight) {
            actionX = panelX + 16;
            actionY += 24;
        }
        addLegacyButton(actionX, actionY, width, label,
                action, LumiLegacyButton.Kind.NORMAL);
        actionX += width + 4;
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

    @Override public boolean isPauseScreen() { return false; }
}
