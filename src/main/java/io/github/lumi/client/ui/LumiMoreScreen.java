package io.github.lumi.client.ui;

import io.github.lumi.LumiMod;
import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import java.util.ArrayList;
import java.util.List;
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
    private int actionTop;
    private int actionBottom;
    private int actionScroll;
    private int maximumActionScroll;

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
        boolean hintVisible = supportsContextualHint(panelHeight)
                && addContextualHint(
                        ClientContextualHelpHint.MORE,
                        panelX + 12, panelY + 50, panelWidth - 24);
        actionTop = panelY + 58
                + (hintVisible ? contextualHintOffset(8) : 0);
        creditY = panelY + panelHeight - 32;
        actionBottom = creditY - 4;
        addActions(List.of(
                new MoreAction("luma.action.dimensions", dimensions),
                new MoreAction("luma.more.deleted_saves_title", deletedVersions),
                new MoreAction("luma.more.onboarding_title", onboarding),
                new MoreAction("luma.hotkeys.title", hotkeys),
                new MoreAction("luma.more.special_thanks_title", thanks),
                new MoreAction("luma.action.open_diagnostics", diagnostics),
                new MoreAction("luma.action.check_updates", updates),
                new MoreAction("luma.action.open_cleanup", cleanup),
                new MoreAction("luma.action.manual_compare", manualCompare),
                new MoreAction("luma.action.reset_contextual_hints",
                        this::resetContextualHints)));
    }

    private void addActions(List<MoreAction> actions) {
        int left = panelX + 16;
        int right = panelX + panelWidth - 16;
        int x = left;
        int y = actionTop;
        List<PlacedAction> placed = new ArrayList<>(actions.size());
        for (MoreAction action : actions) {
            Component label = Component.translatable(action.key());
            int width = LumiLegacyButton.contentWidth(right - left, label);
            if (x > left && x + width > right) {
                x = left;
                y += 24;
            }
            placed.add(new PlacedAction(action, label, x, y, width));
            x += width + 4;
        }
        maximumActionScroll = requiredScrollRows(y + 18, actionBottom);
        actionScroll = Math.min(actionScroll, maximumActionScroll);
        for (PlacedAction item : placed) {
            int renderedY = item.y() - actionScroll * 24;
            if (renderedY < actionTop || renderedY + 18 > actionBottom) continue;
            addLegacyButton(item.x(), renderedY, item.width(), item.label(),
                    item.action().callback(), LumiLegacyButton.Kind.NORMAL);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        renderLegacyPanel(graphics, panelX + 12, panelY + 50,
                panelWidth - 24, panelHeight - 62);
        int textX = panelX + 16;
        int textWidth = Math.max(1, panelWidth - 32);
        graphics.drawString(font, clippedHeader(
                        title, textX, panelX + panelWidth - 16),
                textX, panelY + 18,
                LegacyLumiTheme.TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("luma.more.help").getString(), textWidth),
                textX, panelY + 40, LegacyLumiTheme.MUTED, false);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        String version = FabricLoader.getInstance().getModContainer(LumiMod.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("luma.window.credit").getString(), textWidth),
                textX, creditY, LegacyLumiTheme.MUTED, false);
        graphics.drawString(font, font.plainSubstrByWidth(
                        Component.translatable("luma.window.mod_version", version).getString(),
                        textWidth),
                textX, creditY + 11, LegacyLumiTheme.MUTED, false);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    static int requiredScrollRows(int contentBottom, int viewportBottom) {
        int overflow = Math.max(0, contentBottom - viewportBottom);
        return (overflow + 23) / 24;
    }

    static boolean supportsContextualHint(int panelHeight) {
        return panelHeight >= 200;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= panelX && x < panelX + panelWidth
                && y >= actionTop && y < actionBottom) {
            int replacement = Math.max(0, Math.min(maximumActionScroll,
                    actionScroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != actionScroll) {
                actionScroll = replacement;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private record MoreAction(String key, Runnable callback) { }
    private record PlacedAction(
            MoreAction action, Component label, int x, int y, int width) { }
}
