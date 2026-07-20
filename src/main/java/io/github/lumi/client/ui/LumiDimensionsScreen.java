package io.github.lumi.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Loaded vanilla and mod dimension catalog; it never changes player location. */
public final class LumiDimensionsScreen extends LumiPageScreen {
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
                ProjectTab.MORE);
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.currentDimension = Objects.requireNonNull(
                currentDimension, "currentDimension");
        this.openHistory = Objects.requireNonNull(openHistory, "openHistory");
    }

    @Override
    protected void init() {
        beginScreenInit();
        LumiPageLayout page = pageLayout();
        panelX = page.contentX();
        panelY = page.windowY();
        panelWidth = page.contentWidth();
        panelHeight = page.windowHeight();
        visible = dimensions.get().stream().distinct().sorted().toList();
        int capacity = capacity();
        scroll = Math.min(scroll, Math.max(0, visible.size() - capacity));
        for (int index = 0; index < Math.min(capacity, visible.size() - scroll); index++) {
            String dimension = visible.get(scroll + index);
            addIconButton(
                    panelX + panelWidth - 48,
                    panelY + 76 + index * ROW_HEIGHT,
                    "folder", Component.translatable(
                            "luma.dimensions.open_history", dimension),
                    () -> openHistory.accept(dimension),
                    LumiButton.Kind.NORMAL);
        }
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
            renderPage(graphics, panelX, panelY, panelWidth, panelHeight);
            renderPageHeader(graphics, panelX, panelY, panelWidth, title,
                    Component.translatable("luma.dimensions.help"));
            renderPanel(graphics, panelX + 12, panelY + 62,
                    panelWidth - 24, Math.max(1, panelHeight - 74));
            String current = currentDimension.get();
            int count = Math.min(capacity(), visible.size() - scroll);
            for (int index = 0; index < count; index++) {
                String dimension = visible.get(scroll + index);
                int y = panelY + 72 + index * ROW_HEIGHT;
                LumiTheme.outlined(
                        graphics, panelX + 20, y, panelWidth - 40, 26,
                        LumiTheme.INSET,
                        dimension.equals(current)
                                ? LumiTheme.ACCENT
                                : LumiTheme.INSET_BORDER);
                graphics.drawString(font,
                        font.plainSubstrByWidth(dimension, panelWidth - 100),
                        panelX + 28, y + 6, LumiTheme.TEXT, false);
                if (dimension.equals(current)) {
                    graphics.drawString(font,
                            font.plainSubstrByWidth(
                                    Component.translatable(
                                            "luma.dimensions.current").getString(),
                                    Math.max(1, panelWidth - 100)),
                            panelX + 28, y + 16, LumiTheme.ACCENT, false);
                }
            }
            renderScrollbar(
                    graphics, panelX + panelWidth - 18, panelY + 72,
                    Math.max(0, panelHeight - 86),
                    visible.size(), capacity(), scroll);
            if (visible.isEmpty()) {
                graphics.drawString(font,
                        font.plainSubstrByWidth(
                                Component.translatable("luma.dimensions.empty").getString(),
                                Math.max(1, panelWidth - 48)),
                        panelX + 24, panelY + 76, LumiTheme.MUTED, false);
            }
            super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
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
