package io.github.lumi.client.ui;

import io.github.lumi.network.PackageInspectionPayload;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Legacy confirmation for applying inspected world-state package data. */
public final class LumiPackageInspectionScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 238;
    private final Screen parent;
    private final PackageInspectionPayload inspection;
    private final Runnable importPackage;
    private int panelX;
    private int panelY;
    private String error = "";

    public LumiPackageInspectionScreen(
            Screen parent,
            PackageInspectionPayload inspection,
            Runnable importPackage) {
        super(Component.translatable("luma.share.import_title"));
        this.parent = parent;
        this.inspection = Objects.requireNonNull(inspection, "inspection");
        this.importPackage = Objects.requireNonNull(importPackage, "importPackage");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int buttonWidth = (panelWidth - 48) / 2;
        addLegacyButton(panelX + 20, panelY + 202, buttonWidth,
                Component.translatable("luma.action.import_package"),
                this::confirm, LumiLegacyButton.Kind.PRIMARY);
        addLegacyButton(panelX + 28 + buttonWidth, panelY + 202, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    private void confirm() {
        try {
            importPackage.run();
            minecraft.setScreen(parent);
        } catch (RuntimeException failed) {
            error = failed.getMessage() == null
                    ? "Lumi import could not start" : failed.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        renderLegacyWindow(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16,
                LegacyLumiTheme.TEXT);
        int warningBottom = drawWrapped(
                graphics,
                Component.translatable("luma.share.package_safety_warning"),
                panelX + 20, panelY + 40, panelWidth - 40,
                LegacyLumiTheme.ACCENT);
        int detailsY = Math.max(panelY + 82, warningBottom + 8);
        renderLegacyPanel(graphics, panelX + 20, detailsY, panelWidth - 40, 92);
        graphics.drawString(font, inspection.packageName() + ".lumi",
                panelX + 30, detailsY + 12, LegacyLumiTheme.TEXT, false);
        graphics.drawString(font,
                font.plainSubstrByWidth(inspection.message(), panelWidth - 60),
                panelX + 30, detailsY + 32, LegacyLumiTheme.TEXT, false);
        graphics.drawString(font,
                font.plainSubstrByWidth(inspection.author(), panelWidth - 60),
                panelX + 30, detailsY + 48, LegacyLumiTheme.MUTED, false);
        graphics.drawString(font,
                Component.translatable(
                        "luma.share.package_safety_metadata",
                        inspection.totalBytes(), inspection.objectCount()),
                panelX + 30, detailsY + 66, LegacyLumiTheme.MUTED, false);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    width / 2, panelY + 186, LegacyLumiTheme.DANGER);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int drawWrapped(
            GuiGraphics graphics,
            Component text,
            int x,
            int y,
            int availableWidth,
            int color) {
        int lineY = y;
        for (var line : font.split(text, availableWidth)) {
            graphics.drawString(font, line, x, lineY, color, false);
            lineY += 11;
        }
        return lineY;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
