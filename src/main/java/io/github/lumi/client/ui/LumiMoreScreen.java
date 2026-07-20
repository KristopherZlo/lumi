package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private int actionTop;
    private int actionBottom;
    private int actionScroll;
    private int maximumActionScroll;
    private List<PlacedCategory> placedCategories = List.of();

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
        actionBottom = panelY + panelHeight - 12;
        addCategories(List.of(
                new MoreCategory("luma.more.category_history", List.of(
                        new MoreAction("luma.action.dimensions", dimensions),
                        new MoreAction("luma.more.deleted_saves_title", deletedVersions),
                        new MoreAction("luma.action.manual_compare", manualCompare))),
                new MoreCategory("luma.more.category_guides", List.of(
                        new MoreAction("luma.more.onboarding_title", onboarding),
                        new MoreAction("luma.hotkeys.title", hotkeys),
                        new MoreAction("luma.more.special_thanks_title", thanks))),
                new MoreCategory("luma.more.category_maintenance", List.of(
                        new MoreAction("luma.action.open_diagnostics", diagnostics),
                        new MoreAction("luma.action.check_updates", updates),
                        new MoreAction("luma.action.open_cleanup", cleanup),
                        new MoreAction("luma.action.reset_contextual_hints",
                                this::resetContextualHints)))));
    }

    private void addCategories(List<MoreCategory> categories) {
        int left = panelX + 16;
        int right = panelX + panelWidth - 16;
        int y = actionTop;
        List<PlacedCategory> placed = new ArrayList<>(categories.size());
        for (MoreCategory category : categories) {
            int height = 28 + category.actions().size() * 22;
            List<PlacedAction> actions = new ArrayList<>(category.actions().size());
            int buttonY = y + 22;
            for (MoreAction action : category.actions()) {
                actions.add(new PlacedAction(
                        action, Component.translatable(action.key()),
                        left + 8, buttonY, right - left - 16));
                buttonY += 22;
            }
            placed.add(new PlacedCategory(category.key(), y, height, actions));
            y += height + 6;
        }
        placedCategories = List.copyOf(placed);
        maximumActionScroll = requiredScroll(y - 6, actionBottom);
        actionScroll = Math.min(actionScroll, maximumActionScroll);
        for (PlacedCategory category : placedCategories) {
            for (PlacedAction item : category.actions()) {
                int renderedY = item.y() - actionScroll;
                if (renderedY < actionTop || renderedY + 18 > actionBottom) continue;
                addLegacyButton(item.x(), renderedY, item.width(), item.label(),
                        item.action().callback(), LumiLegacyButton.Kind.NORMAL);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
        renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
        renderLegacyPanel(graphics, panelX + 12, panelY + 50,
                panelWidth - 24, panelHeight - 62);
        renderPageHeader(graphics, panelX, panelY, panelWidth, title,
                Component.translatable("luma.more.help"));
        graphics.enableScissor(
                panelX + 12, actionTop, panelX + panelWidth - 12, actionBottom);
        for (PlacedCategory category : placedCategories) {
            int y = category.y() - actionScroll;
            renderLegacyPanel(graphics, panelX + 16, y,
                    panelWidth - 32, category.height());
            graphics.drawString(font, Component.translatable(category.key()),
                    panelX + 24, y + 7, LegacyLumiTheme.ACCENT, false);
        }
        graphics.disableScissor();
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    static int requiredScroll(int contentBottom, int viewportBottom) {
        return Math.max(0, contentBottom - viewportBottom);
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
                    actionScroll + (verticalAmount < 0 ? 24 : -24)));
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
    private record MoreCategory(String key, List<MoreAction> actions) { }
    private record PlacedAction(
            MoreAction action, Component label, int x, int y, int width) { }
    private record PlacedCategory(
            String key, int y, int height, List<PlacedAction> actions) { }
}
