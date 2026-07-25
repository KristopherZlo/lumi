package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.domain.model.VersionTags;
import io.github.lumi.network.HistorySnapshotPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** Draws one bounded save card and owns its content/action geometry. */
final class LumiCommitCard {
    private static final int CONTENT_INSET = 6;
    private static final int CONTENT_GAP = 4;
    private static final int PREVIEW_WIDTH = 40;
    private static final int PREVIEW_HEIGHT = 22;
    private static final int ACTION_WIDTH = 26;
    private static final int ACTION_HEIGHT = 18;
    private static final int ACTION_STRIDE = 30;
    private static final int ACTION_COUNT = 4;
    private static final int ACTION_CLUSTER_WIDTH =
            ACTION_WIDTH + ACTION_STRIDE * (ACTION_COUNT - 1);
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
    private final Font font;
    private final ClientVersionPreviewStore previews;
    private final String dimensionId;

    LumiCommitCard(
            Font font, ClientVersionPreviewStore previews, String dimensionId) {
        this.font = font;
        this.previews = previews;
        this.dimensionId = dimensionId;
    }

    void render(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            VersionTags tags,
            Layout layout,
            int accentColor,
            boolean head,
            boolean featured) {
        int border = head || featured
                ? accentColor : LumiTheme.INSET_BORDER;
        LumiTheme.outlined(
                graphics, layout.x(), layout.y(), layout.width(), layout.height(),
                featured ? LumiTheme.PANEL : LumiTheme.INSET, border);
        if (layout.showPreview()) {
            drawPreview(graphics, version, layout.previewX(), layout.previewY());
        }
        if (layout.textWidth() <= 0) return;
        graphics.drawString(
                font,
                font.plainSubstrByWidth(VersionText.name(version), layout.textWidth()),
                layout.textX(), layout.messageY(), LumiTheme.TEXT, false);
        if (!layout.showMeta()) return;
        String tagText = tags.isEmpty()
                ? "" : " · #" + String.join(" #", tags.values());
        String featuredText = featured
                ? Component.translatable("luma.dashboard.latest_badge").getString()
                        + " · "
                : "";
        String headText = head
                ? " · " + Component.translatable(
                        "luma.project.active_head_badge").getString()
                : "";
        String meta = featuredText + version.author() + " · "
                + HISTORY_TIME.format(Instant.ofEpochMilli(version.timestampMillis()))
                + " · " + version.statistics().blocks() + " blocks"
                + tagText + headText;
        graphics.drawString(
                font, font.plainSubstrByWidth(meta, layout.textWidth()),
                layout.textX(), layout.metaY(), LumiTheme.MUTED, false);
    }

    private void drawPreview(
            GuiGraphics graphics,
            HistorySnapshotPayload.Version version,
            int x,
            int y) {
        var texture = previews.texture(dimensionId, version.id()).orElse(null);
        if (texture != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED, texture.id(),
                    x, y, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    texture.width(), texture.height(),
                    texture.width(), texture.height());
            return;
        }
        LumiTheme.outlined(
                graphics, x, y, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                LumiTheme.WINDOW, LumiTheme.INSET_BORDER);
        LumiPreviewRenderer.drawPlaceholder(
                graphics, x, y, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                previews.isLoading(dimensionId, version.id()));
    }

    static Layout layout(
            int x, int y, int width, int height,
            boolean showPreview, boolean showMeta, boolean stackedActions) {
        int boundedWidth = Math.max(0, width);
        int boundedHeight = Math.max(0, height);
        int actionX = Math.max(
                x + CONTENT_INSET,
                x + boundedWidth - CONTENT_INSET - ACTION_CLUSTER_WIDTH);
        int actionY = stackedActions
                ? y + Math.max(CONTENT_INSET,
                        boundedHeight - CONTENT_INSET - ACTION_HEIGHT)
                : y + Math.max(0, (boundedHeight - ACTION_HEIGHT) / 2);
        int textX = x + CONTENT_INSET
                + (showPreview ? PREVIEW_WIDTH + CONTENT_GAP : 0);
        int textRight = stackedActions
                ? x + boundedWidth - CONTENT_INSET
                : actionX - CONTENT_GAP;
        int textBandBottom = stackedActions ? actionY : y + boundedHeight;
        int previewY = y + Math.max(0,
                (textBandBottom - y - PREVIEW_HEIGHT) / 2);
        return new Layout(
                x, y, boundedWidth, boundedHeight,
                showPreview, showMeta,
                x + CONTENT_INSET, previewY,
                textX, Math.max(0, textRight - textX),
                actionX, actionY);
    }

    record Layout(
            int x,
            int y,
            int width,
            int height,
            boolean showPreview,
            boolean showMeta,
            int previewX,
            int previewY,
            int textX,
            int textWidth,
            int actionX,
            int actionY) {
        int actionX(int index) {
            if (index < 0 || index >= ACTION_COUNT) {
                throw new IllegalArgumentException("Unknown save-card action: " + index);
            }
            return actionX + index * ACTION_STRIDE;
        }

        int messageY() { return y + (showMeta ? 5 : Math.max(4, (height - 8) / 2)); }
        int metaY() { return y + 17; }
        int right() { return x + width; }
        int bottom() { return y + height; }
        float centerX() { return (x + right()) / 2.0F; }
        float centerY() { return (y + bottom()) / 2.0F; }
        int rotatedX(int childX, int childWidth) {
            return x + right() - childX - childWidth;
        }
        int rotatedY(int childY, int childHeight) {
            return y + bottom() - childY - childHeight;
        }
        int actionsRight() { return actionX(ACTION_COUNT - 1) + ACTION_WIDTH; }
        int actionsBottom() { return actionY + ACTION_HEIGHT; }
    }
}
