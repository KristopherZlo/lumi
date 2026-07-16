package io.github.lumi.client.ui;

import io.github.lumi.client.diagnostics.ClientDiagnostics;
import io.github.lumi.client.state.ClientHistoryStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only support screen; it never scans chunks or mutates history. */
public final class LumiDiagnosticsScreen extends Screen {
    private final Screen parent;
    private final ClientHistoryStore history;
    private ClientDiagnostics diagnostics;
    private int panelX;
    private int panelY;
    private int panelWidth;

    public LumiDiagnosticsScreen(Screen parent, ClientHistoryStore history) {
        super(Component.translatable("luma.screen.diagnostics.title"));
        this.parent = parent;
        this.history = java.util.Objects.requireNonNull(history, "history");
    }

    @Override
    protected void init() {
        Runtime runtime = Runtime.getRuntime();
        diagnostics = ClientDiagnostics.from(
                history.state(),
                FabricLoader.getInstance().isModLoaded("worldedit"),
                FabricLoader.getInstance().isModLoaded("axiom"),
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        panelWidth = Math.min(430, width - 24);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(12, (height - 260) / 2);
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.close"), ignored -> onClose())
                .bounds(panelX + panelWidth - 76, panelY + 12, 60, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 260, 0xee15181d);
        graphics.drawString(font, title, panelX + 16, panelY + 18, 0xffffffff, false);
        graphics.drawString(font, Component.translatable("luma.diagnostics.help"),
                panelX + 16, panelY + 42, 0xffaeb6c2, false);
        int y = panelY + 70;
        y = row(graphics, y, "Dimension", diagnostics.dimension());
        y = row(graphics, y, "Workspace", diagnostics.workspace());
        y = row(graphics, y, "Branch", diagnostics.branch());
        y = row(graphics, y, "Pending keys", Integer.toString(diagnostics.pendingKeys()));
        y = row(graphics, y, "Operation", diagnostics.operation());
        y = row(graphics, y, "Recovery", diagnostics.recovery());
        y = row(graphics, y, "WorldEdit / Axiom",
                diagnostics.worldEdit() + " / " + diagnostics.axiom());
        row(graphics, y, "Used JVM heap", diagnostics.usedHeapMiB() + " MiB");
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int row(GuiGraphics graphics, int y, String label, String value) {
        graphics.drawString(font, label, panelX + 20, y, 0xff8f9aa8, false);
        graphics.drawString(font, font.plainSubstrByWidth(value, panelWidth - 155),
                panelX + 145, y, 0xfff0f3f6, false);
        return y + 21;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
