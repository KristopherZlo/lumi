package io.github.lumi.client.ui;

import io.github.lumi.client.preview.ClientVersionPreviewStore;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

/** Renders graph connectors, clickable nodes and the legacy preview hover card. */
final class LumiHistoryGraphView {
    static final int ROW_HEIGHT = 30;
    private static final int LANE_SPACING = 12;
    private final String dimensionId;
    private final ClientVersionPreviewStore previews;
    private final List<HistoryGraphLayout.Node> nodes;
    private final Map<UUID, Integer> zoneColors;
    private final int x;
    private final int y;
    private final int width;
    private final int graphX;

    LumiHistoryGraphView(
            String dimensionId,
            ClientVersionPreviewStore previews,
            List<HistoryGraphLayout.Node> nodes,
            List<HistorySnapshotPayload.ZoneView> zones,
            int x, int y, int width) {
        this.dimensionId = dimensionId;
        this.previews = previews;
        this.nodes = List.copyOf(nodes);
        this.zoneColors = new HashMap<>();
        zones.forEach(zone -> zoneColors.put(zone.id(), zone.color()));
        this.x = x;
        this.y = y;
        this.width = width;
        this.graphX = x + 10;
    }

    List<LumiHistoryGraphNodeButton> buttons(
            Consumer<HistorySnapshotPayload.Version> open) {
        List<LumiHistoryGraphNodeButton> buttons = new ArrayList<>(nodes.size());
        for (HistoryGraphLayout.Node node : nodes) {
            Integer zoneColor = node.version().zoneId()
                    .map(zoneColors::get).orElse(null);
            buttons.add(new LumiHistoryGraphNodeButton(
                    x, y + node.row() * ROW_HEIGHT, width, node,
                    graphX, LANE_SPACING, zoneColor,
                    () -> open.accept(node.version())));
        }
        return List.copyOf(buttons);
    }

    void renderConnections(GuiGraphics graphics) {
        for (HistoryGraphLayout.Node node : nodes) {
            for (HistoryGraphLayout.Edge edge : node.parentEdges()) {
                int childX = graphX + edge.childLane() * LANE_SPACING;
                int childY = centerY(edge.childRow());
                int parentX = graphX + edge.parentLane() * LANE_SPACING;
                int parentY = centerY(edge.parentRow());
                int bendY = childY + (parentY - childY) / 2;
                int color = LumiHistoryGraphNodeButton.laneColor(edge.childLane());
                graphics.vLine(childX, childY, bendY, color);
                graphics.hLine(Math.min(childX, parentX),
                        Math.max(childX, parentX), bendY, color);
                graphics.vLine(parentX, bendY, parentY, color);
            }
        }
    }

    void renderHover(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        Optional<HistoryGraphLayout.Node> hovered = nodeAt(mouseX, mouseY);
        if (hovered.isEmpty()) {
            return;
        }
        HistorySnapshotPayload.Version version = hovered.orElseThrow().version();
        int cardWidth = Math.min(150, width);
        int cardX = x + width - cardWidth;
        int cardY = Math.max(y, mouseY - 58);
        LegacyLumiTheme.outlined(graphics, cardX, cardY, cardWidth, 56,
                LegacyLumiTheme.PANEL, LegacyLumiTheme.ACCENT);
        previews.texture(dimensionId, version.id()).ifPresent(texture ->
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(),
                        cardX + 5, cardY + 5, 0, 0, 64, 36,
                        texture.width(), texture.height(),
                        texture.width(), texture.height()));
        int textX = cardX + 74;
        graphics.drawString(font,
                font.plainSubstrByWidth(version.message(), cardWidth - 79),
                textX, cardY + 7, LegacyLumiTheme.TEXT, false);
        graphics.drawString(font,
                font.plainSubstrByWidth(version.author(), cardWidth - 79),
                textX, cardY + 20, LegacyLumiTheme.MUTED, false);
        graphics.drawString(font,
                Long.toString(version.statistics().blocks()),
                textX, cardY + 33, LegacyLumiTheme.ACCENT, false);
    }

    Optional<HistoryGraphLayout.Node> nodeAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseX >= x + width || mouseY < y) {
            return Optional.empty();
        }
        int row = (int) ((mouseY - y) / ROW_HEIGHT);
        return row < 0 || row >= nodes.size()
                ? Optional.empty() : Optional.of(nodes.get(row));
    }

    private int centerY(int row) {
        return y + row * ROW_HEIGHT + ROW_HEIGHT / 2;
    }
}
