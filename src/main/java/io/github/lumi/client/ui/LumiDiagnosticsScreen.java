package io.github.lumi.client.ui;

import io.github.lumi.client.onboarding.ClientContextualHelpHint;
import io.github.lumi.client.diagnostics.ClientDiagnostics;
import io.github.lumi.client.state.ClientHistoryStore;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only support screen; it never scans chunks or mutates history. */
public final class LumiDiagnosticsScreen extends LumiModalScreen {
    private static final int BASE_PANEL_HEIGHT = 260;
    private final Screen parent;
    private final ClientHistoryStore history;
    private ClientDiagnostics diagnostics;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentOffset;

    public LumiDiagnosticsScreen(Screen parent, ClientHistoryStore history) {
        super(Component.translatable("luma.screen.diagnostics.title"));
        this.parent = parent;
        this.history = java.util.Objects.requireNonNull(history, "history");
    }

    @Override
    protected void init() {
        beginScreenInit();
        Runtime runtime = Runtime.getRuntime();
        diagnostics = ClientDiagnostics.from(
                history.state(),
                FabricLoader.getInstance().isModLoaded("worldedit"),
                FabricLoader.getInstance().isModLoaded("axiom"),
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        LumiModalLayout layout = fitPanel(width, height, 0);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        boolean hintVisible = addContextualHint(
                ClientContextualHelpHint.DIAGNOSTICS,
                panelX + 12, panelY + 58, panelWidth - 24);
        contentOffset = hintVisible ? contextualHintOffset(8) : 0;
        layout = fitPanel(width, height, contentOffset);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        if (hintVisible) {
            moveContextualHint(panelX + 12, panelY + 58);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
        renderWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        renderPanel(graphics, panelX + 12,
                panelY + 60 + contentOffset,
                panelWidth - 24, rowAreaHeight(panelHeight, contentOffset));
        graphics.drawString(font, title, panelX + 16, panelY + 18,
                LumiTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("luma.diagnostics.help"),
                panelX + 16, panelY + 42, LumiTheme.MUTED, false);
        renderRows(graphics);
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
    }

    private void renderRows(GuiGraphics graphics) {
        List<Map.Entry<String, String>> rows = List.of(
                Map.entry("Dimension", diagnostics.dimension()),
                Map.entry("Workspace", diagnostics.workspace()),
                Map.entry("Branch", diagnostics.branch()),
                Map.entry("Pending keys", Integer.toString(diagnostics.pendingKeys())),
                Map.entry("Operation", diagnostics.operation()),
                Map.entry("Recovery", diagnostics.recovery()),
                Map.entry("WorldEdit / Axiom",
                        diagnostics.worldEdit() + " / " + diagnostics.axiom()),
                Map.entry("Used JVM heap", diagnostics.usedHeapMiB() + " MiB"));
        int top = panelY + 70 + contentOffset;
        int availableHeight = Math.max(1,
                panelY + panelHeight - 8 - top);
        int columns = rowColumns(availableHeight);
        int rowsPerColumn = (rows.size() + columns - 1) / columns;
        int stride = rowStride(availableHeight, rowsPerColumn);
        int columnWidth = (panelWidth - 40) / columns;
        for (int index = 0; index < rows.size(); index++) {
            int column = index / rowsPerColumn;
            int row = index % rowsPerColumn;
            Map.Entry<String, String> entry = rows.get(index);
            row(graphics, panelX + 20 + column * columnWidth,
                    top + row * stride, columnWidth,
                    entry.getKey(), entry.getValue());
        }
    }

    private void row(
            GuiGraphics graphics, int x, int y, int width,
            String label, String value) {
        int labelWidth = Math.min(82, Math.max(42, width / 2));
        graphics.drawString(font,
                font.plainSubstrByWidth(label, labelWidth - 4), x, y,
                LumiTheme.MUTED, false);
        graphics.drawString(font,
                font.plainSubstrByWidth(value, Math.max(1, width - labelWidth)),
                x + labelWidth, y, LumiTheme.TEXT, false);
    }

    static LumiModalLayout fitPanel(
            int screenWidth, int screenHeight, int contentOffset) {
        int panelWidth = Math.min(430, Math.max(1, screenWidth - 16));
        int panelHeight = Math.min(
                BASE_PANEL_HEIGHT + Math.max(0, contentOffset),
                Math.max(1, screenHeight - 16));
        return new LumiModalLayout(
                Math.max(0, (screenWidth - panelWidth) / 2),
                Math.max(0, (screenHeight - panelHeight) / 2),
                panelWidth, panelHeight);
    }

    static int rowAreaHeight(int panelHeight, int contentOffset) {
        return Math.max(1, panelHeight - 68 - Math.max(0, contentOffset));
    }

    static int rowColumns(int availableHeight) {
        int rowsThatFit = Math.max(1,
                1 + (Math.max(1, availableHeight) - 9) / 12);
        return Math.max(1, (8 + rowsThatFit - 1) / rowsThatFit);
    }

    static int rowStride(int availableHeight, int rowsPerColumn) {
        if (rowsPerColumn <= 1) return 0;
        return Math.max(9, Math.min(21,
                (Math.max(1, availableHeight) - 9) / (rowsPerColumn - 1)));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
