package io.github.lumi.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Loaded vanilla and mod dimension catalog; it never changes player location. */
public final class LumiDimensionsScreen extends LumiLegacyPageScreen {
    private static final int ROW_HEIGHT = 30;
    private final Supplier<List<String>> dimensions;
    private final Supplier<String> currentDimension;
    private final Consumer<String> openHistory;
    private List<String> visible = List.of();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scroll;

    public LumiDimensionsScreen(
            Screen parent,
            Supplier<List<String>> dimensions,
            Supplier<String> currentDimension,
            Consumer<String> openHistory) {
        super(parent, Component.translatable("luma.action.dimensions"),
                LegacyProjectTab.MORE);
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.currentDimension = Objects.requireNonNull(
                currentDimension, "currentDimension");
        this.openHistory = Objects.requireNonNull(openHistory, "openHistory");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        LegacyWorkspaceLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        visible = dimensions.get().stream().distinct().sorted().toList();
        int capacity = capacity();
        scroll = Math.min(scroll, Math.max(0, visible.size() - capacity));
        for (int index = 0; index < Math.min(capacity, visible.size() - scroll); index++) {
            String dimension = visible.get(scroll + index);
            addLegacyIconButton(
                    panelX + panelWidth - 48,
                    panelY + 76 + index * ROW_HEIGHT,
                    "folder", Component.translatable(
                            "luma.dimensions.open_history", dimension),
                    () -> openHistory.accept(dimension),
                    LumiLegacyButton.Kind.NORMAL);
        }
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            renderLegacyPage(graphics, panelX, panelY, panelWidth, panelHeight);
            int headerX = panelX + 16;
            int textWidth = Math.max(1, panelWidth - 32);
            graphics.drawString(font, clippedHeader(
                            title, headerX, panelX + panelWidth - 16),
                    headerX, panelY + 18,
                    LegacyLumiTheme.TEXT, false);
            graphics.drawString(font, font.plainSubstrByWidth(
                            Component.translatable("luma.dimensions.help").getString(),
                            textWidth),
                    headerX, panelY + 40, LegacyLumiTheme.MUTED, false);
            renderLegacyPanel(graphics, panelX + 12, panelY + 62,
                    panelWidth - 24, Math.max(1, panelHeight - 74));
            String current = currentDimension.get();
            int count = Math.min(capacity(), visible.size() - scroll);
            for (int index = 0; index < count; index++) {
                String dimension = visible.get(scroll + index);
                int y = panelY + 72 + index * ROW_HEIGHT;
                LegacyLumiTheme.outlined(
                        graphics, panelX + 20, y, panelWidth - 40, 26,
                        LegacyLumiTheme.INSET,
                        dimension.equals(current)
                                ? LegacyLumiTheme.ACCENT
                                : LegacyLumiTheme.INSET_BORDER);
                graphics.drawString(font,
                        font.plainSubstrByWidth(dimension, panelWidth - 100),
                        panelX + 28, y + 6, LegacyLumiTheme.TEXT, false);
                if (dimension.equals(current)) {
                    graphics.drawString(font,
                            font.plainSubstrByWidth(
                                    Component.translatable(
                                            "luma.dimensions.current").getString(),
                                    Math.max(1, panelWidth - 100)),
                            panelX + 28, y + 16, LegacyLumiTheme.ACCENT, false);
                }
            }
            if (visible.isEmpty()) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(
                                Component.translatable("luma.dimensions.empty").getString(),
                                Math.max(1, panelWidth - 48)),
                        panelX + 24, panelY + 76, LegacyLumiTheme.MUTED, false);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        double x = virtualCoordinate(mouseX);
        double y = virtualCoordinate(mouseY);
        if (x >= panelX && x < panelX + panelWidth
                && y >= panelY + 62 && y < panelY + panelHeight) {
            int maximum = Math.max(0, visible.size() - capacity());
            int replacement = Math.max(
                    0, Math.min(maximum, scroll + (verticalAmount < 0 ? 1 : -1)));
            if (replacement != scroll) {
                scroll = replacement;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int capacity() {
        return Math.max(0, (panelHeight - 80) / ROW_HEIGHT);
    }

    @Override public boolean isPauseScreen() { return false; }
}
