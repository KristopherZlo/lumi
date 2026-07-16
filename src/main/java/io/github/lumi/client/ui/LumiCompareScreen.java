package io.github.lumi.client.ui;

import io.github.lumi.client.state.ClientCompareStore;
import io.github.lumi.network.CompareResultPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Read-only asynchronous material summary for one saved version. */
public final class LumiCompareScreen extends Screen {
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 300;
    private static final int MAX_VISIBLE_MATERIALS = 10;
    private final Screen parent;
    private final ClientCompareStore comparisons;
    private final String label;
    private final Supplier<UUID> request;
    private final Consumer<UUID> cancel;
    private boolean requested;
    private UUID requestId;
    private String localError = "";
    private int panelX;
    private int panelY;

    public LumiCompareScreen(
            Screen parent,
            ClientCompareStore comparisons,
            String label,
            Supplier<UUID> request,
            Consumer<UUID> cancel) {
        super(Component.translatable("luma.screen.compare.title"));
        this.parent = parent;
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.label = Objects.requireNonNull(label, "label");
        this.request = Objects.requireNonNull(request, "request");
        this.cancel = Objects.requireNonNull(cancel, "cancel");
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(16, (height - PANEL_HEIGHT) / 2);
        addRenderableWidget(Button.builder(
                Component.translatable("luma.action.back"), ignored -> onClose())
                .bounds(width / 2 - 60, panelY + PANEL_HEIGHT - 30, 120, 20)
                .build());
        if (!requested) {
            requested = true;
            try {
                requestId = request.get();
            } catch (RuntimeException failed) {
                localError = failed.getMessage() == null
                        ? "Lumi Compare could not start" : failed.getMessage();
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        graphics.fill(panelX, panelY, panelX + panelWidth,
                panelY + PANEL_HEIGHT, 0xee15181d);
        graphics.drawCenteredString(font, title, width / 2, panelY + 16, 0xffffffff);
        graphics.drawCenteredString(font,
                Component.literal(font.plainSubstrByWidth(label, panelWidth - 48)),
                width / 2, panelY + 34, 0xffaeb6c2);
        if (!localError.isEmpty()) {
            drawError(graphics, localError);
        } else {
            comparisons.result().ifPresentOrElse(
                    result -> drawResult(graphics, result),
                    () -> drawLoading(graphics));
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawLoading(GuiGraphics graphics) {
        graphics.drawCenteredString(font,
                Component.translatable("luma.compare.loading_title"),
                width / 2, panelY + 82, 0xfff0f3f6);
        graphics.drawCenteredString(font,
                Component.translatable("luma.compare.loading"),
                width / 2, panelY + 102, 0xff8f9aa8);
    }

    private void drawResult(GuiGraphics graphics, CompareResultPayload result) {
        if (!result.error().isEmpty()) {
            drawError(graphics, result.error());
            return;
        }
        graphics.drawString(font,
                Component.translatable(
                        "luma.compare.section_summary",
                        result.changedSections(), result.changedEntityChunks()),
                panelX + 20, panelY + 62, 0xfff0f3f6, false);
        graphics.drawString(font,
                Component.translatable("luma.compare.materials_title"),
                panelX + 20, panelY + 82, 0xffaeb6c2, false);
        int visible = Math.min(MAX_VISIBLE_MATERIALS, result.materials().size());
        for (int index = 0; index < visible; index++) {
            CompareResultPayload.Material material = result.materials().get(index);
            long delta = Math.subtractExact(material.after(), material.before());
            graphics.drawString(font,
                    Component.translatable("luma.compare.material_entry", material.id(), delta),
                    panelX + 28, panelY + 100 + index * 14, 0xfff0f3f6, false);
        }
        if (result.materials().isEmpty()) {
            graphics.drawString(font,
                    Component.translatable("luma.materials.empty"),
                    panelX + 28, panelY + 100, 0xff8f9aa8, false);
        } else if (result.materials().size() > visible) {
            graphics.drawString(font,
                    Component.translatable(
                            "luma.materials.more", result.materials().size() - visible),
                    panelX + 28, panelY + 100 + visible * 14, 0xff8f9aa8, false);
        }
    }

    private void drawError(GuiGraphics graphics, String error) {
        graphics.drawCenteredString(font,
                Component.literal(font.plainSubstrByWidth(error, PANEL_WIDTH - 48)),
                width / 2, panelY + 88, 0xffff6b6b);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override
    public void onClose() {
        if (requestId != null) {
            cancel.accept(requestId);
            requestId = null;
        }
        minecraft.setScreen(parent);
    }
}
