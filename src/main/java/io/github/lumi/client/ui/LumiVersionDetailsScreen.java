package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Read-only legacy-style details for one saved version. */
public final class LumiVersionDetailsScreen extends LumiLegacyModalScreen {
    private static final int PANEL_WIDTH = 540;
    private static final int PANEL_HEIGHT = 286;
    private static final int PREVIEW_WIDTH = 240;
    private static final int PREVIEW_HEIGHT = 135;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final String dimensionId;
    private final HistorySnapshotPayload.Version version;
    private final ClientVersionPreviewStore previews;
    private final Runnable restore;
    private final Optional<Runnable> compareToParent;
    private final Runnable delete;
    private int panelX;
    private int panelY;

    public LumiVersionDetailsScreen(
            Screen parent,
            String dimensionId,
            HistorySnapshotPayload.Version version,
            ClientVersionPreviewStore previews,
            Runnable restore,
            Optional<Runnable> compareToParent,
            Runnable delete) {
        super(parent, Component.translatable(
                "luma.screen.save_details.title", version.message()));
        this.parent = parent;
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.version = Objects.requireNonNull(version, "version");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.compareToParent = Objects.requireNonNull(
                compareToParent, "compareToParent");
        this.delete = Objects.requireNonNull(delete, "delete");
    }

    @Override
    protected void init() {
        beginLegacyInit();
        int panelWidth = Math.min(PANEL_WIDTH, width - 32);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int buttonWidth = Math.max(52, (panelWidth - 52) / 4);
        int buttonY = panelY + PANEL_HEIGHT - 30;
        addLegacyButton(panelX + 16, buttonY, buttonWidth,
                Component.translatable("luma.action.restore"),
                restore, LumiLegacyButton.Kind.PRIMARY);
        LumiLegacyButton compare = addLegacyButton(
                panelX + 20 + buttonWidth, buttonY, buttonWidth,
                Component.translatable("luma.action.compare"),
                () -> compareToParent.ifPresent(Runnable::run),
                LumiLegacyButton.Kind.NORMAL);
        compare.active = compareToParent.isPresent();
        addLegacyButton(panelX + 24 + buttonWidth * 2, buttonY, buttonWidth,
                Component.translatable("luma.action.delete_save"),
                delete, LumiLegacyButton.Kind.DANGER);
        addLegacyButton(panelX + 28 + buttonWidth * 3, buttonY, buttonWidth,
                Component.translatable("luma.action.back"),
                this::onClose, LumiLegacyButton.Kind.NORMAL);
    }

    @Override
    public void render(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LegacyRenderContext render = beginLegacyRender(graphics, mouseX, mouseY);
        try {
            int panelWidth = Math.min(PANEL_WIDTH, width - 32);
            renderLegacyWindow(
                    graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);
            graphics.drawString(font,
                    font.plainSubstrByWidth(version.message(), panelWidth - 40),
                    panelX + 20, panelY + 18, LegacyLumiTheme.TEXT, false);
            renderPreview(graphics);
            int metadataX = panelX + 280;
            int metadataWidth = Math.max(0, panelWidth - 300);
            graphics.drawString(font,
                    Component.translatable(
                            "luma.save_details.summary_help",
                            DATE_FORMAT.format(
                                    Instant.ofEpochMilli(version.timestampMillis()))),
                    metadataX, panelY + 58, LegacyLumiTheme.MUTED, false);
            graphics.drawString(font,
                    Component.translatable(
                            "luma.save_details.raw_info_author", version.author()),
                    metadataX, panelY + 82, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable(
                            "luma.save_details.raw_info_type", version.kind().name()),
                    metadataX, panelY + 102, LegacyLumiTheme.TEXT, false);
            graphics.drawString(font,
                    Component.translatable("luma.save_details.raw_info_title"),
                    metadataX, panelY + 132, LegacyLumiTheme.MUTED, false);
            graphics.drawWordWrap(font,
                    Component.translatable(
                            "luma.save_details.raw_info_id", version.id().hex()),
                    metadataX, panelY + 150, metadataWidth,
                    LegacyLumiTheme.TEXT);
            super.render(
                    graphics, render.mouseX(), render.mouseY(), partialTick);
        } finally {
            endLegacyRender(graphics);
        }
    }

    private void renderPreview(GuiGraphics graphics) {
        int x = panelX + 20;
        int y = panelY + 52;
        var texture = previews.texture(dimensionId, version.id()).orElse(null);
        if (texture == null) {
            LegacyLumiTheme.outlined(
                    graphics, x, y, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    LegacyLumiTheme.INSET, LegacyLumiTheme.INSET_BORDER);
            graphics.drawCenteredString(font,
                    Component.translatable("luma.history.no_preview"),
                    x + PREVIEW_WIDTH / 2, y + PREVIEW_HEIGHT / 2 - 4,
                    LegacyLumiTheme.MUTED);
            return;
        }
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, texture.id(),
                x, y, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                texture.width(), texture.height(),
                texture.width(), texture.height());
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
