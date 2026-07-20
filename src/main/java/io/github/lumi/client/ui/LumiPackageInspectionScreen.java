package io.github.lumi.client.ui;

import io.github.lumi.network.PackageInspectionPayload;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Confirmation for applying inspected world-state package data. */
public final class LumiPackageInspectionScreen extends LumiModalScreen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 238;
    private final Screen parent;
    private final PackageInspectionPayload inspection;
    private final Runnable importPackage;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
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
        beginScreenInit();
        LumiModalLayout layout = fitPanel(width, height);
        panelX = layout.x();
        panelY = layout.y();
        panelWidth = layout.width();
        panelHeight = layout.height();
        int buttonWidth = (panelWidth - 48) / 2;
        int actionY = panelY + actionOffset(panelHeight);
        addButton(panelX + 20, actionY, buttonWidth,
                Component.translatable("luma.action.import_package"),
                this::confirm, LumiButton.Kind.PRIMARY);
        addButton(panelX + 28 + buttonWidth, actionY, buttonWidth,
                Component.translatable("luma.action.cancel"),
                this::onClose, LumiButton.Kind.NORMAL);
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
        ScaledRenderContext render = beginScaledRender(graphics, mouseX, mouseY);
        try {
        renderWindow(graphics, panelX, panelY, panelWidth, panelHeight);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16,
                LumiTheme.TEXT);
        int warningBottom = drawWrapped(
                graphics,
                Component.translatable("luma.share.package_safety_warning"),
                panelX + 20, panelY + 36, panelWidth - 40,
                LumiTheme.ACCENT);
        int actionY = panelY + actionOffset(panelHeight);
        int errorSpace = error.isEmpty() ? 0 : 14;
        int detailsY = Math.max(panelY + 68, warningBottom + 6);
        int detailsHeight = Math.max(1,
                actionY - detailsY - 8 - errorSpace);
        int lineStride = Math.max(9, Math.min(16,
                Math.max(0, detailsHeight - 17) / 3));
        renderPanel(
                graphics, panelX + 20, detailsY, panelWidth - 40, detailsHeight);
        graphics.drawString(font, inspection.packageName() + ".lumi",
                panelX + 30, detailsY + 7, LumiTheme.TEXT, false);
        graphics.drawString(font,
                font.plainSubstrByWidth(inspection.message(), panelWidth - 60),
                panelX + 30, detailsY + 7 + lineStride,
                LumiTheme.TEXT, false);
        graphics.drawString(font,
                font.plainSubstrByWidth(inspection.author(), panelWidth - 60),
                panelX + 30, detailsY + 7 + lineStride * 2,
                LumiTheme.MUTED, false);
        String metadata = Component.translatable(
                "luma.share.package_safety_metadata",
                inspection.totalBytes(), inspection.objectCount()).getString();
        graphics.drawString(font,
                font.plainSubstrByWidth(metadata, panelWidth - 60),
                panelX + 30, detailsY + 7 + lineStride * 3,
                LumiTheme.MUTED, false);
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, errorText(error),
                    width / 2, actionY - 13, LumiTheme.DANGER);
        }
        super.render(graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endScaledRender(graphics);
        }
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

    static LumiModalLayout fitPanel(int screenWidth, int screenHeight) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(1, screenWidth - 32));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(1, screenHeight - 16));
        return new LumiModalLayout(
                Math.max(0, (screenWidth - panelWidth) / 2),
                Math.max(0, (screenHeight - panelHeight) / 2),
                panelWidth, panelHeight);
    }

    static int actionOffset(int panelHeight) {
        return panelHeight - 28;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
